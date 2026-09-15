# Codex 手机客户端替代方案

核查日期：2026-09-16。

目标：先体验别人维护的客户端；满足日常使用就替换 CodeCarry 的 Codex 使用路径，存在明确缺口时再考虑借鉴。范围优先 Android、手机 Web、自有主机上的 Codex。

证据范围：查阅项目维护者的仓库、文档、商店及发布记录；本次没有安装、接入现有服务或进行手机实测。功能描述代表文档声明，试用顺序是根据安装门槛和需求匹配作出的判断，不是体验评分。`main` 文档可能领先发布版。

## 先试哪几个

1. **Happy**：先装现成 Android 客户端，最容易判断聊天、通知和手机操作是否顺手。
2. **HAPI**：先用 Web/PWA；重点体验终端与手机协作、队列、审批和会话恢复。原生 Android 目前需要额外构建，不必为第一轮试用先承担这项工作。
3. **codex-web**：想体验接近 Codex Desktop 的界面，或必须保留已有 Unix app-server 时再试。
4. **Codex Gateway / CX-Codex**：分别留给多 SSH 主机工作台、Windows 主机与 Android 壳需求。

先试前两个就足以回答“现成产品能否替代”；无需同时部署五套。

## 候选与实际入口

### 1. Happy：现成手机 App

