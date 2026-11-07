# 首页推荐与三类榜单

Date: 2026-11-07
Status: contract reference（实现与口径说明）

本文件是首页“通用推荐 + 近期热门 + 累计热榜 + 最新上线”的口径与实现说明。生命周期语义以 [14-skill-lifecycle.md](./14-skill-lifecycle.md) 为准，本文件不另立发布或可见性规则。

## 1 四个区块回答不同问题

| 区块 | 问题 | 数据来源 | 排序 |
|---|---|---|---|
| 通用推荐 | 有哪些可以看的公开 Skill？ | 可公开分发集合随机取样 | `random()` |
| 近期热门 | 最近 7 天谁被下载得最多？ | `skill_download_daily` | 窗口求和降序 |
| 累计热榜 | 历史上谁被下载得最多？ | `skill.download_count` | 累计值降序 |
| 最新上线 | 谁最近第一次出现在市场？ | `skill.first_published_at` | 首次公开时间降序 |

推荐是随机取样，**不是个性化**，界面文案不得暗示“为你推荐”。

## 2 下载计数口径

- **累计**：`skill.download_count` 与 `skill_version_stats.download_count`，由已发布版本下载同步累加。
- **按日**：`skill_download_daily(skill_id, download_date, download_count)`，同一事件同步累加，`download_date` 取 UTC 自然日。
- **计的是什么**：授权通过并成功构建下载结果的次数。它不是安装成功人数，也不代表客户端完整接收；页面与接口文档不得把下载量表述为安装量。
- 审核绑定下载、授权的未发布版本下载不计入公开下载量，见[业务流 §3](./05-business-flows.md)。

## 3 7 天窗口口径

- 窗口 = 以 UTC 自然日计、含“今天”在内的 7 个自然日，即 `[today - 6, today]`。
- 实现上由应用层用注入的 `Clock` 计算 `windowStart` / `windowEndExclusive`，再作为参数传入查询，避免依赖数据库会话时区。
- 只对 `skill_download_daily` 求和。**不得**用累计值、平均值或按比例缩放来推算窗口数值。
- 计数桶只为实际发生下载的日期建行，所以“没有行”等价于“该窗口没有下载记录”。

## 4 空数据口径

| 情形 | 行为 |
|---|---|
| 窗口内无任何下载记录 | 近期热门返回空列表，界面显示真实空态，不回退为累计榜 |
| 可公开分发 Skill 少于推荐位上限 | 只返回真实存在的条目，不补造、不复用不存在的坐标 |
| 可公开分发 Skill 不够触发“换一换” | 允许用真实条目补齐一行，但不得返回池外条目 |
| 榜单整体无数据 | 返回空列表与真实空态，不生成示例数据 |

## 5 匿名可见性

匿名请求对四个区块都只返回**可公开分发**的 Skill，必须同时满足：

- `skill.status = 'ACTIVE'`（容器未归档）
- `skill.hidden = FALSE`
- 所属 `namespace.status <> 'ARCHIVED'`
- 最新版本指针指向的版本 `status = 'PUBLISHED'`
- 该版本 `download_ready = TRUE` 且 `yanked_at IS NULL`
- `visibility = 'PUBLIC'`；`NAMESPACE_ONLY` 仅对调用者所属 namespace 的成员可见

`PRIVATE` 永远不进公开区块，即使调用者是 ADMIN 也不进市场列表。下架或移除后，四个区块立即不再返回该 Skill，见[移除与下架](./05-business-flows.md)。

## 6 首次公开时间

- 字段：`skill.first_published_at`，写入一次后不再改变。
- 语义与写入点见[生命周期 §3.3](./14-skill-lifecycle.md#33-first-published-at)。
- 关键约束：**发布新版本不重置首次上线时间**。`skill_version.published_at` 属于版本，每个版本各自记录，不能用来推断 Skill 的首次上线时间。
- 因此“最新上线”排序在重复发布、重发、审核通过、yank 后回退指针、取消归档等场景下保持稳定。

## 7 接口与实现锚点

- 接口：`GET /api/web/home/recommendations`、`GET /api/web/home/rankings`，见[API 设计 §7.1](./06-api-design.md)。
- 应用服务：[HomeDiscoveryAppService](../server/skillhub-app/src/main/java/com/iflytek/skillhub/service/HomeDiscoveryAppService.java) —— 计算窗口、解析榜单类型、处理“换一换”的排除与补齐。
- 查询仓储：[JpaSkillRankingQueryRepository](../server/skillhub-app/src/main/java/com/iflytek/skillhub/repository/JpaSkillRankingQueryRepository.java) —— 原生 SQL，集中承载公开分发过滤。
- 数据迁移：`V120__skill_first_published_and_daily_downloads.sql`。
- 测试锚点：`HomeDiscoveryAppServiceTest`（窗口、空态、排除与补齐）、`HomeRankingQueryRepositoryTest`（真实 PostgreSQL 上的排序、过滤、首次公开稳定性）、`SkillPublishServiceTest`（新版本不重置首次公开时间）。

## 8 已知边界

- 搜索页 `sort=newest` 仍按 `updated_at`，与首页“最新上线”语义不同，差异与迁移条件见[搜索架构 §8](./04-search-architecture.md)。
- 历史数据回填取 `MIN(skill_version.published_at)`，只能得到“现存版本中最早的一次发布”；已物理删除历史版本的 Skill 无法重建绝对首次时间。
- 窗口按 UTC 自然日切分，不做本地时区换算；跨时区用户看到的“今天”边界是 UTC 日界。
