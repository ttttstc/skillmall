# Suite 发布任务设计验收

## 验收范围

- 参考方案：方案 3 的任务列表分层、方案 2 的独立详情页。
- 实现页面：`/dashboard/suites?tab=publishing`、`/dashboard/suites/publishing/:operationId`。
- 视口：桌面端 1487 × 1058；移动端 390 × 844。

## 对照结论

| 检查项 | 结果 | 证据 |
|---|---|---|
| 控制台任务分层 | 通过 | 保留“需要处理 / 进行中 / 最近完成”，终态记录位于“最近完成” |
| 独立任务详情 | 通过 | 查看任务进入独立路由，不再回到创建 Suite 页面 |
| 取消后的可追溯性 | 通过 | 任务记录保留，并明确说明已有 SkillVersion 与审核任务不会撤回 |
| 成员审核入口 | 通过 | 取消后仍显示“查看技能版本与审核状态” |
| 状态表现 | 通过 | 任务使用静态状态图标，没有持续旋转图标 |
| 响应式 | 通过 | 390px 视口没有横向溢出，操作与成员信息仍可访问 |
| 现有设计系统 | 通过 | 复用 DashboardPageHeader、Card、Tabs、Button、Pagination 与 Lucide 图标 |

参考图包含多条示例任务；真实预览库只有一条已停止记录，因此实际页面保留空分层并展示真实数据。参考详情图展示到“套件审核”，而当前 Bundle 工作流的职责止于“创建套件草稿”，实际实现按领域状态保留三阶段，避免暗示系统会自动提交 Suite 审核。

## 缺陷分级

- P0：无。
- P1：无。
- P2：无。

## 自动化结果

- 桌面端任务列表与详情页完成同视口截图对照。
- 真实普通用户会话完成任务列表、详情、返回、成员审核入口和移动端检查。
- 浏览器过程无 console error 或 pageerror。

final result: passed

## SkillMall UCD 对齐检查 — 2026-11-07

### 结论与阻碍

原图与实际页面已分别打开查看，但没有在同一比较输入中完成同视口对照。尝试构造同屏对照时，浏览器策略阻止了所需的页面地址；独立验收的截图接口也超时。不能声明像素级一致性或通过视觉验收。

### 仍需核对的差异

- 首页和搜索页的领域区仅有真实空态，未配置目录或推荐数据；原图展示的是完整分类与技能列表。这是当前范围与数据能力限制。
- 发布页已按原图改为三步向导（上传文件/文件预览/基本信息）；「代码仓导入」复用 GitCode 导入面板。
- 个人技能表格只展示接口实际提供的名称、版本、状态和更新时间；原图还有创建人、创建时间等列。当前测试库只有一条本人技能，多行密度未验证。
- 示例技能没有原图中的插画图标，当前使用名称首字母和彩色底色；真实头像优先显示。原图品牌、CANN 顶层导航及专题按既定范围未实现。
- 详情页原有举报入口仍存在，虽然一期需求延后举报；本次遵照视觉改造和既有功能保持约束没有改变业务行为，需后续处理该冲突。

### 必查视觉面

- 字体与排版：分别目视检查了页面层级；字体家族、字重和像素尺寸没有完成同屏测量。
- 间距与布局：首页、搜索、详情、发布、个人页已分别查看；同视口测量与多行数据密度仍未完成。
- 色彩与主题：浅色页面已查看；深色技能卡头像文字对比已修复并在浏览器检查。
- 图像与图标：真实头像优先，缺失头像使用首字母；与原图示例图形存在可见差异。
- 文案与内容：使用真实数据和四语言资源；未评测状态与尚未验证的安装命令有明确提示。

### 验证记录

Luna Max 独立只读检查了页面 DOM/AX 和部分计算样式，因截图超时未能进行独立视觉验收。浏览器已检查搜索卡片/列表、空结果、详情标签、发布样例包预览、个人页、登录注册；无法单独统计 pageerror。未提交新的技能。

