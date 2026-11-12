# GitCode 代码仓导入 · 设计契约

> 状态：已与需求方对齐（"设计遵守 UCD，支持 gitcode_source_binding"），待实现。
> 依据：UCD `5.发布Skill\发布-代码仓导入*.png`；GitCode 开放 API 实测（2026-11-12，本机代理直连）。

## 1. 范围

首期（本期）：
- 发布页新增「代码仓导入」入口，按 UCD 三步向导：① 填写地址 → ② 文件预览 → ③ 基础信息 → 发布。
- 新增 `gitcode_source_binding`：记录命名空间与 GitCode 仓库/目录/版本的关系（与 `github_source_binding` 并列）。
- 导入产物走**现有发布管道**（`SkillPublishAppService.publish(namespace, List<PackageEntry> …)`），不新增第二套校验/扫描/版本逻辑。

非本期（二期）：
- 绑定后的自动同步（webhook / 轮询 / sync worker）、私有仓库 OAuth/PAT、冲突人工绑定 UI。

## 2. UCD 交互规格（逐屏）

共用骨架：`← 返回`；标题「发布Skill」+ 副标题「上传 Skill 文件，审核通过后将同步展示到 SkillMall 插件广场」；
左栏步骤条 `① 填写地址 ② 文件预览 ③ 基础信息`（当前步实心圆点，已完成步对勾，竖线连接）；
顶部来源切换：`本地上传 | 代码仓导入`（分段控件，代码仓导入选中）；
底部固定操作条：`取消` + `上一步`(非首步) + `下一步`/`提交审核`（黑色胶囊，右对齐居中）。

- 第 1 步 填写地址：仅一个必填字段「* 代码仓地址」（输入框，占位示例 `https://gitcode.com/<owner>/<repo>`）。
  校验通过后进入第 2 步；解析出多个技能目录时在第 2 步顶部给出目录选择。
- 第 2 步 文件预览：左侧文件树（目录可折叠，默认展开 SKILL.md 所在层），右侧先「技能元数据」
  （名称 / 描述）再渲染 SKILL.md 正文；不可预览文件给原因与下载。
- 第 3 步 基础信息：沿用现有发布表单（命名空间、可见性、显示名称、变更说明、头像）与校验；
  提交即调用导入接口。

## 3. GitCode API 实测结论

| 能力 | 端点 | 免登录 | 备注 |
|---|---|---|---|
| 分支列表 | `GET https://api.gitcode.com/api/v5/repos/{owner}/{repo}/branches` | ✅ 200 | 含 commit sha/date，可用于校验仓库存在与默认分支 |
| 标签列表 | `GET …/tags` | ✅ 200 | 含 tag → commit sha |
| 目录列表 | `GET …/contents/?ref={ref}`（**必须尾斜杠**） | ✅ 200 | 返回 `[{type,name,path,sha,size}]` |
| 文件内容 | `GET …/contents/{path}?ref={ref}` | ✅ 200 | `encoding=base64`，可直接解码 |
| 原始文件 | `GET https://raw.gitcode.com/{owner}/{repo}/raw/{ref}/{path}` | ✅ 200 | 备用通道 |
| 仓库元信息 | `GET …/repos/{owner}/{repo}` | ❌ 401 | 不作为依赖 |
| 归档下载 | `…/archive/*`、`…/zipball` | ❌ 401/登录页 | **不依赖**：自己遍历 + 组包 |

结论：**不下载归档**。遍历 `contents` 取树与 base64 内容，直接构造 `List<PackageEntry>`，天然带上限控制。

## 4. 后端设计

### 4.1 新增类（`server/skillhub-app`）
- `com.iflytek.skillhub.gitcode.GitcodeUrlParser`：解析 `https://gitcode.com/{owner}/{repo}[.git][/tree/{ref}[/{path}]]`，非法即 `error.gitcode.url.invalid`。
- `com.iflytek.skillhub.gitcode.GitcodeClient`：java.net.http，域名白名单（`api.gitcode.com`、`gitcode.com`、`raw.gitcode.com`），
  连接 5s / 单请求 15s / 总预算 60s，拒绝解析到私网/环回地址；返回 branches/tags/tree/file。
