# SkillMall 首期项目交接

更新时间：2026-11-06（Asia/Shanghai）  
目标接收方：用户指定的 `deepseek-v4,.1-flash`（名称按用户原文记录，未验证该模型在当前环境可用）。

## 交接目标

在已有 SkillHub 源码基础上，按已确认设计建设 SkillMall 首期；先在用户本机完成真实功能闭环和验收。当前阶段已完成需求讨论、设计基线和任务拆分，**尚未实现功能，也未运行本地端到端验收**。本文件用于交接，不表示授权提交、推送、发布、部署或开启多 Agent。

## 项目与必读资料

- 工作区：`D:\AI\workspace\1-cann\self\skillmall`
- UCD 原始设计：`D:\AI\workspace\1-cann\设计\SkillMall（外发）1105\SkillMall（外发）1105`。其中包含 `.pix` 原始文件及页面状态 PNG；首期页面严格对照已有设计，已确认的例外见设计基线。
- OpenSpec 基线：`openspec/changes/skillmall-local-mvp/`，先读 `proposal.md`、`design.md`、`specs/skillmall-local-mvp/spec.md` 和 `tasks.md`。
- 读仓库 `AGENTS.md` 和 `CONTEXT.md`。项目规则要求按适用范围读取 `.trellis/spec/`，但本次检查时 `.trellis` 不存在；不要假装已经读取，实施前重新确认并报告。
- 仓库规则还引用 `@RTK.md`，本次未在根目录找到。不得因此自行改写用户规则。
- 检查对应业务源码、测试和 `docs/04-search-architecture.md`、`docs/05-business-flows.md`、`docs/14-skill-lifecycle.md`；文档与代码冲突时报告，不静默更改既有策略。

用户在本会话还提供了额外项目规则：分析默认只读；复杂任务先列执行卡，已明确的设计不重复追问；代码优先最小实现与手术式改动，适用时启用 `caveman` 相关 skill；不要自动提交、推送、发布或启用多 Agent。修复先复现并验证；连续修复失败时停止猜补丁。每次 5 小时额度耗尽后暂停。

## 当前 Git 与工作区状态

- Remote：`https://github.com/ttttstc/skillmall.git`；上次检查为 `main...origin/main`。
- **重要：上次检查时绝大部分项目文件，包括 `AGENTS.md`、`CONTEXT.md`、`docs/`、`openspec/`、前后端和 CLI，在 Git 状态里均显示未跟踪。**这不是可以清理或覆盖的临时文件；先重新检查实际状态，保护全部现有内容。不擅自执行 `git clean`、重置、删除或批量暂存。
- UCD 和 OpenSpec 基线当时只存在本地工作区，未提交到远端；GitHub issue 已注明这一点。不要假设从远程克隆后就能得到本次设计资料。
- 用户偏好小步、可验证、可回滚；只有获授权才提交、推送或部署。跨 Agent 并行改代码需先获得用户明确授权，并使用独立 worktree；当前没有该授权。

## 已确认的首期范围

1. SkillMall 独立运行；视觉和首期交互遵从 UCD。顶部只保留市场本身可用的品牌、导航和登录，不接 AI 基础设施看板。产品可见品牌从 SkillHub 换为 SkillMall；保留开源署名、实际 SkillHub CLI 名称及现有协议兼容。
2. 首期复用 SkillHub 本地账号进行自助注册、登录和身份鉴权；设一个指定调试管理员。**本地账号只是临时方案，不做 GitCode 身份绑定、自动合并或迁移。** GitCode 登录和看板接入以后单独设计。
3. 访客可浏览、搜索、查看和下载公开 Skill；登录用户可从本地包或 GitCode 公开仓发布。格式及包边界检查通过后直接公开，不要求人工审核和自动质检。管理员可设置官方标识、下架；首期无举报、下架恢复流程。
4. GitCode 导入默认分支当前最新 commit；预览和发布固定同一个 commit。仓库包含多个 Skill 时供用户选择。版本记录来源仓库、Skill 路径、完整 commit 和内容摘要；源仓不自动同步，用户主动更新。只支持 GitCode 公开仓，不支持私有仓或 GitHub 导入。
5. 不同发布者可以拥有同名 Skill，使用完整坐标区分。内容更新形成新版本并保留历史；所有者可管理及移除自己的 Skill。CLI 安装到同一目录遇到不同来源同名 Skill 时提示冲突，不自动覆盖。
6. 详情页提供 README、文件、版本历史、下载及经过验证的安装指引。优先复用 SkillHub CLI；实际客户端命令未验证前不声称可用。安全评测区域显示“未评测”，不捏造通过结论。
7. 预置一级领域：设计、开发、测试、工程、项目管理、其他。首页提供通用推荐及换一换；近期热门按近 7 天下载量、下载热榜按累计下载量、最新上线按首次公开时间。提供收藏，不做独立点赞。
8. 专题、领域规范包、领域自动校验、安全扫描／综合评测流程、举报、人物榜单、CANNWork 的脚手架和打包、账号绑定、自动仓库同步及公网部署均暂缓。

