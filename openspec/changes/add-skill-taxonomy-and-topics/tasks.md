# Tasks — add-skill-taxonomy-and-topics

图例：`[ ]` 未开始 / `[x]` 完成。每个里程碑结束都要跑该里程碑的"验证"项，
验证失败不得进入下一里程碑。构建通过、单元测试通过都**不等于**验收。

---

## M1 分类基础 + 可见性收敛 + 缺陷修复

### M1.1 迁移 V124 ✅ 已完成（2026-11-27，在真实库副本上验证）

- [x] `V124__skill_taxonomy_and_category_admin.sql`
  - [x] `skill_category`：`parent_id`、`level`、`slug`、`legacy_domain`、`sort_order`、`enabled`、审计列
  - [x] 形状约束：`ck_category_level`、`ck_category_shape`
  - [x] `parent_level` 生成列 + `uk_category_id_level` + `fk_category_parent_is_root`
  - [x] `UNIQUE NULLS NOT DISTINCT (id, parent_id)`（PG 16，供 `skill.sub_category_id` 组合外键）
  - [x] `skill_category_translation(category_id, locale, display_name)`，`UNIQUE(category_id, locale)`
  - [x] `skill_category_admin`：`status`、`granted_by/at`、`revoked_by/at`、`version`、
        `root_level` 生成列 + `fk_category_admin_is_root`、`uk_category_admin_active` 部分唯一索引
  - [x] `skill.category_id` / `skill.sub_category_id` + `ck_skill_sub_requires_category`
        + `fk_skill_sub_under_category`
  - [x] `skill.created_by` 回填：`UPDATE skill SET created_by = owner_id WHERE created_by IS NULL`
  - [x] 一级种子 6 条（`legacy_domain` 1:1）+ 24 条翻译（zh/en/ko/ru），**二级不预置**
  - [x] 回填 `skill.category_id` from `legacy_domain`；`DO` 块断言无 NULL
  - [x] 索引：`idx_skill_category_public`、`idx_skill_sub_category_public`、`idx_skill_created_by_public`
  - [x] **不出现**任何 `UPDATE skill SET visibility=...`
- [x] 迁移断言测试（手工在真实副本上验证，后续补自动化用例）

**已验证的证据**（`CREATE DATABASE taxonomy_check TEMPLATE skillhub`，源库 Flyway=V123，25 个技能全部 PUBLIC）：

| 检查 | 结果 |
|---|---|
| V124 在 V123 库副本上执行 | exit 0，仅幂等 `DROP ... IF EXISTS` 的 NOTICE |
| 存量公开技能是否被改写 | 25 → 25，**未被改写** |
| `category_id IS NULL` | 0 |
| `created_by IS NULL` | 0 |
| 一级/二级/翻译 | 6 / 0 / 24（二级按设计不预置） |
| 插 `level=3` | 被 `ck_category_level` 拒绝 |
| 插一级但带 `parent_id` | 被 `ck_category_shape` 拒绝 |
| 二级与一级不匹配 | 被 `fk_skill_sub_under_category` 拒绝 |
| 有二级、`category_id=NULL` | 被 `ck_skill_sub_requires_category` 拒绝 |
| 把分类管理员挂到二级 | 被 `fk_category_admin_is_root` 拒绝 |
| 合法 `design→system-design` 挂载 | 通过 |
| 挂载后改父为 `development` | 被组合外键拒绝 |

> 附注：验证用的临时库 `taxonomy_check` 保留在 `skillmall-verify-pg` 容器内，后续 V125 可直接复用。
> 另注：存量 25 个技能全部落在「其他」（V121 的诚实回填所致），印证了 §15「其他会泛滥」的风险。

### M1.2 领域层

