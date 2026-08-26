# Cursor 控制面：技术链路可行性

调查日期：2026-03-26（仓库本地日期以会话为准）。
范围：CodeCarry 是否能以类似 OpenCode / DSH 的方式增加 Cursor 后端；官方协议、开源复用、ToS 边界。
结论：**可行，但“Cursor 控制面”不是一条链路。** 官方提供三条互不兼容的面；手机端不能直接跑 Cursor agent。不要走 IDE 私有协议 / OpenAI 兼容反代。

## 1. CodeCarry 现状（本仓库）

- 后端边界是 `ServerType`：只有 `OPENCODE` 和 `DSH`。未知持久化类型在 DataStore 读取时丢弃。见 `app/src/main/kotlin/dev/wuxie233/codecarry/domain/model/ServerConfig.kt`、`ServerRepository.kt`。
- 1.9.0 产品删除了 Codex / Pi Stack / Pi Roundtable，明确“每种后端自有 transport，不复用 OpenCode REST/SSE”。见 `docs/specs/dsh-control-surface-1.9.md`。
- Chat 壳可复用：`ChatBackendCapabilities` 按 `ServerType` 开关附件、@file、模型、终端、slash。Cursor 会是第三个 capability 矩阵，不是第三个 Chat UI。
- 插入点与 DSH 同级：`data/cursor/`（transport + reduce）+ Home 连接分支 + Sessions/Chat 的 `isCursor` 分流。不能复用 OpenCode `EventReducer` 或 DSH mux/host。

## 2. 官方三条链路（可合法使用）

### A. Cloud Agents API（公有 HTTP，最像“远程控制面”）

