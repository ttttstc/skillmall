# 两级分类治理、分类级审批与专题能力

## Why

当前分类是**六个值的闭集枚举**（`SkillDomain`），产品口径明确写「不建模二级、三级目录」
（`docs/29-skill-domain-categories.md:22`）。但实际运营需要：

1. 技能量大之后，一级分类（测试、运维等）内部无法再分层，用户找不到东西；
2. 分类没有人负责：谁都能改自己技能的分类，但没有"类目负责人"对本类目的分类质量、
   公开范围负责；
3. 私有技能转公开的申请目前只能由平台 `SKILL_ADMIN`/`SUPER_ADMIN` 批，
   平台成为瓶颈，且审批人并不了解该业务领域；
4. 缺少"专题"这种运营化组织技能的方式，首页与市场只能按分类和榜单陈列。

同时存在两个已确认的缺陷：

- **同一公开技能的所有者权限信息不一致**：`canSubmitPromotion` 在三条读路径上有三种算法，
  其中 `JpaMySkillQueryRepository:290-306` **完全没有权限判断**，导致别人 owner 的公开技能
  在"我的收藏/我的订阅"里显示"可提交提升"，详情页却显示不可。
- **Swagger 的 Bearer 授权未真正接入接口**：`OpenApiConfig` 未声明 `securitySchemes`
  （Swagger UI 没有 Authorize 按钮）；且 `authorizeApiToken` 对未列入
  `API_TOKEN_POLICIES` 的 `/api/**` 一律判 `unsupported` 拒绝，绝大多数文档化接口
  拿令牌调用仍返回 403。

## What Changes

- **可见性策略变更（最大改动）**：去掉"上传即发布 `PUBLIC`/`NAMESPACE_ONLY`"的直发路径。
  交互式上传**不再让用户选可见性**，新建技能一律 `PRIVATE`；公开只能经"申请 → 审批"获得，
  审批人 = 该一级分类的分类管理员 或 平台 `SKILL_ADMIN`/`SUPER_ADMIN`。已 `PUBLIC` 即终态，
  不存在"再公开"；存量公开技能**不回溯降级**。
- 一级分类从闭集枚举改为**引用数据表** `skill_category`（自引用 + `level`，用数据库约束把
  "仅两级"钉死），**一级固定为现有 6 个枚举值**（设计/开发/测试/工程/项目管理/其他）；
  二级分类由**本类目的分类管理员**增删。
- 新增**分类管理员**作用域授权（`skill_category_admin`，仿 `organization_role_binding`：
  可撤销、留痕、按分类隔离），由超级管理员按一级分类指定。
- 分类管理员可：管理本类目二级分类；为**本类目下的公开技能**设置/取消二级分类；
  **审批本类目下"私有→公开"的申请**（禁止自审）。
- 「全部技能」页左侧栏改为两级分类目录（**严格按 UCD**：可展开树、文件夹图标、缩进二级、
  工具栏排序胶囊、多选分类 chip 下拉、4 列卡片、加载更多、列表/卡片切换）。
- 技能绑定/解绑一级分类：owner 可做；**上传、更新、申请公开**三处均可绑定；`domain` 线值保留兼容。
- 新增**专题**：平台级、可编辑的技能集合，含 10 套预设封面、安装命令行、三段式使用指南、
  技能列表；仅**专题管理员**与超级管理员可创建；专题管理员由超管授予。
- 修复两个缺陷：权限位改为单一权威投影（由 `SkillOwnerAccessPolicy` 派生，并保证
  **超管在全部接口都有权限**）；OpenAPI 补齐 `bearerAuth` 声明与令牌策略覆盖，并加守护测试防回归。
- 顺带修复：平台角色改为**增量授予**（现为覆盖式，会抹掉用户已有角色，
  是授予专题管理员的前置条件）；可见性审批补写审计。

## Impact

- 后端：`skillhub-domain`（分类聚合、分类管理员、能力投影、审核权限）、
  `skillhub-app`（分类/专题/审核 API、管理台、`AuthContextFilter`、`OpenApiConfig`）、
  `skillhub-auth`（令牌策略）、`skillhub-search`（分类过滤谓词）。
- 数据库：新增迁移 V124（分类与管理员）、V125（专题与 `TOPIC_ADMIN`）、
  V126（删 `skill.domain`）；V124 含历史数据回填，**一级分类映射有损**（见 design D1）。
- 前端：`/search`（全部技能左侧两级目录）、`/dashboard/publish`（分类选择）、
  新增 `/dashboard/category-admin`、`/admin/categories`、`/admin/topics`、
  `/topics`、`/topics/{slug}`、`/dashboard/topics/new`；4 个 locale 文案；
  `make generate-api` 重新生成类型。
- 文档：`docs/29-skill-domain-categories.md`（§1 闭集与"不建模二级"作废）、
  `docs/02-domain-model.md`（补 domain、组织/角色模型，修多角色陈述）、
  `docs/14-skill-lifecycle.md`（§4.1 补"版本审核携带目标可见性"路径）、
  `docs/skill-visibility.md`（审批人从平台角色扩展到分类管理员）。

## Open Decisions

已定版（2026-11-27）：一级分类 = 现有 6 枚举；两级分类；二级由一级管理员创建；
上传默认私有、公开走审批；超管全接口有权限；全部技能页严格按 UCD。

仍待确认 4 条（见 `design.md` §16.1）：
**D7** AI 扩写是否真接模型；**D8** 专题审批走独立页还是并入统一收件箱；
**`NAMESPACE_ONLY` 是否保留**；**`confirm-publish` 取消还是改义**。
