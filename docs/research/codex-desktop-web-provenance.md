# Codex Desktop Web 界面来源与候选

核查日期：2026-09-16。

范围：查阅维护者 README、构建脚本、连接实现与包元数据；没有安装替代项目，也没有进行功能或手机体验实测。区分“提取官方界面”“自行重写相似界面”与“桌面功能是否全部接通”。

## 结论

当前使用的 **0xcaff/codex-web 已经是官方桌面前端提取适配版**，不能因为界面出现 ChatGPT、Chat、Work 就把它判断为自行仿写 ChatGPT 网页。其构建脚本下载官方应用包，再解出 `ChatGPT.app` 内的 `app.asar`；当前脚本引用的应用版本为 `26.901.41123`。[下载脚本](https://github.com/0xcaff/codex-web/blob/main/scripts/prepare)、[解包脚本](https://github.com/0xcaff/codex-web/blob/main/scripts/prepare_asar)

但“同源界面”不等于“完整桌面功能”：该项目 README 明确列出 terminal、git worker、browser panel 等尚未接通。因此，更换底座应比较桥接完整度、移动使用和兼容维护成本，而不是仅比较截图是否像 Desktop。[项目 README](https://github.com/0xcaff/codex-web)

## 候选比较

### yusing/codex-web：保留更多官方桌面运行层

- 同样提取官方 macOS 应用；同时保留 Electron main process，只把 renderer 的 IPC 边界改为浏览器通信。它不是自行重写的产品界面。[架构契约](https://github.com/yusing/codex-web/blob/main/doc/architecture/index.md)
- README 声明 Chat、Terminal、Files、Review、右键菜单、项目选择可用；移除了 Browser、Show pet 和 telemetry。该声明尚未经过本次实测。[README](https://github.com/yusing/codex-web)
- 明确使用或复用本机 app-server daemon，Web 适配服务停止不停止 daemon。实现从 `CODEX_HOME` 定位 `app-server-control/app-server-control.sock`，把该 Unix socket 传给本地 WebSocket 代理。因此它确实采用共享常驻 app-server 方式；任意自定义 socket 路径的公开启动选项没有在此次核查中确认。[运行主机源码](https://github.com/yusing/codex-web/blob/main/src/runtime-host.mjs)、[代理源码](https://github.com/yusing/codex-web/blob/main/src/app-server-proxy.mjs)
- 需要 Linux、Node 22+、Electron 运行库和支持 `app-server daemon` 的 CLI；部署命令会下载构建应用并管理 systemd user service。手机布局和具体 daemon 版本兼容尚未验证。[README](https://github.com/yusing/codex-web)

**建议边界：** 如果优先要 Terminal / Files / Review，值得作为隔离试用对象。不能直接承诺比当前方案稳定，也不能把官方组件保留较多视为全面兼容证明。

### pavel-voronin/codex-web-local：自行实现的轻量相似界面

- README 自称复现 desktop UI；仓库包含自己的 Vue 组件及 Vite 构建，没有上述下载、提取官方 Desktop 的构建方式。属于自行实现的客户端。[README](https://github.com/pavel-voronin/codex-web-local)、[包声明](https://github.com/pavel-voronin/codex-web-local/blob/main/package.json)、[组件目录](https://github.com/pavel-voronin/codex-web-local/tree/main/src/components)
- 后端直接 `spawn('codex', ['app-server'])`，使用 stdio JSON-RPC，通过 HTTP RPC 和 SSE 暴露给网页；释放 bridge 时会停止所拥有的子进程。此实现没有已存在 Unix socket 的连接路径，不能直接视为当前共享 daemon 的替代入口。[连接实现](https://github.com/pavel-voronin/codex-web-local/blob/main/src/server/codexAppServerBridge.ts)
- GitHub 标记该仓库于 2026-04-03 归档。此次未验证完整终端、Git、子代理或手机能力。[仓库状态](https://github.com/pavel-voronin/codex-web-local)

**建议边界：** 可读源码借鉴自主 UI；不优先推荐作为持续跟进当前 Desktop 和共享 daemon 的底座。

### coder/codex-server：公开源码证据不足

- 核查时 `coder/codex-server` GitHub 地址返回 404，无法据此确认其当前架构、可维护性和共享 socket 能力。[仓库入口](https://github.com/coder/codex-server)
- `@coder/codex-server` 的 npm latest 元数据仍指向该仓库，版本为 `26.519.41501-2`。包存在不等于当前源码可用或已经验证适合部署。[npm 元数据](https://registry.npmjs.org/@coder/codex-server/latest)

**建议边界：** 暂不作为已验证可替换的候选；需要先补齐当前源码与维护证据。

## 对当前决策的影响

1. 继续修当前版本并 fork 是合理选项：它已满足“官方 Desktop 前端来源”这一条件。
2. 如果缺的主要是终端、文件和 Review，先隔离试用 yusing 方案再决定底座，避免同时维护两个长期分支。
3. 两个提取官方应用的方案都仍包含浏览器桥接边界；提取同一前端不能直接证明分页问题不存在。当前分页问题仍需独立追踪请求、游标与加载触发，不能以替代项目的架构说明代替诊断。

以上为基于源码和维护者说明的判断；本轮没有 fork、迁移服务或修改运行中的部署。