- [ ] `Category` / `CategoryTranslation` / `CategoryAdmin` 实体 + 仓储接口（domain）+ JPA adapter（infra）
- [ ] `CategoryShapePolicy`：仅两级、父必为一、slug 唯一域、删前占用检查
- [ ] `Skill`：`categoryId`/`subCategoryId`/`createdBy` getter/setter + 父子一致性校验
- [ ] `CategoryPermissionChecker` + `CategoryAuthorizationService`（含禁止自审）
- [ ] `SkillCapabilityService` → `SkillCapabilities`（唯一权限投影）
- [ ] `SkillOwnerAccessPolicy` 增加 `SUPER_ADMIN` 短路；删除 `SkillPublishService` 内联副本改为调用
- [ ] `SkillVisibilityService`：只接受 `PRIVATE → PUBLIC`；拒 `NAMESPACE_ONLY`；补写 `SKILL_VISIBILITY_CHANGED`
- [ ] `SkillPublishService.interactiveUpload`：固定 `PRIVATE` + 可信发布者分支 + `SKILL_PUBLISH_DIRECT` 审计
- [ ] `ReviewPermissionChecker.canApprovePublicVisibility`（键 = `requestedVisibility==PUBLIC`，非 subjectType）
- [ ] `ReviewTaskRepository.findByStatusAndSkillCategoryIn(...)`（仓库级谓词）
- [ ] 双向互斥：`assertNoPendingVisibility` 扩展为内容版本 ↔ 可见性申请双向检查

### M1.3 应用层

- [ ] `AuthContextFilter`：注入 `adminCategoryIds`（永远非 null）
- [ ] 发布接口：忽略 `visibility` 并记审计；`confirm-publish` 改为创建公开申请
- [ ] CLI：`CliSkillController` 默认 `PRIVATE`；可信令牌走直发
- [ ] ClawHub compat：`publishSkill`/`publish` 按 B 方案分流（缺分类 → `OTHER`）
- [ ] `CategoryDisplayResolver`：按 id 集合批量取名称（locale 回退 当前→zh→en→slug）
- [ ] DTO：`SkillSummaryResponse`/`SkillDetailResponse` 增加 `category`、`subCategory`
- [ ] `SkillCapabilityService` 接入三处读路径，删除 `withManagementState` 的 `state == null` 短路

### M1.4 缺陷修复（可与 M1.1–M1.3 并行）

- [ ] `OpenApiConfig`：`bearerAuth` + 全局 `SecurityRequirement`
- [ ] `RouteSecurityPolicyRegistry.API_TOKEN_POLICIES`：补齐文档化只读接口 scope
- [ ] 守护测试：枚举 OpenAPI operation × 策略/仅会话归属
- [ ] 角色增量授予：`POST/DELETE /api/v1/admin/users/{userId}/roles[/{code}]`，保留旧覆盖式接口

### M1 验证

- [ ] 约束测试：第三级 / 错父 / 有二级无一级 全被拒（PG 集成）
- [ ] 权限测试：超管全写接口不 403（参数化）
- [ ] 一致性测试：detail / summary / my-skills / stars / subscriptions 权限位全等
- [ ] 策略测试：Web/CLI/compat 上传 → 私有；可信令牌 → 公开 + `SKILL_PUBLISH_DIRECT`
- [ ] 迁移断言测试通过
- [ ] `make test-backend-app`（必须带 `-am`）

---

## M2 分类管理员 + 分类级审批 + 管理台

- [ ] 超管 API：`/api/v1/admin/categories`（一级 CRUD、排序、启停）
- [ ] 超管 API：`/api/v1/admin/categories/{id}/admins`（列表含已撤销 / 授予 / 撤销）
- [ ] 分类管理员 API：`/my/managed-categories`、二级 CRUD、`/skills/{ns}/{slug}/sub-category`
- [ ] `GET /reviews?scope=category`：仓库级过滤 + 总量一致
- [ ] `GovernanceWorkbenchAppService.visiblePendingReviews` 改为委托 checker（消除第二份语义）
- [ ] 前端 `/admin/categories`：一级表格 + 多管理员指派弹窗 + 二级总览 + 删除保护
- [ ] 前端 `/dashboard/category-admin`：二级 CRUD + 本类目公开技能挂二级 + 「待我审批」tab
- [ ] 导航三处注册（`app/layout.tsx`、`pages/dashboard.tsx`、`shared/components/user-menu.tsx`）
- [ ] 审计：`CATEGORY_*`、`CATEGORY_ADMIN_GRANT/REVOKE`、`SKILL_SUB_CATEGORY_CHANGED`

### M2 验证

- [ ] 分类管理员矩阵逐格（超管/本类目/他类目/owner/空间管理员/普通用户）
- [ ] 跨类目写操作 403；自审 403（仅超管可批自己）
- [ ] 队列 `scope=category` 分页一致性（不出现短页）
- [ ] 删除在用二级分类被拒绝并返回占用数

