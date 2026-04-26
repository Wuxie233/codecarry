---
date: 2026-04-27
topic: "Swipe-to-Archive (替换左划删除) + 归档对话浏览入口"
status: validated
---

# Swipe-to-Archive 设计

## Problem Statement

会话列表当前的左划行为是**直接删除**(无确认弹窗、无撤销)。这与典型的"开发完一个需求就归档"的工作流不匹配 —
用户想要的是清屏,但**保留对话以便日后查阅**。

需要解决两件事:
1. 左划手势从 **delete** 改为 **archive**(归档对话)
2. 提供清晰、显眼的入口让用户随时回到归档对话区找信息

## Constraints

### 已存在,直接复用(不要重复造轮子)

- `Session.time.archived: Long?` 数据字段 + `Session.isArchived` 派生属性
- `OpenCodeApi.archiveSession()` / `OpenCodeApi.restoreSession()`
- `SessionListViewModel.archiveSession(id)` / `restoreSession(id)` / `archiveProjectSessions(dir)`
- 行内 `DropdownMenu` 的 Archive/Restore/Delete 切换逻辑(`SessionRowMenuAction`)
- `SessionListPreferences` 持久化机制(DataStore)

### 不能动

- **右划 = rename** 保持不变(高频操作)
- **多选模式删除能力** 保留(归档变主路径,但删除仍可达)
- `SessionListViewModel` 现有数据流核心(`uiState`、`groups`、`prefsFlow`)

### 新约束

- Material 3 `SingleChoiceSegmentedButtonRow` + `SegmentedButton`(M3 1.2+)— planner 阶段确认 compose-bom 版本
- 归档空间内 filter chip(working / has-changes / has-errors)语义不适用,需要做可见性取舍
- 偏好迁移:旧版 `SessionFilter.ARCHIVED` 必须自动映射到新的 `SessionScope.ARCHIVED`

## Approach

**顶部 Segmented Control 切换 [Inbox / Archived] 两个空间**,叠加左划归档 + Snackbar undo(5s 撤销)。

### 设计决策来源(via octto session)

| 决策项 | 选择 |
|---|---|
| 归档展示方式 | **D — 顶部 Segmented Control [Inbox / Archived]** |
| Snackbar undo(5s) | **是** |
| 右划保持 rename | **是** |
| 删除入口处理 | **保留现状(行内菜单 + 多选模式)** |

### 拒绝的替代方案

- **A 仅强化现有 ARCHIVED filter chip**:入口不够直观,用户不一定意识到 chip 是归档入口
- **B 项目分组下挂归档子组**:语义清晰但层级嵌套复杂,折叠状态持久化成本高
- **C 列表底部归档入口**:需要决定子页面或原地展开,多一次跳转

### 核心理念

`SessionFilter.ARCHIVED` 与 `WORKING / HAS_CHANGES / HAS_ERRORS` **概念不同维**:
- `WORKING / HAS_CHANGES / HAS_ERRORS` 是会话的**状态**
- `ARCHIVED` 是会话的**位置**

把"位置"上提到顶部 segmented control,把"状态"留在 filter chip 行,语义清晰、扩展性最好(批量归档、自动归档 N 天前、归档清理都自然)。

## Architecture

### 总体结构(高层)

```
SessionListScreen
├─ TopBar(现有)
├─ SessionScopeSegmentedControl(新增) ← 关键新组件
│    └─ [Inbox] [Archived (N)]
├─ SessionListTopControls(现有,需调整)
│    ├─ Search
│    ├─ Sort
│    └─ FilterChips(scope-aware:Archived 空间下隐藏或仅 ALL)
├─ Project Groups List(现有)
│    └─ SessionRow with SwipeToDismissBox(改:左划 = 归档/还原)
└─ Snackbar Host(新增 undo 控制)
```

### 数据层变化

引入新的概念 `SessionScope`,**取代**现有 `SessionFilter.ARCHIVED`:

- `SessionScope { INBOX, ARCHIVED }` — 表达"主收件箱 / 归档仓库"两个空间
- `SessionListPreferences.scope: SessionScope`(默认 `INBOX`)— 持久化用户上次停留空间
- `SessionFilter` 中**移除 `ARCHIVED`** 项,其余保留

