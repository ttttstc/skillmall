# Skill Lifecycle

Date: 2026-10-15
Status: contract reference

本文件是 Skill 生命周期的统一导航入口；业务语义结合正式需求、设计与实现测试维护。发现冲突时应明确记录并确认预期，不能自动以代码或旧文档裁定。本次对齐依据见第 8 节；原始 latest 指针的 PRIVATE 语义见第 3.1 节。本页不定义 SuiteVersion 的状态模型。

## 1. 设计原则

- skill 生命周期不再被建模为一个混杂状态机，而是拆分为容器状态、版本状态、审核工作流状态和可见性覆盖层
- 前端不再从 `status + hidden + latestVersionStatus + viewingVersionStatus` 拼装状态，而统一消费后端 lifecycle projection
- destructive action 和 reversible action 必须分离；`withdraw-review` 只表示撤回提审，不表示删除版本
- 对外仍可保留 `latest` 协议词汇，其公共分发语义必须严格等价于 latest published；原始持久化字段的现状另列

## 2. 状态模型

### 2.1 Skill 容器状态

- `ACTIVE`
- `ARCHIVED`

说明：

- `hidden` 是独立治理覆盖层，不属于 `Skill.status`
- `SkillStatus.HIDDEN` 不再视为有效生命周期语义

### 2.2 SkillVersion 版本状态

| 状态 | 含义 |
|---|---|
| `DRAFT` | 历史兼容状态，可提审或删除；不作为普通上传的新建状态 |
| `SCANNING` | 等待当前安全扫描完成 |
| `SCAN_FAILED` | 扫描执行失败；不等同于发现风险 |
| `UPLOADED` | 上传保留，等待提审或 PRIVATE 确认发布；不保证已经扫描 |
| `PENDING_REVIEW` | 已提交审核，等待审核决定 |
| `PUBLISHED` | 已发布；实际分发还需满足可见性、容器状态与可安装条件 |
| `REJECTED` | 审核拒绝后保留的版本 |
| `YANKED` | 曾发布、现已撤回分发的版本 |

### 2.3 ReviewTask 审核工作流状态

- `PENDING`
- `APPROVED`
- `REJECTED`

`ReviewTask` 仅表达审核流程，不再被前端当作展示态来源。

## 3. 核心语义

### 3.1 Latest

- 公共 `latest`、搜索、兼容接口和默认公开分发必须解析到已发布版本，不能把未发布内容作为公开 latest。
- 审核通过及自动发布时，`Skill.latestVersionId` 指向发布版本；无已发布版本时允许为空。
- PRIVATE 上传/重发会把原始 `latestVersionId` 指向新建的 `UPLOADED` 版本；启用扫描后该版本可能处于 `SCANNING`。这是**已确认的预期语义**，权威表述见 §4.1 发布路径权威事实表，发布测试明确覆盖该指针更新；该字段本身不构成“已发布”证明。
- 详情和 owner summary 的 projection 检查指针目标是否 `PUBLISHED`，否则查找已发布版本；批量公开摘要只检查原始指针目标是否可安装，不执行该回退。不能假设各读模型都以相同方式修复指针；`latest` 标签和消费者也不能无条件沿用原始指针。
- 上述 PRIVATE 指针行为是**已确认语义，不是待定例外**：不再为“把原始指针统一为只指向已发布版本”单独设计，也不再保留未决讨论。各消费方一律按本节的回退与授权规则处理，公共 published 约束不因此放宽。

### 3.2 Lifecycle Projection

详情页、我的技能、我的收藏、搜索等读模型统一基于以下 projection：

- `headlineVersion`：projection 的默认主展示候选；前端可按选中版本或管理预览选择展示内容，不保证每个页面都直接显示该字段
- `publishedVersion`：最新已发布版本
- `ownerPreviewVersion`：有权限的管理者可见的新一轮未发布工作版本（排除 `PUBLISHED` / `YANKED`，且比已有发布版本更新）
- `resolutionMode`：`PUBLISHED` / `OWNER_PREVIEW` / `NONE`

约束：

