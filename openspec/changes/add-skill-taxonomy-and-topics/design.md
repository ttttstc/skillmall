# 两级分类治理与专题能力 — 设计

Date: 2026-11-27
Status: Draft（待产品评审，未实施）
Change-Id: `add-skill-taxonomy-and-topics`
Branch base: `origin/main` @ `88b07a4`（新分支 `feat/taxonomy-review-topic`）

本文件是本次四项需求的全量设计。它**只描述设计，不描述已实现行为**；所有"现状"结论都来自
对 `origin/main` 当前代码的实读，标注了 `path:line`。与既有契约冲突的地方在 §1.3 显式列出，
按仓库规则（AGENTS.md「Report conflicts and known deviations」）不静默改写业务口径。

---

## 0 摘要

四件事，两种性质：

| 需求 | 性质 | 影响面 |
|---|---|---|
| ① 两级分类 + 分类管理员 + 分类级审批 | 领域模型变更（闭集枚举 → 引用数据表）+ 新授权作用域 | 大：数据库、搜索、审核、前端导航、管理台 |
| ② 「全部技能」两级目录 | 前端改造 + 查询参数扩展 | 中：`/search` 页左侧栏已存在，替换筛选块 |
| ③ 两个缺陷修复 | 缺陷 | 小-中：权限投影收敛、OpenAPI/令牌策略 |
| ④ 专题（Topic） | 全新领域 | 大：新聚合、新页面、专题管理员、审核 |

核心结论：

0. **最先要定的是"一级到底指什么"。** 读 UCD 后发现左栏一级是**组织/部门**
   （昇腾/鲲鹏/越影/设计/测试/IT装备/项目管理/其他），二级才是**领域分类**
   （系统设计/软件开发/…）；而需求原文说"上传时选择一级类别"，发布页却只有一个
   **「领域分类」下拉**、没有组织字段。这两种读法会导出完全不同的数据模型与授权作用域。
   我建议**读法 B**（一级=领域分类，组织作独立属性），详见 §1.4 与 D0。
1. **一级分类必须从"闭集枚举"变成"引用数据表"**，否则无法承载 UCD 的两级目录。
   现有 `SkillDomain` 六值枚举与 `docs/29-skill-domain-categories.md:22`「不建模二级、三级目录」
   的契约将被取代（§1.3 冲突 C1）。
2. **分类管理员不能复用平台角色**。平台角色是全局的、无撤销轨迹，且唯一的分配入口
   `AdminUserAppService.updateUserRole`（`server/skillhub-app/.../service/AdminUserAppService.java:101-107`）
   是「先 `deleteByUserId` 再插一条」，复用它会在授予分类管理员时**清掉用户已有的超级管理员角色**。
   应仿 `organization_role_binding`（`V60__organization_foundation.sql:112-151`）建带作用域、可撤销的绑定表。
3. **"私有→公开"审批目前有两条绕过通道，已由 §0.1 #1 的决策一次性关闭**：
   ① 交互式上传选 `PUBLIC` 直接发布、不经审核（`docs/14-skill-lifecycle.md:97,109`）→
   改为"上传一律私有、公开只走申请"；
   ② 版本审核携带 `targetVisibility` 时 `ReviewService.approveReview:284-286` 直接改可见性
   且跳过 `validateApproval` → 该字段必须拒收（§6.2 要点 3）。
   这两条不关闭，分类管理员的审批权就没有实际约束力。
4. **`专题` 在代码中零实现**（全仓 grep 无匹配）；且 UCD 要求的「AI 扩写」**没有可用的 LLM 底座**
   （`llmProvider` 只属于安全扫描器配置，`SkillScannerProperties.java:104`）。这是范围问题，见 D7。
5. **两个缺陷的真实根因已定位**，且都不是"一处 bug"（§11）：
   所有者权限不一致 = 维护者判定被复制 6 处、3 种语义；
   Swagger Bearer = 缺 `securitySchemes` + 令牌白名单拒绝 + 会话 cookie 触发 CSRF，三重叠加。

---

## 0.1 已确认的产品决策（2026-11-27，按你的答复落地）

| # | 你的原话（要点） | 落地结论 | 影响章节 |
|---|---|---|---|
| 1 | 去掉重新上传 `PUBLIC` 直发；上传的 skill 默认私有；靠用户申请公开、由对应类别管理员/系统管理员审批后公开 | **交互式上传不再让用户选可见性**，新建技能一律 `PRIVATE`；`PUBLIC` 只能经"申请→审批"获得。`NAMESPACE_ONLY` 是否保留见 §16（默认仍按申请处理） | 推翻 `docs/14-skill-lifecycle.md:91,97,109` 的首期直发策略；`SkillPublishService.interactiveUpload` 必须改；`SkillVisibilityService` 成为**唯一**公开通道；原 D3/D13 由此自动关闭 |
| 2 | 产品需要二级分类，二级分类由一级分类管理员管理和创建 | 两级分类确定；二级的增删改由该一级的**分类管理员**执行，超管全可 | §4.2 / §4.4 / §5 |
| 3 | 公开技能没有再公开的概念；所有接口超管都有权限 | (a) 可见性申请只覆盖 `PRIVATE → NAMESPACE_ONLY/PUBLIC`，不存在"已公开再申请公开"；(b) **超管在全部接口都有权限**，所有权限投影必须包含 `SUPER_ADMIN` | 简化 §6 状态机；直接修 §11.1 的 B 项（`canManageRestrictedSkill` 漏判 `SUPER_ADMIN`） |
| 4 | 一级分类按现有枚举 6 个 | 一级 = `DESIGN/DEVELOPMENT/TESTING/ENGINEERING/PROJECT_MANAGEMENT/OTHER`；`legacy_domain` **1:1 映射，无有损** | §14 种子数据简化；风险表删除"有损映射"项 |
| 5 | 全量 skill 页一/二级展示严格按 UCD | 视觉与交互照 UCD：左栏可展开树、文件夹图标 + chevron、二级缩进无图标、工具栏排序胶囊、多选分类 chip 下拉、4 列卡片、加载更多、列表/卡片切换 | §9.1 / §9.6；**唯一有意偏离**：二级不画 chevron（产品已定只两级） |
| 6 | 每个 skill 上传时支持配置一级分类，也可在更新和公开时绑定 | **Web 上传表单一级分类必填**（第二轮改为必填）；更新与"申请公开"时均可改绑。**"解绑" = 改为「其他」**，不置 NULL（保住 `null → OTHER` 不变量） | §9.2；§0.2 #2 |
| 7 | 上传 skill 时分类必填一级类别 | Web 表单 `✳` 必填；**CLI / ClawHub 兼容层传不了分类**（`PublishPayload` 无该字段、CLI 无该参数）→ 缺省落「其他」并标"未分类"。见 §0.2 #1 | §4.5 / §9.2 / §0.2 |
| 8 | B 可信发布者豁免方案 | 三入口默认 `PRIVATE` + 提审；持 `SKILL_ADMIN`/`SUPER_ADMIN` 令牌可直发 `PUBLIC`，写审计 `SKILL_PUBLISH_DIRECT`（detail 带 `bypass=TRUSTED_PUBLISHER`） | §6.0 |
| 9 | 技能在二级分类里时，二级分类要在 skill 信息上体现和展示 | 列表卡片/列表行 chip = **`二级 ?? 一级`**；详情页显示"一级 / 二级"两级；专题成员行同款。名称按当前页分类 id **批量解析**，不做 per-item join | §7.4 / §8.6 / §9.1 |
| 10 | skill 列表页支持按分类维度筛选、按创建人筛选 | 分类：左栏两级树（定位）+ 工具栏多选二级 chip（OR，限同一一级下）；创建人：新增 `GET /api/web/skill-authors?q=` + 工具栏选择器。筛选条件都进**同一公开分发谓词** | §7.1 / §8.1 / §9.1 |

### §0.2 第二轮决策的四个连带结论（已按推荐值定，可选覆盖）

1. **"必填"只对 Web 交互上传强制**（选 A 方案）。理由：`MultipartPackageExtractor.PublishPayload`
   只有 `namespace/slug/displayName/version/changelog/tags/forkOf`（`:41-48`），**没有分类字段**；
   CLI 发布也没有 `domain`/`category` 参数。全线必填会让旧 CLI 与 ClawHub 客户端 publish 直接 400。
   程序化入口缺省落「其他」，详情页标"未分类"，可由超管批量治理。
2. **"解绑" = 改为「其他」**。既满足必填，又保留脱敏能力，且不破坏 `Skill.java:203-205` 的 `null → OTHER` 不变量。
3. **新增作者检索接口**：portal 层只有 `MeController`（只看自己），没有公开用户列表/搜索。
   新增 `GET /api/web/skill-authors?q=&limit=20 → [{userId, displayName, avatarUrl, skillCount}]`，
   只统计**有可公开分发技能**的作者；`ownerDisplayName` 本来就在列表 DTO 里返回，不新增隐私暴露。
4. **"创建人"按 `created_by`**（非 `owner_id`）。`Skill` 同时有 `ownerId`（非空）与 `createdBy`（可空），
   所有权转移只改 owner。迁移回填 `created_by = owner_id WHERE created_by IS NULL`，
   展示与筛选都用 `created_by`（为空回退 `owner_id`）。

**由此关闭的开放问题**：D0（分类模型读法）、D1（一级集合）、D3（上传提权绕过）、
D13（Path B 绕过）、原 §16 的第 1/2/9 项。

**仍然开放**：D7（AI 扩写是否真接模型）、D8（专题审批形态）、
`NAMESPACE_ONLY` 是否保留、卡片副标题的数据来源（UCD 是部门名，但本次不引入组织模型）。

> 说明：本文件 §1「现状核查」描述的是**当前代码**（尚未改），作为改造基线；
> 凡与新决策冲突处，以 §0.1 与 §6.0 为准。

---

## 1 现状核查

### 1.1 一级分类（现状）

| 事实 | 证据 |
|---|---|
| 六个值闭集：`DESIGN, DEVELOPMENT, TESTING, ENGINEERING, PROJECT_MANAGEMENT, OTHER` | `server/skillhub-domain/.../domain/skill/SkillDomain.java:17-23` |
| 默认值 `OTHER`，getter/setter 把 null 归一为 `OTHER` | `Skill.java:203-205, 324-326` |
| 持久化是 `skill.domain VARCHAR(32)`，**可空、无 CHECK 约束**（有意） | `V121__skill_domain_category.sql:1-12` |
| 部分索引 `idx_skill_domain_public on skill(domain) WHERE status='ACTIVE' AND hidden=FALSE` | `V121:18-20` |
| 改分类两个入口：发布表单 `domain` 字段、`POST /skills/{ns}/{slug}/domain` | `SkillPublishController.java:87,116`；`SkillDomainController.java:41-60` |
| 读分类 `GET /skills/{ns}/{slug}/domain` | 同上 |
| 权限：owner / 空间 ADMIN-OWNER / `SUPER_ADMIN`；先"只读降级"再身份校验 | `SkillOwnerAccessPolicy.java:48-56`；`SkillDomainService.java:50-51` |
| 审计：仅独立改分类入口写 `SKILL_DOMAIN_CHANGED`；发布路径**不写** | `SkillDomainAppService.java:67-72` vs `SkillPublishAppService.java:121-123` |
| 无任何分类 CRUD 管理端 | `controller/admin/**` 18 个控制器中无 domain 相关 |

### 1.2 权限与审核（现状）

- 平台角色来自**数据库** `role` 表，V1 种子四个：`SUPER_ADMIN / SKILL_ADMIN / USER_ADMIN / AUDITOR`
  （`V1__init_schema.sql:135-139`）。`USER` 不是表行，是在角色集为空时**合成**的
  （`PlatformRoleDefaults.java:12,17-26`）。
- 请求上下文唯一注入点：`AuthContextFilter.java:83-90` 写入 `userId` / `platformRoles` / `userNsRoles`。
- 空间角色仅三个：`OWNER / ADMIN / MEMBER`（`NamespaceRole.java:3-5`）。
- 审核权限唯一闸门：`ReviewPermissionChecker.canReviewNamespace`（`:63-76`）——
  对**非 TEAM 空间（含 GLOBAL）一律返回 false**，只有 `SKILL_ADMIN`/`SUPER_ADMIN` 能批。
  这正是"分类管理员审批"要拓宽的那个点。
- 审核任务已携带 `skill_id`、`previous_visibility`、`requested_visibility`、`subject_type`
  （`ReviewTask.java:18-46,69-70`），因此按分类路由**不需要改表**。
- 可见性扩范围申请已有完整链路：`SkillVisibilityDialog` → `POST /skills/{ns}/{slug}/visibility`
  → `SKILL_METADATA` 类型的 `ReviewTask` → `POST /reviews/{id}/approve`。