## Components

### 1. `SessionScope`(新增)

- **位置**:`data/preferences/SessionListPreferences.kt`
- **职责**:表达 Inbox / Archived 两个空间
- **持久化**:写入 DataStore,App 启动恢复

### 2. `SessionScopeSegmentedControl`(新增 Composable)

- **位置**:`ui/screens/sessions/components/SessionScopeSegmentedControl.kt`
- **职责**:渲染顶部两段切换器,Archived 一侧显示计数徽标
- **输入**:`currentScope`、`archivedCount`、`onScopeChange`
- **视觉**:`SingleChoiceSegmentedButtonRow`,Inbox 用 Inbox 图标,Archived 用 Archive 图标

### 3. `SessionListViewModel`(增强)

- **新方法** `setScope(SessionScope)` → 持久化 + 触发 sessions 视图刷新
- **新派生流** `archivedCount: StateFlow<Int>` → 用于 segmented control 徽标
- **现有 `setFilter` 调整**:filter 不再表达 ARCHIVED,纯粹在 inbox 内做状态筛选
- **过滤逻辑改写**:
  1. 按 `scope` 先分流:`INBOX` = `!isArchived`,`ARCHIVED` = `isArchived`
  2. INBOX 内再按 filter 二次筛选;ARCHIVED 内 filter 强制 `ALL`
- **新 undo channel** `_undoState: Channel<UndoAction>`
  - `UndoAction.Archive(id, title)`、`UndoAction.Restore(id, title)`

### 4. `SessionRow` swipe 行为(改造)

- **位置**:`SessionListScreen.kt` 现有 `dismissState`(约 1542-1557)+ background content(约 1776-1847)
- **左划行为**:
  - Inbox 空间 → `onArchive()`
  - Archived 空间 → `onRestore()`(对称合理)
- **右划**:`onRename()` 不变
- **背景与文案**(scope-aware 动态切换):
  - Inbox:`tertiaryContainer` + Archive 图标 + "归档"
  - Archived:`secondaryContainer` + Unarchive 图标 + "还原"

### 5. `SessionUndoSnackbar`(新增轻量逻辑)

- **位置**:`SessionListScreen.kt` 内 `SnackbarHost` + ViewModel 一侧的 undo channel
- **职责**:归档/还原后弹 Snackbar `已归档 "标题" [撤销]`(5s)
- **关键决策**:
  - **不**做"延迟实际归档"的 trick — 立刻调 archive,撤销 = 调 restore
  - 简单可靠,网络抖动也不会出错(restore 幂等)
  - 用户连续左划多条 → 后弹覆盖前弹(标准 Material 行为)

### 6. `SessionListTopControls`(微调)

- **位置**:`components/SessionListTopControls.kt:179-207`(filter chip 行)
- **改动**:
  - filter chip 行**隐藏 ARCHIVED chip**(已上提到 segmented control)
  - Archived 空间下:filter 行**整体折叠**(无 working/has-errors 概念)
  - sort 控件保留(归档列表也需要排序)

## Data Flow

### 切换 scope

```
User taps [Archived] segment
  → SessionScopeSegmentedControl.onScopeChange(ARCHIVED)
  → ViewModel.setScope(ARCHIVED)
  → preferencesRepo.setScope(ARCHIVED)        [persist]
  → prefsFlow emits new prefs
  → uiState combine 重新计算 groups
       ├─ scope=ARCHIVED → 仅 isArchived=true
       └─ filter 不生效(等同 ALL)
  → UI 重渲染列表
```

### Inbox 空间左划归档

```
User left-swipes session row in Inbox
  → SwipeToDismissBox.confirmValueChange(EndToStart)
  → onArchive()
  → ViewModel.archiveSession(id)
       ├─ api.archiveSession(conn, id)        [server]
       ├─ loadSessions() 刷新
       └─ _undoState.send(UndoAction.Archive(id, title))
  → Snackbar 显示 "已归档 [撤销]"(5s)
       └─ 用户点 [撤销]
            → ViewModel.restoreSession(id)
            → api.restoreSession(conn, id)
            → loadSessions()
            → Snackbar dismiss
```

