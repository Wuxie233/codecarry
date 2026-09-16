# 两个 Codex Web 项目的能力对比

核查日期：2026-09-16。本轮仅查源码、项目文档及 GitHub API，未运行 yusing，也未修改线上服务。支持声明不等于实机验收。

## 固定基线

- 0xcaff/codex-web：`0dfdc10768c724d9a6ba507a93c3d6314ef073d8`；301 stars，最后提交 2026-09-12，近 30 天 2 次提交。
- yusing/codex-web：`274f06662b1589f1af85341d6021b44c33563a38`；1 star，最后提交 2026-08-11，近 30 天 0 次提交。
- 活跃度来自本轮 GitHub API 查询；不是稳定性评分。[0xcaff API](https://api.github.com/repos/0xcaff/codex-web)、[yusing API](https://api.github.com/repos/yusing/codex-web)

## 架构与能力

| 能力 | 0xcaff | yusing |
| --- | --- | --- |
| 前端来源 | 下载官方 Desktop 26.901.41123 并打补丁 | 下载官方桌面应用并打补丁，最新提交适配 26.803.81509 |
| 官方主进程代码 | 保留，Node 加载官方 main bundle，Electron API 由 shim 替代 | 保留，在实际 headless Electron 中运行 main/preload |
| 会话、聊天、子代理 | README 声明 subagents、inline images、transcription 可用 | README 声明 Chat 可用；有子代理标题元数据补丁 |
| 终端 | README 明确尚未接通 | README 声明 Terminal 可用；实际 Electron/worker 桥接提供实现基础，未实测 |
| 文件 | 有 editor sidepanel、上传、工作目录选择、宿主文件路由 | README 声明 Files 与项目选择可用；未实测完整编辑能力 |
| Diff / Git | git worker integration 尚未接通；不应将显示 diff 等同完整 Git 工作流 | README 声明 Review 可用；不能据此承诺所有提交、分支、worktree 操作 |
| 浏览器面板 | roadmap | 明确移除 |
| 共享 app-server | 官方提供 proxy helper，可指定 CODEX_UNIX_SOCKET | runtime-host 启动或复用 CODEX_HOME 下的 daemon control socket |
| Web 重启 | 使用独立 daemon 时可保持执行 | 文档明确 Web 与 daemon 生命周期分离 |
| 登录认证 | 无内置认证，需外置保护 | capability URL 授权；不是多用户账号系统 |
| 移动 / PWA | 上游有移动侧栏行为和 PWA manifest；不代表离线或推送支持 | 有 standalone PWA manifest；未找到专门手机布局，未实测 |
| 运行依赖 | Node、Codex；共享 proxy helper 额外需要 websocat | Linux/systemd、Node、Electron 库、构建工具、支持 daemon 命令的 CLI |
| 升级 | 固定包版本，人工迁移补丁，有 UPGRADING.md | 默认下载最新 DMG，但补丁仅接受 26.803.81509；其他版本直接拒绝 |

0xcaff 依据：[架构](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/ARCHITECTURE.md)、[功能和缺口](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/README.md)、[服务实现](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/src/server/main.ts)、[升级](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/UPGRADING.md)。

yusing 依据：[README](https://github.com/yusing/codex-web/blob/274f06662b1589f1af85341d6021b44c33563a38/README.md)、[runtime host](https://github.com/yusing/codex-web/blob/274f06662b1589f1af85341d6021b44c33563a38/src/runtime-host.mjs)、[app-server proxy](https://github.com/yusing/codex-web/blob/274f06662b1589f1af85341d6021b44c33563a38/src/app-server-proxy.mjs)。

## yusing 的额外限制

- `src/upstream-patches.mjs` 固定 `pinnedApplicationVersion = "26.803.81509"`，其他应用版本直接报错；`src/extract-build.mjs` 默认下载动态最新 DMG。当前直接部署能否成功未验证，可能需要匹配旧包或更新补丁。这不是无条件自动跟随上游升级。
- browser bridge 的 `getPathForFile`、`startFileDrag` 为 unsupported；README 的 Files 支持不能延伸为手机上传和拖拽全部可用。
- 两项依据：[补丁实现](https://github.com/yusing/codex-web/blob/274f06662b1589f1af85341d6021b44c33563a38/src/upstream-patches.mjs)、[构建实现](https://github.com/yusing/codex-web/blob/274f06662b1589f1af85341d6021b44c33563a38/src/extract-build.mjs)。

## 与当前分页、兼容问题的关系

两者都适配官方 IPC，不是完全无改动的 Desktop。0xcaff 的 proxy helper 丢弃启动参数后连接既有 socket；因此桌面进程原本要注入的配置不会自动应用到共享 daemon。yusing 的 daemon 启动也使用自有参数，不能推定它完整保留 Desktop 的启动配置。

尤其，两者都关闭 initial sidebar bootstrap：0xcaff 的 `codex_desktop:get-initial-sidebar-bootstrap` 返回 null；yusing 对应桥接 `getInitialSidebarBootstrap` 也返回 null。因此 yusing 有一条相关提交，不构成它已解决当前分页问题的证据。[0xcaff shim](https://github.com/0xcaff/codex-web/blob/0dfdc10768c724d9a6ba507a93c3d6314ef073d8/src/browser/shim.ts)、[yusing patches](https://github.com/yusing/codex-web/blob/274f06662b1589f1af85341d6021b44c33563a38/src/upstream-patches.mjs)

当前环境额外添加的 Android 壳、认证代理、手机覆盖式侧栏、深色背景和 Desktop MCP 修复，属于本地集成，不能算作 0xcaff 原生能力。此前已观察到分页还有下一页但未处于请求状态的转圈；根因仍需请求与游标对照，不属于本报告已解决项。

## 决策建议

- yusing 的优势集中在桌面工作台功能，特别是 Terminal / Files / Review；代价是 Electron 运行环境和较旧、较少的维护活动。
- 当前方案已有部署与移动修复，0xcaff 更新的 Desktop 包较新；若主需求是手机接续会话，迁移收益尚未得到验证。
- 若未来主要增加自定义需求，两者都需要维护生成包补丁，而不是直接修改完整的官方 UI 源码；改动越深入，升级成本越高。底座选择应计入这个共同限制。
- 建议先保留当前服务，把 yusing 作为有明确终端/Review 需求时的隔离对照对象；在分页问题定位前，不把换项目当作修复。
