import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from websockets.asyncio.client import connect
from websockets.asyncio.server import serve, unix_serve
from websockets.exceptions import InvalidStatus
from codex_bridge import CodexBridge


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