- 默认公开展示、安装与搜索以最新已发布版本为候选；显式版本或自定义标签可指向其他 `PUBLISHED` 版本，仍须通过入口授权与就绪检查
- owner 详情页只有在不存在 `publishedVersion` 时，才允许 `headlineVersion = ownerPreviewVersion`
- compat latest 和默认公开下载采用最新已发布版本语义；promotion 可选择来源 skill 的任一 `PUBLISHED` 版本，不要求等于 `publishedVersion`。原始指针与各入口解析差异见第 3.1 节
- 管理者的授权预览下载与公开分发不同：owner 或 namespace ADMIN/OWNER 可下载 `UPLOADED` / `PENDING_REVIEW`，当前 owner 另可受控下载自己的 `DRAFT` / `REJECTED`，仍须通过 Skill 可见性、文件就绪及其他安全检查；不因此放行 `SCANNING` / `SCAN_FAILED` / `YANKED` 或公开分发未发布版本。常规 `downloadLatest` 读取原始指针后也使用相同版本授权判断，因此在上述 PRIVATE 指针语义下，默认下载不一定是已发布版本；该行为不授予公众访问权。
- 已发布版本可安装还要求 `downloadReady=true`、`yankedAt=null`；下载要求容器为 `ACTIVE`，元数据可读不等于可下载

### 3.3 First Published At

`skill.first_published_at` 记录**该 Skill 第一个版本进入 `PUBLISHED` 状态的时刻**，是本仓库“首次上线”的唯一字段。

- 只在 `first_published_at` 为空时写入，即 `Skill.markFirstPublished`；一旦有值，任何后续写入都是空操作。
- 发布新版本、重发已发布版本、审核通过、提升、yank 后回退指针、归档后取消归档，都**不重置**该值。这保证“最新上线”排序描述的是“这个 Skill 什么时候第一次出现在市场”，而不是“它最近一次被改动是什么时候”。
- 写入点为发布路径的四个 chokepoint：[SkillPublishService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillPublishService.java)（直发）、[SkillReviewSubmitService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillReviewSubmitService.java)（PRIVATE 确认发布）、[ReviewService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/ReviewService.java)（审核通过）、[PromotionService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/PromotionService.java)（提升）。
- 语义边界：本字段记录的是“首个 PUBLISHED 版本”，不是“首个对匿名访客可见的版本”。`PRIVATE` 技能先发布、之后扩大可见范围到 `PUBLIC` 时不重置该值；扩范围本身不是发布新版本，见 §4.9。
- 与 `skill_version.published_at` 的区别：`published_at` 属于**版本**，每个版本各自记录且随新版本更新；`first_published_at` 属于 **Skill 容器**，只记录一次。禁止用“最新版本的 `published_at`”推断首次上线时间。
- 历史数据由迁移 `V120__skill_first_published_and_daily_downloads.sql` 回填为 `MIN(skill_version.published_at)`；该回填只能得到“现存版本中最早的一次发布”，对已物理删除历史版本的 Skill 不具备重建能力，因此回填值应视为“已知最早发布”而不是绝对首次。
- 空值语义：`NULL` 表示尚无任何已记录发布。公开列表本身要求最新版本 `PUBLISHED`，因此空值在实践中只出现在异常数据上；排序按 `NULLS LAST` 处理，不隐藏该 Skill，也不伪造时间。

## 4. 代码实际链路

### 4.1 发布路径、可见性与审核归属

> **首期产品变更（GitHub issue #2）：允许直接公开发布。** 本地上传的 `PUBLIC` / `NAMESPACE_ONLY` 技能包通过包校验即直接发布，不再要求安全扫描器启用，也不再等待人工审核；扫描退化为可选的纵深防御，其结果只记录、不阻断、不回退已发布版本。GitHub 同步与 Suite Bundle 成员仍走各自独立的审核队列，`PRIVATE` 上传仍需显式确认发布。审核能力本身保留，用于上述内部队列、扩大可见范围和内容治理，不再是公开发布的前置条件。

**发布路径权威事实表。** 下表是本仓库对发布路径的唯一权威表述；其他文档只解释或指向它，不得另立一份生命周期步骤。