- 文档：[Cloud Agents API](https://cursor.com/docs/cloud-agent/api/endpoints)、[API Overview](https://cursor.com/docs/api)
- 基址：`https://api.cursor.com`。v1 公测；v0 仍可用（webhook 还在 v0）。
- Auth：API key（`crsr_…`）。Basic `key:` 或 Bearer。用户 key 或 service account。
- 能力：创建/列出 agent、run、SSE stream、取消、artifact、模型目录、仓库列表、fleet/pool。这是 **Cursor 托管 VM 上的云 agent**，不是本机 IDE 窗口。
- 官方移动端：[Cursor for iOS](https://cursor.com/docs/cloud-agent/mobile) 就是这条面 + Remote Control。Android **planned, no date**（[help](https://cursor.com/help/ai-features/mobile-app)）。
- 对 CodeCarry：Android 可直接打 HTTPS，**不需要本机 sidecar**。产品形态接近官方 iOS，而不是“连本机 Cursor 窗口”。

### B. Cursor CLI ACP（本机 agent，stdio，不是 HTTP 服务）

- 文档：[ACP](https://cursor.com/docs/cli/acp)、[CLI using](https://cursor.com/docs/cli/using)
- 启动：`agent acp`。传输：stdio + JSON-RPC 2.0 + 换行分帧。
- 流程：`initialize` → `authenticate` (`cursor_login`) → `session/new|load` → `session/prompt`；流式 `session/update`；工具批准 `session/request_permission`（`allow-once` / `allow-always` / `reject-once`）。
- Cursor 扩展方法：阻塞 `cursor/ask_question`、`cursor/create_plan`；通知 `cursor/update_todos`、`cursor/task`、`cursor/generate_image`。
- Auth：先 `agent login`，或 `--api-key` / `CURSOR_API_KEY`。
- 对 CodeCarry：手机不能 spawn `agent`。必须在开发机上再包一层 TCP/HTTP/WS。ACP 规范本身开源：[agentclientprotocol](https://github.com/agentclientprotocol/agent-client-protocol)，有 [Kotlin SDK](https://github.com/agentclientprotocol/kotlin-sdk)（JVM 为主，Android 目标未完成）。

### C. `@cursor/sdk` + SDK Bridge（官方推荐的跨语言本机/云统一面）

- 文档：[TypeScript SDK](https://cursor.com/docs/sdk/typescript)、[SDK Bridge](https://cursor.com/docs/sdk/bridge)
- 协议仓：[cursor/sdk-bridge](https://github.com/cursor/sdk-bridge)（MIT，`sdk.v1` protobuf）。
- Bridge 是本机小服务器，内嵌 `@cursor/sdk`，loopback HTTP/1.1 + Connect（可用 JSON POST，**不是**经典 gRPC/HTTP2）。
- 同一套 API 覆盖 **local**（agent loop 在本机、文件在磁盘）和 **cloud**（Cursor VM）。推理始终走 Cursor 托管模型。
- 两个密钥：Cursor API key + 进程内 bridge bearer。默认听 `127.0.0.1`。
- 官方态度：TS/Python 用一等 SDK；其他语言写 thin adapter。Java/Kotlin adapter 需要自己做，但协议受支持。
- 已知摩擦：bridge 错误有时塌成 `internal: internal error`；默认 loopback，LAN 暴露要自己做反代/鉴权（和 DSH 的 `trustedHosts` 同类问题）。

### 官方明确不是控制面的东西

- 没有 OpenAI 兼容 `/v1/chat/completions`。论坛员工：[unofficial proxy 调私有 client 端点违反 ToS §1.5，可封号](https://forum.cursor.com/t/using-cursor-frontier-models-like-composer-2-5-in-external-harnesses-e-g-codex/164676)。合法路径只有 CLI / Agent SDK / Cloud Agents API，且始终跑 Cursor harness，不是裸模型。
- 没有“打开 Cursor IDE 就能当 OpenCode 那样的 HTTP server”。
- IDE Composer / `api2.cursor.sh` protobuf 是私有协议。ToS §1.5 禁止 reverse engineer / 提取内部结构（[terms](https://cursor.com/terms-of-service)）。

## 3. 开源项目：能复用什么

按“给 CodeCarry 用”排序。许可证与协议以仓库当时状态为准。

| 项目 | 协议 | 控什么 | 复用建议 |
| --- | --- | --- | --- |
| [cursor/sdk-bridge](https://github.com/cursor/sdk-bridge) MIT | Connect/`sdk.v1` protobuf，loopback HTTP | 官方 local+cloud agent | **协议真源。** 不要 fork 产品，vendor proto，在宿主机跑 binary，Android 做 Connect JSON 客户端或再包一层 REST。 |
| [sanjaysingh/cursor-cp](https://github.com/sanjaysingh/cursor-cp) MIT | REST + WebSocket，默认 :8747 | 本机 `@cursor/sdk` session、模型、仓库、Telegram | **最接近“本机控制面 sidecar”。** 星少（约 2），但是官方 SDK 上的产品形状。可参考 API 划分，不建议当硬依赖。 |
| [jon-makinen/cursor-local-remote](https://github.com/jon-makinen/cursor-local-remote) MIT | Next.js HTTP + SSE | spawn `agent -p --output-format stream-json`；读 `~/.cursor/projects/` transcript | **最像 OpenCode 的 HTTP/SSE。** 约 33★。会话历史来自磁盘 transcript，不是 IDE 窗口。可参考 REST 形状；CLI stream-json 比 ACP 脆。 |
| [tryAGI/CursorAgents](https://github.com/tryAGI/CursorAgents) MIT | 官方 Cloud Agents OpenAPI 生成的 C# | 仅云 agent | 参考 OpenAPI，不要引 C#。Android 直接对着官方 YAML 写 Ktor。 |
| [aidmet/cursor-agent-api](https://github.com/aidmet/cursor-agent-api) | 非官方 Python，映射 v0 | 云 agent | 参考即可；v1 已改成 agent+run。 |
| [agentclientprotocol/kotlin-sdk](https://github.com/agentclientprotocol/kotlin-sdk) | ACP JSON-RPC | 通用 ACP 客户端 | 若选本机 ACP：JVM 模型可复用；仍需宿主机 `agent acp` + 网络封装。Android target 未完成。 |
| [len5ky/CursorRemote](https://github.com/len5ky/CursorRemote) 专有许可 | CDP 扫 IDE DOM + socket.io | 本机 Cursor **窗口** | **跳过。** 许可收费、DOM 易碎、不是 agent 协议。 |
| Cursor-To-OpenAI / zhx47/cursor-api / everestmz/cursor-rpc | 反编译 protobuf → `api2.cursor.sh` | 把订阅当模型 API | **跳过。** 官方已定性违规。 |

OpenAI 兼容 CLI 包装（cursorpipe、cursor-agent-api-proxy、cursor-api-proxy）只解决“当 LLM endpoint”，不是 CodeCarry 要的 session/permission/workspace 控制面。

## 4. 三条产品路径对照 CodeCarry

```
路径 Cloud（无 sidecar）
  手机 ──HTTPS──► api.cursor.com /v1/agents
  像官方 iOS：云 VM + PR/diff，不碰本机工作区

路径 Local sidecar（像现在连 OpenCode/DSH）
  手机 ──HTTP(S)/SSE/WS──► 开发机 sidecar ──► agent acp 或 cursor-sdk-bridge 或 @cursor/sdk
  文件、shell、测试在本机；需要 LAN/反代/token，和 DSH trustedHosts 同类

路径 IDE CDP
  手机 ──► 扫 Cursor 窗口 DOM
  不推荐：脆、许可、ToS、也不是 agent 生命周期
```

能力缺口（无论哪条）：

- 官方 iOS 也不是 IDE：无编辑器、无终端、无文件树；只有 agent 指挥 + diff/PR。
- Cloud 路径没有本机未提交工作区（除非 Self-hosted pool / My Machines / Remote Control，那些是 Cursor 桌面功能，不是公开 Android API）。
- Local 路径没有“官方 HTTP 端口”；必须自建 sidecar。Bridge 默认只绑 loopback。
- Cursor 付费计划才能跑 cloud/remote agent；API key 计费走 usage dashboard 的 SDK/API 标签。

## 5. 工程可行性判断

| 路径 | 技术可行性 | 法律/稳定 | 和 CodeCarry 匹配 | 工作量量级 |
| --- | --- | --- | --- | --- |
| Cloud Agents API 原生 `ServerType.CURSOR` | 高。Ktor + SSE，和 OpenCode 心智接近 | 官方、beta 可变 | 产品是云 agent，不是“遥控这台机器上的 Cursor” | 中（一个新 transport，小于 DSH） |
| 宿主机跑 sdk-bridge / cursor-cp，Android 当客户端 | 高 | 官方 SDK | 最像现有“填 URL 连服务器” | 中高（sidecar 运维 + 新 transport） |
| 宿主机跑 `agent acp` + 自研 WS 桥，Android 用 ACP Kotlin 模型 | 中 | 官方 ACP | 本机会话/权限最完整 | 高（stdio 进程生命周期、权限、Cursor 扩展方法） |
| 复用 cursor-local-remote 的 HTTP | 中 | CLI 官方，HTTP 形状非官方 | 最快出 Chat | 中，但 stream-json 与 transcript 文件会漂 |
| 反编译 IDE / OpenAI 反代 | 能做 | **不要做** | 和 1.9 删 Codex/Pi 的理由同类 | 永久维护税 |

1.9 的教训直接适用：第三方/脆弱 transport 进 `ServerType` 后，删除成本是整条产品面。Cursor 只应接 **官方、有版本合同的面**（Cloud OpenAPI 或 `sdk.v1` 或 ACP）。

## 6. 建议（工程默认，产品未确认）

若目标是“手机指挥 Cursor 干活”，优先：

1. **Cloud Agents API 作为第一期**：无 sidecar、官方 HTTP、和 iOS 同源。Chat + session list + follow-up + cancel + model。PR/artifact 可第二期。
2. **本机能力不要伪装成 Cloud**：若用户要改这台 Linux/Windows 上的未提交代码，第二期再加 sidecar（`cursor-sdk-bridge` 或自研薄 REST，协议跟 `sdk.v1`，不要 CDP）。
3. **不要**把 cursor-local-remote / CursorRemote 嵌进 APK。最多当 sidecar 参考实现。
4. **不要**碰 `api2.cursor.sh` protobuf。

## 7. 来源

- https://cursor.com/docs/api
- https://cursor.com/docs/cloud-agent/api/endpoints
- https://cursor.com/docs/cli/acp
- https://cursor.com/docs/cli/using
- https://cursor.com/docs/sdk/typescript
- https://cursor.com/docs/sdk/bridge
- https://cursor.com/docs/cloud-agent/mobile
- https://cursor.com/help/ai-features/mobile-app
- https://cursor.com/terms-of-service
- https://forum.cursor.com/t/using-cursor-frontier-models-like-composer-2-5-in-external-harnesses-e-g-codex/164676
- https://github.com/cursor/sdk-bridge
- https://github.com/sanjaysingh/cursor-cp
- https://github.com/jon-makinen/cursor-local-remote
- https://github.com/agentclientprotocol/kotlin-sdk
- `docs/specs/dsh-control-surface-1.9.md`
- `app/src/main/kotlin/dev/wuxie233/codecarry/domain/model/ServerConfig.kt`
- `app/src/main/kotlin/dev/wuxie233/codecarry/ui/screens/chat/ChatBackendCapabilities.kt`