- `com.iflytek.skillhub.gitcode.GitcodeSkillScanner`：递归遍历（深度 ≤ 6，文件 ≤ 300，单文件 ≤ 2 MiB，总计 ≤ 10 MiB），
  找出所有含 `SKILL.md` 的目录；解析 frontmatter 得 name/description/version。
- `com.iflytek.skillhub.gitcode.GitcodeImportAppService`：编排 预览 / 导入；导入时构造 entries 调 `SkillPublishAppService.publish(...)`，
  成功后写 `gitcode_source_binding` 与来源溯源；失败不落绑定。
- `com.iflytek.skillhub.controller.GitcodeImportController`：三个端点（见 4.3）。
- `GitcodeSourceBinding`（实体）+ `GitcodeSourceBindingRepository` + `GitcodeSourceBindingAppService`（列表查询）。

### 4.2 数据模型（`V{next}__gitcode_source_binding.sql`）

```sql
create table gitcode_source_binding (
  id              bigserial primary key,
  namespace_id    bigint       not null,
  owner_login     varchar(128) not null,
  repository_name varchar(256) not null,
  skill_path      varchar(512) not null default '',
  git_ref         varchar(256) not null,
  ref_type        varchar(20)  not null, -- BRANCH | TAG | COMMIT
  commit_sha      varchar(64)  not null,
  link_type       varchar(20)  not null, -- ONE_SHOT | BOUND
  visibility      varchar(20)  not null,
  created_by      varchar(128) not null,
  last_imported_at timestamptz not null,
  skill_id        bigint,
  skill_version_id bigint,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  constraint uq_gitcode_source unique (namespace_id, owner_login, repository_name, skill_path)
);
create index idx_gitcode_binding_namespace on gitcode_source_binding (namespace_id);
```

溯源：技能详情页「来自 GitCode」需要 `repository/path/ref/commit`，由 binding 反查（`skill_version_id` 关联）。

### 4.3 端点契约

```
POST /api/v1/skills/gitcode/inspect        // 预览（不落库）
  req  { repoUrl, ref?, path? }
  resp { owner, repository, defaultBranch, ref, refType, commitSha,
         skills: [{ path, name, version, description, fileCount, totalBytes }] }

POST /api/v1/skills/{namespace}/import/gitcode
  req  { repoUrl, ref?, path, visibility, confirmWarnings?, displayName?, changelog? }
  resp 同现有 PublishResponse

GET  /api/v1/namespaces/{namespaceSlug}/gitcode-sources?page&size
  resp 绑定列表（owner/repo/path/ref/commit/visibility/lastImportedAt/skillId）
```

错误码（新增 i18n，四语）：
`error.gitcode.url.invalid` / `error.gitcode.unreachable` / `error.gitcode.repo.notFound` /
`error.gitcode.skillMd.notFound` / `error.gitcode.skillAmbiguous` / `error.gitcode.tooLarge` / `error.gitcode.tooManyFiles`

### 4.4 规则

- **版本**：优先 TAG 名（ref 为 tag 时）；否则取 SKILL.md frontmatter `version`；两处都没有则拒绝导入
  （不编造版本号）。同 `repository+path+version` 已存在时，走现有发布冲突语义，不覆盖。
- **可见性/审核**：`link_type=ONE_SHOT` 与「本地上传」同口径（PUBLIC/NAMESPACE_ONLY 校验通过即发布；PRIVATE 进 UPLOADED）；
  二期 `BOUND` 才走 sync 审核队列。
- **幂等**：`namespace+owner+repo+path` 唯一；同 commit 重复导入返回同一版本（或明确提示“无变更”）。
- **审计**：记录 actor、repo、path、ref、commit sha；沿用现有审计设施。
- **安全**：域名白名单 + 私网拒绝 + 大小/数量/深度上限 + 总预算；导入内容仍走现有包校验与安全扫描。

## 5. 与 `github_source_binding` 的关系

并列的 provider 表，不做通用化抽象（首期）。二期若做同步，再抽 `source_provider` 枚举与公共 `sync_run/task`
（GitHub 侧已有 `github_sync_run/task/worker_presence` 可参照，不直接复用表名，避免语义混淆）。

