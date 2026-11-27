## ADDED Requirements

### Requirement: REQ-TOP-01 专题 SHALL 是平台级、可编辑、非版本化的技能集合

专题 SHALL 是独立聚合 `skill_topic`，SHALL NOT 复用技能套件（`SkillSuite`）的版本台账。
专题 SHALL 包含：标题、封面副标题、说明、安装命令行、封面预设、三段使用指南、
有序的成员技能列表、状态、安装计数。专题 SHALL 归属平台，SHALL NOT 归属某个命名空间。
专题成员 SHALL 只能是有序引用，SHALL NOT 复制技能内容或版本。

#### Scenario: 编辑专题成员顺序
- **WHEN** 有权限的编辑者调整成员顺序
- **THEN** 顺序立即保存并对详情页生效，不产生新的版本记录

### Requirement: REQ-TOP-02 只有专题管理员与超管 SHALL 能创建与编辑

创建与编辑专题 SHALL 要求平台角色 `TOPIC_ADMIN` 或 `SUPER_ADMIN`。
专题管理员 SHALL 由超级管理员授予；授予 SHALL 是**增量**的，
SHALL NOT 覆盖该用户的其他平台角色。普通用户、分类管理员、`SKILL_ADMIN`
SHALL NOT 获得创建权。

#### Scenario: 授予专题管理员
- **WHEN** 超管给一个已是 `SUPER_ADMIN` 的用户授予 `TOPIC_ADMIN`
- **THEN** 该用户同时持有两个角色，不丢失任何原有角色

#### Scenario: 普通用户创建专题
- **WHEN** 无 `TOPIC_ADMIN`/`SUPER_ADMIN` 的用户请求创建专题
- **THEN** 返回 403，不产生草稿

### Requirement: REQ-TOP-03 专题 SHALL 经超管审批后才公开

专题状态 SHALL 为 `DRAFT → PENDING_REVIEW → PUBLISHED | REJECTED`，另有 `ARCHIVED`。
专题管理员提交后进入 `PENDING_REVIEW`；超管批准后方可 `PUBLISHED`；拒绝 SHALL 要求给出原因，
被拒后可修改并重新提交。超管创建的专题 SHALL 可直接发布（跳过审核）。
公开列表与详情 SHALL 只暴露 `PUBLISHED` 的专题。

#### Scenario: 拒绝专题
- **WHEN** 超管拒绝一个待审专题且填写原因
- **THEN** 状态变为 `REJECTED`，作者可见原因，公开列表不出现

#### Scenario: 未公开专题的公开访问
- **WHEN** 匿名访客请求一个 `PENDING_REVIEW` 专题的详情
- **THEN** 返回未找到，不泄露草稿内容

### Requirement: REQ-TOP-04 专题页面 SHALL 按 UCD 呈现

列表页 SHALL 为三列卡片（每页 9 条、数字分页），卡片含预设渐变封面（标题 + 副标题）、
重复标题、说明（最多 3 行截断）、安装数与技能数、`查看专区` 按钮，并在有权限时提供
`+ 创建专题`。详情页 SHALL 依次呈现：主题色 Hero（标题、说明、安装数、技能数、编辑入口）、
`安装` 区（复制命令行 + zip 下载）、`使用指南` 区（快速开始编号步骤、详细指南的概述与核心技能）、
`技能` 区（成员列表）。

#### Scenario: 命令行过长
- **WHEN** 安装命令行超过一行或含换行
- **THEN** 代码块软换行显示，不横向滚动、不截断；复制得到完整原文

### Requirement: REQ-TOP-05 创建向导 SHALL 为三步，指南为三段

向导 SHALL 为 `基础信息 → 选择技能 → 使用指南` 三步，底部操作条随步骤变化，
末步按钮为「提交审核」。基础信息 SHALL 含必填的专题名称、封面副标题、专题说明（≤1000）、
安装命令行（≤1000）、封面预设选择（10 套预设主题，每套含首页封面与详情背景两张预览；
SHALL NOT 允许自由上传）。选择技能 SHALL 为左右两栏：左侧可筛选可搜索的技能列表（添加）、
右侧已选列表（移除），并显示数量。使用指南 SHALL 为三个独立文本框：
`快速开始`（≤1000，格式 `###<步骤标题>\n<说明>`）、`指南说明`（≤1000）、`技能说明`（≤1000）。

#### Scenario: 快速开始被渲染为编号步骤
- **WHEN** 快速开始字段按 `###` 分段填写并提交
- **THEN** 详情页渲染为编号步骤列表，标题与说明分别对应

#### Scenario: AI 扩写
- **WHEN** 用户查看使用指南步骤
- **THEN** 系统 SHALL NOT 提供「AI扩写」页签或一键扩写入口（本期不做该能力）

### Requirement: REQ-TOP-06 专题成员 SHALL 只能是可公开分发的技能

选择技能时 SHALL 只允许选择可公开分发的技能。成员技能后续被下架或取消公开时，
专题 SHALL 保留该成员条目并标注为不可用，SHALL NOT 自动移除；
专题安装包的生成 SHALL 跳过不可用成员并在结果中说明。

#### Scenario: 成员被下架
- **WHEN** 专题中某成员技能被下架（`hidden=true`）
- **THEN** 专题详情该行显示为不可用；打包下载跳过它并返回说明；专题仍可访问

### Requirement: REQ-TOP-07 专题安装计数 SHALL 与下载行为对应

专题的 `安装数` SHALL 由安装/下载行为驱动并持久化；列表与详情 SHALL 使用同一口径。
成员技能数 SHALL 按当前可用成员计算，SHALL NOT 与历史计数混淆。

#### Scenario: 下载后计数变化
- **WHEN** 匿名访客下载专题 zip
- **THEN** 安装数增加 1，列表与详情读数一致