| 路径 | 版本状态 | 容器可见性 | 标题/摘要 | 人工审核 |
|---|---|---|---|---|
| 交互式上传 `PUBLIC` / `NAMESPACE_ONLY` | 直接 `PUBLISHED` | 立即应用 | 立即生效 | 否 |
| 交互式上传 `PRIVATE` | `UPLOADED`（启用扫描时扫描期间可为 `SCANNING`，结束后回到 `UPLOADED`） | **立即**变为 `PRIVATE` | **立即**生效 | 否，仅需在详情页确认发布 |
| 扩范围申请（如 `PRIVATE`→`PUBLIC`） | 不变 | 批准后才变 | 不变 | 是 |
| GitHub 同步 / Suite Bundle 成员 | `PENDING_REVIEW` | 不变 | 待批准 | 是 |

> **表注（“GitHub 同步 / Suite Bundle 成员”行的“标题/摘要”栏）：** 该行的“待批准”严格只指**更新既有技能**。技能**首次**进入内部审核队列时，标题/摘要会先行写入容器：写入条件是 `autoPublish || visibility == PRIVATE || skill.getLatestVersionId() == null`（[SkillPublishService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillPublishService.java)），容器上还没有任何已发布版本指针时即命中第三项，因为此时没有旧标题需要保护。这种情况下待审版本的 `displayName` / `summary` 在批准前已生效，但版本状态仍是 `PENDING_REVIEW`，发布指针不动（仍为空），公开侧不会因此分发该版本。指针回到空值的其他情形（例如已发布版本全部 yank 后，见 §4.8）同样按此条件处理。

三条最容易误解的地方：`PRIVATE` 上传在同一个事务内就把容器可见性切为 `PRIVATE`，并把原始 `latestVersionId` 指向这个尚未发布的版本，所以公开侧**立即**不再提供旧版本，不存在“旧版本继续可见直到审核批准”的过渡期。标题与摘要对 `PRIVATE` 上传同样立即生效，不等待确认发布。`SCANNING` 只是版本生命周期状态，表示扫描正在进行，既不是可见性，也不表示已发布或未发布；`PUBLIC` / `NAMESPACE_ONLY` 直发时版本已经是 `PUBLISHED`，扫描不会把它降为 `SCANNING`。

- 上表按**路径**分类，不按权限分类。持有平台 `SUPER_ADMIN` 的交互式上传属于权限旁路：`autoPublish` 为真，即使选择 `PRIVATE` 也直接置为 `PUBLISHED`（容器可见性仍写为 `PRIVATE`），并额外跳过目标 namespace 成员检查。
- 只有**扩范围申请**（§4.9）会在 `SkillVisibilityService` 中创建审核任务；交互式上传路径从不创建审核任务，`PRIVATE` 也不需要审核，只需确认发布。
- 扫描执行失败可进入 `SCAN_FAILED`。扫描完成不代表无风险；风险发现与警告确认仍是独立检查，但都不再是公开发布的门槛。过期扫描结果不能完成新一轮扫描。
- 直发在同一事务内同时应用**容器可见性**：交互式上传把请求的 `visibility` 写到 Skill 容器并移动发布指针，因此 `PRIVATE` 技能被以 `PUBLIC` 重新上传后立即公开，不再等待 §4.9 的范围审核。只有带 `PUBLISHED` 版本的容器才可能被匿名发现。
- 头像受众复核改为**事后异步**：直发不再产生内容审核任务，头像候选按其自身审核策略处理；技能发布后由 [SkillAvatarScopeReviewListener](../server/skillhub-app/src/main/java/com/iflytek/skillhub/listener/SkillAvatarScopeReviewListener.java) 监听 `SkillPublishedEvent` 复核受众。§4.9 的“扩大范围申请”只适用于**不发布新版本**、单独调整可见范围的入口，不是扩大受众的唯一路径。
- `submit-review` 接受 `UPLOADED`（兼容历史 `DRAFT`），设为 `PENDING_REVIEW` 并创建审核任务，保存请求的目标可见性。
- `confirm-publish` 仅用于 PRIVATE Skill 的 `UPLOADED`（兼容 `DRAFT`）：转为 `PUBLISHED`，更新发布时间与 latest，发出发布事件，不触发新扫描。

### 4.2 审核通过

