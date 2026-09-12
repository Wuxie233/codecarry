import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from websockets.asyncio.client import connect
from websockets.asyncio.server import serve, unix_serve
from websockets.exceptions import InvalidStatus, ConnectionClosed
from codex_bridge import CodexBridge, MAX_DAEMON_MESSAGE


class BridgeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.socket = str(Path(self.temp.name)/'daemon.sock')
        self.received = []
        async def daemon(ws):
            async for message in ws:
                self.received.append(message)
                await ws.send(message)
                await ws.send('{"method":"turn/completed","params":{}}')
        self.daemon = await unix_serve(daemon, self.socket, compression=None)
        self.bridge = CodexBridge(self.socket, 'x'*40)
        self.server = await serve(self.bridge.handle, '127.0.0.1', 0, process_request=self.bridge.authorize)
        self.url = 'ws://127.0.0.1:'+str(self.server.sockets[0].getsockname()[1])

    async def asyncTearDown(self):
        self.server.close(); await self.server.wait_closed()
        self.daemon.close(); await self.daemon.wait_closed()
        self.temp.cleanup()

    async def test_authentication_precedes_daemon_access(self):
        for token in [None, 'bad']:
            with self.assertRaises(InvalidStatus) as caught:
                async with connect(self.url, additional_headers={} if token is None else {'Authorization':'Bearer '+token}):
                    self.fail('unauthenticated connection accepted')
            self.assertEqual(401,caught.exception.response.status_code)
        self.assertEqual([],self.received)

    async def test_native_rpc_and_notifications_survive_reconnect(self):
        for i in range(2):
            async with connect(self.url,additional_headers={'Authorization':'Bearer '+'x'*40}) as ws:
                message=json.dumps({'id':i,'method':'turn/steer','params':{'input':[{'text':'追发消息'}]}})
                await ws.send(message)
                self.assertEqual(message,await asyncio.wait_for(ws.recv(),2))
                self.assertEqual('turn/completed',json.loads(await ws.recv())['method'])
        self.assertEqual(2,len(self.received))

    async def replace_daemon(self, handler):
        self.daemon.close()
        await self.daemon.wait_closed()
        self.daemon = await unix_serve(handler, self.socket, compression=None)

    async def test_long_history_above_old_limit_and_following_rpc(self):
        # The old 16 MiB receive limit disconnected instead of delivering history.
        payload = json.dumps({'id': 1, 'result': {'text': 'x' * (17 * 1024 * 1024)}})
        async def daemon(ws):
            await ws.recv()
            await ws.send(payload)
            await ws.send('{"method":"turn/completed","params":{}}')
            await ws.send(await ws.recv())
        await self.replace_daemon(daemon)
        async with connect(self.url, compression=None, max_size=MAX_DAEMON_MESSAGE,
                           additional_headers={'Authorization': 'Bearer ' + 'x'*40}) as ws:
            await ws.send('{"id":1,"method":"thread/resume"}')
            self.assertEqual(payload, await asyncio.wait_for(ws.recv(), 10))
            self.assertEqual('turn/completed', json.loads(await ws.recv())['method'])
            await ws.send('{"id":2,"method":"thread/list"}')
            self.assertEqual('{"id":2,"method":"thread/list"}', await ws.recv())

    async def test_oversize_daemon_response_reports_1009(self):
        self.bridge.max_daemon_message = 1024
        async def daemon(ws):
            await ws.recv()
            await ws.send('x' * 1025)
            await ws.wait_closed()
        await self.replace_daemon(daemon)
        async with connect(self.url, additional_headers={'Authorization': 'Bearer ' + 'x'*40}) as ws:
            await ws.send('{}')
            with self.assertRaises(ConnectionClosed):
                await asyncio.wait_for(ws.recv(), 5)
            self.assertEqual(1009, ws.close_code)
            self.assertEqual('Codex message exceeds bridge size limit', ws.close_reason)

    async def test_abnormal_daemon_close_does_not_leak_reason(self):
        async def daemon(ws):
            await ws.close(1011, 'private upstream details')
        await self.replace_daemon(daemon)
        async with connect(self.url, additional_headers={'Authorization': 'Bearer ' + 'x'*40}) as ws:
            await asyncio.wait_for(ws.wait_closed(), 5)
            self.assertEqual(1011, ws.close_code)
            self.assertEqual('Codex daemon connection closed', ws.close_reason)

    async def test_browser_origin_rejected(self):
        with self.assertRaises(InvalidStatus) as caught:
            async with connect(self.url,origin='https://untrusted.example',additional_headers={'Authorization':'Bearer '+'x'*40}):
                self.fail('origin accepted')
        self.assertEqual(403,caught.exception.response.status_code)

    async def test_daemon_unavailable_is_retryable_close(self):
        self.daemon.close(); await self.daemon.wait_closed()
        async with connect(self.url,additional_headers={'Authorization':'Bearer '+'x'*40}) as ws:
            await ws.wait_closed()
            self.assertEqual(1011,ws.close_code)


if __name__ == '__main__':
    unittest.main()
