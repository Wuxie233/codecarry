#!/usr/bin/env python3
"""Authenticated WebSocket access to the existing Codex local daemon.

This process owns connections only. It never starts, stops, or resumes agents.
TLS termination belongs to the reverse proxy; the listener is loopback-only.
"""
import argparse
import asyncio
import hmac
import logging
import signal
from http import HTTPStatus
from pathlib import Path

from websockets.asyncio.client import unix_connect
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

MAX_MESSAGE = 16 * 1024 * 1024
LOG = logging.getLogger("codecarry.codex")


class CodexBridge:
    def __init__(self, socket_path, token, max_connections=32):
        if not token or any(c.isspace() for c in token):
            raise ValueError("Token must be nonempty and contain no whitespace")
        self.socket_path = str(socket_path)
        self.authorization = "Bearer " + token
        self.max_connections = max_connections
        self.active = 0

    async def authorize(self, connection, request):
        values = request.headers.get_all("Authorization")
        if len(values) != 1 or not hmac.compare_digest(values[0].encode(), self.authorization.encode()):
            return connection.respond(HTTPStatus.UNAUTHORIZED, "Unauthorized\n")
        if request.path != "/":
            return connection.respond(HTTPStatus.NOT_FOUND, "Not found\n")
        # Native clients do not send Origin. Do not grant browser pages access.
        if request.headers.get_all("Origin"):
            return connection.respond(HTTPStatus.FORBIDDEN, "Origin not allowed\n")

    async def handle(self, client):
        if self.active >= self.max_connections:
            await client.close(1013, "Connection limit reached")
            return
        self.active += 1
        pumps = []
        try:
            async with unix_connect(
                self.socket_path, uri="ws://localhost", compression=None,
                user_agent_header=None, open_timeout=10, close_timeout=5,
                max_size=MAX_MESSAGE, max_queue=16,
                ping_interval=20, ping_timeout=20,
            ) as daemon:
                async def relay(source, target):
                    async for message in source:
                        if not isinstance(message, str):
                            await source.close(1003, "JSON text frames required")
                            return
                        await target.send(message)
                pumps = [asyncio.create_task(relay(client, daemon)), asyncio.create_task(relay(daemon, client))]
                done, _ = await asyncio.wait(pumps, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    task.result()
        except ConnectionClosed:
            pass
        except (OSError, TimeoutError):
            LOG.warning("Codex daemon unavailable")
            await client.close(1011, "Codex daemon unavailable")
        except Exception:
            # Neither credentials nor RPC payloads belong in logs.
            LOG.warning("Codex connection failed")
            await client.close(1011, "Codex connection failed")
        finally:
            for task in pumps:
                task.cancel()
            await asyncio.gather(*pumps, return_exceptions=True)
            self.active -= 1


async def run(args):
    token_path = Path(args.token_file)
    if token_path.stat().st_mode & 0o077:
        raise ValueError("Token file must have mode 0600")
    bridge = CodexBridge(args.socket, token_path.read_text().strip())
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    async with serve(
        bridge.handle, "127.0.0.1", args.port, process_request=bridge.authorize,
        compression=None, max_size=MAX_MESSAGE, max_queue=16,
        open_timeout=10, close_timeout=5, ping_interval=20, ping_timeout=20,
    ):
        LOG.info("Listening on loopback port %d", args.port)
        await stop.wait()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--socket", default=str(Path.home()/".codex/app-server-control/app-server-control.sock"))
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--port", type=int, default=18767)
    logging.basicConfig(level=logging.INFO)
    asyncio.run(run(parser.parse_args()))
