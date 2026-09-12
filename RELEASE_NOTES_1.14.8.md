# CodeCarry 1.14.8

- 修复 Codex 桥接层在完整对话历史超过 16 MiB 时断开连接的问题，daemon 响应上限提高到 64 MiB，保留完整历史。
- 改善 Codex 连接错误诊断：保留 WebSocket 关闭码与接收异常，超限和上游异常不再被桥接层隐藏为正常关闭。

服务器端需同时更新并重启 CodeCarry Codex bridge；仅升级 APK 无法改变旧桥接层的 16 MiB 限制。无需重启 Codex daemon 或正在执行的任务。
