## ADDED Requirements

### Requirement: REQ-CADM-01 分类管理员 SHALL 是按一级分类隔离的可撤销授权

分类管理员 SHALL 存储在独立的作用域绑定表 `skill_category_admin`，绑定对象为
`(category_id, user_id)`，SHALL NOT 用平台角色表达。绑定 SHALL 有 `ACTIVE` / `REVOKED` 状态、
授予人与时间、撤销人与时间，撤销 SHALL NOT 物理删除历史。同一 `(category_id, user_id)`
SHALL 最多一条 `ACTIVE` 记录；同一用户 SHALL 可同时是多个一级分类的管理员。
绑定对象 SHALL 只能是一级分类，由数据库约束保证。

#### Scenario: 撤销后重授予
- **WHEN** 超管撤销某用户的分类管理员身份，之后又重新授予
- **THEN** 旧记录保持 `REVOKED` 且保留撤销人与时间；新授予产生新的 `ACTIVE` 记录；历史可查

#### Scenario: 试图把管理员挂到二级分类
- **WHEN** 调用方以 `level = 2` 的分类 id 授予管理员
- **THEN** 请求被拒绝，不产生绑定

### Requirement: REQ-CADM-02 分类管理员 SHALL NOT 通过平台角色获得权限

分类管理员身份 SHALL NOT 写入平台角色集合。所有依赖平台角色的门禁（`hasRole('SKILL_ADMIN')` 等）
SHALL NOT 因分类管理员身份而被满足，避免其在全部管理接口上获得平台级权限。
分类管理员权限 SHALL 只由显式的分类作用域判定提供。

#### Scenario: 分类管理员访问平台管理接口
- **WHEN** 仅持有某分类管理员身份的用户调用 `/api/v1/admin/**`
- **THEN** 返回 403，不因其分类管理员身份而被放行

### Requirement: REQ-CADM-03 分类管理员 SHALL 只能管理本类目的二级分类

分类管理员 SHALL 能在自己管理的一级分类下创建、重命名、排序、启停、删除二级分类；
SHALL NOT 能在他人的一级分类下做任何写操作；SHALL NOT 能创建、修改、删除一级分类。
一级分类的维护 SHALL 只有超级管理员可做。
删除仍被技能引用的二级分类 SHALL 被拒绝，并提示先解绑；SHALL NOT 级联清空技能分类。

#### Scenario: 在他类目下建二级
- **WHEN** 分类管理员对不属于自己的 `categoryId` 提交二级分类创建
- **THEN** 返回 403，不产生任何行

#### Scenario: 删除在用二级分类
- **WHEN** 分类管理员删除一个仍被公开技能引用的二级分类
- **THEN** 返回业务冲突并列出占用数量，分类与技能关系均不变

### Requirement: REQ-CADM-04 二级分类的挂载 SHALL 限于本类目的公开技能

分类管理员 SHALL 能为**自己一级分类下、且可见性为 PUBLIC 且未被隐藏/归档**的技能设置或取消二级分类。
对非 PUBLIC、已隐藏、已归档或不属于本类目的技能，SHALL 拒绝并说明原因。
技能所有者 SHALL NOT 自行设置二级分类（二级是运营归属，不是作者自填）。

#### Scenario: 给私有技能挂二级
- **WHEN** 分类管理员对其类目下一个 PRIVATE 技能提交二级分类
- **THEN** 请求被拒绝，技能二级分类不变

### Requirement: REQ-CADM-05 分类管理员 SHALL 能审批本类目的"转公开"申请

审批入口 SHALL 复用既有统一审核接口与权限判定点，SHALL NOT 新增平行审批队列。
审批判定 SHALL 同时满足：任务为可见性申请（`requestedVisibility = PUBLIC`）、
关联技能的一级分类属于审批人管理的分类、任务处于待审、且申请人与审批人不是同一人。
不满足时 SHALL 拒绝，且不得回退为"任意管理员可批"。

#### Scenario: 审批他类目的申请
- **WHEN** 分类管理员批准一条属于他人一级分类的转公开申请
- **THEN** 返回 403，任务仍为待审，技能可见性不变

#### Scenario: 审批自己提交的申请
- **WHEN** 分类管理员是自己提交的转公开申请的审批人
- **THEN** 仅超级管理员可批准该申请；分类管理员自身被拒绝

### Requirement: REQ-CADM-06 分类管理员的待审队列 SHALL 在查询层过滤

`GET /api/{v1,web}/reviews` 的 `scope=category` SHALL 在数据访问层按"我管理的分类"过滤，
SHALL NOT 采用"先分页再内存过滤"。队列总量与页内条数 SHALL 使用同一过滤条件。
所有读取同一队列的服务 SHALL 复用同一判定，SHALL NOT 各自内联重写角色判断。

#### Scenario: 队列分页一致性
- **WHEN** 分类管理员按 `scope=category` 翻到第 2 页
- **THEN** 每页条数与总数一致，不出现"少行但总数不变"的短页

### Requirement: REQ-CADM-07 超管 SHALL 能查看与操作全部分类的权限设置与审批

超级管理员 SHALL 能列出任意一级分类的管理员（含已撤销）、授予与撤销、
管理任意层级分类、审批任意类目的申请，并且在全部接口上 SHALL 不被权限判定拒绝。

#### Scenario: 超管操作他人的类目
- **WHEN** 超级管理员在任意一级分类下建二级、挂分类、批准申请
- **THEN** 全部成功；每步都留下审计，审计中含操作者与目标分类
