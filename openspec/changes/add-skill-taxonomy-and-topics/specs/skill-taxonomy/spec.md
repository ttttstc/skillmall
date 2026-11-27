## ADDED Requirements

### Requirement: REQ-TAX-01 分类 SHALL 为两级引用数据，且仅两级

一级分类 SHALL 是数据表 `skill_category` 中 `level = 1` 的行，二级分类 SHALL 是 `level = 2` 且必带
`parent_id` 的行。系统 SHALL NOT 支持第三级。一级分类初始集合 SHALL 是现有六个线值
`DESIGN / DEVELOPMENT / TESTING / ENGINEERING / PROJECT_MANAGEMENT / OTHER`，并 SHALL 与旧字段
`skill.domain` 一对一映射、不产生任何信息损失。

数据库 SHALL 用约束而不是注释保证形状：`level ∈ {1,2}`；`level=1 ⇒ parent_id IS NULL`；
`level=2 ⇒ parent_id IS NOT NULL`；父行 SHALL 为一级（由生成列 + 组合外键保证）。

#### Scenario: 试图创建第三级
- **WHEN** 调用方对一条 `level = 2` 的分类提交子分类
- **THEN** 请求被拒绝，不产生任何行，返回可本地化的业务错误，而不是 500

#### Scenario: 把二级分类当作父
- **WHEN** 写入一条 `parent_id` 指向 `level = 2` 的分类
- **THEN** 数据库约束拒绝写入；服务层在写库前给出同样的拒绝与错误码

### Requirement: REQ-TAX-02 技能的一级分类 SHALL 与二级分类保持父子一致

技能 SHALL 通过 `category_id` 关联一级分类、通过可空的 `sub_category_id` 关联二级分类。
存在 `sub_category_id` 时其父 SHALL 等于该技能的 `category_id`。系统 SHALL NOT 接受
"有二级但无一级"的状态。

#### Scenario: 二级不属于同一条一级
- **WHEN** 维护者给技能设置一条父分类与技能当前 `category_id` 不同的二级分类
- **THEN** 请求被拒绝，技能分类不变，返回明确的业务错误

#### Scenario: 清除二级但保留一级
- **WHEN** 分类管理员清除某公开技能的二级分类
- **THEN** `sub_category_id` 置空、`category_id` 保留，技能仍属于原一级分类

### Requirement: REQ-TAX-03 Web 上传 SHALL 强制选择一级分类，程序化入口 SHALL 安全降级

Web 交互上传与更新表单 SHALL 要求一级分类必填（界面显示必填标记）。
CLI 与 ClawHub 兼容发布载荷当前不含分类字段，因此 SHALL 缺省落到 `OTHER` 并在技能上标记
"未分类"，SHALL NOT 因此拒绝整个发布请求。`OTHER` SHALL 是"未分类/解绑"的合法落点：
解绑一级分类等价于改为 `OTHER`，`category_id` SHALL NOT 置 NULL。

#### Scenario: Web 上传未选分类
- **WHEN** 用户在发布表单未选择一级分类就提交
- **THEN** 表单校验阻止提交并提示必填，服务端同样拒绝（前端校验不作为唯一防线）

#### Scenario: CLI 发布未提供分类
- **WHEN** CLI 客户端发布一个不含分类的包
- **THEN** 技能创建成功并落在 `OTHER`，详情展示"未分类"，审计记录来源为 CLI

### Requirement: REQ-TAX-04 分类 SHALL 在技能信息上展示，且不产生逐条查询

技能列表卡片、列表行与详情 SHALL 展示其分类：卡片与列表行显示 `二级分类 ?? 一级分类`，
详情页 SHALL 同时显示一级与二级。名称 SHALL 按请求 locale 解析，回退顺序为
当前语言 → `zh` → `en` → `slug`。服务端 SHALL 按当前页涉及的分类 id 集合批量解析名称，
SHALL NOT 为每条技能单独查询分类。

#### Scenario: 技能只有一级分类
- **WHEN** 技能有 `category_id` 且无 `sub_category_id`
- **THEN** 卡片 chip 显示一级分类名，详情页只显示一级，不显示空白二级

#### Scenario: 分类缺当前语言翻译
- **WHEN** 请求 `ko` 且该分类没有 `ko` 翻译
- **THEN** 按回退顺序返回 `zh`/`en`/`slug`，不返回原始 i18n key，不返回空串

### Requirement: REQ-TAX-05 列表 SHALL 支持按分类维度与创建人筛选，且只能收窄公开分发结果

列表与搜索 SHALL 支持 `category`、`subCategory`（可多值）、`creator` 参数。
筛选条件 SHALL 拼进既有公开分发谓词内部，SHALL NOT 形成第二条可见性通道，
因此隐藏、归档、私有、未发布的技能 SHALL NOT 因筛选而出现。`null` SHALL 表示不过滤。
未知分类线值 SHALL 返回 400 而不是静默忽略；`subCategory` 多值内部为 OR、与 `category`/`creator`
之间为 AND；跨一级混合勾选 SHALL 返回 400。`creator` 传未知用户 SHALL 返回空结果而非 400。

#### Scenario: 分类筛选不放大可见范围
- **WHEN** 匿名为某分类请求列表，该分类下存在隐藏技能
- **THEN** 结果不包含隐藏技能；总数与页内行数使用同一谓词

#### Scenario: 多选二级分类
- **WHEN** 同一一级分类下勾选两个二级分类
- **THEN** 返回属于其中任一二级的技能（OR），总数与分页一致

### Requirement: REQ-TAX-06 分类目录 SHALL 可匿名读取并带可信计数

`GET /api/{v1,web}/categories` SHALL 返回启用中的两级树、稳定排序与每个节点的技能计数。
计数 SHALL 只统计可公开分发的技能（与列表谓词同谓词），SHALL NOT 把隐藏/私有/归档计入。
停用的分类 SHALL NOT 下发。

#### Scenario: 分类下无公开技能
- **WHEN** 某一级分类下所有技能都不是可公开分发状态
- **THEN** 该节点仍返回但计数为 0，不因计数为 0 而消失

### Requirement: REQ-TAX-07 旧 `domain` 契约 SHALL 兼容并可预期地下线

`domain` 查询参数与发布字段 SHALL 继续被接受并映射到对应一级分类；与 `category` 同时出现时
SHALL 以 `category` 为准并在响应中回显实际生效值。`skill.domain` 列 SHALL 在迁移后不再被业务代码读取，
其物理删除 SHALL 留到后续版本，且 SHALL 有一个受测试保护的检查防止新增读取。

#### Scenario: 旧客户端按 domain 筛选
- **WHEN** 客户端传 `domain=TESTING`
- **THEN** 结果与 `category=<测试对应 slug>` 完全一致，分页与计数一致
