# 领域分类（一级分类）

Date: 2026-11-07
Status: contract reference（实现与口径说明）

本文件说明 Skill 的一级领域分类：有哪些分类、默认值从哪来、谁能改、筛选如何与可见性共存。
生命周期与发布路径以 [14-skill-lifecycle.md](./14-skill-lifecycle.md) 为唯一权威，首页区块口径见
[28-home-rankings-and-download-metrics.md](./28-home-rankings-and-download-metrics.md)，检索实现见
[04-search-architecture.md](./04-search-architecture.md)。本文件不另立发布、可见性或状态机规则。

## 1 六个一级分类，且只有一级

| 线值 | 中文 | 含义 |
|---|---|---|
| `DESIGN` | 设计 | 视觉、交互、原型、设计系统类能力 |
| `DEVELOPMENT` | 开发 | 编码、调试、重构、代码生成类能力 |
| `TESTING` | 测试 | 用例、自动化测试、质量校验类能力 |
| `ENGINEERING` | 工程 | 构建、发布、运维、平台工程类能力 |
| `PROJECT_MANAGEMENT` | 项目管理 | 计划、协作、需求与进度管理类能力 |
| `OTHER` | 其他 | 不属于以上任一类的，以及尚未选择分类的 |

分类是**闭集枚举**，不是可运行时编辑的分类法：产品只发布这六个一级分类，**不建模二级、三级目录**。
新增一个分类意味着同时改代码枚举、数据库迁移、四语翻译和界面，属于发版动作，
不能由任何后台录入或用户输入产生。这样每个分类都必然有翻译、有索引、有筛选入口。

实现：后端 `SkillDomain`，前端 `web/src/shared/lib/skill-domains.ts`（同一份线值的镜像）。

## 2 默认值是 OTHER

- `Skill.domain` 可空，实体 getter/setter 把 `null` 归一为 `OTHER`；新建 Skill 不选分类时落在 `OTHER`。
- 本字段引入之前创建的 Skill 由迁移 `V121__skill_domain_category.sql` 回填为 `OTHER`。
- 回填选 `OTHER` 而不是猜测：仓库里没有可信信号推断历史 Skill 的分类，**猜一个错的比诚实的"其他"更糟**，
  且发布者可以随时在编辑页改正。

因此"某分类为空"和"某 Skill 没有分类"是两件事：没有分类的 Skill 属于 `OTHER`，不会被任何筛选漏掉。

## 3 谁能改，怎么改

分类属于 **Skill 容器**，不属于版本：重新上传安装包不会顺带改分类，改分类也不需要发布新版本。

| 路径 | 端点 | 说明 |
|---|---|---|
| 发布（新建或更新包）时选择 | `POST /api/{v1,web}/skills` 的 `domain` 表单字段 | 与版本在同一事务写入，不会出现"版本已发、分类没落"的中间态 |
| 仅改分类 | `POST /api/{v1,web}/skills/{namespace}/{slug}/domain` | 不重新上传安装包 |
| 读取当前值 | `GET /api/{v1,web}/skills/{namespace}/{slug}/domain` | 供编辑表单回填 |

权限与其它维护写入完全一致：Skill 当前所有者、所属 namespace 的 `ADMIN`/`OWNER`、或平台 `SUPER_ADMIN`。
先过"只读降级"检查（TEAM namespace 里已退出成员的旧所有者不得再写），再过身份/角色检查——
两条规则互不覆盖，顺序与生命周期写入相同。越权写入由**服务端**拒绝，不依赖前端隐藏按钮。
分类变更写审计 `SKILL_DOMAIN_CHANGED`，detail 同时记录 `previousDomain` 与 `targetDomain`。

## 4 筛选与可见性

筛选是**公开分发谓词之上的一个额外条件**，不是第二条可见性通道：

- 匿名可读。`domain` 只是首页推荐、三类榜单、搜索列表上的可选查询参数，不引入新的鉴权要求。
- 筛选**只能收窄**结果。实现上它被拼进既有的可公开分发谓词内部，因此隐藏、归档、`PRIVATE`、
  未发布或已下架的 Skill 不会因为"按分类筛选"而出现。
- 空分类返回空列表，不是全量。未知分类线值返回 400 而不是静默忽略——
  忽略会让"筛了却没生效"看起来像"这个分类里什么都有"。
- 榜单的计数（累计、窗口、首次公开时间）与是否筛选无关，见[首页口径 §2–§3](./28-home-rankings-and-download-metrics.md#2-下载计数口径)。

可见性判定本身见[生命周期文档](./14-skill-lifecycle.md)与[可见性说明](./skill-visibility.md)，
不在此重复。

## 5 索引

筛选永远与公开分发谓词一起使用，所以数据库索引是**部分索引**
（`idx_skill_domain_public` on `skill(domain) WHERE status = 'ACTIVE' AND hidden = FALSE`），
而不是给全表建索引。分类筛选与匿名可见性同谓词，才有这个形状。