### Archived 空间左划还原

```
User left-swipes session row in Archived
  → onRestore()
  → ViewModel.restoreSession(id)
  → loadSessions()
  → Snackbar "已还原 [撤销]"(5s)
       └─ 撤销 → archiveSession(id)
```

## Error Handling

| 场景 | 处理 |
|---|---|
| `api.archiveSession` 失败(网络 / 4xx / 5xx) | UI 不切换(loadSessions 拉回原状);Snackbar 显示 "归档失败,请重试",**不**显示撤销按钮 |
| Snackbar 撤销期间用户切 scope | 取消 undo channel,Snackbar 立即消失 |
| Snackbar 撤销期间 SSE 推来同 session 更新 | 不影响,restore 幂等 |
| 用户连续左划多条 | 后一条 Snackbar 覆盖前一条;每条独立 archive,只有最后一次 undo 可见(可接受) |
| 旧版偏好 `filter=ARCHIVED` | 反序列化时迁移为 `scope=ARCHIVED, filter=ALL` |
| compose-bom 版本不支持 SingleChoiceSegmentedButtonRow | planner 阶段检查并升版本(M3 1.2+ 都支持) |

## Testing Strategy

### 单元测试(ViewModel 层)

扩展 `SessionListViewModelTest.kt`:

- **scope 过滤**
  - scope=INBOX 时不显示 archived sessions
  - scope=ARCHIVED 时仅显示 archived
- **filter 在 archived 空间不生效**
  - scope=ARCHIVED + filter=WORKING 仍返回 archived 全集
- **`archivedCount` 派生流**
  - 增/删 archived session 后 count 正确变化
- **`setScope` 持久化**
  - 调用后 prefs 中 scope 字段更新
- **undo channel**
  - archive 后 channel 收到 `UndoAction.Archive`
  - restore 后 channel 收到 `UndoAction.Restore`

### 偏好迁移测试

扩展 `SessionListPreferencesRepositoryTest.kt`:

- 旧版 `filter=ARCHIVED` → 加载后变成 `scope=ARCHIVED, filter=ALL`

### Swipe 行为测试

扩展 `SessionRowMenuActionsTest.kt`(或新增 `SessionRowSwipeTest.kt`):

- Inbox 空间 EndToStart 左划 → 触发 archive 回调
- Archived 空间 EndToStart 左划 → 触发 restore 回调
- 两个空间 StartToEnd 右划 → 都触发 rename(回归)

### UI 测试(看现有覆盖,可选)

- Segmented control 显示 archived count
- 切换 scope 时 filter chip 行可见性

### 不能破坏的现有测试

- `BuildActiveConversationsTest` — 活跃对话 banner 数据流不受影响
- `SessionListEmptyStateTest` — empty state 行为扩展:Archived 空间空时显示对应文案

## Open Questions

**无阻塞性问题**,几个交给 planner 阶段定:

- **Snackbar 时长**:Material 默认 4s,倾向 5s(多 1 秒决策窗口)
- **Archived 空间下 filter 行**:完全隐藏,还是保留 sort 控件(倾向后者)
- **compose-bom 版本检查**:`gradle/libs.versions.toml`,确保支持 M3 1.2+
- **多语言**:strings.xml 中英先齐(`sessions_scope_inbox` / `sessions_scope_archived` / `sessions_archive_action` / `sessions_restore_action` / `sessions_undo_action` / `sessions_archive_success` / `sessions_archive_failed`),其余语言走 lokit 流程

## Implementation Outline(交给 planner 细化)

1. **数据层**:`SessionScope` 枚举、`SessionListPreferences` 加 scope 字段、DataStore 迁移逻辑
2. **ViewModel**:`setScope`、`archivedCount` 流、scope-aware 过滤、undo channel
3. **UI**:新增 `SessionScopeSegmentedControl`、改 `SessionListTopControls`、改 `SessionRow` swipe 行为、加 SnackbarHost
4. **文案**:strings.xml 中英先齐,其余语言后续走 lokit
5. **测试**:覆盖上述所有用例
6. **手测**:网络断开归档失败 / Snackbar 撤销 / scope 持久化跨 App 重启
