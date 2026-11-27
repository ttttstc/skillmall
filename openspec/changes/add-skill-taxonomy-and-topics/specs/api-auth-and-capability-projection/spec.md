## ADDED Requirements

### Requirement: REQ-AUTH-01 权限位 SHALL 由单一权威策略派生

`canManageLifecycle`、`canSubmitPromotion`、`ownerReadOnly` 等权限投影 SHALL 由
`SkillOwnerAccessPolicy` 的同一份判定派生，SHALL NOT 在各读模型里各自实现。
详情、摘要（市场/搜索）、我的技能、我的收藏、我的订阅 SHALL 对同一 `(技能, 查看者)`
给出**一致**的权限位。"移除成员后的只读降级" SHALL 在所有读路径生效，
SHALL NOT 因为缺少管理态上下文而被跳过。

#### Scenario: 同一技能在多处一致
- **WHEN** 一个被移出团队空间的所有者查看自己的公开技能
- **THEN** 详情、我的技能、我的收藏、我的订阅对该技能的管理能力判定一致（均为只读）

#### Scenario: 他人技能不得显示可提升
- **WHEN** 用户浏览"我的收藏"中属于他人的公开技能
- **THEN** 该条目 SHALL NOT 显示"可提交提升"

### Requirement: REQ-AUTH-02 超级管理员 SHALL 在全部接口有权限，且与界面一致

所有权限判定 SHALL 对 `SUPER_ADMIN` 短路放行；所有权限投影在超管视角下 SHALL 与该短路结论一致
（不得出现"界面显示无权限、接口却允许"）。系统 SHALL 有一条参数化测试，枚举写接口并断言
超管身份不返回 403。`/api/v1/admin/**` SHALL 继续只接受网页会话，
API 令牌 SHALL NOT 因超管身份而获得该入口（授权放行不等于开放入口）。

#### Scenario: 超管非所有者
- **WHEN** 超管既非技能所有者也不是空间管理员，查看该技能详情并执行维护操作
- **THEN** 详情权限位显示为可管理，且对应写操作成功

#### Scenario: 超管用令牌调管理接口
- **WHEN** 超管用 API 令牌调用 `/api/v1/admin/**`
- **THEN** 仍按既有会话限定拒绝；该拒绝不代表超管缺少权限，错误信息 SHALL 可区分

### Requirement: REQ-AUTH-03 维护者判定 SHALL NOT 在原生 SQL 中重复实现

数据访问层的"可管理/可见"判据 SHALL 使用由权威策略产出的集合（如可管理技能 id 集合），
或 SHALL 与该策略保持一致（含 `SUPER_ADMIN` 与只读降级）。SHALL NOT 存在遗漏超管、
遗漏只读降级的独立 SQL 判据。

#### Scenario: 套件选择器与详情页一致
- **WHEN** 同一技能同时出现在套件候选选择器与详情页
- **THEN** 两处的可管理结论一致

### Requirement: REQ-AUTH-04 OpenAPI SHALL 声明 Bearer 方案

生成的 OpenAPI 文档 SHALL 包含 `securitySchemes.bearerAuth`（HTTP bearer，
说明凭证为 SkillHub API 令牌而非 JWT）与全局 `security` 引用，
使 Swagger UI 渲染可用的 Authorize 入口并对请求附加 `Authorization` 头。
仅接受会话的接口（如 `/api/v1/admin/**`）SHALL 在文档中单独标注，
不得表现为可用令牌调用。

#### Scenario: Swagger 里带令牌调只读接口
- **WHEN** 开发者在 Swagger UI 填入有效 API 令牌并调用文档化的只读接口
- **THEN** 请求携带 `Authorization: Bearer …` 并被正常授权

### Requirement: REQ-AUTH-05 令牌可达范围 SHALL 与文档一致并可守护

每个在文档中暴露的 `/api/v1/**` 操作 SHALL 要么在令牌策略中登记所需 scope，
要么在"仅会话"清单中登记；SHALL NOT 出现"文档里有、令牌调用一律 403 且无登记"的接口。
系统 SHALL 有一条守护测试枚举 OpenAPI 操作并校验上述归属，缺失即失败。
未登记路径 SHALL 继续默认拒绝（不得反转为默认放行）。

#### Scenario: 新增接口忘记登记
- **WHEN** 新增一个 `/api/v1/**` 接口但未登记 scope 或"仅会话"
- **THEN** 守护测试失败，阻断合入

#### Scenario: 带令牌调写接口但浏览器有会话
- **WHEN** 同一浏览器已登录（存在会话 cookie）又在 Swagger 用令牌调写接口
- **THEN** 仍因缺少 CSRF 令牌被拒绝；文档 SHALL 说明可用无痕窗口或使用 CLI

### Requirement: REQ-AUTH-06 平台角色授予 SHALL 为增量

分配平台角色 SHALL 提供增量授予与撤销能力，SHALL NOT 清除用户已有角色。
既有覆盖式接口 SHALL 保留以兼容，但文档 SHALL 标注其覆盖语义。
撤销最后一个角色 SHALL 使该用户回落到默认 `USER` 语义。

#### Scenario: 增授角色不丢超管
- **WHEN** 超管给已持有 `SUPER_ADMIN` 的用户增授 `TOPIC_ADMIN`
- **THEN** 两个角色同时存在，后续请求同时具备两种能力

#### Scenario: 撤销全部角色
- **WHEN** 某用户的最后一个平台角色被撤销
- **THEN** 该用户按默认 `USER` 处理，不获得任何管理能力
