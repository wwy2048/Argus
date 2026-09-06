# 问题跟踪器：GitHub

本仓库的问题与规格以 GitHub issue 的形式存在。所有操作统一使用 `gh` CLI。

## 约定

- **创建 issue**：`gh issue create --title "..." --body "..."`。多行内容使用 heredoc。
- **读取 issue**：`gh issue view <number> --comments`，用 `jq` 过滤评论，同时拉取标签。
- **列出 issue**：`gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`，并配合相应的 `--label` 与 `--state` 过滤。
- **评论 issue**：`gh issue comment <number> --body "..."`
- **添加 / 移除标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **关闭**：`gh issue close <number> --comment "..."`

仓库由 `git remote -v` 推断；在克隆内运行时 `gh` 会自动完成。

## 将 PR 作为分类入口

**PRs as a request surface: no.**（是否将 PR 作为请求入口：否）_（若本仓库把外部 PR 视为功能请求，请把此处改为 `yes`；`/triage` 会读取此标志。）_

设为 `yes` 时，PR 与 issue 走同一套标签与状态，使用 `gh pr` 的对应命令：

- **读取 PR**：`gh pr view <number> --comments`，并用 `gh pr diff <number>` 查看差异。
- **列出待分类的外部 PR**：`gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments`，仅保留 `authorAssociation` 为 `CONTRIBUTOR`、`FIRST_TIME_CONTRIBUTOR` 或 `NONE` 的项（去掉 `OWNER`/`MEMBER`/`COLLABORATOR`）。
- **评论 / 打标签 / 关闭**：`gh pr comment`、`gh pr edit --add-label`/`--remove-label`、`gh pr close`。

GitHub 的 issue 与 PR 共用同一套编号空间，因此裸的 `#42` 可能是其中任一种：先用 `gh pr view 42` 解析，失败再回退到 `gh issue view 42`。

## 当技能说“发布到问题跟踪器”

创建一个 GitHub issue。

## 当技能说“抓取相关工单”

运行 `gh issue view <number> --comments`。

## 路径查找（wayfinding）操作

由 `/wayfinder` 使用。**地图**是一个单一 issue，**子 issue** 作为工单。

- **地图**：一个标记为 `wayfinder:map` 的单一 issue，承载 Notes / Decisions-so-far / Fog 正文。`gh issue create --label wayfinder:map`。
- **子工单**：经由 GitHub 子 issue 端点（`gh api` 子 issue 端点）与地图关联的 issue。若未启用子 issue，则在地图正文中把子项加入任务列表，并在子项正文顶部写 `Part of #<map>`。标签：`wayfinder:<type>`（`research`/`prototype`/`grilling`/`task`）。认领后，工单分配给驱动开发的开发者。
- **阻塞**：GitHub 的**原生 issue 依赖**，即 UI 中可见的正式表示。用 `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>` 添加边，其中 `<blocker-db-id>` 是阻塞者的数字**数据库 id**（`gh api repos/<owner>/<repo>/issues/<n> --jq .id`，_不是_ `#number` 或 `node_id`）。GitHub 报告 `issue_dependencies_summary.blocked_by`（仅开放阻塞者，即实时门禁）。若依赖不可用，则回退为在子项正文顶部写一行 `Blocked by: #<n>, #<n>`。当所有阻塞者都关闭时，该工单解除阻塞。
- **前沿查询**：列出地图的开放子项（`gh issue list --state open`，限定到地图的子 issue / 任务列表），去掉存在开放阻塞者（`issue_dependencies_summary.blocked_by > 0`，或在 `Blocked by` 行中有开放 issue）或有指派者的项；按地图顺序取第一个。
- **认领**：`gh issue edit <n> --add-assignee @me`，即会话的首次写入。
- **解决**：`gh issue comment <n> --body "<answer>"`，然后 `gh issue close <n>`，最后把上下文指针（gist + 链接）追加到地图的 Decisions-so-far 中。
