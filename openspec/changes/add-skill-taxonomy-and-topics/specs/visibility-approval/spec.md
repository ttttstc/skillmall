## ADDED Requirements

### Requirement: REQ-VAPR-01 交互式上传 SHALL 一律私有，公开 SHALL 只能经审批获得

上传、重新上传、更新包 SHALL NOT 改变技能的可见性；新建技能 SHALL 一律为 `PRIVATE`。
`PUBLIC` SHALL 只能通过"提交公开申请 → 审批通过"获得。请求体中的可见性字段 SHALL 被忽略
（并记录审计），SHALL NOT 因被忽略而返回 400，以兼容既有 CLI 与草稿。
系统 SHALL 不再存在"上传即公开"的路径。

#### Scenario: 上传时声明公开
- **WHEN** 普通维护者上传时在请求中传入 `visibility=PUBLIC`
- **THEN** 技能创建为 `PRIVATE`；响应明确当前不可见；审计记录该字段被忽略

#### Scenario: 通过重新上传提权
- **WHEN** 某 `PRIVATE` 技能的所有者重新上传一个包
- **THEN** 技能可见性仍为 `PRIVATE`，不进入市场与搜索

### Requirement: REQ-VAPR-02 可见性 SHALL 收敛为二元，`NAMESPACE_ONLY` SHALL 下线

系统 SHALL 只对外提供 `PRIVATE` 与 `PUBLIC` 两种可见性。`NAMESPACE_ONLY` SHALL 从发布、
申请、筛选与界面中移除；作为目标值提交时 SHALL 被拒绝。存量 SHALL 按"无该状态数据"处理，
迁移 SHALL NOT 改写任何技能的可见性。

#### Scenario: 申请空间成员可见
- **WHEN** 维护者提交目标为 `NAMESPACE_ONLY` 的可见性申请
- **THEN** 请求被拒绝并说明该范围已下线；技能可见性不变

### Requirement: REQ-VAPR-03 「提交审核」SHALL 是唯一的公开入口，且表现为待评审

`confirm-publish`（界面「提交审核」）SHALL NOT 将版本或技能置为已发布/公开；
它 SHALL 创建一条可见性申请（目标 `PRIVATE → PUBLIC`），技能仍为 `PRIVATE`，
对外状态表现为"待评审"。批准前 SHALL NOT 出现在市场、搜索或匿名下载结果中。
用户 SHALL 无需在另一个入口重复发起申请。

#### Scenario: 上传后提交审核
- **WHEN** 维护者上传后点击「提交审核」
- **THEN** 生成一条待审的可见性申请；市场与搜索仍不可见；「我的技能」显示"申请公开 · 审核中"

#### Scenario: 批准前尝试匿名访问
- **WHEN** 匿名访客在批准前请求该技能详情或下载
- **THEN** 返回未授权/未找到，不泄露内容

### Requirement: REQ-VAPR-04 审批通过 SHALL 生效公开，拒绝 SHALL 保持私有

批准 SHALL 将技能置为 `PUBLIC` 并使其进入公开分发；拒绝或撤回 SHALL 保持 `PRIVATE`。
审批 SHALL 复用既有可见性申请的校验（技能与空间处于可用状态、版本未漂移、绑定的扫描结果可用时须安全）；
过期申请 SHALL 被拒绝而不是覆盖更新的状态。批准 SHALL 写入可见性变更审计，
记录审批人、审批人作用域与审批任务 id。

#### Scenario: 审批期间技能状态漂移
- **WHEN** 申请提交后技能的可见性或发布版本已变化
- **THEN** 审批以过期拒绝，技能保持当前状态，申请结束为拒绝/失效

#### Scenario: 批准后可见
- **WHEN** 分类管理员或平台管理员批准申请
- **THEN** 技能出现在市场与搜索；审计含 `approvedBy` 与 `reviewTaskId`

### Requirement: REQ-VAPR-05 可信发布者 SHALL 可直发，但必须留痕

持有 `SKILL_ADMIN` 或 `SUPER_ADMIN` 的调用方 SHALL 能直接以 `PUBLIC` 发布（三个入口一致）。
该直发 SHALL 写审计 `SKILL_PUBLISH_DIRECT`，detail SHALL 至少含
`bypass=TRUSTED_PUBLISHER`、入口（WEB/CLI/COMPAT）与目标可见性。
非可信发布者 SHALL NOT 命中该分支。

#### Scenario: 管理员令牌通过 CLI 直发
- **WHEN** 持 `SKILL_ADMIN` 令牌的 CLI 以 `PUBLIC` 发布
- **THEN** 技能立即公开，且审计中出现 `SKILL_PUBLISH_DIRECT` 与入口 `CLI`

#### Scenario: 普通用户令牌通过兼容层发布
- **WHEN** 普通用户令牌走 ClawHub 兼容发布
- **THEN** 技能为 `PRIVATE` 并需走申请；不产生 `SKILL_PUBLISH_DIRECT` 审计

### Requirement: REQ-VAPR-06 申请与内容审核 SHALL 双向互斥

同一技能 SHALL NOT 同时存在待审的内容版本与待审的可见性申请。
两种申请任一方存在时，提交另一方 SHALL 被拒绝，并提示先完成或撤回；
双向检查 SHALL 在同一技能上并发安全。已公开技能 SHALL 不再提供"申请公开"入口
（不存在"再公开"）；缩小范围 SHALL 立即生效、不产生申请。

#### Scenario: 已有待审内容版本时申请公开
- **WHEN** 技能存在待审内容版本，维护者提交公开申请
- **THEN** 请求被拒绝，两条记录均不变

#### Scenario: 已公开技能再次申请
- **WHEN** 对已 `PUBLIC` 的技能提交公开申请
- **THEN** 返回业务冲突并说明已是公开状态

### Requirement: REQ-VAPR-07 存量 SHALL 不被回溯改写

迁移 SHALL NOT 包含任何修改 `visibility` 的语句；已公开技能 SHALL 保持公开，
已私有技能 SHALL 保持私有。系统 SHALL 有一条受测试保护的断言：
迁移前后 `visibility='PUBLIC'` 的技能数量一致。

#### Scenario: 迁移后抽查存量公开技能
- **WHEN** 迁移在含公开技能的库上执行
- **THEN** 这些技能仍为公开且仍可被匿名搜索到；计数与迁移前一致