- 既有"作用域管理员"的唯一成熟先例：`organization_role_binding`（`V60:112-151`），
  带 `ACTIVE/REVOKED`、`revoked_by/at`、`UNIQUE(org,user,role) WHERE ACTIVE`、`authority_version` 失效机制；
  **但没有任何 HTTP 入口**（`OrganizationRoleBindingService` 在 `skillhub-app` 无调用方）。

### 1.3 与既有文档/契约的冲突（必须先确认）

| 编号 | 冲突 | 既有口径 | 本设计 |
|---|---|---|---|
| **C1** | 是否允许二级分类 | `docs/29-skill-domain-categories.md:22`「产品只发布这六个一级分类，**不建模二级、三级目录**」；`skill-domains.ts:1-6` 注释「deliberately not configurable at runtime」 | 一级=引用数据表，二级=可运行时增删（限本类目管理员）；`docs/29` 需改写 |
| **C2** | 分类变更是否恒有审计 | `docs/29:50`「分类变更写审计 `SKILL_DOMAIN_CHANGED`」 | 现状只在独立入口写，发布路径不写。本设计要求**两条路径都写**，即收紧为文档原意 |
| **C3** | 平台角色是否多角色 | `docs/02-domain-model.md:343`「一个用户可持有多个平台角色」 | 现状 schema 支持、唯一写接口是覆盖式。本设计需先修成**增量授予**（§11.3），否则无法安全授予专题管理员 |
| **C4** | 审核任务状态个数 | `docs/14-skill-lifecycle.md:40-44` 只列 `PENDING/APPROVED/REJECTED` 三个 | 代码有四个，多一个 `SUPERSEDED`（`ReviewTaskStatus.java:3-8`）。需补文档，本设计不改变该枚举语义 |
| **C5** | 可见性审批是否留审计 | 隐含要求（AGENTS.md：发布/下载/删除/授权变更必须保留审计） | `SkillVisibilityService` **没有** `AuditLogService` 依赖（`:20-42`），批准驱动的可见性翻转只留 `REVIEW_APPROVE`，没有 `SKILL_VISIBILITY_*`。本设计必须补（§6.2 要点 7） |

另：`docs/02-domain-model.md` 通篇**没有** `domain` 字段、也没有组织模型，已落后于 V121/V60，属独立文档债。

### 1.4 UCD 与需求的分类模型歧义（**最先要定的一件事**）

> **已定（2026-11-27，见 §0.1 #2/#4/#5）**：采用**读法 B**，且一级固定为现有 6 个枚举值。
> UCD 左栏的「组织/部门」（昇腾/鲲鹏/越影/…）是**设计稿占位**，不进入分类树；
> 「组织」也**不作为独立属性引入**（因此本文件不新增 `skill.owner_org`）。
> 卡片副标题的数据来源留待 §16 确认（可先用 namespace 显示名）。
>
> 以下保留原始分析，用于追溯当时为什么会误判——UCD 的组织层与需求的一级分类在视觉上完全同构。

逐张读 UCD 后发现一个会**改变整个数据模型**的歧义，必须先定，否则 §4 全表结构与 §5 授权作用域都要重做。

事实（均来自 UCD 与操作说明图）：

- 「全部技能」左栏**一级是组织/部门**：昇腾、鲲鹏、越影、设计、测试、IT装备、项目管理、其他（8 个，
  均带文件夹图标 + chevron，**无数量角标**，且**没有"全部"节点**——"全部"是工具栏里的排序胶囊）。