- `PENDING_REVIEW -> PUBLISHED`
- review task 标记为 `APPROVED`
- `Skill.latestVersionId` 指向该版本
- skill 展示元数据从发布版本刷新

### 4.3 审核拒绝

- `PENDING_REVIEW -> REJECTED`
- review task 标记为 `REJECTED`
- 版本保留，可后续删除

### 4.4 撤回审核

- `withdraw-review` 的统一语义是 `PENDING_REVIEW -> UPLOADED`
- 同时删除关联的 `PENDING review_task`
- 该操作是可逆、非破坏性的
- 当前代码只允许提交人本人撤回

### 4.5 重传新版本

- 若发现旧的 `PENDING_REVIEW` 版本，会先把旧版本自动撤回到 `UPLOADED`
- 然后按可见性及自动发布条件创建新版本，走 4.1 的对应路径
- 普通交互上传的自动撤回与手动撤回保持同一目标状态；Bundle 发布禁止自动撤回，已有待审版本时应阻塞，由用户先处理

### 4.6 已发布版本重发

- rerelease 当前本质上是从已发布版本复制并重新走发布流程
- 入口先检查生命周期管理权限，要求来源版本为 `PUBLISHED`、目标版本号未占用；内部传空平台角色集且 `forceAutoPublish=false`，不沿用平台特权直发
- 新版本按原 skill 的可见性进入 4.1 对应路径：`PUBLIC` / `NAMESPACE_ONLY` 直接 `PUBLISHED`，`PRIVATE` 仍为 `UPLOADED`；内部绕过重复成员校验不等于绕过可见性与维护权限检查
- 该能力是复制内容重新发布，不是生命周期展示态
- 重发**不继承**变更说明：`rereleasePublishedVersion` 给新版本传 `changelog = null`（[SkillPublishService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillPublishService.java)），即使来源版本带说明，重发产生的新版本在版本历史里显示为“没有说明”
- 这与**提升**不同：提升会复制来源版本的说明（[PromotionService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/PromotionService.java) 的 `setChangelog(sourceVersion.getChangelog())`）。两条路径的说明继承口径目前不一致，属于**待定产品口径**，由 issue #22 跟踪（同一张票也覆盖删除后读接口 400/404 语义与 GitHub 测试偶发）；本节只如实记录当前行为，不在文档里替产品拍板

### 4.7 隐藏 / 恢复 / 归档 / 撤回已发布版本

- 隐藏：设置 `hidden=true` 并记录治理信息，不改变版本状态；不抹除 owner / namespace 管理者的访问权限
- 恢复：设置 `hidden=false` 并更新治理信息
- 归档：`Skill.status = ARCHIVED`，保留记录并禁止下载；公开元数据仍可能可读
- 取消归档：`Skill.status = ACTIVE`
- yank：`PUBLISHED -> YANKED`，记录撤回人、时间和原因，设置 `downloadReady=false`；没有对应的恢复发布操作

### 4.8 Yank 后指针修正

- yank 已发布版本时，若命中当前 `latestVersionId`，必须重算 latest published pointer，并从回退版本的 metadata 恢复展示名和摘要
- 若仍有其他 `PUBLISHED` 版本，则指向最新一个
- 若已无任何 `PUBLISHED` 版本，则 `latestVersionId = null`

### 4.9 独立调整已发布技能的可见范围

可见范围属于 Skill 容器，不需要新建 SkillVersion。缩小立即生效；扩大创建 `SKILL_METADATA` 范围申请，与内容待审互斥。原范围、目标范围和发布版本快照保存在 ReviewTask，审核通过才更新范围；旧审批不能覆盖新版本。未扫描的已发布包复用存量 bundle 扫描结果并保持 PUBLISHED；扫描器启用时申请绑定新扫描 taskId，未通过不得批准；部署方未启用扫描器时申请照常进入人工审核，不把“没有扫描器”变成无法申请的硬阻塞（首期产品变更，扫描不再是门禁）。头像按目标受众复用同一审核申请。范围事件仅刷新搜索，不发送“新内容版本发布”通知。操作和接口详见[使用指南](./skill-visibility.md)。

### 4.10 版本变更说明