---

## M3 全部技能页 + 发布页

- [ ] `category-tree.tsx`：一级文件夹图标 + chevron、二级缩进无 chevron、展开/选中态、键盘可达、aria
- [ ] 工具栏：排序胶囊、多选二级 chip + 复选框面板、**创建人选择器**
- [ ] `GET /api/web/skill-authors?q=&limit=20`（只统计有公开技能的作者）
- [ ] URL：`category`/`sub`（多值）/`creator`；`router.tsx:318` 白名单改为一级用常量、二级服务端 400
- [ ] 卡片/列表行 chip = `二级 ?? 一级`；副标题按 §16 结论
- [ ] 发布页：删除可见性下拉；一级分类 `✳` 必填；底部「提交审核」；完成页引导"如需公开请提交审核"
- [ ] 埋点：稳定 id + `data-category-slug`
- [ ] i18n 4 locale + 键对齐测试

### M3 验证

- [ ] 分类/创建人筛选进同一谓词；count 与 rows 一致；未知 slug 400；跨一级混合 400
- [ ] 树键盘操作与 `aria-expanded`/`aria-current`
- [ ] 发布页无可见性字段、必填校验前后端一致
- [ ] 4 locale 键对齐测试通过

---

## M4 专题

- [ ] `V125__skill_topic.sql`：`skill_topic`（含 `slogan`、`cover_preset`、三段指南、状态、计数）、
      `skill_topic_member`（有序、唯一）、`TOPIC_ADMIN` 角色种子、`topic:*` 权限
- [ ] 领域：`Topic` 聚合 + 状态机 + `TopicAuthorizationPolicy` + 成员可用性
- [ ] API：公开读（列表/详情/下载）、`/my/topics`、CRUD、`submit`、`/api/v1/admin/topics/*` 审批
- [ ] 打包：复用套件 zip 能力，跳过不可用成员并在结果中说明；安装计数
- [ ] 前端：`/topics`（3 列 9 条 + 数字分页）、`/topics/{slug}`（Hero/安装/指南/技能）、
      `/dashboard/topics/new`（三步 + 三分栏指南 + 10 套预设封面）、`/admin/topics`（含待审）
- [ ] 封面用 CSS 渐变 + SVG 徽标（10 套，含封面与背景两种预览）
- [ ] **不实现** AI 扩写：无页签、无 `guide_ai_draft` 字段

### M4 验证

- [ ] 状态机全路径：DRAFT→PENDING→PUBLISHED/REJECTED→（改）→重提；ARCHIVED
- [ ] 拒绝必须带原因；未公开专题匿名 404
- [ ] 成员下架后置灰 + 打包跳过 + 专题仍可访问
- [ ] 命令行多行软换行 + 复制完整原文
- [ ] 授予 `TOPIC_ADMIN` 不丢 `SUPER_ADMIN`

---

## M5 文档同步 + 全量回归

- [ ] `docs/29-skill-domain-categories.md`：§1 闭集/"不建模二级"作废；补分类管理员与审批
- [ ] `docs/14-skill-lifecycle.md` §4.1：**整表重写**（去直发、补"提交审核=申请公开"、补 Path B 关闭）
- [ ] `docs/skill-visibility.md`：重写为"申请-审批"单通道
- [ ] `docs/02-domain-model.md`：补分类模型、`created_by` 语义、修多角色陈述、补 `USER` 角色合成
- [ ] `docs/06-api-design.md`：新增/变更接口
- [ ] `openspec/changes/change-published-skill-visibility/specs/skill-visibility/spec.md:25`
      REQ-VIS-04 与代码不一致（扫描非门禁）→ 同步修正
- [ ] 全量回归：`make test-backend-app`、`make generate-api`、前端 `pnpm test`

---

## 交付前检查清单

- [ ] 迁移在**含存量公开技能**的库上执行过，且公开计数未变
- [ ] 三个发布入口（Web/CLI/compat）行为一致且均有测试
- [ ] 所有新写操作都有对应审计动作，且审计可在审计页检索到
- [ ] 文档与代码无冲突（尤其 §4.1 权威表、可见性说明）
- [ ] 未提交、未推送（等授权）