- 入口：[项目](https://github.com/slopus/happy)、[Android 商店](https://play.google.com/store/apps/details?id=com.ex3ndr.happy)、[Web App](https://app.happy.engineering/)、[官网与文档](https://happy.engineering/)。也提供 iOS 和 macOS 客户端。
- 试用路径：主机安装 `npm install -g happy`，通过 `happy codex` 启动会话，再从手机连接。README 已将旧包名 `happy-coder` 替换为 `happy`。
- 会话模式：通过 Happy wrapper 启动。README 描述手机接管时切入 remote mode 并重启会话；没有证明可直接附着 CodeCarry 当前使用的任意 shared daemon。不要把“继续上下文”当成“原执行进程完全不变”。
- 可体验的 UX：推送提醒、手机与终端切换、聊天和工具展示。
- 限制：需要接受 Happy 的 CLI/同步服务链路；现有长任务能否原样接管需另验。以上来自[官方 README](https://github.com/slopus/happy)。

### 2. HAPI：先体验 Web，再考虑原生客户端

- 入口：[项目与快速开始](https://github.com/tiann/hapi)、[发布下载](https://github.com/tiann/hapi/releases)、[原生客户端指南](https://github.com/tiann/hapi/blob/main/docs/guide/native-apps.md)。支持 Web/PWA、Telegram Mini App、Android/iOS。
- 试用路径：`npx @twsxtd/hapi hub --relay` 启动 hub，再用 `npx @twsxtd/hapi codex` 启动 Codex；打开 hub 打印的 Web 地址。手机端创建会话还需要运行 Runner。
- 当前原生指南给 Android 8.0+ 的源码构建路径；官方构建工作流不发布 Google Play。Android 连接要求 HTTPS。核查到的最新 GitHub Release `v0.30.7` 有主机二进制，没有 APK，因此第一轮直接使用 Web。[原生指南](https://github.com/tiann/hapi/blob/main/docs/guide/native-apps.md)、[该版资产](https://github.com/tiann/hapi/releases/tag/v0.30.7)。
- 会话模式：当前指南声明 Codex **0.154.0+** 的终端与手机可同时连接同一个 app-server；`hapi resume <id>` 附着 HAPI 的活跃 execution。**独立启动各自拥有 app-server，不是全局 daemon**。原始终端关闭会结束它拥有的 execution；额外附着的终端退出只断开自身。其他 live owner 的线程不会被自动接管。[共享会话契约](https://github.com/tiann/hapi/blob/main/docs/guide/codex-shared-sessions.md)。
- 可体验的 UX：原生队列与显式 steering、审批/问题跨端解决、会话恢复、文件和 diff、草稿暂存。先核对实际安装的 CLI/Hub/Runner 版本；本次没有证明上述 `main` 行为全部进入 npm 当前发布包。

### 3. 0xcaff/codex-web：浏览器里的 Desktop 风格界面

- 入口：[项目、演示与安装](https://github.com/0xcaff/codex-web)。浏览器客户端；宿主覆盖 Linux/macOS 等能运行 Codex CLI 与 Node 的环境。
- 试用路径：已登录 Codex 的主机运行 `npx --yes github:0xcaff/codex-web`；默认本机入口 `127.0.0.1:8214`，手机访问需 VPN/代理。
- 会话模式：默认托管后端，也明确提供 `codex_remote_proxy`、`CODEX_UNIX_SOCKET` 对接已运行 Unix app-server 的示例。这是五个候选中，文档对现有 Unix socket 复用说明最直接的一个；和具体 daemon 版本的兼容性仍未实测。
- 可体验的 UX：Desktop 风格工作区、子代理、内联图片、编辑器侧栏。
- 限制：README 列出 terminal、git worker、browser panel 尚未接好；鉴权由外部 VPN/代理负责。它提取并包装 Desktop 前端，不能据此推定上游 UI 资产可复制到 CodeCarry。[README](https://github.com/0xcaff/codex-web)、[构建与包声明](https://github.com/0xcaff/codex-web/blob/main/package.json)。

### 4. Codex Gateway：多主机 Web 工作台备选

- 入口：[项目、截图和 Docker 部署](https://github.com/yunhaoli24/codex-gateway)。中文/英文 Web，有移动布局。
- 试用路径：克隆含子模块的仓库，用 Docker Compose 构建、创建用户、配置反向代理，再添加 SSH 主机；比前几项更重。
- 会话模式：Gateway 通过 SSH 管理远程 app-server，每台 host 共享 RPC 与事件分发，可发现并恢复线程。README 的同线程多端同步承诺，不能证明它直接复用已有任意 Unix daemon。
- 可体验的 UX：多主机/项目导航、子代理侧栏、文件/diff/终端/预览并列、长任务通知。
- 限制：会升级 Codex、启动及重启 stale app-server。试用需要先隔离当前工作中的 runtime。其面板密度是否适合手机聊天未实测；仓库宣称的 E2E 覆盖不是本次验收结果。[架构与功能说明](https://github.com/yunhaoli24/codex-gateway)。

### 5. CX-Codex：Windows 主机与 Android 壳备选

- 入口：[项目与 Windows 安装](https://github.com/Qjzn/CX-Codex)、[Release APK/Web 包](https://github.com/Qjzn/CX-Codex/releases)、[Android 说明](https://github.com/Qjzn/CX-Codex/blob/main/docs/android-shell.zh-CN.md)。核查到 `v2.8.0` 提供签名 APK 和校验文件。
- 试用路径：Windows 主机按官方 bootstrap 配好服务，手机安装 Release APK 并填写服务地址/密钥；也可先用浏览器。项目明确 npm 包未发布，不应杜撰 npx 安装命令。
- 会话模式：使用本机 Codex、登录态和项目，提供历史及后续对话；本轮文档不足以证明会接管另一个活跃 daemon。
- 可体验的 UX：单栏手机消息流、附件集中在 `+` 菜单、前台恢复补同步、任务浮窗。
- 限制：Android 是 Web 服务壳；Windows/Windows Server 为主要支持场景，不能把它当成已验证的 Linux 原生替代。截图明确采用演示数据，不证明长会话可靠性。[README](https://github.com/Qjzn/CX-Codex)。

## 活跃状态与复用声明

下表是 GitHub API 在核查日返回的默认分支最新提交日期（UTC），只能说明近期有提交，不能证明维护质量或未来持续投入；五个仓库当时均未归档。

| 项目 | 最近提交 / 快照 | 许可证证据 |
| --- | --- | --- |
| Happy | 2026-09-14 / `c8552c0` | [MIT LICENSE](https://github.com/slopus/happy/blob/main/LICENSE) |
| HAPI | 2026-09-15 / `08dfce6` | [AGPL-3.0 LICENSE](https://github.com/tiann/hapi/blob/main/LICENSE) |
| codex-web | 2026-09-12 / `0dfdc10` | [package.json 声明 MIT](https://github.com/0xcaff/codex-web/blob/main/package.json)；GitHub API 未识别仓库许可证，不扩展推定 Desktop 资产授权 |
| Codex Gateway | 2026-09-12 / `8514266` | [MIT LICENSE](https://github.com/yunhaoli24/codex-gateway/blob/main/LICENSE) |
| CX-Codex | 2026-09-08 / `124e7c5` | [MIT LICENSE](https://github.com/Qjzn/CX-Codex/blob/main/LICENSE) |

状态来源：[Happy API](https://api.github.com/repos/slopus/happy)、[HAPI API](https://api.github.com/repos/tiann/hapi)、[codex-web API](https://api.github.com/repos/0xcaff/codex-web)、[Gateway API](https://api.github.com/repos/yunhaoli24/codex-gateway)、[CX API](https://api.github.com/repos/Qjzn/CX-Codex)；日期来自各仓库 `/commits?per_page=1` 响应。

这里只记录项目声明，不作许可证兼容性结论。体验产品与复制实现是两件事：如以后移植代码，需要核对目标文件、第三方依赖及随附资产的许可；先借鉴经实际体验认可的交互，不直接搬前端 bundle、品牌或图片。

## 如何决定替换还是借鉴

建议拿同一项日常任务逐个体验，记录具体阻碍，而不是先比较功能数量：

| 场景 | 通过的实际表现 |
| --- | --- |
| 中文聊天与连续追问 | 输入、粘贴、换行、草稿和发送自然；运行中消息去向明确 |
| 长会话与子代理 | 历史能读，返回主会话不迷路，能分清哪个任务在运行 |
| 手机后台与弱网 | 锁屏后回来能恢复，完成状态不滞留，审批不重复提交 |
| 图片、代码与文件 | 图片可看、代码可复制、横向内容可操作、文件入口明确 |
| 跨端协作 | 终端与手机控制的是预期线程；接管是否重启执行能解释清楚 |
| 日常维护 | 更新和连接成本可接受，不需要长期维护自己的 fork 才能用 |

**替换条件建议**：主要日常任务可完成，关键恢复/审批可靠，维护成本低于 CodeCarry；不要求功能逐项一样。

**借鉴条件建议**：替代品仍有明确阻碍，但某个交互显著更顺手；此时只记录该交互、截图和复现步骤，再决定是否改 CodeCarry。本次没有决定重构、迁移生产会话或停掉现有客户端。