后续需在允许的环境中将原图和同尺寸同状态的实际页面放在同一比较输入，逐页复核剩余视觉差异，特别是图标、字体、间距和多行表格。

final result: blocked（已被下方 2026-11-08 实测取代，本节保留为历史记录）

## SkillMall UCD 对齐实测 — 2026-11-08

### 方法与证据

- 后端：`skillhub-app-0.1.0.jar`，`--spring.profiles.active=local`，端口 8099，库 `skillmall_ucd`，Redis db 3，独立 `STORAGE_BASE_PATH`。
- 前端：`web/dist` 静态服务 + `/api` 反代，端口 3125（`D:\AI\workspace\1-cann\self\.verify-evidence\ucd\serve.mjs`）。
- 数据：账号 `ucd`（userId `usr_2be2a758-...`），命名空间 `personal-f09a3293`；发布 6 个中文技能覆盖 6 个领域；3 个技能加星、2 次下载；design-decision-matrix 有 1.3.0 第二版本。
- 视口：1920×1080、整页截图；UCD 原图同为 1920 宽，逐页并置比对。
- 截图：`.verify-evidence/ucd/shots/01-landing.png` … `07-my-stars.png`。
- 设计原图：`D:\AI\workspace\1-cann\设计\SkillMall（外发）1105\SkillMall（外发）1105\页面\`。

### 运行时健康

7 个路由（`/`、`/skills`、`/search`、技能详情、`/dashboard/publish`、`/dashboard/skills`、`/dashboard/stars`）逐页注入收集器后导航：

- `console.error` 调用：0
- `window.onerror` / `unhandledrejection`：0
- 非 2xx `fetch`：0

全部页面 H1 正确、无空白页。

### 逐页对照

| 页面 | 一致或更完备 | 偏差 | 判定 |
| --- | --- | --- | --- |
| `/` 落地页（承担 UCD 首页职责） | Hero 标题/副标题/搜索框/两个按钮（探索全部技能 描边 + 发布 Skill 实心）；推荐 Skills 四列卡片含版本、下载、星标；近期热门/累计热榜/最新上线 三榜单；领域 tab 全 7 项 + **该领域技能单行列表 + 查看更多** | 无「热门 Skills」四榜单 tab 形态；无「精选专题」四卡；缺右下角回顶浮钮 | 基本对齐（领域 Skills 区本次已补齐） |
| ~~`/skills`~~ | **本次已合并进 `/`**：该路由现在重定向到首页，不再提供第二套首页 | 原状：Hero 文案与按钮主次与 UCD 相反；无四榜单 tab；无领域列表；无精选专题；多出「快速开始」代码块区 | 已消除（两套首页并存） |
| `/search` 全部技能 | 搜索框、网格/列表切换、四列卡片、「加载更多」；**「← 返回」**、标题「全部技能」+「共 N Skill」、**四档排序 全部/热门/下载量/最近上榜**（本次补齐） | 左侧为「只看已收藏」+7 个领域胶囊，非 UCD 的多级筛选树；卡片缺分类 chip；缺多选下拉筛选；多 footer | 部分对齐（本次补头部与排序，筛选树/分类 chip 仍缺） |
| 技能详情 | ← 返回、图标+名称+作者+命名空间、收藏/分享、指标条、README/安装说明/文件/版本历史 四 tab、Markdown 渲染（标题/列表/表格）、右侧安装与下载；**「用户评价」区已按 UCD 删除** | 指标条只有 下载量 / 收藏 两格，UCD 第三格「安全（绿勾）查看风险报告」落在右栏「安全审核」卡；右栏信息密度远高于 UCD（新增 文件浏览/评分/命名空间/订阅/所属套件/标签管理/生命周期管理/提升到全局）；无「专题」标签与分类面包屑 | 偏差（P2） |
| 发布页 | 标题、副标题、步骤条（①上传文件②文件预览③基本信息）、分段控件「本地上传\|代码仓导入」、拖拽区、已选文件、底部「取消\|上一步\|下一步\|确认发布」 | 三步向导与分段控件已按 UCD 实现（`web/src/pages/dashboard/publish.tsx`，`?step=` 支持深链接）；拖拽区文案仍用「仅支持 zip 格式」而非「须含 SKILL.md / 最多 1 个 / 总大小不超过 10.00 MB」；未做「已选文件橙色条」与文件列表勾选；更新（编辑已有技能）路径仍为单页表单 | 基本对齐（向导本次已补齐，文案细节 P3） |
| 我的技能 | 标题、副标题、搜索、表格（名称/版本/状态/更新时间/操作）、分页（10 条/页，`web/src/pages/dashboard/my-skills.tsx:28` `PAGE_SIZE = 10`，仅在 `total > PAGE_SIZE` 时渲染） | 左侧缺 下载/关注 统计；缺「我的专题」入口；表格缺 序号/创建人/创建时间/更新人 列；操作是文字按钮（更新/可见范围/申请提升到全局）而非铅笔/垃圾桶图标；缺「共 N 条」文案 | 偏差（P2） |
| 我的收藏 | 标题、副标题、行内图标/名称/版本/星标/下载、分页（12 条/页，数据不足一页时不显示） | 无「← 返回」；无搜索框；无分类 chip；多 footer | 偏差（P2）；**行内「取消收藏」「下载」已补齐** |

### 全局差异（既定范围外，记录备查）

- 顶部导航：UCD 为 CANN AI 基础设施看板 + SkillMall 二级导航；实现为 SkillMall 自有导航（本期已确认不要 CANN 导航）。
- 技能图标：UCD 为彩色插画图标；实现为名称首字母 + 彩色底色，真实头像优先。
- 缺失页：UCD「4.专题」分组（专题列表/专题详情）未实现。

### 建议修复项（按优先级）

1. ~~P1｜`/skills` 与 `/` 两套首页并存~~ → **本次已合并**（见下节）。
2. ~~P1｜`/` 领域 Skills 区无可视内容~~ → **已修复**（见下节）。原状：`web/src/pages/landing.tsx` 该区只渲染 `DomainFilter`，且与上方推荐区是同一个 `domain` 状态——点选后变化发生在上方的推荐与榜单区块，本区看不到任何反馈。
2. ~~P1｜`/dashboard/stars` 缺「取消收藏」「下载」~~ → **本次已修复**（见下节）。
3. ~~P2｜`/search` 缺返回与「共 N Skill」式标题、排序缺 热门/最近上榜~~ → **本次已修复**（见下节）。
4. P2｜「我的技能」表格缺 创建人/创建时间/更新人 三列、缺「共 N 条」文案（分页本身已有：`my-skills.tsx:28` `PAGE_SIZE = 10`，`total > PAGE_SIZE` 才渲染，实测账号只有 6 个技能故未出现——早先记的「无分页」是误判，已更正）。
5. P3｜详情页右栏信息密度、用户评价区去留、指标条三格化需产品确认。

### 本次修复：我的收藏行内操作

- `web/src/features/skill/skill-list-row.tsx`：新增可选 `actions` 节点，渲染在指标区之后；外层 `stopPropagation` 保证点操作按钮不会同时触发整行跳转。
- `web/src/pages/dashboard/stars.tsx`：新增 `StarredSkillRow`，行内提供「取消收藏」（`useToggleStar` → DELETE，成功后按 query key 失效自动刷新）与「下载」（`/api/web/skills/{ns}/{slug}/versions/{version}/download`，版本取 `headlineVersion`，无版本时隐藏）。
- 四语新增 `stars.actions.unstar` / `stars.actions.download`。
- 测试：`stars.test.ts` 由 5 例增到 8 例，覆盖「渲染两个操作」「取消收藏原地生效且不跳转」「无版本时隐藏下载」。

验证：

- typecheck / eslint（全量）/ vite build 均通过。
- 真机（3125，真实登录 ucd）：收藏页 3 行各带「取消收藏」「下载」，下载 href 指向 `/api/web/skills/personal-f09a3293/{slug}/versions/{version}/download`；点击「取消收藏」后列表 3 → 2 行，未发生跳转，0 console error、0 失败请求。
- 下载端点直连：`200`、`application/x-zip-compressed`、1135 字节。

### 本次修复：领域 Skills 区单行列表

- 新增 `web/src/features/skill/domain-skill-list.tsx`：`DomainSkillList({ domain, onSelect })` 复用既有 `useSearchSkills`（`sort=downloads`、`size=6`）请求市场列表接口，带 `domain` 过滤；行内为 图标首字 + 名称 +（选中单一领域时）分类 chip + 描述 + 星标/下载数，「查看更多」指向 `/search?sort=downloads&domain=…`。
- 数据来源不新造：复用第 5 个 issue 已有的 `?domain=` 列表过滤，与搜索页同一接口、同一排序口径；未新增后端接口。
- 分类 chip 只在该区选中具体领域时显示——列表摘要载荷不含每条技能的分类，猜一个比不显示更糟。
- 四语新增 `home.domainViewAll`（查看更多 / View all / Смотреть все / 전체 보기），四语键数保持 3272 对齐。
- `docs/frontend-analytics-manifest.json` 重新生成：新增 `discovery.domain.open`、`discovery.domain.viewall`，uniqueIds 822 → 824。
- 测试：`landing.test.tsx` 增 2 例（渲染领域行与「查看更多」/ 该领域无技能时显示 domainEmpty），8 → 10 例。

验证（真机 3125，公开页无需登录）：

- 未选领域：该区 6 行（设计决策矩阵、容量评估模型、渐进式重构指南、随手记录整理器、路线图构建器、测试计划生成器），「查看更多」href 为 `/search?q=&sort=downloads&page=0&starredOnly=false`。
- 点「设计」：只剩 1 行「设计决策矩阵」并带「设计」chip，href 追加 `&domain=DESIGN`。
- 点「测试」：只剩 1 行「测试计划生成器」并带「测试」chip，href 追加 `&domain=TESTING`。
- 全程 `errs: []`（0 console error / 0 未捕获异常 / 0 失败请求）。

### 本次修复：首页合并（`/skills` → `/`）

- UCD 只有一个首页。实现里 `/`（landing）与 `/skills`（HomePage）是两套并存的首页，其中 `/skills` 没有任何导航入口、只能手输 URL 才能到达，内容与 UCD 也不一致（Hero 主次相反、无领域列表与榜单 tab）。
- 合并方式：`/skills` 路由改为在 `beforeLoad` 里 `throw redirect({ to: '/', replace: true })`，旧书签与外部链接仍能落到首页；删除 `web/src/pages/home.tsx` 与其测试（`home.test.tsx`），移除路由里的 `HomePage` 懒加载引用。
- 四语删除仅 `home.tsx` 使用的 7 个键：`home.subtitle`、`home.description`、`home.browseSkills`、`home.publishSkill`、`home.popularDescription`、`home.latestDescription`、`home.viewAll`；`home.domainEmpty`、`home.popularTitle`、`home.latestTitle`、`home.recommend.empty` 仍被首页/搜索页使用，保留。
- `docs/frontend-analytics-manifest.json` 重生成：routes 仍 54（路由条目保留为重定向），literalJsxSites 827 → 822、literalJsxValues 989 → 982。

验证：

- `tsc --noEmit` 0、全量 eslint 0、`vite build` 0、全量 vitest 279 文件 / 1494 例全过（原 280 / 1498，减少的正是被删的 `home.test.tsx`）。
- 真机（3125）：访问 `/skills` → 落到 `/`，H1 为「SkillMall」；访问 `/skills?tab=mine` 同样落到 `/`；访问 `/` 正常渲染首页。


### 本次修复：`/search` 头部与四档排序

- 头部：新增「← 返回」（`data-analytics-id="search.header.back"`），优先 `history.back()`，无历史时回首页；标题改为「全部技能」+「共 {{count}} Skill」（`search.resultTotal`），计数为 0 时回落到 `search.allSkillsHint`。
- 排序：按钮改为 UCD 的 全部 / 热门 / 下载量 / 最近上榜 四档，默认「全部」。其中「全部」对应既有的 `relevance` 排序（无关键词时等价于最新），「最近上榜」即既有 `newest`。
- 「热门」是新语义，后端新增：`PostgresFullTextQueryService` 增加 `RECENT_POPULAR_WINDOW_DAYS = 7` 与 `popular` 分支，按 `skill_download_daily` 近 7 天窗口求和倒序（`ORDER BY (SELECT COALESCE(SUM(...)) FROM skill_download_daily popular_window WHERE ... download_date >= :popularWindowStart AND < :popularWindowEndExclusive) DESC, s.updated_at DESC, d.skill_id DESC`）。窗口口径与 `HomeDiscoveryAppService.RECENT_WINDOW_DAYS = 7` 对齐，避免搜索页「热门」与首页「近期热门」两套定义。
- 参数只绑到 nativeQuery，不绑 countQuery：计数 SQL 在 `ORDER BY` 处被截断，绑定不存在的参数会在运行时报错。
- 四语重写 `search` 块：新增 `back`、`resultTotal`，`sort` 由「label/relevance/downloads/stars/newest」改为「all/popular/downloads/newest」，删除死键 `search.results`。
- `docs/frontend-analytics-manifest.json` 重新生成：新增 `search.header.back`、`search.sort.popular`，移除 `search.sort.relevance`，uniqueIds 811 → 813。

验证：

- 后端单测：`PostgresFullTextQueryServiceTest` 24 例通过，新增 `popularSortShouldRankByTheRecentDownloadWindow`（断言窗口子查询存在、计数 SQL 不含窗口、窗口差 7 天）。
- 前端门禁：`tsc --noEmit`、全量 eslint、`vite build`、全量 vitest（280 文件 / 1498 例）全部通过。
- 排序语义真机对照（8099，`/api/web/skills?size=6`）：给 `test-plan-generator` 造一条 2026-10-01 的 50 次下载记录（窗口外）后，`sort=popular` 把它排在最后、`sort=downloads` 把它排在第一 —— 证明「热门」用的是近 7 天窗口而非累计下载数。
- 页面真机（3125）：标题「全部技能 / 共 31 Skill」、按钮「返回」「全部 热门 下载量 最近上榜」；点「热门」URL 变 `sort=popular`、首屏为 设计决策矩阵 → 容量评估模型 → 渐进式重构指南 → 随手记录整理器 → 路线图构建器（与接口同序）；点「下载量」首屏变为 测试计划生成器（50 次）；点「返回」回到上一页；`errs: []`。

### 验证记录

- 逐页整页截图与 UCD 原图并置比对（7 个页面 / 6 张 UCD 图），非像素级测量：字号、行高、间距未逐项量取。
- 浏览器会话为真实登录用户 `ucd`，未使用 mock 鉴权。
- 未执行：详情页「安装说明」「文件」「版本历史」三个 tab 的逐屏截图；移动端布局；深色主题。

final result: passed（结构对照）；视觉像素级未测；首页「领域 Skills」区、我的收藏行内操作、`/search` 头部与四档排序三处 P1/P2 已修复，其余差异清单如上（`/search` 筛选树与卡片分类 chip、「我的技能」列与「共 N 条」、详情页右栏与指标条），需产品确认后再决定修复范围
