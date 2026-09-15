# codex-web：客户端形态与本机接入

核查日期：2026-09-16。上游快照：[`0dfdc10768c724d9a6ba507a93c3d6314ef073d8`](https://github.com/0xcaff/codex-web/tree/0dfdc10768c724d9a6ba507a93c3d6314ef073d8)。本文是官方文档与源码研究，未安装、未验证手机实际体验；本机服务地址和运行记录不写入共享报告。

## 有客户端吗

有**浏览器客户端和 PWA 配置**；本次未发现项目提供原生 Android APK 或桌面安装包。手机先用浏览器打开，在浏览器支持时可添加到主屏幕。`assets/manifest.json` 声明 `display: standalone`，页面补丁引用 `/manifest.json`；构建时复制到 `scratch/asar/webview/manifest.json`，图标输出为 `/assets/pwa-icon-512.png`。这些证据不能证明离线使用、后台推送或所有浏览器的安装体验。[manifest](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/assets/manifest.json)、[页面补丁](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/patches/webview-pwa.patch)、[构建脚本](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/scripts/prepare_asar)、[仓库文件树](https://github.com/0xcaff/codex-web/tree/0dfdc10768c724d9a6ba507a93c3d6314ef073d8)。

它并非重新实现一套相似界面：安装时下载 Codex Desktop 资产，解包并应用补丁，在 Node 中模拟 Electron 主进程接口，再用 WebSocket 连接浏览器。因此需要主机程序，不能只上传静态文件。当前资产固定为 Desktop `26.901.41123`。[架构](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/ARCHITECTURE.md)、[下载脚本](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/scripts/prepare)。

## 依赖与安装

- 主机需要已经登录的 Codex CLI。官方快速入口是 `npx --yes github:0xcaff/codex-web` 或 `nix run github:0xcaff/codex-web`，默认浏览器地址为 `http://127.0.0.1:8214`。[README](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/README.md)
- 非 Nix 源码安装需要 Node/npm、Git、Bash、curl、unzip、patch。Node 建议使用 **22.12+ 的 22.x 或 24.x**：这是锁文件里 `@electron/asar`、Vite 和 `better-sqlite3` 引擎范围的交集建议，不是作者单独承诺的最低版本。[package-lock.json](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/package-lock.json)、[prepare](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/scripts/prepare)、[prepare_asar](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/scripts/prepare_asar)
- `better-sqlite3` 含原生组件；本机若不能使用预编译包，还可能需要 Python/C++ 构建环境。上游 Nix 打包明确单独编译此组件。[Nix 构建](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/default.nix)

已有共享 daemon 的机器，建议使用固定提交的独立 checkout：便于检查安装步骤、保持 proxy 路径稳定和回退。示意命令（目录和 socket 由部署方填写）：

```bash
git clone https://github.com/0xcaff/codex-web.git codex-web
cd codex-web
git checkout 0dfdc10768c724d9a6ba507a93c3d6314ef073d8
npm ci
```

`npm ci` 的 `prepare` 生命周期会下载 Desktop、解包、打补丁并编译浏览器与服务；需预留下载与展开空间。无需运行 Electron GUI。[package.json](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/package.json)、[架构](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/ARCHITECTURE.md)

## 接入已经运行的 Unix app-server

推荐链路：浏览器 → codex-web 的 HTTP/IPC WebSocket → proxy → **现有 Unix socket**。现有 CodeCarry bridge 可保持独立运行。复用 daemon 是为了让 Web 前端重启与执行进程分离；并不自动证明所有活跃线程控制和重连场景都兼容。[README 的 proxy 接入说明](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/README.md#proxying-to-app-server-advanced-usage)

### 上游原版 helper

仓库自带 `scripts/codex_remote_proxy`，不是必须通过 Nix 获得。它过滤前置 `-c value`，确认调用为 `app-server` 后，用 `websocat` 将 stdio 转至 Unix WebSocket；依赖 Bash 和 `websocat`，默认帧缓冲为 `104857600` 字节，可用 `CODEX_BUFFER_SIZE` 覆盖。[helper 源码](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/scripts/codex_remote_proxy)

```bash
export CODEX_UNIX_SOCKET=/absolute/path/to/existing.sock
export CODEX_CLI_PATH="$PWD/scripts/codex_remote_proxy"
node src/server/main.js --host 127.0.0.1 --port 8214
```

**不要用 `npm run server` 启动这个接入模式**：当前脚本会重新设置 `CODEX_CLI_PATH=$(which codex)`，覆盖上面的 proxy。直接运行构建后的 Node 入口保留配置。服务使用 CLI 参数 `--host` / `--port`。[启动脚本](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/package.json)、[参数解析](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/src/server/main.ts#L250-L300)

### 原生 proxy 不能直接当作已验证替代

README 另提到 `codex app-server proxy --sock ...` 是 stdio 协议桥，但这不证明任意本机 CLI 构建与 wrapper 都能直接替换 `websocat`。本文不提供该替代启动步骤；部署以原项目 helper 为基准，缺少 `websocat` 时先解决依赖。不能把 `CODEX_CLI_PATH` 直接指向普通 CLI 就当成接入现有 daemon。[上游对原生 proxy 的说明](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/README.md#proxying-to-app-server-advanced-usage)

## 手机访问与认证

codex-web 没有内置登录边界。源码还将宿主根目录通过 `/@fs/` 静态路由提供给客户端；可访问 Web 服务的人应被视为具有服务进程的主机操作权限。保留 loopback 监听，手机通过已有 VPN、SSH 隧道或带认证的 HTTPS 代理进入；外层认证必须覆盖页面、文件、上传和 WebSocket。[安全说明](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/README.md#security)、[HTTP 与 WebSocket 路由](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/src/server/main.ts#L388-L463)

代理需要传递 `/__backend/ipc` WebSocket upgrade。manifest 和多个路由使用根绝对路径，首次部署建议独立站点根路径，避免随意挂 `/codex/` 子路径。此建议来自路由与资源路径，并非上游已验证的所有反向代理配置。[服务入口](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/src/server/main.ts)、[manifest](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/assets/manifest.json)

## 怎么试用与验收

打开页面后选择主机工作目录、新建一条短会话，再测试历史线程。作者声明目前支持子代理、内联图片、编辑器侧栏和转写；terminal、git worker、browser panel 等仍列为未接通项。[功能与 roadmap](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/README.md#features)

建议按以下次序验收；这是本次建议，不是已完成的测试：

1. HTTP 页面、manifest、静态资源与 IPC WebSocket 均正常。
2. 前端只连接目标 daemon，没有新建第二个执行进程。
3. 用新建测试线程收发消息，再验证终端与 Web 同一线程的实时状态。
4. 手机测试中文输入、图片、长回复、子代理跳转、审批、锁屏再回来。
5. 单独重启 Web 服务，确认 daemon 与测试任务仍在；再检查历史和实时状态恢复。

保留 CodeCarry 入口，等真实手机试用通过再决定是否迁移。源码适配说明和 HTTP 成功都不能代替上述使用验收。