## 现有项目证据与实现风险

- SkillHub 现有普通 PUBLIC 发布文档描述扫描与审核门槛；管理员直发也可能受扫描和下载就绪条件影响。直接公开是明确的新目标，实施时检查 Web、CLI、兼容入口、生命周期、搜索索引、下载就绪和审计；不得仅隐藏审核界面或授予普通用户超级管理员权限。
- SkillHub 现有 GitHub 同步链路固定 commit，并通过 Skill 版本关联摄入记录；这不证明现有 GitCode 手动导入可用，也不要求复刻整个定时同步系统。注意 GitCode 地址与跳转限制、路径/解压边界以及预览与最终内容一致性。
- 已看到 Skill 级和版本级累计下载计数，但还未证明可计算近 7 天数据；如需补充，保持最小持久统计，不虚构历史值。下载请求数不等于安装成功人数。
- namespace + slug 具备同名分离基础，CLI 代码存在来源与目标冲突检查；实际个人空间公开发布、客户端安装、所有入口及并发行为尚需验证。
- 本机曾匿名 `git ls-remote` 成功读取 `https://gitcode.com/cann-agent/skills.git` 的默认分支 HEAD；仓库 README 列出多个 Skill。但**尚未通过 SkillMall 完成真实 GitCode 导入**，网页访问还出现过限流；后续真实联调需重新读取 commit 并报告结果。
- 已执行 `openspec validate skillmall-local-mvp --type change --strict --no-interactive`，结果通过。该结果仅验证规格格式，不代表代码实现或端到端通过。

## 已创建 GitHub Issues

仓库：[ttttstc/skillmall](https://github.com/ttttstc/skillmall/issues)。截至交接前检查，以下均 OPEN，均有 `ready-for-agent` 标签；原生阻塞关系已创建并核验。实际开始前重新检查状态。

| Issue | 任务 | 阻塞于 |
|---|---|---|
| [#1](https://github.com/ttttstc/skillmall/issues/1) | 独立市场浏览与详情 | 无 |
| [#2](https://github.com/ttttstc/skillmall/issues/2) | 本地账号与直接公开发布 | 无 |
| [#3](https://github.com/ttttstc/skillmall/issues/3) | 我的 Skill 与版本管理 | #2 |
| [#4](https://github.com/ttttstc/skillmall/issues/4) | GitCode 固定快照导入 | #2、#3 |
| [#5](https://github.com/ttttstc/skillmall/issues/5) | 领域分类发布与发现 | #1、#2 |
| [#6](https://github.com/ttttstc/skillmall/issues/6) | 收藏与我的收藏 | #1 |
| [#7](https://github.com/ttttstc/skillmall/issues/7) | 首页推荐与三类榜单 | #1 |
| [#8](https://github.com/ttttstc/skillmall/issues/8) | 下载与可验证的安装指引 | #1、#2 |
| [#9](https://github.com/ttttstc/skillmall/issues/9) | 管理员官方标识与下架 | #1 |
| [#10](https://github.com/ttttstc/skillmall/issues/10) | 统一市场产品品牌 | #1；需与 #2 的登录注册页面协调 |

## 接续建议与验证

先确认用户希望接手的 issue 和当前授权。任务前沿是 #1、#2；选择一个可独立验收切片，不因为两项都无阻塞就自动并行或扩大范围。复杂工作开始前按项目规则给出简短执行卡，说明目标、范围、非目标、验收和停止点。

优先复用项目现有检查；后端 app 测试使用带 `-am` 的入口，例如 `make test-backend-app`，避免依赖旧模块产物。读清启动／测试脚本后再运行，保护已有服务和数据。最终按真实浏览器、服务和本地依赖验证，包含真实 GitCode 公开仓；将通过、失败、未运行和环境阻塞分别报告。截图、单元测试或构建不能替代端到端证据。