- 交互式发布可选携带变更说明（`changelog`）；非空值去除首尾空白后保存在**本次新建的版本**上，空值保存为 `null`，读取方据此区分“没有说明”和“说明为空”
- 变更说明属于版本记录本身：旧版本保留各自发布时的说明，发布新版本不会回写到历史版本
- 该字段不参与发布路径判定，也不改变 §2.2 的版本状态机；它只随版本出现在版本历史与版本详情中
- 只有**交互式发布**（含 Web 表单与 CLI 发布）接受调用方提供的说明；重发与提升不走这条通道，各自的说明继承口径见 §4.6 与 §5.3
- 超过 4000 字符的说明在写入任何记录之前被拒绝（`error.skill.publish.changelog.tooLong`），不会留下部分写入的技能或版本；空白的判定与浏览器 `trim()` 一致，纯不换行空格（U+00A0）等字符组成的说明按“没有说明”保存为 `null`，直连 API 与网页提交结果相同

### 4.11 作者移除

- 作者移除与管理员下架是两种不同动作：移除是把整个 Skill 及其版本、文件、标签、收藏、评分、报告、版本统计和待审记录一并**硬删除**，下架只是 `hidden` 治理覆盖层（§4.7）。本文件不为作者移除设计恢复流程
- 授权：三个删除入口的门槛不同，但对“能不能删这个技能”的所有权判定是同一个——当前所有者本人，或平台 `SUPER_ADMIN`；普通用户删除别人的技能一律 403，原技能与版本保持不变
  - 门户入口 `DELETE /api/web/skills/id/{id}` 与 `DELETE /api/web/skills/{namespace}/{slug}`：按上面的所有权判定执行
  - API 令牌管理入口 `DELETE /api/v1/skills/id/{id}` 与 `DELETE /api/v1/skills/{namespace}/{slug}`：入口本身只放行平台 `SUPER_ADMIN`（`hasRole('SUPER_ADMIN')`），按管理员语义执行，不按 owner 判定；这一入口的语义不因本次收紧而改变
  - CLI 入口 `DELETE /api/cli/v1/skills/{namespace}/{slug}`：与门户入口复用同一所有权判定（当前所有者本人或平台 `SUPER_ADMIN`）。非所有者调用返回 403，技能、版本、搜索文档与审计记录都不变
  - API 令牌的 `skill:delete` scope 只表示“可以调用 CLI 删除入口”，**不等于可以删除任意人的技能**：scope 是入口门槛，归属判定在服务端按上面的所有权规则单独执行。令牌默认 scope 是否应包含 `skill:delete`（当前默认包含）另由 #24 跟踪，CLI 越权面本身由 #23 跟踪，本文件的判定不依赖默认 scope 的取值
  - 技能提升相关的移除仍须先走 §5.4 的撤销流程
- 移除时先删除搜索文档（`SearchIndexService.remove`），再删除数据库记录，对象存储在事务提交后清理并在失败时走补偿；因此公开列表、搜索、详情、版本、resolve 与下载都不再返回该技能
- 审计保留：动作 `DELETE_SKILL_HARD`，目标类型 `SKILL`，目标 ID 为被移除的技能 ID；详情记录操作人、客户端信息与 `namespaceId`（数字）加 `slug` 的键值对——不是门户 URL 里 `namespace/slug` 那种文本坐标，`namespaceId` 需要使用方自行解析成空间。审计记录不随业务记录删除

## 5. 对外协议约束

### 5.1 Public / Search / Compat

- 对外协议可以继续暴露 `latestVersion`、`latest`、默认下载等概念
- 但它们都必须严格表示“最新已发布版本”
- compat 层内部实现必须从统一 lifecycle projection 的 `publishedVersion` 映射，不允许自行推导“当前版本”

### 5.2 Frontend

- 页面状态展示统一消费 projection
- 不再新增旧兼容字段依赖
- `hidden` 仅作为治理标记展示，不参与版本状态拼装

### 5.3 提升更新与撤销