## 6. 任务拆分

后端（Java）：
1. Flyway 迁移 + 实体/Repository
2. `GitcodeUrlParser` + `GitcodeClient`（含上限与私网防护）
3. `GitcodeSkillScanner`（找 SKILL.md、frontmatter、上限）
4. `GitcodeImportAppService`（preview/import + 绑定写入 + 复用 publish）
5. Controller + 单测（URL 解析、上限、多技能歧义、正常导入路径）

前端：
1. 发布页来源切换 + 三步向导骨架（步骤条、底部操作条）按 UCD
2. 第 1 步地址输入与校验、第 2 步文件树 + 元数据 + 渲染、第 3 步复用现有表单
3. 领域模型/查询封装（TanStack Query）+ 错误映射 + 埋点（`skill.import.*`）

文案（四语，由 Lead 统一落）：`publish.sourceRepo`、`publish.repoAddress`、`publish.repoAddressPlaceholder`、
`publish.step.address`、`publish.step.preview`、`publish.step.basic`、`publish.next`、`publish.prev`、
`publish.repo.inspect.failed`、`publish.repo.multipleSkills`、错误码文案。

## 7. 已确认决策（2026-11-12，需求方拍板）

1. **仅公开仓库**：本期只支持公开 GitCode 仓库；私有仓库（PAT / OAuth）列入二期。
2. **同一仓库多目录**：允许同一命名空间把同一仓库的不同子目录分别导入成多个技能，`skill_path` 区分（与业界 monorepo 处理一致）。
3. **来源展示**：详情页展示「来自 GitCode」徽标并可回跳 `https://gitcode.com/{owner}/{repo}/blob/{sha}/{path}`。

仍待二期确认（不阻塞本期）：
- `BOUND` 类型的自动同步（webhook / 轮询）与 sync 审核队列接线。
- 私有仓库的凭据模型（平台服务账号 vs 导入者 OAuth）。

## 8. 实现落点与偏差（2026-11-12 实施记录）

实现文件（`server/skillhub-app`）：`gitcode/{GitcodeUrlParser, GitcodeClient, GitcodeSkillScanner,
GitcodeImportAppService, GitcodeSourceBinding, GitcodeSourceBindingRepository, GitcodeSourceBindingAppService,
GitcodeRefType, GitcodeLinkType, GitcodeErrors}`、`controller/GitcodeImportController`、
`dto/{GitcodeInspectRequest, GitcodeInspectResponse, GitcodeImportRequest, GitcodeSourceBindingResponse}`、
`db/migration/V121__gitcode_source_binding.sql`；前端 `web/src/features/gitcode/**` + 发布页向导。

与本文档的偏差（均已确认合理）：
1. DDL 增加 `namespace_id REFERENCES namespace(id)`，对齐既有迁移风格。
2. 「缺版本」复用既有 `error.skill.metadata.requiredField.missing`（arg=version），未新增第 8 个 gitcode 码。
3. ref 无法解析时复用 `error.gitcode.url.invalid`；frontmatter 解析失败透传既有 `error.skill.metadata.*`。
4. TAG 优先：ref 为 tag 时把 SKILL.md 顶层 `version:` 改写为 tag 名（保留其余行），保证「发布版本 = tag」。
5. 幂等：同 commit + 同 visibility 重复导入直接返回既有版本并刷新 `lastImportedAt`，不重复下载。
6. 三个端点同时映射 `/api/v1` 与 `/api/web`，并加 `@RateLimit(category="gitcode")`。
7. inspect 在「path 已 pin」或「仓库仅一个技能」时额外返回 `files / skillMdPath / skillMd`，供第 2 屏渲染。
8. 目录列举统一以解析出的 commit sha 作为 ref，保证 inspect 与 import 看到同一份内容。

验证：后端 48 项 GitCode 测试通过（含真实 GitCode 联调 smoke，该测试手跑后已删除，不入库）；
V121 在真实 PostgreSQL 16 容器验证建表/FK/唯一约束；前端 typecheck、eslint 通过，analytics 清单 808→830 且测试通过。


