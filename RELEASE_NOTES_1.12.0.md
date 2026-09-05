# CodeCarry 1.12.0

- 恢复 Codex 服务器类型，可通过带 Token 的 WSS 地址连接服务器上的 Codex。
- 支持会话列表、历史、实时状态、流式内容、追发消息、中断、审批与提问。
- 恢复后台连接管理、重连及 Codex 通知跳转；保留 OpenCode 和 DSH。
- 新增鉴权后端，将公网连接转接到 Codex 共享 daemon。终端使用
  `codex --remote unix://`，即可与手机控制同一运行时。
- 使用 `turn/start` 返回的轮次确认发送，避免等待事件期间阻塞追发；迟到响应不覆盖已完成状态。
- 修复新建空会话尚无持久历史时的加载问题，并展示不同 provider 的会话。

部署与连接说明见 `codex-bridge/README.md`。独立 `codex exec` 进程不属于
共享 daemon 的实时控制范围。

验证：720 项 JVM 测试、4 项后端测试通过；Release lint 与签名验证通过。

APK SHA-256: `d335587cb49d8681d9c86f7830434c4a5d9b96117c0086628d170bda714475fc`