- 提升要求所选版本属于团队来源 Skill 且为 `PUBLISHED`，可选历史版本，再单独进入平台提升审核；后续更新同一全局 Skill 时为它新增不可变的已发布版本。
- 首次和后续提升均优先从所选来源版本的 `parsedMetadataJson` 读取展示名和摘要；字段为空才回退到审核时来源 Skill 的展示信息，非空但解析失败则抛错。
- 全局 Skill 也可独立上传并审核。提升更新与独立上传使用相同版本号时不能互相覆盖；不同号版本分别发布，latest 指向最后通过审核的版本，不按版本字符串大小排序。
- 撤销提升不同于归档或隐藏：平台审核通过后移除全局派生 Skill 的公众入口及记录，保留来源团队 Skill、提升和撤销审计，并保护共用对象存储文件。重新提升形成新的全局 Skill ID。
- 提升生成的全局版本会**复制来源版本的变更说明**（[PromotionService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/PromotionService.java) 的 `setChangelog(sourceVersion.getChangelog())`），与 §4.6 重发不继承说明的口径不同；这两条路径的说明继承差异属于**待定产品口径**，由 issue #22 跟踪。

### 5.4 已提升技能如何删除

团队技能与提升生成的全局技能是两条关联记录。只撤回待审核版本、取消更新或撤回尚未批准的申请，**不会删除已经发布的全局技能，也不会解除首次提升的关联**。

若删除时提示须先撤销全局发布（旧版本提示“已提升的技能须通过审核撤销流程删除”），按以下顺序操作：

1. 打开**来源团队技能详情页**，在提升区域选择“申请撤销全局发布”，填写原因并提交。
2. 平台管理员在提升管理的撤销申请中审核；申请处于待审或被拒绝时，全局技能仍然存在，两条技能都不能绕过该流程直接删除。具有相应平台治理权限的管理员也可在来源详情页直接撤销。
3. 撤销批准后，全局技能及其地址自动删除，来源团队技能和版本仍保留。此时如需连来源一起删除，由技能所有者或超级管理员执行来源技能的永久删除。

撤销时已有的全局更新申请会失效，不能在全局记录删除后重新发布到该记录。提升撤销保留提升、撤销与删除审计；来源技能后续永久删除仍按既有物理删除流程清理其业务记录，操作审计继续保留。不要通过手工清除来源关联字段来绕过撤销审核。

## 6. 权限边界

### TEAM 成员移除后的当前所有者

账号正常但已不是 TEAM 成员的当前技能所有者，保留自己的技能、私有内容、草稿及历史版本的查看和受控下载权限，不能凭所有者身份执行以下写操作。成员移除不删除技能、不迁移地址、不改 owner、不自动撤回待审核申请；既有审核人继续按原安全与审核规则处理。重新加入且账号正常、所有权未变时恢复原有维护权限。

本条只收紧普通 owner 的成员边界，空间／平台管理员的原有独立权限不变；其他空间和 GLOBAL 原有规则不变。账号停用仍由原认证机制拒绝，不能通过 owner 匹配绕过。以下各行中的 owner 写权限均以这条成员边界为前提，不影响管理员独立路径。

### 操作边界