- 展开「昇腾」后**二级才是领域分类**：系统设计、软件开发、集成验证、通用测试、专项测试（缩进约 22px）。
- 操作说明图注解原文：「**1. 默认：选中显示自己对应的组织。**」
- 卡片副标题写「**开发公共部SER**」——说明"组织"是**技能上自动带出的属性**，不是用户填的。
- 但「发布 Skill」页第 2 行左侧只有一个**必填单值下拉「领域分类」**（占位「请选择」），
  词表与上面二级同一套（…/专项测试/**硬件开发**），**全页没有任何"组织"字段**。

两种互斥读法：

| 维度 | 读法 A：一级=组织，二级=领域分类 | 读法 B：一级=领域分类，二级=新细分；组织正交 |
|---|---|---|
| 一级（超管配管理员） | 昇腾/鲲鹏/越影/设计/测试/IT装备/项目管理/其他 | 设计/开发/测试/工程/项目管理/其他（≈现有 6 枚举） |
| 二级（分类管理员增删） | 系统设计/软件开发/集成验证/通用测试/专项测试… | 各领域管理员自行新增的细分 |
| 上传时用户选的 | 组织（**但 UCD 里没有这个字段**） | 「领域分类」下拉 = 一级（与需求原文"选择一级类别"吻合） |
| 卡片副标题「开发公共部SER」 | 就是一级组织名 | 独立的组织属性，与分类树无关 |
| 与需求原文吻合度 | 「在**自己管理的类别**下建二级」「审批自己类别下私有技能转公开」很顺（=部门管理员） | 「上传时**选择一级类别**」「更新时**绑定/解绑一级类别**」很顺 |
| 代价 | 需求里"上传时选一级类别"无法实现（组织是自动带出）；发布页"领域分类"实际在选二级 | UCD 左栏的组织分组无处安放；需额外引入"组织"维度 |

**建议：读法 B + 「组织/部门」作为独立属性。**

- 一级分类 = 领域分类（沿用现有 6 个词汇，超管配管理员）；
- 二级分类 = 各领域管理员增删的细分；
- **组织/部门**做成技能上的独立字段（默认取用户归属，超管可改），只用于展示（卡片副标题）与筛选，
  **不参与"分类管理员"的授权作用域**；
- UCD 里"按组织浏览"作为左栏的**第二个折叠分组**并存，视觉不改。

理由：同时满足需求原文三处硬描述（上传选一级、按一级配管理员、在自己类别下建二级），
且不必把"组织"塞进分类树。

**若采纳读法 A**，则需你另行确认：上传时到底选不选一级、发布页的「领域分类」下拉映射到哪一级。
（本文件其余章节按读法 B 展开；选定 A 后 §4/§5/§7 需相应改写。）

---

## 2 目标与范围

### 2.1 包含

1. 一级分类可运行时维护（超管），二级分类由**本类目分类管理员**增删。
2. **上传不再选可见性**：新建技能一律 `PRIVATE`；公开只能经"申请 → 分类管理员/平台管理员审批"
   （§6.0）。这是策略变更，不是单纯加功能。
3. 技能绑定/解绑一级分类（owner 可做；上传、更新、申请公开三处均可绑定）；公开技能绑定/取消二级分类（**分类管理员**可做）。
4. 分类管理员：由超管按一级分类逐个指定；可审批**本类目下私有技能转公开**的申请。
5. 超管可查看与操作全部审批、全部权限设置；**超管在全部接口都有权限**（§11.1 硬要求）。
6. 「全部技能」页展示两级分类目录（仅两级，严格按 UCD 布局）。
7. 修复同一公开技能所有者权限信息不一致。
8. 修复 Swagger Bearer 未真正接入接口。
9. 专题：创建（仅专题管理员/超管）、详情页、列表、安装、使用指南；专题管理员由超管添加；
   配套超管配置页面。

### 2.2 不包含（本期显式排除）

- 三级及更深分类（**硬约束：仅两级**）。
- 分类管理员的"分类内内容审核"（版本审核仍归 `SKILL_ADMIN`/空间管理员）。
- 用户自定义分类。
- 专题的版本化（专题是可编辑集合，不做不可变版本台账）。
- 专题「AI 扩写」的真实模型接入（见 D7，建议做能力开关 + 空实现）。
- 专题评论/收藏/订阅的社交扩展。

---

## 3 关键设计决策（含待确认项）

下表是设计的骨架。**已按 2026-11-27 答复定版**：D0=B、D1=(a)、D2=(a)、D3 与 D13 由 §6.0 统一关闭；
**表内 D0/D1/D2/D13 行残留的 `[待确认]` 标记一律作废，视为已定**。
仍开放：D7、D8，以及 §16.1 的另外两条。

| 编号 | 决策点 | 选项 | 建议 | 理由 / 代价 |
|---|---|---|---|---|
| **D0** [待确认·最高优先] | **分类模型读法**：一级到底是"组织/部门"还是"领域分类" | 见 §1.4 读法 A / 读法 B | **读法 B**（一级=领域分类，组织作为正交属性） | 只有 B 能同时满足需求原文"上传时选一级类别"与 UCD 左栏组织分组；选 A 则 §4/§5/§7 需重写 |
| **D1** [待确认] | 一级分类的**初始集合**（以 D0=B 为前提） | (a) 沿用现有 6 个：设计/开发/测试/工程/项目管理/其他<br>(b) 采用 UCD 二级词表作为一级：系统设计/软件开发/集成验证/通用测试/专项测试/硬件开发<br>(c) 现有 6 个为一级，UCD 那套作为各一级下的初始二级 | **(c)** | 保住历史数据不丢；UCD 词表作为二级落地，视觉一致。<br>若选 (b) 则历史 `DESIGN/DEVELOPMENT/ENGINEERING` 需有损映射到新词表 |
| **D2** [待确认] | 「解绑一级分类」的落点 | (a) 置为 `OTHER`<br>(b) 允许 `NULL` 表示未分类 | **(a)** | 保护既有不变量 `null → OTHER`（`Skill.java:203-205`）与部分索引形状；`OTHER` 就是"未分类" |
| **D3** ✅已定 | 是否堵住「私有→公开」审批绕过 | 你的答复选了比 (c) 更彻底的方案：**去掉上传直发** | **已由 §6.0/§0.1 #1 关闭** | 上传一律私有、公开只走申请，绕过通道不复存在；代价是 `docs/14` §4.1 权威表需整表重写 |
| **D4** [待确认] | 二级分类的归属判定 | (a) 只允许 `PUBLIC` 技能挂二级<br>(b) 任意可见性技能都可挂 | **(a)** | 原文"为自己类别下的**公开技能**设置/取消二级分类"；目录页只展示公开技能，二级对私有技能无展示意义 |
| **D5** | 分类管理员权限的存储 | (a) 复用平台角色 `CATEGORY_ADMIN`<br>(b) 新建作用域绑定表 `skill_category_admin` | **(b)** | (a) 全局生效 + 覆盖式分配会把用户其他角色清掉（§1.2），会造成越权与权限丢失 |
| **D6** | 专题管理员的承载方式 | (a) 新增平台角色 `TOPIC_ADMIN`<br>(b) 专题专用绑定表 | **(a)**，但**必须先修**平台角色为增量授予 | 专题是平台级、不分作用域，语义上就是平台角色；修好增量授予后同时解掉 C3 文档债 |
| **D7** [待确认] | 「AI 扩写」本期是否实现 | (a) 实现（需引入 LLM 网关/密钥/限流/成本）<br>(b) 只保留「内容填写」页签，扩写做成可插拔端口 + 默认关闭<br>(c) 完全不做，UI 不留入口 | **(b)** | 全仓无 LLM 能力（`llmProvider` 只属于扫描器配置）；强行实现要新增外部依赖、密钥管理与成本口径，超出"分类+专题"主干。<br>建议：`TopicGuideExpansionPort` 接口 + 配置开关，默认无实现时页签隐藏，UCD 视觉保留 |
| **D8** [待确认] | 专题提审后由谁批 | (a) 超管批（专题管理员创建 → 超管审核；超管创建 → 直接发布）<br>(b) 复用统一审核队列（新增 `TOPIC` 主体类型） | **(a)** 一期 | (b) 需要动 `ReviewSubjectType`、`ReviewTask.subject_version` 非空约束、三处队列 UI 与通知；(a) 用一个轻量状态机 + 审计即可，代价是专题不进统一队列。<br>若你要求"所有审批都在一个收件箱"，则选 (b)，工期 +约 1/3 |
| **D9** | 二级分类筛选是否进全文检索 SQL | (a) 加进 `s.sub_category_id = :id` 条件<br>(b) 前端过滤 | **(a)** | 必须与公开分发谓词同谓词（`docs/29:54-58` 契约），前端过滤会泄露隐藏/私有结果 |
| **D10** | 一级分类管理权 | 超管独有（增删改、指派管理员） | 是 | 原文只把"二级增删"下放给分类管理员；一级由超管维护 |
| **D11** | 分类管理员能否跨类目操作 | 否，严格限本类目 | 是 | 否则等于造出第二个 `SKILL_ADMIN` |
| **D12** | 删除二级分类时已挂载技能怎么办 | (a) 阻断（有技能在用则不可删）<br>(b) 级联清空 `sub_category_id` 并审计 | **(a)** 优先阻断，提供"先解绑"引导 | 静默清空属破坏性副作用；阻断更安全，代价是管理员要多一步 |
| **D13** [待确认] | 是否关闭 Path B（版本审核携带 `targetVisibility` 可直接改可见性） | (a) 关闭：`submit-review` 不再接受非 `PRIVATE` 的 `targetVisibility`<br>(b) 保留但串到 `SkillVisibilityService` 的校验与审批<br>(c) 不处理 | **(a)** | 不处理则分类管理员审批可被整条绕过（§6.2 要点 3）。<br>**代价**：可能影响依赖该字段的既有调用方（需先确认 CLI/外部是否使用），并需改 `docs/14` 的权威表 |

---

## 4 领域模型

### 4.1 命名

- **Category（一级分类）**：`skill_category` 中 `level = 1` 的行。
- **SubCategory（二级分类）**：`skill_category` 中 `level = 2` 的行，必有 `parent_id`。
- 单表自引用 + `level` 列，**用约束把"仅两级"钉死**，而不是靠注释。

### 4.2 `skill_category`

```sql
CREATE TABLE skill_category (
    id           BIGSERIAL PRIMARY KEY,
    parent_id    BIGINT      REFERENCES skill_category(id) ON DELETE RESTRICT,
    level        SMALLINT    NOT NULL,
    slug         VARCHAR(64) NOT NULL,
    legacy_domain VARCHAR(32),                       -- 仅 level=1，映射旧 SkillDomain 线值
    sort_order   INTEGER     NOT NULL DEFAULT 0,
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by   VARCHAR(128) REFERENCES user_account(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_category_level CHECK (level IN (1, 2)),
    CONSTRAINT ck_category_shape CHECK (
        (level = 1 AND parent_id IS NULL) OR (level = 2 AND parent_id IS NOT NULL)
    ),
    -- 供组合外键使用：level=1 时 parent_level 为 NULL
    parent_level SMALLINT GENERATED ALWAYS AS (
        CASE WHEN parent_id IS NULL THEN NULL ELSE 1::SMALLINT END
    ) STORED
);

-- 让"父必须是 level=1"成为数据库事实
ALTER TABLE skill_category
    ADD CONSTRAINT uk_category_id_level UNIQUE (id, level);
ALTER TABLE skill_category
    ADD CONSTRAINT fk_category_parent_is_root
    FOREIGN KEY (parent_id, parent_level) REFERENCES skill_category (id, level);

-- slug 在同一父下唯一（NULL 父需单独索引，PG 默认唯一约束不合并 NULL）
CREATE UNIQUE INDEX uk_category_root_slug  ON skill_category (slug) WHERE parent_id IS NULL;
CREATE UNIQUE INDEX uk_category_child_slug ON skill_category (parent_id, slug) WHERE parent_id IS NOT NULL;
CREATE UNIQUE INDEX uk_category_legacy_domain ON skill_category (legacy_domain)
    WHERE legacy_domain IS NOT NULL;
CREATE INDEX idx_category_parent_sort ON skill_category (parent_id, sort_order, id);
```

设计取舍：

- **为什么不建两张表**：两级结构用自引用 + 形状约束足够，且"新增第三级"会被 `ck_category_shape`
  与组合外键同时拦住；两张表反而让"父/子"查询要跨表 union。
- **为什么 `legacy_domain` 单列**：旧线值（`DESIGN` 等）必须继续被 CLI/外部调用方接受；
  映射关系放数据里，而不是在代码里写 `if` 表。
- **对照 `V121` 的既有取向**：V121 有意不给 `domain` 加 CHECK（"加分类不应触发迁移"）。
  这里改为**外键引用**：新增分类是插行，不需要迁移；需要迁移的只是结构，不是取值。两者不矛盾。

### 4.3 `skill_category_translation`

```sql
CREATE TABLE skill_category_translation (
    id           BIGSERIAL PRIMARY KEY,
    category_id  BIGINT      NOT NULL REFERENCES skill_category(id) ON DELETE CASCADE,
    locale       VARCHAR(16) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (category_id, locale)
);
```

沿用 label 的翻译模型（`label_translation`，`docs/2026-03-20-skill-label-system-design.md:56-72`）。
展示回退顺序：请求语言 → `zh`（本产品为中文主站）→ `en` → `slug`。

> 注意：UCD 文案是中文单语；但前端有 4 个 locale（`en/zh/ko/ru`，`web/src/i18n/config.ts:18-23`）且
> 有键对齐测试。**运行时分类名不能走前端 i18n key**（否则每加一个分类要改 4 个 JSON），
> 必须由服务端返回 `displayName`，前端直出。这是 §9 的硬约束。

### 4.4 `skill_category_admin`（分类管理员绑定）

仿 `organization_role_binding`（`V60:112-151`），把 `organization_id` 换成 `category_id`：

```sql
CREATE TABLE skill_category_admin (
    id          VARCHAR(64) PRIMARY KEY,
    category_id BIGINT      NOT NULL,
    user_id     VARCHAR(128) NOT NULL REFERENCES user_account(id),
    status      VARCHAR(16) NOT NULL,
    granted_by  VARCHAR(128) NOT NULL,
    granted_at  TIMESTAMPTZ NOT NULL,
    revoked_by  VARCHAR(128),
    revoked_at  TIMESTAMPTZ,
    version      BIGINT     NOT NULL DEFAULT 0,
    -- 外键列清单不能写常量，用一个恒为 1 的生成列把"只能挂一级分类"变成外键
    root_level  SMALLINT GENERATED ALWAYS AS (1::SMALLINT) STORED,
    CONSTRAINT ck_category_admin_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_category_admin_revocation CHECK (
        (status = 'ACTIVE'  AND revoked_by IS NULL     AND revoked_at IS NULL) OR
        (status = 'REVOKED' AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL)
    ),
    -- 只能把管理员挂到一级分类上
    CONSTRAINT fk_category_admin_is_root
        FOREIGN KEY (category_id, root_level) REFERENCES skill_category (id, level)
);

CREATE UNIQUE INDEX uk_category_admin_active
    ON skill_category_admin (category_id, user_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_category_admin_user_active
    ON skill_category_admin (user_id) WHERE status = 'ACTIVE';
```

**一行一人一分类**；同一用户可以是多个分类的管理员（多个 ACTIVE 行）。
撤销保留历史（不物理删除），满足"权限设置可被超管查看"。

### 4.5 `skill` 表改动

```sql
ALTER TABLE skill ADD COLUMN category_id     BIGINT REFERENCES skill_category(id);
ALTER TABLE skill ADD COLUMN sub_category_id BIGINT REFERENCES skill_category(id);
```

> 不新增"组织/部门"字段：§0.1 #4 已定一级 = 现有 6 个枚举值，UCD 的组织层是占位，
> 不进入数据模型。卡片副标题先用 namespace 显示名（§16 待确认）。

保证 `sub_category_id` 的父就是 `category_id`：

```sql
-- 组合外键：子分类的 (id, parent_id) 必须等于 (sub_category_id, category_id)
ALTER TABLE skill ADD CONSTRAINT fk_skill_sub_under_category
    FOREIGN KEY (sub_category_id, category_id)
    REFERENCES skill_category (id, parent_id);

-- MATCH SIMPLE 下只要有一列为 NULL 该外键就不检查，
-- 因此必须显式堵住"有二级、无一级"这种状态
ALTER TABLE skill ADD CONSTRAINT ck_skill_sub_requires_category CHECK (
    sub_category_id IS NULL OR category_id IS NOT NULL
);
```

> ⚠️ 该外键要求被引用列 `(id, parent_id)` 唯一。`parent_id` 在一级分类上为 NULL，PG 默认
> 唯一约束把 NULL 视作各不相同，因此普通 `UNIQUE (id, parent_id)` 不成立。
> **目标库是 PostgreSQL 16**（`docker-compose.yml:18` `postgres:16-alpine`），
> PG 15+ 支持 `NULLS NOT DISTINCT`，可直接写成：
>
> ```sql
> ALTER TABLE skill_category
>     ADD CONSTRAINT uk_category_id_parent UNIQUE NULLS NOT DISTINCT (id, parent_id);
> ```
>
> 这样 `fk_skill_sub_under_category` 成立，**不需要触发器**。
> 若部署方使用更旧的 PG（如外部托管库），则退化为"服务层强校验 + `BEFORE INSERT/UPDATE` 触发器"，
> 并在发布说明里记录该差异。

索引（与公开分发谓词同形，参照 `idx_skill_domain_public`）：

```sql
CREATE INDEX idx_skill_category_public
    ON skill (category_id) WHERE status = 'ACTIVE' AND hidden = FALSE;
CREATE INDEX idx_skill_sub_category_public
    ON skill (sub_category_id) WHERE status = 'ACTIVE' AND hidden = FALSE;
```

### 4.6 `skill.domain` 的去留

**单一行状态**，避免"枚举 + 表"双真源（这正是缺陷①的病根类别）：

1. `V124`：建新表/新列 → 用 `legacy_domain` 把 `skill.domain` 回填进 `skill.category_id`；
   `skill.domain` **保留但自本版起不再被任何代码读取**。
2. 加一条架构测试：`server/` 中除迁移外不得出现 `s.domain` / `getDomain()` 的读用法（白名单仅 DTO 兼容层）。
3. `V125`（下一版）删除 `skill.domain` 列与 `idx_skill_domain_public`。

回填映射（按 D1 的结论二选一，下表按建议 (b) 展开）：

| 旧 `domain` | → 一级分类 slug |
|---|---|
| `DESIGN` | `design` |
| `TESTING` | `testing` |
| `PROJECT_MANAGEMENT` | `project-management` |
| `OTHER` | `other` |
| `DEVELOPMENT` | `other`（有损，见 D1） |
| `ENGINEERING` | `other`（有损，见 D1） |
| `NULL` | `other` |

---

## 5 权限模型

### 5.1 作用域总览

| 作用域 | 载体 | 现有/新增 | 能否被分类管理员借用 |
|---|---|---|---|
| 平台角色 | `role` / `user_role_binding` | 现有（+`TOPIC_ADMIN` 新增） | ❌ 全局，不能 |
| 空间角色 | `namespace_member`（OWNER/ADMIN/MEMBER） | 现有 | ❌ 跨语义 |
| **分类管理员** | `skill_category_admin` | **新增** | — |
| 专题管理员 | 平台角色 `TOPIC_ADMIN` | **新增** | — |

### 5.2 权限矩阵

| 操作 | 超管 | 分类管理员（本类目） | 技能 owner | 空间 ADMIN/OWNER | 普通用户 |
|---|:--:|:--:|:--:|:--:|:--:|
| 建/改/删/排序**一级**分类 | ✅ | ❌ | ❌ | ❌ | ❌ |
| 建/改/删/排序**二级**分类 | ✅ | ✅（仅本类目下） | ❌ | ❌ | ❌ |
| 指派/撤销分类管理员 | ✅ | ❌ | ❌ | ❌ | ❌ |
| 绑定/解绑技能**一级**分类 | ✅ | ❌（不代 owner 决策） | ✅ | ✅ | ❌ |
| 设置/取消公开技能**二级**分类 | ✅ | ✅（仅本类目 + 仅 PUBLIC） | ❌ | ❌ | ❌ |
| 审批"私有→公开"申请 | ✅ | ✅（仅本类目的技能） | ❌（不可自审） | ❌（GLOBAL 空间不再放行，见 §6） | ❌ |
| 审批内容版本/提升 | ✅ | ❌ | ❌ | ✅（仅 TEAM） | ❌ |
| 创建/编辑专题 | ✅ | ❌ | ❌ | ❌ | ❌ |
| 审批专题 | ✅ | ❌ | ❌ | ❌ | ❌ |

### 5.3 授权实现

新增 `CategoryAuthorizationService`（应用层）与 `CategoryPermissionChecker`（领域层）：

```java
// domain: 纯判定，无 IO
public boolean isCategoryAdmin(String userId, Long categoryId);
public boolean canManageSubCategories(String userId, Long categoryId);
public boolean canTagPublicSkill(String userId, Long categoryId, Skill skill);
public boolean canApprovePublicVisibility(String userId, Long categoryId, ReviewTask task);
```

`canTagPublicSkill` 必须同时满足：

1. `skill.getVisibility() == PUBLIC`（D4）；
2. `skill.getCategoryId().equals(categoryId)`；
3. 调用者是该 `categoryId` 的 ACTIVE 管理员；
4. 技能未被隐藏、未归档（隐藏/归档技能不做分类运营）。

`canApprovePublicVisibility` 必须同时满足：

1. `task.getSubjectType() == SKILL_METADATA`；
2. `task.getRequestedVisibility() == PUBLIC`（只批"转公开"，不批缩小范围/内容版本）；
3. 任务关联技能的一级分类 ∈ 调用者管理的分类；
4. `!task.getSubmittedBy().equals(userId)`（**禁止自审**，与既有 `canReview` 精神一致）；
5. 任务处于 `PENDING`。

**禁止把分类管理员写进 `platformRoles`**。若那样做，`@PreAuthorize("hasRole('SKILL_ADMIN')")`
会在一瞬间对全部 18 个 `controller/admin/**` 生效，等于把分类管理员升格为平台技能管理员。
分类管理员只能通过**显式注入的 `adminCategoryIds`** 或服务层查询参与判定。

### 5.4 请求上下文注入

在唯一的注入点 `AuthContextFilter.java:83-90` 增加：

```java
request.setAttribute("adminCategoryIds", categoryAdminQuery.activeCategoryIds(principal.userId()));
```

- 契约与 `userNsRoles` 一致：**永远是 Set，空集而非 null**；
- 为避免每个请求多一次查询，`CategoryAuthorizationService` 用请求级缓存
  （`@RequestScope` 或显式 `request.setAttribute` 记忆），仅在实际需要时加载；
- 新增测试：匿名请求该属性为空集、不抛异常。

---

## 6 审核与可见性路由

### 6.0 新的可见性策略（已定，本节优先于其余章节的现状描述）

**一句话**：上传不带可见性、一律私有；公开是"申请 + 审批"的结果，不是发布的参数。

```
上传/重新上传  ──────────────►  PRIVATE（固定，用户不可选）
                                   │
                                   │ 用户发起「申请公开」
                                   ▼
                          ReviewTask(SKILL_METADATA,
                                     requestedVisibility=PUBLIC|NAMESPACE_ONLY)
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    ▼                    ▼
   本一级分类的分类管理员      平台 SKILL_ADMIN         SUPER_ADMIN
   （限本类目、禁止自审）       （全部类目）             （全部类目）
              └────────────────────┼────────────────────┘
                                   ▼ 批准
                                PUBLIC / NAMESPACE_ONLY
```

与现状的差异（逐条对应 §0.1 #1/#3）：

| 项 | 现状（代码） | 新策略 |
|---|---|---|
| 上传时选可见性 | `PUBLIC`/`NAMESPACE_ONLY` 直发 `PUBLISHED`（`docs/14:97`） | **表单不再提供可见性选择**；一律 `PRIVATE` |
| 重新上传的可见性 | 以 `PUBLIC` 重传立即公开（`docs/14:109`） | 不改变容器可见性；上传不是提权手段 |
| 公开的唯一入口 | 直发 / 扩范围申请两条 | **只有"申请公开"一条** |
| 已公开技能 | 可再申请"扩大范围" | 已 `PUBLIC` 即终态，**不存在"再公开"**（§0.1 #3） |
| 缩小范围 | 立即生效 | 保留立即生效（不涉及审批） |

**必须同步改的代码点**

1. `SkillPublishService.interactiveUpload`：删除 `PUBLIC`/`NAMESPACE_ONLY` 直发分支；
   新建容器可见性固定 `PRIVATE`，版本按既有 `UPLOADED` 语义落库。
   **可信发布者豁免（D9 / §0.1 #8）**：

   ```java
   boolean trusted = platformRoles.contains("SKILL_ADMIN") || platformRoles.contains("SUPER_ADMIN");
   if (trusted && requestedVisibility == PUBLIC) {
       // 直发，但必须留痕
       audit.record(actor, "SKILL_PUBLISH_DIRECT", "SKILL", skillId, ...,
               AuditDetail.of("bypass", "TRUSTED_PUBLISHER", "via", entryPoint));
   } else {
       visibility = PRIVATE;   // 普通用户：一律私有，等申请
   }
   ```

   三个入口（Web / CLI / ClawHub compat）共用这一判定，避免各写一套。
   注意：`SKILL_ADMIN` 也获得直发权（它本来就能审批全部类目，直发是其审批权的等价物）；
   若不希望如此，把 `trusted` 收紧为仅 `SUPER_ADMIN` 即可，判定点只有这一处。

2. **`confirm-publish` 重定义为"提审"**（§0.1 #6，D6）：
   不再把版本置 `PUBLISHED`，而是**创建一条 `SKILL_METADATA` 公开申请**
   （`requestedVisibility=PUBLIC`、`previousVisibility=PRIVATE`），
   技能与版本仍保持私有/未发布；对外状态表现为"待评审"。
   批准后由既有 `SkillVisibilityService.approve` 落 `PUBLIC`。
   这样"提交审核"就是发布流程的最后一步，用户不必再另找"申请公开"入口（业界对齐结论见 §0.1 与计划）。
2. 发布接口的 `visibility` 表单字段：**保留字段但不参与决策**（丢弃并记审计），
   还是直接标 deprecated —— 建议**保留并忽略**，避免旧 CLI/草稿报 400。
3. `SkillVisibilityService`：成为公开的唯一通道；移除"已 `PUBLIC` 再申请"的入口
   （`target.ordinal() < current` 的扩范围分支只允许从 `PRIVATE` 出发）。
4. `SkillReviewSubmitService.submitForReview` 的 `targetVisibility`：**必须拒绝**
   （见 §6.2 要点 3），否则仍是绕过通道。
5. `docs/14-skill-lifecycle.md` §4.1 权威表要整表重写；`docs/skill-visibility.md`
   的"上传即生效"表格（`:15-20`）与"提权上传不经审核"（`:5`）要改。

**存量数据**：已 `PUBLIC` 的技能**保持公开、不回溯降级**；本次变更只约束新上传与新的可见性变更。
（迁移侧见 §14。）

---

### 6.1 现状约束

`ReviewPermissionChecker.canReviewNamespace`（`:63-76`）对**非 TEAM 空间直接 false**，
而公开技能多在 `global`（GLOBAL）空间（`V1__init_schema.sql:162-163`）。
因此今天 GLOBAL 空间的"私有→公开"申请只有 `SKILL_ADMIN`/`SUPER_ADMIN` 能批。
分类管理员要进来，必须显式拓宽这个方法——**不能靠新增平台角色**。

### 6.2 改动

下面 7 点是**必须同时做**的，漏掉任何一条，分类审批都会变成一条看起来能用、实际能被绕过的通道。

**要点 1：判定键必须是 `requestedVisibility`，不能是 `subjectType`。**
`SKILL_METADATA` 同时承载"可见性扩范围"与"头像变更/移除"，`SKILL_VERSION` 也能携带目标可见性
（见要点 3）。按 `subjectType` 路由必然误判。既有代码在 `ReviewService.java:243,418` 用的判据就是
`requestedVisibility != null`，应沿用。`ReviewTask.forVisibility` 另外会把
`subjectVersion` 写成 `"visibility:<uuid>"`（`ReviewTask.java:147-155`），可作为调试特征但不要当键。

**要点 2：唯一决策点是 `ReviewPermissionChecker.canReview`，只在那里加分支。**
approve / reject / view / 单条可见性判断全部经由它
（`ReviewService.approveReview:238-241`、`rejectReview:337-340`、`canViewReview:454-460`
→ `ReviewPermissionChecker.canViewReview:52-61`）。改一处即四处生效，不要在 controller 各写一遍。

```
canReview(task, userId, ...):
  if (task.requestedVisibility == PUBLIC && task.skillId != null
      && canApprovePublicVisibility(userId, categoryOf(task.skillId), task)):
      return true
  return 既有分支（SKILL_ADMIN / SUPER_ADMIN / TEAM OWNER|ADMIN）
```

`canApprovePublicVisibility` 的硬判条件见 §5.3。其中"禁止自审"对新角色同样生效：
分类管理员若同时是提交人，只能由 `SUPER_ADMIN` 批（与 `canReview:25-35` 的既有精神一致），
否则等于给了自己给自己提权的路径。

**要点 3：必须同时关闭 Path B，否则整条分类审批可被绕开（本设计最重要的发现）。**
`SkillReviewSubmitService.submitForReview:118-119` 把请求里的 `targetVisibility` 写到
`skill_version.requestedVisibility`；审核通过时 `ReviewService.approveReview:284-286` 直接
`skill.setVisibility(...)`。这条路径：

- **不经过** `SkillVisibilityService.validateApproval` —— 没有扫描校验、没有 staleness 校验；
- 审批权只受命名空间规则约束（TEAM 的 ADMIN/OWNER 即可），与分类无关。

后果：任何 owner 走"提交版本审核并携带 PUBLIC"就能跳过分类管理员。必须二选一：

- **推荐**：收紧入口——`submit-review` 不接受非 `PRIVATE` 的 `targetVisibility`（或直接忽略该字段），
  使"扩大可见性"只有 `SkillVisibilityService` 一条通道；
- 备选：让携带目标可见性的版本审核也串到 `SkillVisibilityService`（复用同一申请与校验）。

同时 `docs/14-skill-lifecycle.md` §4.1 的权威表要补一行"版本审核携带目标可见性"，因为该文
`:107` 声称"只有扩范围申请会创建审核任务"，与 Path B 的存在不符。

**要点 4：队列过滤必须落在仓库查询里，不能沿用现有的事后过滤。**
`ReviewPortalAppService.java:214-216` 现在是"先分页、再 `stream().filter(canViewReview)`"，
结果是页内条数变少而 `getTotalElements()` 不变（既有缺陷）。新增过滤要走仓库谓词：

```java
// 与 findByStatus / findByNamespaceIdAndStatus 并列
Page<ReviewTask> findByStatusAndSkillCategoryIn(
        ReviewTaskStatus status, Collection<Long> categoryIds, Pageable pageable);
```

实现用 `review_task JOIN skill`（`review_task.skill_id → skill.category_id`）。
`SUITE_VERSION` 任务的 `skill_id` 为空（`ReviewTask.forSuiteVersion:118-133` 不设该字段），
因此天然不进入分类队列——这符合"分类管理员不管套件"的口径，但要在注释里写明，避免日后被当成 bug"修复"。
若将来分页性能不达标，再把 `category_id` 冗余到 `review_task` 并在创建时写入（需回填存量 PENDING 行）。

**要点 5：队列有两个独立读取方，必须同步改，否则会出现"审核页看不到、收件箱看得到"。**
`ReviewPortalAppService.listReviewsByOrigin:201-212` 与
`GovernanceWorkbenchAppService.visiblePendingReviews:174-189`。后者**没有复用**
`ReviewPermissionChecker`，而是把角色判断内联重写了一遍。本设计要求后者改为委托 checker +
同一仓库谓词，消除第二份语义。

**要点 6：无管理员时的回落。** 某分类没有 ACTIVE 管理员 → 该分类的申请只落在
`SKILL_ADMIN`/`SUPER_ADMIN`；不自动指派、不改队列归属、不降级为"谁都能批"。

**要点 7：补审计缺口。** `SkillVisibilityService` 目前**没有** `AuditLogService` 依赖（`:20-42`，
其 `audits` 字段是 `SecurityAuditRepository`），批准驱动的可见性翻转只留 `REVIEW_APPROVE`
（target=`REVIEW_TASK`），没有任何 `SKILL_VISIBILITY_*`。既然本次要在这条路径上新增审批人，
必须补写 `SKILL_VISIBILITY_CHANGED`，detail 至少含
`approvedBy`、`reviewTaskId`、`approverScope=CATEGORY_ADMIN`。否则"谁把私有技能变成公开的"
在审计里查不到——这违反 AGENTS.md 对授权变更必须留痕的要求。

**队列参数与前端**

`GET /api/{v1,web}/reviews` 增加可选参数 `scope`：

| `scope` | 语义 |
|---|---|
| 缺省 | 既有行为（平台审核角色看全部；空间管理员看本空间） |
| `category` | 只看"我作为分类管理员可批"的任务（本类目 + 转公开 + PENDING + 非自审） |

前端「技能审核」页增加 tab「我的分类」，复用同一 hook（`useReviewList`）与同一审批弹窗，
不新增一套审批 UI（`review-detail.tsx:426-525` 的"评论 + 二次确认"约定照用）。

### 6.3 「发布」按钮的语义要重新界定（原 D3 已由 §6.0 关闭）

现状有两条让人混淆的"发布"：
`submit-review`（提审内容版本）与 `confirm-publish`（PRIVATE 上传后确认发布）。
§6.0 生效后，用户视角只剩两条动作，建议这样收敛：

| 用户动作 | 现在的实现 | 新语义 |
|---|---|---|
| 上传/更新安装包 | 发布 + 可选可见性 | 仅上传内容，容器保持 `PRIVATE` |
| 「提交审核」 | 提审**内容版本**（`PENDING_REVIEW`） | 保留：内容治理用，与"是否公开"解耦 |
| 「申请公开」 | `POST .../visibility` 扩范围 | **唯一的公开入口**，落到 `SKILL_METADATA` 申请 |
| `confirm-publish` | PRIVATE 上传后确认发布 → `PUBLISHED` | 建议**取消该入口**，或改为"确认发布版本"（只影响 `PUBLISHED` 指针，不改可见性） |

需要你确认的一点：`confirm-publish` 是取消还是改义。它影响
`SkillReviewSubmitService.confirmPublish` 与一条集成测试
（`SkillApprovalVisibilityFlowIntegrationTest`）。

同时必须补的测试：

- `SkillPublishServiceTest`：上传任意 `visibility` 入参 → 容器一律 `PRIVATE`；
- `SkillVisibilityServiceTest`：`PRIVATE → PUBLIC/NAMESPACE_ONLY` 创建申请；
  `PUBLIC → *` 缩小立即生效；**不存在** `PUBLIC → PUBLIC` 的申请路径；
- `SkillReviewSubmitServiceTest`：`submit-review` 携带 `targetVisibility` 被拒绝（§6.2 要点 3）。

---

## 7 搜索与目录查询

### 7.1 查询参数

| 参数 | 状态 | 说明 |
|---|---|---|
| `domain` | **保留（兼容）** | 旧线值 `DESIGN`…；解析为对应 `category_id`，未知值 400 |
| `category` | 新增 | 一级分类 slug |
| `subCategory` | 新增 | 二级分类 slug，**可多值**（多选 = OR）；每个都必须属于 `category`（否则 400，不静默空集） |
| `creator` | 新增 | 创建人 `userId`；按 `skill.created_by`（为空回退 `owner_id`）。与 `category`/`subCategory` 是 **AND** 关系 |

约束：

- 所有筛选都是**收窄**，必须拼进同一个公开分发谓词；`null` 表示不过滤（不得变成"空集"）。
- `creator` 与 `category` 同时出现 = AND；`subCategory` 多值内部 = OR。
- 多选 `subCategory` **限定同一 `category` 下**（UCD 的下拉面板就是某一级下的选项）；
  跨一级的混合勾选返回 400，而不是静默取交集为空。
- `creator` 传未知用户 → 返回空列表而不是 400（作者可能已删号），但需在响应里可区分
  "无结果"与"参数非法"。

`domain` 与 `category` 同时出现时以 `category` 为准（并在响应里回显实际生效值），
避免"两个筛选互相覆盖却看不出来"。

### 7.2 SQL 落点（关键：同一谓词）

两处手写原生 SQL 必须同步，否则**总数与页内行数会不一致**
（count 由同一 SQL 文本派生，见 `PostgresFullTextQueryService.java:262-270`）：

- 搜索：`PostgresFullTextQueryService.java:127-147` 的公开分发谓词块**内部**追加：
  ```sql
  AND s.category_id = :categoryId
  AND s.sub_category_id = :subCategoryId
  ```
  （`null` 表示不过滤——保持 `SearchQuery.java:18-21`「absent filter must widen」语义）
- 榜单/首页：`JpaSkillRankingQueryRepository.java:32-48` 的 `ELIGIBLE_SKILL_WHERE`
  与 `domainFilter()`（`:144-146`）同形扩展。

索引：见 §4.5 的两个部分索引，形状与谓词一致。

### 7.3 目录树接口

`GET /api/{v1,web}/categories` 返回启用中的两级树 + 计数：

```json
{
  "categories": [
    {
      "id": 1, "slug": "ascend", "displayName": "昇腾", "sortOrder": 0,
      "count": 5231,
      "subCategories": [
        { "id": 11, "slug": "system-design", "displayName": "系统设计", "count": 812 },
        { "id": 12, "slug": "software-development", "displayName": "软件开发", "count": 1904 }
      ]
    }
  ]
}
```

- 匿名可读；`enabled=false` 的分类不下发；
- `count` 只统计**可公开分发**的技能（与 `ELIGIBLE_SKILL_WHERE` 同谓词），
  避免"目录显示 10 个，点进去 0 个"；
- 计数按请求实时聚合（分类数量有限，`GROUP BY` 成本可控）；若性能不达标再加物化视图。

---

## 8 API 设计

统一响应 `{code,msg,data,timestamp,requestId}`；`/api/v1/**` 与 `/api/web/**` 双映射
（沿用 `SkillDomainController:30` 的既有做法）。所有新路由**必须同时**登记到
`RouteSecurityPolicyRegistry`（访问级别）与 `ApiTokenPolicy`（令牌 scope），否则会落到默认级别。

### 8.1 公开只读

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/categories` | 两级目录 + 计数（§7.3） |

### 8.2 超级管理员 — 分类治理

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/admin/categories` | 建一级/二级（`parentId` 为空即一级） |
| PUT | `/api/v1/admin/categories/{id}` | 改名/翻译/排序/启停 |
| DELETE | `/api/v1/admin/categories/{id}` | 删除；有子分类或技能在用 → 409（D12） |
| PUT | `/api/v1/admin/categories/order` | 批量排序 |
| GET | `/api/v1/admin/categories/{id}/admins` | 列出管理员（含已撤销） |
| POST | `/api/v1/admin/categories/{id}/admins` | 指派（body `{userId}`） |
| DELETE | `/api/v1/admin/categories/{id}/admins/{userId}` | 撤销 |
| GET | `/api/v1/admin/categories/{id}/skills` | 该类目下公开技能（供运营下挂二级） |

`/api/v1/admin/**` 已属 `SESSION_ONLY_ROUTES`（`RouteSecurityPolicyRegistry.java:352`），
Swagger 里必须标注"需网页会话"，见 §11.2。

### 8.3 分类管理员

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/{v1,web}/my/managed-categories` | 我管理的分类（含二级） |
| POST | `/api/{v1,web}/categories/{categoryId}/sub-categories` | 建二级（校验 categoryId ∈ 我的） |
| PUT | `/api/{v1,web}/categories/{categoryId}/sub-categories/{id}` | 改名/排序 |
| DELETE | `/api/{v1,web}/categories/{categoryId}/sub-categories/{id}` | 删除（D12） |
| PUT | `/api/{v1,web}/skills/{ns}/{slug}/sub-category` | 设置 `{subCategoryId}` 或置空（PUBLIC + 本类目） |
| GET | `/api/{v1,web}/reviews?scope=category` | 我的分类待审（§6.2） |

### 8.4 技能一级分类（兼容扩展）

既有 `GET/POST /skills/{ns}/{slug}/domain` **保留**（`domain` 线值继续可用），
新增并列能力而不破坏 CLI：

| 方法 | 路径 | body |
|---|---|---|
| GET | `/skills/{ns}/{slug}/category` | — |
| PUT | `/skills/{ns}/{slug}/category` | `{"categoryId": 12}` 或 `{"categoryId": null}`（=OTHER，D2） |

发布表单同时接受 `category`（新）与 `domain`（旧）；两者都给时 `category` 优先。

### 8.5 专题

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/{v1,web}/topics` | 匿名，仅 `PUBLISHED` |
| GET | `/api/{v1,web}/topics/{slug}` | 匿名，仅 `PUBLISHED` |
| GET | `/api/{v1,web}/topics/{slug}/download` | 匿名，打包 zip（复用套件打包能力） |
| GET | `/api/{v1,web}/my/topics` | 专题管理员/超管 |
| POST | `/api/{v1,web}/topics` | 专题管理员/超管 |
| PUT | `/api/{v1,web}/topics/{id}` | 作者或超管 |
| POST | `/api/{v1,web}/topics/{id}/submit` | 作者，`DRAFT→PENDING_REVIEW` |
| POST | `/api/v1/admin/topics/{id}/approve`\|`reject` | 超管（D8=a） |
| POST | `/api/v1/admin/topics/{id}/archive` | 超管 |
| POST | `/api/v1/admin/users/{userId}/roles` | 增量授予角色（**新增**，修 C3） |
| DELETE | `/api/v1/admin/users/{userId}/roles/{code}` | 撤销角色 |

---

## 9 前端设计

### 9.1 「全部技能」两级目录

现状：`web/src/pages/search.tsx` 已是 `grid lg:grid-cols-[236px_minmax(0,1fr)]`（`:281`），
`<aside>`（`:282-317`）里已经放着 `DomainFilter`（`:315`）。
**不需要新页面骨架，把那一块换成分类树。**

按 UCD 精读后的准确规格（与 UCD 的唯一差异：一级是 6 个枚举值，不是组织）：

- 左栏宽 **232px**（UCD 实测；现有实现 236px，可保持 236 不强行改）；
- **一级行 = 6 个一级分类**（设计 / 开发 / 测试 / 工程 / 项目管理 / 其他）
  + **实心文件夹图标 + 名称 + 右侧 chevron**，**无数量角标**；
  展开态 = 浅灰圆角行底；hover = 更浅灰底；
- **二级行 = 该一级下的分类管理员所建的二级分类**，**无图标**，缩进约 22px，
  选中态 = 浅灰圆角行底；**不渲染 chevron**（产品已定只两级，这是有意偏离 UCD，
  见 §16-10）；
- 一级行没有"全部"节点——UCD 的「全部」是工具栏里的**排序胶囊**，不是分类节点。
  "回到不筛选"的交互用工具栏 chip 上的 `✕`（UCD 的做法）；
- 二级选中不体现在面包屑（页面只有「← 返回」），而是体现为**工具栏的多选 chip**：
  浅灰 chip「集成验证 ✕」「系统设计 ✕」，可多选、可各自删除，右侧 chevron 打开下拉；
- 工具栏下拉是**复选框多选面板**，选项为该一级下的二级分类；
- 卡片栅格：4 列、每张宽 ≈355px、间距 12px、卡高 ≈150px、行间距 ≈17px；
  单卡结构 = 34px 彩色圆角方块图标 + 标题 + **副标题**（数据来源见 §16）+
  二级分类 chip + 单行截断描述 + 底行 ★收藏数 ⬇下载数；
- 列表模式 = 单张白卡内 13 行，行结构 [34px 图标][标题][二级分类 chip][单行描述]
  右对齐 [★][⬇]，行高 ≈66px、1px 分隔线；
- 视图切换 = 灰底分段控件（三横线=列表 / 四宫格=卡片），当前项白底+阴影；
- **分页风格**：本页用「加载更多」（居中 + 环形箭头图标）；
  而「全部专题」与向导用数字分页 —— 两种风格并存是 UCD 的现状，建议**保持各自现状**，
  不强行统一（见 §16-21）。

需要改动的实现点：

- `router.tsx:311-324` 增加 `category` / `sub`（`sub` 可多值，对应 chip 多选）；
  现有 `domain` 保留为兼容别名；
- `router.tsx:318` 现在用 `SKILL_DOMAINS` 白名单校验，未知值**静默丢弃**。
  一级分类仍是这 6 个（可继续用常量校验）；**二级分类是动态的**，
  必须改成"服务端返回的 slug 集合"或"只过滤非法字符、其余透传由服务端 400"，
  否则新建的二级会从 URL 里消失；
- 分类名**直出服务端 `displayName`**（一级可继续用 `home.domain.*` 常量翻译，
  二级必须用服务端名称），不走 `t()`；
- 埋点：现有实现要求 analytics id 是**字面量**（`domain-filter.tsx:47`、`publish.tsx:450-452`），
  一级可以枚举字面量；**二级是动态的**，改为"一个稳定 id + `data-category-slug`"，
  并同步调整埋点清单。

### 9.2 发布/编辑表单

UCD「发布 Skill → 基础信息」的**实际字段顺序**（两列栅格，逐项对齐）：

| 行 | 左 | 右 |
|---|---|---|
| 1 | ✳ Slug（input「请输入」） | ✳ 显示名称（input「请输入」） |
| 2 | **✳ 领域分类（下拉 `select`，占位「请选择」）** | 标签（下拉 `select`，占位「请选择」，**选填**） |
| 3 | **图标**：分段控件「预设图标」（白底选中）|「自定义」；下方一排 **10 个 56px 圆角色块**（薰衣草/长春花/薄荷/淡黄/珊瑚/天蓝/粉/绿/粉/珊瑚），选中 = 2px 黑色描边环 | （同行占满） |
| 4 | 描述（textarea「请输入」，0/1000，**选填**） | （占满） |
| 5 | ✳ 版本号（input「请输入」） | （占满） |
| 6 | 变更说明（textarea「请输入」，0/1000，**选填**） | （占满） |

要点：

- **「一级分类」是单值、平铺下拉**——不是树、不是多选；选项 = 6 个一级分类
  （UCD 里叫"领域分类"，与本设计的"一级分类"同一物）。
- **UCD 发布页本来就没有「可见性」字段**——与 §0.1 #1「上传默认私有、用户不填可见性」
  完全一致。因此发布表单**移除现有 `visibility` `<Select>`**
  （`publish.tsx:424-436`），新建技能一律 `PRIVATE`。
- **"支持解绑一级分类"与"必填"的冲突已按 §0.1 #6 解决**：选填，
  未选落 `OTHER`；更新时"清除"即落 `OTHER`（不是置 NULL）。
- 更新已有技能时走 `PUT /skills/{ns}/{slug}/category`
  （现有 `useChangeSkillDomain`，`use-skill-queries.ts:109-126`，改路径/字段）。
- 「申请公开」是**独立入口**（"我的技能"行的「可见范围」按钮 / 详情页），不在发布表单里；
  这是与 §6.0 配套的交互收敛。
- 该页三步步名随入口不同：本地上传 = 上传文件 / 文件预览 / 基础信息；
  代码仓导入 = 填写地址 / 文件预览 / 基础信息。页头副标题
  「上传 Skill 文件，审核通过后将同步展示到 SkillMall 插件广场」。底部固定条
  「取消 / 上一步 / 提交审核」。
- 底部「提交审核」的语义见 §6.3（内容版本提审，与"是否公开"解耦）。

### 9.3 分类管理员工作台

新增路由 `/dashboard/category-admin`（`router.tsx`），`requireAuth` + 页面内按
`/my/managed-categories` 判定（不新增平台角色，故 `RoleGuard` 不适用）：

- 左：我管理的分类列表；
- 中：二级分类增删改排序（复用 `admin/labels.tsx` 的 `Card + Table + Dialog`
  与 `toFormState/validateLabelFormState` 形态，仓库无表单库，`labels.tsx:60-107` 是模板）；
- 右/下：该分类下公开技能表格，逐行"设置/清除二级分类"；
- 顶部 tab「待我审批」→ 复用 `/dashboard/reviews` 的表格与审批弹窗，传 `scope=category`。

导航入口需改 3 处（`app/layout.tsx:92-95`、`pages/dashboard.tsx:37-66` 的 `SIDEBAR_GROUPS`、
`shared/components/user-menu.tsx:165-189`）。

### 9.4 超管配置页

新增 `/admin/categories`（`SUPER_ADMIN`，照 `router.tsx:773-778` 的 labels 路由写法）：

- 一级分类表格：名称（多语言）、排序、启用、**分类管理员列**（显示当前管理员，
  「配置管理员」弹窗里可搜索用户、增删）；
- 二级分类以展开行或侧栏展示，超管也可直接维护（矩阵里超管全绿）；
- 删除保护提示（D12）。

新增 `/admin/topics`：专题列表 + 待审专题（D8=a）。

### 9.5 专题页面（按 UCD 逐页对齐）

**A. `/topics`「全部专题」列表**

- 顶部渐变 Hero 带：`← 返回`、标题「全部专题」、副标题「优质内容专题合集，精准学习，一步到位」、
  两个统计胶囊 `安装数 10240` / `技能 6080`；右侧搜索框「请输入搜索内容」；
  右上角 `+ 创建专题`（仅专题管理员/超管可见）。
- 卡片网格 **3 列 × 3 行 = 9/页**，数字分页（`Pagination`，与 admin 页一致）。
- 单卡结构（自上而下）：
  1. 封面：预设渐变底 + 几何徽标水印 + **封面大标题** + **一行副标题**（白色，压在渐变上）；
  2. 卡片标题（与封面标题同文）；
  3. 说明段落（最多 3 行，超出截断）；
  4. 底行：左 `10 安装数` `8 技能`，右「查看专区」按钮（outline 小圆角）。
- 空态：无专题时按 `EmptyState` 组件给"暂无专题"。

**B. `/topics/{slug}` 专题详情**

- Hero：渐变背景 + 大标题「...」+ 说明 + 统计胶囊（`安装数` / `技能`）；右上「编辑专题」
  （作者或超管）。
- **安装** 区（`Download` 图标 + 标题「安装」）：
  - `命令行` 黑色胶囊标签 + 说明「运行此命令，安装该技能包中的全部技能」+ 代码块
    （等宽、可复制按钮「复制」）；
  - `下载` 黑色胶囊标签 + 说明「包含该专题所有技能的 zip 包」+ 文件胶囊
    （`<专题名>.zip`，点击下载）。
- **使用指南** 区（`BookOpen` 图标 + 标题）：
  - 「快速开始」：编号圆圈 + 加粗小标题 + 一行说明，逐条；
  - 「详细指南」：`概述` / `核心技能` 小节，核心技能逐条 `技能名（加粗）+ 说明`。
- **技能** 区：技能行列表（图标、名称、二级分类标签、描述、收藏数），行点击进技能详情。
- **边界（UCD「多行说明」明确要求）**：`安装命令行` 最长 1000 字、可含多行；
  代码块必须 `whitespace-pre-wrap break-all` **软换行**，不得横向溢出或截断；
  「复制」复制**完整原文**（含换行）。

**C. `/dashboard/topics/new` 创建向导**

左侧固定步骤条 `① 基础信息 → ② 选择技能 → ③ 使用指南`（已完成显示 `✓`，
当前为实心黑圆），底部固定 `取消 / 上一步 / 下一步 / 提交审核`。

- **① 基础信息**
  | 字段 | 必填 | 形态 | 限制 |
  |---|:--:|---|---|
  | 专题名称 | ✅ | 单行输入，placeholder「请输入」 | ≤128 |
  | 专题说明 | ✅ | textarea，placeholder「说明专题能做什么，以及适合谁使用」，右下角计数 | ≤1000 |
  | 安装命令行 | ✅ | textarea，placeholder「请输入」，右下角计数 | ≤1000，可多行 |
  | 选择首页专题封面和专题详情背景图 | ✅ | **预设主题横向轮播**（左右圆形箭头，选中深色 2px 环，hover 抬升），**共 10 套主题**；每套装两张预览叠放：上=首页封面（约 2:1），下=详情背景（同色淡出到白） | 枚举键 |
  | 封面副标题 | ✅ | 单行输入（**UCD 有、向导未列，见 §16-11**） | ≤64 |
- **② 选择技能**
  左右两栏白卡（左≈784px / 右≈787px）。
  左卡「全部技能」：搜索框「点击此处添加筛选条件」+ 扁平胶囊筛选行（`全部` / `昇腾` 黑底选中 / `越影` / `设计` / `测试` / `IT装备…`）+ 右侧圆形 `>` 横向滚动钮 + 9 行 + 数字分页；
  行 hover → 灰底 + 右侧 `＋`；已添加 → 右侧绿色 `✓`。
  右卡「已选技能（n）」（**全角括号**）：同款搜索 + 已选行，行 hover → 灰底 + 右侧 `✕` 删除；已选超过阈值时右卡**独立分页**。
  空态：线稿开箱插画 + 「暂未添加技能」+「请从左边添加技能」。
- **③ 使用指南**
  顶部灰底分段控件 `内容填写`（白底选中）| `AI扩写`。
  **`内容填写` = 三张带描边卡片，三个独立文本框，各 0/1000**（不是一个大 textarea）：

  | 卡片 | 字段标签 | 提示 / 预填 |
  |---|---|---|
  | ① | 快速开始 | 灰字提示「专题主要工作流以及工作流的描述」；预填模板（86/1000）：`###描述软件任务` 换行 `需涵盖期望的变更内容、相关约束条件以及已有的实施计划。在启动工作前，务必澄清任何缺失或存在歧义的需求`。格式约定 = `###<步骤标题>\n<说明>`，每步一段 |
  | ② | 指南说明 | 提示「描述专题的所有技能组合一起的用途」 |
  | ③ | 技能说明 | 提示「描述专题所选技能的使用说明」 |

  **`AI扩写` = 整块大 textarea**（AI 渐变描边：上蓝下粉），占位「简单描述专题工作流、专题的使用说明概述以及各个技能的使用说明」，右上浅灰胶囊「✨ 一键扩写」，右下 0/1000。

  **三段文本到详情页的映射（UCD 详情页结构反推）**：
  `快速开始` → 详情页「快速开始」编号步骤列表（按 `###` 分段，编号圆圈 + 竖连接线）；
  `指南说明` → 「详细指南 > 概述」；
  `技能说明` → 「详细指南 > 核心技能」（按技能分段渲染）。
  底部按钮由「下一步」变为「**提交审核**」。

> **两处 UCD 与需求的偏差，需要确认（已列入 §16）**
> 1. 封面上的**副标题（slogan）**在创建向导里没有对应字段，而列表页/详情页都需要它。
>    建议**新增 `slogan` 字段**（≤64 字），而不是从 `专题说明` 截首行——截断会产生
>    "副标题读起来像半句话"的观感问题，且说明改写会连带副标题漂移。
> 2. UCD 底部是「提交审核」，说明专题创建后**要经过审批**才发布（与 D8 相关）。

4. 文案需落到 4 个 locale（`en/zh/ko/ru`），并有键对齐测试
   （`ko-locale.test.ts:21-37`、`ru-locale.test.ts:27-30`）。

---

### 9.6 视觉规范（UCD 实测，供实现对照）

| 项 | 值 |
|---|---|
| 页面底色 | 极浅暖灰→冷蓝渐变 `#FBF7F6 → #F3F7FB` |
| 卡片 | 纯白 `#FFF` + 1px `#EDEDED` 描边 + **12px** 圆角 + 极淡阴影 |
| 主文字 / 次级文字 | `#161616` / `#8A8A8A` |
| 强调色 | 青蓝 `#0F7A8A` 级；专题页随主题色变化（青/紫/蓝/绿…） |
| 按钮 | 主操作 = 黑底白字**全圆角胶囊**；次操作 = 白底 1px 灰边胶囊 |
| 标签 chip | `#F2F2F2` 底、4–6px 圆角、12px 字 |
| 必填标记 | 字段名前红色 `✳` |
| 字号层级 | 页面 H1 28px/600；专题 Hero H1 36–40px/700；区块 h2 20px/600 + 20px 蓝色线性图标；卡片标题 15–16px/600；正文 12–13px |
| 代码块 | monospace 加粗青色；**必须折行**（`whitespace-pre-wrap break-all`），不横向滚动 |
| 栅格 | 容器 max-width ≈1700（1920 下左右各 108）；全部技能页左栏 **232px**、内容区 1455px、**4 列**每张 ≈355px、间距 12px、卡高 ≈150px |
| 数字分页 | `‹ 1 2 3 … 5 ›`，当前页浅灰圆角方块（复用 `Pagination`） |

> 仓库现状：`shared/ui` 没有 `Badge`/`Tooltip`/`Popover`/`Combobox`/`Tree` 组件，
> 徽标用内联 span + `badge-soft` 工具类。分类树与多选面板需要**新建组件**，
> 样式取自 `DashboardSidebar`（分组标签 :246-250、active :259-262）与
> `file-tree-node.tsx`（缩进 `depth * 16 + 12`），不要自创一套 chrome。

---

## 10 专题领域

### 10.1 为什么不复用 `SkillSuite`

`SkillSuite` 是"namespace 拥有、内容不可变版本化、可安装"的套件
（`SkillSuite.java:18-66` + `SkillSuiteVersion` + `SkillSuiteVersionMember`），带自己的
`SUITE_VERSION` 审核主体和 bundle 发布/校验。专题是**平台级、可编辑、无版本台账、以运营展示为主**的集合。
复用会把版本、namespace 归属、bundle 校验三套语义一起拖进来，且专题"编辑即生效"与套件"发版"冲突。
**结论：新建聚合，但复用两件东西**——打包下载（`SkillSuiteBundle*` 的 zip 能力）与技能可用性判定
（`SkillSuiteMemberAvailability`）。

### 10.2 模型

```sql
CREATE TABLE skill_topic (
    id              BIGSERIAL PRIMARY KEY,
    slug            VARCHAR(128) NOT NULL UNIQUE,
    title           VARCHAR(128) NOT NULL,          -- 封面大标题 + 卡片名称（UCD 必填，无计数）
    slogan          VARCHAR(64)  NOT NULL,          -- 封面副标题（UCD 有，向导未列，见 §16-11）
    description     VARCHAR(1000) NOT NULL,         -- 专题说明，0/1000
    install_command VARCHAR(1000) NOT NULL,         -- 安装命令行，可多行，展示折行（UCD「多行说明」）
    cover_preset    VARCHAR(32)  NOT NULL,          -- 10 套预设主题之一（封面图 + 详情背景图）
    -- 使用指南三分栏（UCD「内容填写」是三张卡，不是一个 textarea）
    guide_quick_start    TEXT,                      -- ① 快速开始，0/1000，格式 "###<步骤标题>\n<说明>"
    guide_overview       TEXT,                      -- ② 指南说明（概述），0/1000
    guide_skill_notes    TEXT,                      -- ③ 技能说明（核心技能），0/1000
    guide_ai_draft       TEXT,                      -- 「AI扩写」页签的独立草稿，0/1000（D7）
    status          VARCHAR(24)  NOT NULL,
    featured        BOOLEAN      NOT NULL DEFAULT FALSE,
    install_count   BIGINT       NOT NULL DEFAULT 0,
    created_by      VARCHAR(128) NOT NULL REFERENCES user_account(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(128),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMPTZ,
    CONSTRAINT ck_topic_status CHECK (
        status IN ('DRAFT','PENDING_REVIEW','PUBLISHED','REJECTED','ARCHIVED'))
);

CREATE TABLE skill_topic_member (
    id         BIGSERIAL PRIMARY KEY,
    topic_id   BIGINT  NOT NULL REFERENCES skill_topic(id) ON DELETE CASCADE,
    skill_id   BIGINT  NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    added_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (topic_id, skill_id)
);
CREATE INDEX idx_topic_member_skill ON skill_topic_member (skill_id);
CREATE INDEX idx_topic_status_featured ON skill_topic (status, featured, published_at DESC);
```

专题正文按**作者语言原样展示**（内容不是 UI 标签），不建翻译表。

### 10.3 状态机（D8=a）

```
DRAFT --submit--> PENDING_REVIEW --approve(超管)--> PUBLISHED --archive--> ARCHIVED
                        |
                        +--reject(超管)--> REJECTED --edit--> DRAFT
超管直接创建/编辑：DRAFT --publish--> PUBLISHED（跳过审核）
```

- 拒绝必须带原因（沿用 `review-detail.tsx:464-477` 的"拒绝需填理由"约定）；
- 专题成员技能在下线/隐藏后，详情页该行显示"已下架"并置灰，**不自动移除**（移除是显式编辑动作）；
  打包下载时跳过不可用成员并在结果里标注（复用 `SkillSuiteMemberAvailability`）。

### 10.4 专题管理员

- 新增平台角色 `TOPIC_ADMIN`（`V124` 插入 `role` 行 + `role_permission`：
  `topic:manage`、`topic:approve` 中的管理侧）；
- **前置条件**：先修平台角色为增量授予（§11.3），否则给某人加 `TOPIC_ADMIN` 会抹掉其 `SUPER_ADMIN`；
- 配置入口：`/admin/users` 现有角色下拉改为**多选/多行授予**；新增
  `POST/DELETE /api/v1/admin/users/{userId}/roles[/{code}]`；旧 `PUT .../role` 保留为"覆盖式"兼容。

---

## 11 缺陷修复

### 11.1 同一公开技能的所有者权限信息不一致

**结论：这不是一处 bug，而是"维护者/所有者判定被复制了 6 处、出现 3 种语义"的系统性问题。**

权威规则只有一份：`SkillOwnerAccessPolicy`（`assertCanManage:48-56` / `isReadOnly:20-38` /
`assertCanWrite`）。它就是真正拦住写操作的那份——`SkillVisibilityService:48-49`、
`SkillDomainService:50-51`、`SkillPublishService:1135-1140`、`PromotionService:130` 都调它。
**所有 DTO 权限位必须由它派生，不能各写一份。**

已核实的 5 类分歧（按对用户的影响排序）：

**A. `canSubmitPromotion` 三处三算法，其中一处完全没有权限检查（最可能对应你看到的现象）**

| 读路径 | 实现 | 校验 owner/maintainer | 做只读降级 |
|---|---|---|:--:|
| 详情 | `SkillQueryService.canSubmitPromotion:1044-1066` | ✅ `canManageRestrictedSkill && !isReadOnly` | ✅ |
| 我的技能/收藏/订阅 | `JpaMySkillQueryRepository.canSubmitPromotion:290-306` | ❌ 无任何权限判断 | ❌ |
| 市场/搜索 | `SkillSearchAppService.toSummaryResponse:306-334` | — 硬编码 `false` | — |

关键放大点：`getSkillSummaries` 同时被 `MySkillAppService.listMyStars:166-173` 与
`listMySubscriptions:175-197` 使用，**这两个列表里会出现别人 owner 的公开 TEAM 技能**，
于是一条技能在"我的收藏/我的订阅"里显示"可提交提升"，点进详情却显示不可。
只有 `listMySkills` 事后用 `ManagementState.ownerReadOnly` 打了补丁
（`MySkillAppService.java:129-144` + `SkillSummaryResponse.withManagementState:119-124`），
另外两个调用点没有补——这就是"同一公开技能，权限信息不一致"最直接的来源。

**B. 投影漏判 `SUPER_ADMIN`，造成"界面说你不能，接口却允许"**
`SkillQueryService.canManageRestrictedSkill:1034-1042` 只判 owner / 空间 ADMIN / 空间 OWNER，
**没有 `SUPER_ADMIN`**；它喂给 `canManageLifecycle`（`:297-298`）与 `canSubmitPromotion`（`:1064`）。
而真正拦写的 `SkillOwnerAccessPolicy.assertCanManage` 与
`SkillPublishService.assertCanManageLifecycle:1131-1144` **放行 `SUPER_ADMIN`**。
结果：超管既非 owner 也非空间管理员时，详情页 `canManageLifecycle=false`，
但 `POST .../visibility`、`POST .../domain` 都能成功。
前端已在 `web/src/pages/skill-detail.tsx:256,258` 用 `hasRole('SUPER_ADMIN')` 自己兜了一层，
反过来证明这个字段早已不可信。

**C. 维护者规则在原生 SQL 里又抄了一遍，且抄漏**
`SkillSuiteCandidateQueryRepository.java:70-72`、
`SkillSuiteReferenceQueryRepository.java:171-186` 用
`s.owner_id=:userId OR s.namespace_id IN (:adminNamespaceIds)` 判"可管"，
**既漏 `SUPER_ADMIN`，也漏 `isReadOnly` 的"移除成员降级"**。
于是同一技能在套件选择器里可管、在详情页不可管。

**D. 用"无平台角色"的上下文算权限位**
`SkillQueryService.getSkillDetail(namespace, slug, user, roles)`（`:243-246`）内部把
`platformRoles` 传成 `Set.of()`。任何走这个 4 参重载的调用方
（`ClawHubRegistryFacade.java:77`、`SkillAvatarAppService.java:343`）都会把
`ownerReadOnly` / `canManageLifecycle` 算成"该用户没有任何平台角色"。

**E. `withManagementState` 的短路进一步放大上面的分歧**
`SkillSummaryResponse.java:119-124`：

```java
canSubmitPromotion && (state == null || !state.ownerReadOnly())
```

`state == null` 时不做只读降级；而 `state` 的注释明确写着
"never populated by public discovery or social lists"（`:80`），
即**市场/搜索/社交列表恰好永远走不到降级分支**。

**修复方案**

0. **硬要求（§0.1 #3）：`SUPER_ADMIN` 在全部接口都有权限。**
   这不是"顺手加一行"，而是要作为**可测的不变量**：
   - 所有权限判定（`SkillOwnerAccessPolicy`、`ReviewPermissionChecker`、
     `VisibilityChecker`、`LabelPermissionChecker`、新增的 `CategoryPermissionChecker`）
     必须有 `SUPER_ADMIN` 短路分支；
   - 所有 `canManage*` / `canSubmit*` / `canApprove*` **投影位**在超管视角下必须为 `true`
     （`canInteract`/`canReport` 这类"是否需要操作按钮"的位除外，需逐个确认语义）；
   - 加一条**架构级测试**：枚举全部 `/api/**` 的写接口，以超管身份断言不返回 403
     （可用 `@SpringBootTest` + 参数化，或退化为"逐个权限类的超管用例"）。
   现状反例正是 §11.1 B 项：`canManageRestrictedSkill:1034` 漏判 `SUPER_ADMIN`，
   导致界面显示无权限但接口允许——用户体感就是"超管权限不一致"。
1. 单一投影：`SkillCapabilityService.for(skill, viewer)` → `SkillCapabilities`（领域层），
   内部**只调用** `SkillOwnerAccessPolicy` 与新增的 `CategoryAuthorizationService`：

   ```java
   public record SkillCapabilities(
       boolean ownerReadOnly,
       boolean canManageLifecycle,
       boolean canSubmitPromotion,
       boolean canSubmitVisibilityReview,
       boolean canApprovePublicVisibility,   // 新增（分类管理员）
       boolean canTagSubCategory             // 新增（分类管理员）
   ) {}
   ```

2. 三条读路径（详情 / 摘要 / 我的技能·收藏·订阅）全部改为消费该投影：
   删除 B 的私有副本、A 的无校验副本、E 的短路。

3. 两个原生 SQL 的维护者判据改为"先取 id 集合"（由投影服务产出 `manageableSkillIds`），
   或至少补齐 `SUPER_ADMIN` 与移除成员降级；**禁止再各写一套 join 条件**。

4. `getSkillDetail` 的 4 参重载改为显式要求 `platformRoles`（编译期逼调用方想清楚），
   或内部改成"不算权限位"，禁止用它产出 `canManage*`。

5. 收敛 `SkillOwnerAccessPolicy` 与 `SkillPublishService#assertCanManageLifecycle` 的内联副本
   （`SkillOwnerAccessPolicy` 的 javadoc `:42-47` 自己承认存在这份副本）。

6. **契约测试（防回归核心）**：

   ```
   同一 (skill, viewer) 组合下：
     detail.canManageLifecycle == summary.canManageLifecycle == mySkills.canManageLifecycle
     detail.canSubmitPromotion == listMyStars[i].canSubmitPromotion
                               == listMySubscriptions[i].canSubmitPromotion
   参数化：owner 在空间内 / 已被移出 / 超管 / 空间管理员 / 普通用户
          × PUBLIC | NAMESPACE_ONLY | PRIVATE
          × TEAM | GLOBAL
   ```

7. 顺带修复 `docs/02-domain-model.md:343` 的多角色陈述（§11.3）。

### 11.2 Swagger 的 Bearer 授权未真正接入接口

两个独立成因，都必须修：

**成因 A：OpenAPI 里根本没有 securityScheme。**
`OpenApiConfig.java:18-27` 只设了 `Info` 与 `Server`，没有 `components.securitySchemes`、
也没有全局 `security`。因此 Swagger UI 连「Authorize」按钮都不存在，导出的 `schema.d.ts` 也没有鉴权声明。

修：

```java
new OpenAPI()
    .components(new Components().addSecuritySchemes("bearerAuth",
        new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("SkillHub API Token")
            .description("在『个人设置 → API 令牌』创建；仅 /api/v1/** 文档所标注的接口可用")))
    .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
```

**成因 B：令牌白名单把绝大多数接口判为 unsupported。**
`RouteSecurityPolicyRegistry.authorizeApiToken`（`:401-420`）对任何 `/api/**` 路径：

- 命中 `API_TOKEN_POLICIES` 且 scope 足够 → allow；
- 命中但 scope 不足 → missingScope；
- **未命中任何策略 → `unsupported(path)` → 拒绝**（`ApiTokenScopeService.java:56-62` 转成 403）。

而 `API_TOKEN_POLICIES`（`:280-320` 一带）只列了发布/删除/CLI 读等十余条。
所以即使 Swagger 里填了合法令牌，`GET /api/v1/skills/{ns}/{slug}`、搜索、分类目录等
**都返回 403 "API token cannot access endpoint"**。这就是"授权没真正接进接口"。

修（保守、可测）：

1. 为文档化的只读接口补齐 `skill:read` 策略（`GET /api/v1/skills/**`、`/api/v1/skills`
   搜索、`/api/v1/categories`、`/api/v1/topics/**`）；
2. 新增**守护测试**：枚举 OpenAPI 文档里所有 `/api/v1/**` 的 operation，
   断言每个都能在 `API_TOKEN_POLICIES` 或 `SESSION_ONLY_ROUTES` 里找到归属。
   这样以后新增接口忘记登记策略会在 CI 失败，而不是上线后 403；
3. `/api/v1/admin/**` 保持 `SESSION_ONLY`（现状 `:352`），并在 OpenAPI 里给这些
   operation 标注 `security: []` + 说明"仅网页会话"，避免误导；
4. 不改成"未命中即允许"——那会把最小权限反转为默认放开，属安全回退。

**第三个坑（容易漏，必须写进文档与 Swagger 说明）：浏览器里的 Swagger 调写接口仍会被 CSRF 拦。**

`RouteSecurityPolicyRegistry.shouldIgnoreCsrf`（`:423-435`）的放行条件是
**"没有会话 cookie"且"带 `Bearer`"**：

```java
if (!hasSessionCookie && authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
    return true;
}
```

所以只要操作者在同一浏览器里登录过 SkillHub（有 `SESSION` cookie），
哪怕在 Swagger 里填了合法 API 令牌，`POST/PUT/DELETE` 仍然会因为缺少 CSRF token 返回 403。
这不是缺陷，是设计；但"Swagger 授权没接进接口"的体感有一半来自这里。处理方式：

- Swagger UI 里明确提示"带令牌调写接口请使用无痕窗口（不持有会话 cookie）"，
  或"写操作请用 CLI / curl"；
- 读接口不受影响（GET 不需要 CSRF）。

**第四点：不需要改认证链路。** 服务端令牌认证是完整的
（`ApiTokenAuthenticationFilter:68-114`，`shouldNotFilter:116-122` 覆盖 `/api/{v1,web,cli}/`），
**不要**为此引入 JWT 解码器——凭证契约就是 SkillHub API 令牌（不透明串、DB 中存储哈希）。

### 11.3 平台角色增量授予（专题管理员的前置）

现状 `AdminUserAppService.updateUserRole`（`:88-110`）先 `deleteByUserId` 再插一条
（`:101-107`），是覆盖式；UI 也承认只能编辑"第一个角色"（`users.tsx:105-107`）。
而 `docs/02-domain-model.md:343` 声称支持多角色。

修：

- 新增 `grantUserRole(userId, code, actorRoles)` / `revokeUserRole(userId, code, actorRoles)`，
  语义为**增量**，受同样的 `SUPER_ADMIN` 保护（`AdminUserAppService:96-99` 的既有护栏照抄）；
- 旧 `PUT /users/{id}/role` 保留，文档标注"覆盖式，仅用于单角色账号迁移"；
- 前端 `/admin/users` 角色列改为多选标签 + 增删；
- 撤销最后一个角色时落回合成 `USER`（保持 `PlatformRoleDefaults:17-26` 语义）。

---

## 12 审计

仓库**没有审计动作枚举**，全是裸字符串（如 `"SKILL_DOMAIN_CHANGED"`，`SkillDomainAppService.java:67`），
拼错即静默失效。本设计新增以下动作名，并在应用层集中为常量类，避免散落：

| 动作 | target | detail 关键字段 |
|---|---|---|
| `CATEGORY_CREATE` / `CATEGORY_UPDATE` / `CATEGORY_DELETE` / `CATEGORY_ORDER_UPDATE` | `CATEGORY` | `slug`、`level`、`parentId`、变更前后 |
| `CATEGORY_ADMIN_GRANT` / `CATEGORY_ADMIN_REVOKE` | `CATEGORY` | `userId`、`categoryId` |
| `SKILL_CATEGORY_CHANGED` | `SKILL` | `previousCategory`、`targetCategory` |
| `SKILL_PUBLISH_DIRECT` | `SKILL` | `bypass=TRUSTED_PUBLISHER`、`via`（WEB/CLI/COMPAT）、`visibility` |
| `SKILL_VISIBILITY_CHANGED` | `SKILL` | `approvedBy`、`reviewTaskId`、`approverScope`（既有缺口，本次补写） |
| `SKILL_SUB_CATEGORY_CHANGED` | `SKILL` | `previousSubCategory`、`targetSubCategory`、`actorCategoryScope` |
| `TOPIC_CREATE` / `TOPIC_UPDATE` / `TOPIC_SUBMIT` / `TOPIC_APPROVE` / `TOPIC_REJECT` / `TOPIC_ARCHIVE` | `TOPIC` | `slug`、`status` 前后、`reason` |
| `USER_ROLE_GRANT` / `USER_ROLE_REVOKE` | `USER` | `roleCode` |

另：按冲突 C2，**发布路径也要写 `SKILL_CATEGORY_CHANGED`**（现状只在独立入口写）。

---

## 13 测试策略

按仓库「change-impact matrix」分层；以下是本次必须补的**契约级**用例：

后端：

- `CategoryPermissionCheckerTest`：矩阵逐格（超管/本类目管理员/他类目管理员/owner/空间管理员/普通用户）。
- `CategoryShapeConstraintTest`（PG 集成）：插第三级、给二级挂父、把二级当一级 → 都被约束拒绝。
- `SkillSubCategoryAssignmentTest`：非 PUBLIC 技能拒绝；跨类目拒绝；清除成功。
- `ReviewPermissionCheckerTest`：分类管理员只能批"本类目 + 转公开 + 非自审"；
  GLOBAL 空间的非转公开任务仍只有平台角色能批（**防止意外放宽**）。
- `SkillVisibilityReviewRoutingTest`：`scope=category` 只返回可批任务；无管理员分类回落平台队列。
- `SkillCapabilityConsistencyTest`（**缺陷①防回归**）：§11.1 第 4 条的跨读路径一致性参数化测试。
- `ApiTokenPolicyCoverageTest`（**缺陷②防回归**）：OpenAPI 全量 operation × 策略归属。
- `PostgresFullTextQueryServiceTest` / `JpaRankingQueryRepositoryTest`：新过滤条件进同一谓词，
  且 count 与 rows 同条件；`null` 不过滤；未知 slug 400。
- `TopicLifecycleTest`：DRAFT→PENDING→PUBLISHED/REJECTED；成员技能下线后的展示与打包降级。
- 迁移测试：V124 回填后每行 `category_id` 非空、且与旧 `domain` 映射一致。

前端：

- `category-tree.test.tsx`：展开/收起、键盘、`aria-expanded/current`、选中联动 URL。
- `search.test`：URL 里 `category/sub` 与 `domain` 的优先级；未知 slug 不再被静默丢弃。
- `publish` 分类选择 + 清除分类。
- 4 locale 键对齐（既有测试会强制）。
- 专题向导三步的表单校验与计数上限、成员增删排序。

明确**不宣称**：以上不等于部署验证；对象存储 zip 下载、SSO 登录、真实 LLM 扩写需独立环境验收。

---

## 14 迁移与发布

迁移号从 **V124** 起（现最大 V123，`server/skillhub-app/src/main/resources/db/migration`）。

| 迁移 | 内容 | 可回滚性 |
|---|---|---|
| `V124__skill_taxonomy_and_category_admin.sql` | `skill_category`(+translation) / `skill_category_admin` / `skill.category_id`、`skill.sub_category_id` / 回填 / 索引 / 种子数据 | 结构可回滚（新表新列），**回填有损**（D1） |
| `V125__skill_topic.sql` | `skill_topic` / `skill_topic_member` / `TOPIC_ADMIN` 角色种子 / `topic:*` 权限 | 可回滚 |
| `V126__drop_skill_domain.sql` | 删 `skill.domain` 与 `idx_skill_domain_public` | 需重建列并再回填 |

发布顺序建议（避免新旧不兼容）：

1. V124 + 代码切换读 `category_id`（`domain` 仍在，但不再被读）→ 灰度；
2. V125 专题 + 角色增量授予；
3. 下一个版本 V126 删 `domain` 列。

**种子数据（按 D0=B、D1=(c)）**

一级 = 现有 6 个词汇（保住历史数据，`legacy_domain` 一对一，**无有损映射**）：

| level | slug | 中文 | legacy_domain |
|---|---|---|---|
| 1 | `design` | 设计 | `DESIGN` |
| 1 | `development` | 开发 | `DEVELOPMENT` |
| 1 | `testing` | 测试 | `TESTING` |
| 1 | `engineering` | 工程 | `ENGINEERING` |
| 1 | `project-management` | 项目管理 | `PROJECT_MANAGEMENT` |
| 1 | `other` | 其他 | `OTHER` |

二级 = UCD 的领域词表（作为**初始二级**挂在合适的一级下，分类管理员后续可增删）：

| 父（一级） | 二级 slug | 中文 |
|---|---|---|
| `design` | `system-design` | 系统设计 |
| `development` | `software-development` | 软件开发 |
| `development` | `hardware-development` | 硬件开发 |
| `testing` | `integration-verification` | 集成验证 |
| `testing` | `general-testing` | 通用测试 |
| `testing` | `specialized-testing` | 专项测试 |

> D1 已定（§0.1 #4）：`legacy_domain` 与 `skill.domain` **一对一**，**没有有损映射**，
> 也不存在"历史技能落入其他"的信息损失。回填后校验：
> `COUNT(*) WHERE category_id IS NULL` 必须为 0。

**存量可见性**（§6.0）：迁移**不改变任何现有技能的可见性**。
已 `PUBLIC` 的继续公开、已 `NAMESPACE_ONLY` 的继续空间可见；只有**新上传**一律 `PRIVATE`。
迁移脚本里不出现任何 `UPDATE skill SET visibility=...` —— 这条要写进 code review checklist，
因为"默认私有"很容易被误实现成"把所有技能改成私有"。

---

## 15 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| **"默认私有"被误实现成"批量改私有"** | 存量公开技能全部下架，市场瞬间空 | 迁移**不含** `UPDATE visibility`；加 code review checklist + 一条迁移断言测试（迁移前后 `COUNT(*) WHERE visibility='PUBLIC'` 不变） |
| **上传不再选可见性 = 破坏既有 CLI/外部调用方** | 老脚本传 `visibility=PUBLIC` 会怎样？ | 接口**保留字段但忽略**（并记审计），不返回 400；在 CLI 文档标注 deprecated |
| `confirm-publish` 语义变更 | 影响 `SkillReviewSubmitService.confirmPublish` 与一条集成测试 | 二期再做取消，一期只改语义并同步测试（§6.3 列出待确认） |
| 分类树与检索性能 | 目录页每次 `GROUP BY` 全量聚合 | 计数限定公开谓词 + 部分索引；必要时加物化视图/短 TTL 缓存 |
| `scope=category` 队列 | 判定顺序写错可能把内容版本审核也放给分类管理员 | `canApprovePublicVisibility` 硬判 `requestedVisibility==PUBLIC\|NAMESPACE_ONLY` + 禁止自审；配拒绝用例 |
| 删除分类 | 误删导致技能失联 | 默认阻断（D12）+ 二次确认 + 审计；不做硬删除级联 |
| 专题范围蔓延 | AI 扩写/首页精选/评论拉长工期 | D7 显式限缩；一期只做 UCD 主链路 |
| 角色增量授予改造 | 动到用户管理，影响面广 | 保留旧覆盖式接口；新增接口独立测试；先灰度 |

---

## 16 待你确认的开放问题

### 16.0 已关闭

**第一轮（你 2026-11-27 上午的答复）**：D0 分类模型读法、D1 一级集合（= 现有 6 枚举）、
D2 解绑落 `OTHER`、D3 上传提权绕过、D13 Path B 绕过、领域词表角色（降级为二级初始数据）。

**第二轮（同日下午的答复）**：见 §0.2 —— ①必填只对 Web 强制（CLI/compat 缺省 `OTHER`）；
②解绑 = 改为「其他」；③新增 `GET /api/web/skill-authors` 支撑创建人筛选；④创建人按 `created_by`。
另外 D7（不做 AI 扩写）、D8（专题走超管独立审批页）也已定。

> 行为契约以 `specs/` 为准（5 份 spec 的 REQ 编号与 Scenario 是验收依据），
> 本文件负责设计理由、数据模型与取舍；两者冲突时以 spec 为准并回改本文件。

### 16.1 剩余待确认（都已给默认值，可直接反对）

1. **卡片副标题数据来源**：UCD 是部门名（「开发公共部SER」），本次不引入组织模型。
   默认：用**创建人显示名**（与"按创建人筛选"呼应，且不新增模型）。备选：namespace 显示名 / 留空。
2. **二级分类初始数据**：默认**为空**（严格符合"二级由管理员创建"）。备选：预置 UCD 那 6 个。
3. **专题成员排序与上限**：默认不做拖拽（UCD 无把手），按添加顺序；上限 50 条。
4. **专题审核中能否编辑**：默认**不可编辑**（避免审的和发的不一致），可撤回后再改。
5. **分页风格**：默认保持 UCD 现状（全部技能=加载更多；专题/管理页=数字分页）。
6. **发布页其他字段**：Slug 唯一性/字符集、标签是否多选与上限、图标"自定义"是否支持上传 —— 沿用现状，不在本期变更。
7. **安装命令行**：默认直接展示作者填写的那段，不做平台拼接。
8. **首页是否接入专题区块**：默认本期不做，先做 `/topics` 与详情。
9. **空态**：默认沿用 `EmptyState`（"该分类暂无技能"），左栏仍显示该分类。

### 16.2 已定项（不再询问，仅备查）

D4 二级只挂公开技能 · D12 删除在用分类阻断不级联 · 一级分类 Web 必填 · `NAMESPACE_ONLY` 取消 ·
`confirm-publish` 改为提审 · 不做 AI 扩写 · 专题走超管独立审批页 · B 方案可信发布者豁免 ·
创建人按 `created_by` · 新增 `skill-authors` 接口 · 专题字段含 `slogan`（≤64）· 专题封面 10 套纯 CSS ·
专题成员下架置灰不自动移除 · 指南按 `###` 结构化解析。

---

## 17 交付物与推进顺序

已产出：

- 本文件（设计理由、数据模型、取舍、风险）
- `proposal.md`（为什么改、改什么、影响面）
- `specs/skill-taxonomy/spec.md`（REQ-TAX-01..07）
- `specs/category-administration/spec.md`（REQ-CADM-01..07）
- `specs/visibility-approval/spec.md`（REQ-VAPR-01..07）
- `specs/skill-topics/spec.md`（REQ-TOP-01..07）
- `specs/api-auth-and-capability-projection/spec.md`（REQ-AUTH-01..06）
- `tasks.md`（M1–M5 + 交付前检查清单）

推进顺序：M1（分类基础 + 可见性收敛 + 缺陷修复，缺陷部分可并行）→ M2（分类管理员 + 审批 + 管理台）
→ M3（全部技能页 + 发布页）→ M4（专题）→ M5（文档同步 + 全量回归）。

**验收以 `specs/` 的 Requirement/Scenario 为准**；实现与本文件冲突时，先改本文件再改代码，
不得以"文档对齐"为名静默裁定新业务规则。
