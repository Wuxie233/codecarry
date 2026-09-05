# Codex remote control

CodeCarry speaks the Codex app-server JSON-RPC protocol over WebSocket. It
supports thread history and runtime status, streaming items, new messages,
mid-turn steering, interruption, approvals, questions, and reconnect.

## Use the same agent runtime on the terminal and phone

```text
Codex TUI (--remote unix://) ─┐
                            ├─ shared Codex app-server daemon
CodeCarry → HTTPS/WSS proxy → authenticated bridge → Unix control socket
```

On the server, start the managed daemon if it is not running, then attach the
terminal to that daemon:

```sh
codex app-server daemon start
codex --remote unix:// -C /root/CODE/your-project
# Open an existing thread in the same daemon:
codex --remote unix:// resume THREAD_ID
```

In CodeCarry, add a **Codex** server, enter its `wss://` address and bearer token,
connect, then open a thread. Sending while a turn is running calls `turn/steer`
with the current turn ID; otherwise it calls `turn/start`. Stop calls
`turn/interrupt`. Closing the mobile connection does not stop the agent.

An independently started app-server is a different live runtime, even when it
shares `CODEX_HOME`. A standalone `codex exec` process is not automatically
controlled through this bridge. Use the terminal attachment above for shared
live control. The bridge does not scrape terminal output or inject keystrokes.

## Backend

Requires Python 3.10+ and the dependency in `requirements.txt`. The listener is
restricted to `127.0.0.1`; terminate TLS at the reverse proxy. Every WebSocket
handshake requires `Authorization: Bearer <token>`. A token is never accepted
in a URL. Browser Origin requests are rejected. Use a randomly generated token
and a mode-0600 file outside this repository.

```sh
python3 -m pip install -r requirements.txt
python3 codex_bridge.py \
  --socket /root/.codex/app-server-control/app-server-control.sock \
  --token-file /path/to/private/token \
  --port 18767
```

The included systemd unit documents the `like_server` paths. Adjust paths and
the service user before deploying elsewhere. The bridge owns only its client
connections. It never starts, restarts, resumes, or stops the daemon or agent
threads. A missing daemon closes the client with retryable status 1011.

Reverse-proxy the WebSocket endpoint, preserve Authorization, disable buffering,
and allow long-lived connections:

```nginx
location / {
    proxy_pass http://127.0.0.1:18767;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Authorization $http_authorization;
    proxy_set_header Host $host;
    proxy_buffering off;
    proxy_read_timeout 86400s;
    proxy_send_timeout 86400s;
}
```

On `like_server`, `codecarry-codex-bridge.service` uses port 18767 and
`codex.wuxie233.com`. It reuses the existing token file at
`/root/.config/opencode/secrets/codex-app-server.token`. The old independent
listener on port 18766 is not stopped by this deployment. Rollback can point
the public proxy back to that port, then stop only the bridge service.

## Verification

```sh
python3 -m unittest -v
```

Android tests cover JSON-RPC correlation, steering, approvals, connection
generations, reconnect, event reduction, thread UI state, URL validation, and
server persistence. Newly created blank threads keep their start response on
the current connection because no durable rollout exists before the first
turn. Thread lists explicitly include all model providers.

Protocol reference: <https://developers.openai.com/codex/app-server/>.