- `withdraw-review`：仅提交人本人
- 删除版本：owner 或 namespace ADMIN/OWNER，仅限 `DRAFT` / `REJECTED` / `SCAN_FAILED` / `UPLOADED`，禁止删除最后一个版本。数据库删除提交后清理对象存储，失败调用补偿入口；不是跨存储原子删除，补偿持久化的验证限制见[对象存储写入策略](./05-business-flows.md#对象存储写入策略)
- 归档 / 取消归档：owner 或 namespace ADMIN/OWNER
- 作者移除整个技能（硬删除）：门户、CLI 入口均要求当前所有者本人或平台 `SUPER_ADMIN`，只有管理入口（`/api/v1/skills/...`）以 `SUPER_ADMIN` 角色为门槛；`skill:delete` scope 只是 CLI 入口门槛，语义与审计见 §4.11；与管理员下架（`hidden`）分别授权和记录
- 隐藏 / 恢复技能：平台技能治理权限
- yank：owner 或 namespace ADMIN/OWNER 可走普通治理入口；平台治理入口另校验平台权限
- 提交审核：owner 或 namespace ADMIN/OWNER；PRIVATE 确认发布还支持传入平台角色的 SUPER_ADMIN 路径
- 读取：可见性检查区分 PUBLIC、NAMESPACE_ONLY、PRIVATE 和 hidden；没有 latest 指针且未隐藏时，普通 namespace 管理角色不自动获得读取权限，owner 可读。接受平台角色的检查入口另支持 SUPER_ADMIN，不等于每个服务调用均传递该角色
- 写操作授权与读取授权分别校验，不能从管理身份推导所有未发布技能均可读取

## 7. 当前最终约束

- 一个 skill 生命周期的唯一规范入口就是本文件
- 其它文档如 `02-domain-model`、`05-business-flows`、`06-api-design`、`08-frontend-architecture` 必须与本文件保持一致
- 生命周期行为变化先确认正式需求与验收，再同步修改实现、测试及本文；不能以文档对齐名义静默裁定新业务规则

## 8. 依据与验证锚点

- 正式语义：[核心语义规则 §6](./oss-02-core-semantic-rules.md#61-状态转换表)、[Bundle 发布规格](../openspec/changes/add-suite-bundle-publishing/specs/suite-bundle-publishing/spec.md)；Bundle 不自动撤回，SuiteVersion 使用独立模型。
- 提升展示信息：[版本展示名规格](../openspec/changes/refine-display-name-publishing/specs/skill-display-name/spec.md)要求标题与摘要匹配所选版本快照，取代旧提升方案中读取来源 Skill 当前展示信息的描述；[PromotionService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/review/PromotionService.java)保留空元数据的兼容回退。
- 上传及 PRIVATE 指针：[SkillPublishService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillPublishService.java)、[发布测试](../server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/skill/service/SkillPublishServiceTest.java)。
- 首期直发策略（GitHub issue #2）：[SkillPublishService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillPublishService.java) 的交互上传分支（`interactiveUpload`）对 `PUBLIC` / `NAMESPACE_ONLY` 直接置 `PUBLISHED`，扫描启用只触发异步扫描；[SkillVisibilityService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillVisibilityService.java) 的扩大范围申请在无扫描器时仍可进入人工审核。测试锚点见同目录下的 `SkillPublishServiceTest`、`SkillVisibilityServiceTest`。
- 扫描：[SecurityScanService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/security/SecurityScanService.java)、[扫描测试](../server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/security/SecurityScanServiceTest.java)。
- 提审 / PRIVATE 确认：[SkillReviewSubmitService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillReviewSubmitService.java)、[提审与确认测试](../server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/skill/service/SkillReviewSubmitServiceTest.java)。
- 撤回 / 删除 / yank：[SkillGovernanceService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillGovernanceService.java)、[治理测试](../server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/skill/service/SkillGovernanceServiceTest.java)。
- 读模型与分发：[SkillLifecycleProjectionService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillLifecycleProjectionService.java)、[VisibilityChecker](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/VisibilityChecker.java)、[下载测试](../server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/skill/service/SkillDownloadServiceTest.java)、[可见性测试](../server/skillhub-domain/src/test/java/com/iflytek/skillhub/domain/skill/VisibilityCheckerTest.java)。

- 作者移除与版本变更说明：[SkillDeleteAppService](../server/skillhub-app/src/main/java/com/iflytek/skillhub/service/SkillDeleteAppService.java)（门户、CLI 与服务端所有权判定）、[CliSkillAppService](../server/skillhub-app/src/main/java/com/iflytek/skillhub/service/cli/CliSkillAppService.java)（CLI 入口）、[SkillHardDeleteService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillHardDeleteService.java)、[移除入口集成测试](../server/skillhub-app/src/test/java/com/iflytek/skillhub/controller/portal/SkillDeleteFlowIntegrationTest.java)（门户 / CLI / SUPER_ADMIN 三个入口的移除与拒绝）；变更说明的写入与长度校验在 [SkillPublishService](../server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/service/SkillPublishService.java)，读回闭环见 [发布说明读回集成测试](../server/skillhub-app/src/test/java/com/iflytek/skillhub/controller/portal/SkillPublishChangelogReadBackIntegrationTest.java)，测试锚点 `SkillPublishServiceTest`。

以上锚点用于核对具体行为，不代表覆盖了所有状态组合，也不等于真实部署或对象存储验收。
