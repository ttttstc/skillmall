# GitCode 身份认证与登录

本文记录本部署的认证设计：平台只承认 GitCode 身份，同时保留三条进入平台的方式（浏览器授权码登录、可选的同域无感登录、CLI/Agent 的 PAT）。

文中 GitCode 接口事实只写已核对的结论；没有写到的 GitCode 行为不视为已知，也不得据此外推。实现状态与本文不一致时，以代码、测试与当前配置为准。

## 1. 背景与结论

平台不再自行维护账号与密码，也不再把"认证成功"和"有权使用平台"混为一谈：GitCode 负责证明"你是谁"，平台继续负责准入、角色与资源授权。

结论如下：

1. 唯一保证可用的登录路径是标准 OAuth2 授权码登录（通道 A）。原因是 GitCode 官方文档只记录 token 认证，没有文档化的 cookie 认证。
2. 同域无感登录（通道 B）是增强项，默认关闭。只有 Phase 0 探测（见第 9 节）在部署的实际网络路径上证明存在"仅凭允许转发的 cookie 就能换到用户"的端点后，才允许开启；探测不通过就永久保持关闭，不影响通道 A 上线。
3. CLI 与 Agent 用 GitCode PAT 作为 Bearer 凭据（通道 C）。本部署关闭平台自有的 API Token 与 CLI device flow，不再签发新的平台 Token。
4. 三条通道映射到同一个身份模型：`identity_binding(provider_code='gitcode', subject=<gitcode 用户 id>)`，因此同一个人从浏览器或从 CLI 进入，得到同一个平台用户。
5. 准入策略复用既有能力：`skillhub.access-policy.mode=PROVIDER_ALLOWLIST`，`allowed-providers` 只保留 `gitcode`。其他 provider 的登录一律在准入层被拒绝，不会建号、不会建立会话。

## 2. 三个通道

| 通道 | 入口 | 凭据 | 服务端动作 | 本部署默认 |
|---|---|---|---|---|
| A 授权码登录 | 浏览器访问 `/oauth2/authorization/gitcode`，回调 `{baseUrl}/login/oauth2/code/gitcode` | 一次性授权码 | 换 access_token、调用户接口、建立平台会话 | 启用 |
| B 无感登录 | 前端显式调用 `POST /api/v1/auth/session/bootstrap` | 浏览器携带的 gitcode cookie，固定 cookie 名 `GITCODE_ACCESS_TOKEN` | 以其值作 access token 调 `{api-base-url}/user` | 关闭 |
| C PAT | 任意受保护接口上的 `Authorization: Bearer <gitcode PAT>` | GitCode 个人访问令牌 | 调用户接口换用户 | 启用 |

### 2.1 通道 A：OAuth2 授权码登录

参考实现与 agent-hub 一致：

1. 浏览器跳到 `GET https://gitcode.com/oauth/authorize`，**不传 `scope` 参数**。
2. GitCode 回调 `{baseUrl}/login/oauth2/code/gitcode`。
3. 服务端 `POST https://gitcode.com/oauth/token`，`application/x-www-form-urlencoded` 表单：`grant_type=authorization_code`、`code`、`client_id`、`client_secret`、`redirect_uri`（即 `client_secret_post`）。
4. `GET https://api.gitcode.com/api/v5/user`，请求头 `Authorization: Bearer <access_token>`。
5. 映射用户（第 4 节），建立平台会话。会话仍由 Spring Session + Redis 承载，与既有各登录入口共用同一套建立逻辑。

通道 A 不依赖任何未文档化的 GitCode 行为，因此前端登录按钮、首次登录与管理员登录都走它。依赖未文档化行为的只有通道 B，所以 B 必须先探测再决定是否开启。

### 2.2 通道 B：可选的无感登录

前提成立时，浏览器已经登录 gitcode.com；若部署处于 gitcode 子域，gitcode 的 cookie 会随请求到达 SkillHub。通道 B 利用这一点，在**前端显式触发**时才尝试换取平台会话。

- 实现：provider code 为 `gitcode` 的 `PassiveSessionAuthenticator`（SPI 定义见[认证设计](./03-authentication-design.md) 第 3.3 节、[认证扩展与私有 SSO](./11-auth-extensibility-and-private-sso.md)）。
- 触发点只有一个：`POST /api/v1/auth/session/bootstrap`。
- 语义沿用既有契约：总开关关闭时返回 `403`；provider 不受支持返回 `400`；请求中没有有效外部会话返回 `401`；成功时建立标准 Spring Security Session。
- 只读取固定 cookie `GITCODE_ACCESS_TOKEN` 的值，按 access token 处理；`SESSION`、`XSRF-TOKEN`、`HWWAF*` 等其它 cookie 一律不读、不转发。
- 默认 `false`。开启条件是探测通过，而不是"理论上应该可行"。

通道 B 不通过时，用户仍可用通道 A 登录，功能完整，只是多一次跳转。

### 2.3 通道 C：CLI / Agent 的 PAT

- 任意受保护接口都接受 `Authorization: Bearer <gitcode PAT>`，服务端据此调用 `GET https://api.gitcode.com/api/v5/user`，得到与通道 A 相同的用户映射。
- 无效、过期或格式错误的 PAT 一律 `401`，**不回退**到会话身份或匿名身份；这与既有 Bearer 失败闭合规则一致。
- 本部署关闭平台自有 API Token 与 CLI device flow，CLI 与 Agent 直接用 GitCode PAT。
- 缓存：身份结果以 credential 的 SHA-256 为 key 存入 Redis，命中后不再请求 GitCode。正面结果 TTL 300 秒，负面结果 TTL 30 秒（见第 7、8 节）。

## 3. GitCode 接口事实

### 3.1 认证方式

GitCode 官方文档只记录 token 认证，共三种形式：

- 请求头 `Authorization: Bearer <token>`
- 请求头 `PRIVATE-TOKEN: <token>`
- 查询参数 `access_token=<token>`

平台只使用第一种（Bearer），另外两种不接入，避免 token 出现在 URL 与访问日志中。

### 3.2 `GET /api/v5/user`

文档标题为「获取授权用户的资料」。返回字段中与身份相关的部分：

| 字段 | 示例/形态 | 平台用途 |
|---|---|---|
| `id` | 24 位十六进制字符串，如 `64e5ed8f7e20aa73efcbc302` | 身份主体（`subject`） |
| `login` | 登录名 | 写入 `login_name`；展示名候选 |
| `name` | 昵称 | 展示名候选 |
| `avatar_url` | 头像地址 | 头像同步 |
| `email` | 可能为空 | 仅作资料字段；不证明邮箱所有权，不参与准入与账号合并 |
| `type` | 账户类型 | 记录，不参与授权判定 |
| `html_url`、`followers`、`following`、`top_languages` | — | 展示与扩展字段，不参与授权判定 |

`id` 在用户改名后保持不变，因此它是唯一可以安全用作 `subject` 的字段；`login`、`html_url` 会随改名变化，不能作为身份主键。

### 3.3 没有文档化的 cookie 认证

- 官方文档没有 cookie 登录方式。
- 匿名访问 gitcode.com 不会拿到任何登录 cookie。
- gitcode 网页 SPA 把 `access_token`、`refresh_token`、`xauth_token` 保存在浏览器 localStorage。
- 测试中由服务端直接请求 `https://gitcode.com/api/v1/user/oauth/userInfo`，返回 `403`（华为 WAF）。

因此"浏览器已登录 gitcode.com"不等于"服务端可以凭 cookie 识别用户"。这是通道 B 默认关闭、并且必须先探测的直接原因。

### 3.4 限流

GitCode 侧限制为 400 次/分钟、4000 次/小时。平台据此外置缓存（第 2.3 节）：同一凭据在 TTL 内只校验一次，避免自动化客户端把平台的校验请求放大成对 GitCode 的调用。

## 4. 身份映射

平台侧的身份记录仍使用既有表（见[领域模型](./02-domain-model.md) `identity_binding`）：

| 字段 | 取值 |
|---|---|
| `provider_code` | 固定 `gitcode` |
| `subject` | GitCode 用户 `id`（24 位十六进制字符串） |
| `login_name` | GitCode `login` |
| `extra_json` | 其余接口字段（`name`、`avatar_url`、`email`、`type` 等） |

约束：

- 唯一键 `(provider_code, subject)` 是身份相等的唯一判据。同一 `id` 通过通道 A、B、C 进入，解析为同一个 `user_account`。
- 不用 `login`、`email`、`html_url` 做主键或回退键：改名、改邮箱、改名后生成的地址都不能产生第二个平台账号。
- 头像与展示名按现有资料同步逻辑刷新；`email` 只作资料保存。接口不能证明用户控制该邮箱，因此不置 `emailVerified=true`，也不把它用于准入或账号合并（沿用[认证设计](./03-authentication-design.md) 第 3.6、4.1 节的约束）。
- 旧本地账号到 GitCode 身份的绑定迁移不在本次设计范围内，见第 10 节。

## 5. 权限矩阵

| 调用者 | 公开技能读取与下载 | `PRIVATE` / `NAMESPACE_ONLY` 下载 | 私有范围技能的版本/文件预览（`VisibilityChecker.canAccess`，未变） | `/api/v1/admin/**`、`/actuator/prometheus` |
|---|---|---|---|---|
| 匿名 | 允许（保留既有匿名下载限流） | `404` | 拒绝 | `401` |
| GitCode 身份（不在管理员白名单） | 允许 | 仅 owner；其他人 `404` | `PRIVATE`：owner 或空间 `ADMIN` / `OWNER`；`NAMESPACE_ONLY`：空间成员或 owner | `403` |
| GitCode 身份 + 管理员白名单 + 既有角色检查 | 允许 | 仅 owner；管理员不例外 | 同上 | 按角色判定通过 |

补充说明：

- 准入层先于建号执行。`PROVIDER_ALLOWLIST` 只留 `gitcode` 时，其他 provider 的登录在准入阶段失败，不产生用户记录，也不建立会话；失败处理沿用既有 `DENY` 语义。
- 管理员入口不是"登录即管理员"。每次请求都要同时满足：① 身份来自 GitCode；② `login`（或 `id`）命中 `skillhub.auth.gitcode.admin-allowlist`；③ 既有平台角色检查通过。
- 管理员白名单逐请求评估，不缓存在 Session 里，因此把某人移出白名单后，其下一个请求就失去管理员入口，不需要等会话过期。
- `/actuator/prometheus` 与管理员接口同级，不再匿名可抓取。采集端必须提供可验证的 GitCode 身份（例如通道 C 的 GitCode PAT）。

## 6. 下载策略与 404

| 范围 | 谁可以下载 | 其他人的结果 |
|---|---|---|
| `PUBLIC` | 任何人，含匿名 | — |
| `PRIVATE` | 仅当前 owner | `404` |
| `NAMESPACE_ONLY` | 仅当前 owner | `404` |

- 匿名下载公开技能的能力保持不变，既有的匿名下载限流也保持不变（见 [API 设计](./06-api-design.md)）。
- 非 owner 与匿名调用私有范围技能的下载，一律返回 `404`，由 `DomainNotFoundException` 表达，**不返回 `403`**。原因是 `403` 会泄漏"该坐标存在但你看不到"，使私有技能可被枚举；`404` 让"不存在"和"无权下载"对调用方不可区分。
- `VisibilityChecker.canAccess` **本轮有意不改**：详情、版本列表与审核预览仍按既有可见性规则放行，namespace `ADMIN` / `OWNER` 仍可预览待审版本，否则无法完成审核。也就是说，本轮的收紧是"可以看，不能下载"，不是"看不到"。
- `NAMESPACE_ONLY` 是过渡状态：枚举值、UI 与存量数据本轮都不变，后续迭代移除。移除之前，它与 `PRIVATE` 采用同一条下载规则。
- 收窄范围不撤销已经签发的对象存储预签名地址，其行为见[可见范围指南](./skill-visibility.md)。

## 7. 配置清单

### 7.1 平台配置项

| 配置键 | 本部署取值/默认 | 作用 |
|---|---|---|
| `skillhub.auth.legacy-login.enabled` | `false` | 关闭本地密码登录、第三方 OAuth Provider、平台 API Token 与 CLI device flow |
| `skillhub.auth.gitcode.api-base-url` | `https://api.gitcode.com/api/v5` | 身份校验接口基地址 |
| `skillhub.auth.gitcode.admin-allowlist` | 至少一名管理员 | 管理员入口白名单；条目写作 `login:ROLE`（登录名:平台角色），也接受裸 id |
| `skillhub.auth.gitcode.cache.positive-ttl` | `300s` | 校验成功的缓存 TTL |
| `skillhub.auth.gitcode.cache.negative-ttl` | `30s` | 校验失败（无此用户/凭据无效）的缓存 TTL |
| `skillhub.auth.gitcode.timeout` | `3s` | 调用 GitCode 的超时 |
| 无 cookie 配置项 | — | cookie 名固定为 `GITCODE_ACCESS_TOKEN`，其值按 access token 处理，与 Bearer 共用同一次 `/api/v5/user` 校验 |


| `skillhub.auth.session-bootstrap.enabled` | `false` | 控制 `POST /api/v1/auth/session/bootstrap` 是否可用 |
| `skillhub.access-policy.mode` | `PROVIDER_ALLOWLIST` | 准入策略 |
| `skillhub.access-policy.allowed-providers` | `[gitcode]` | 只允许 GitCode |

> `skillhub.access-policy.mode` 在代码里按字面量大写匹配，无法识别的取值会回退到 `OPEN`（`AccessPolicyFactory` 的 `default` 分支）。切换后要确认实际生效的是 `PROVIDER_ALLOWLIST`，不能只看配置文件写了什么。

### 7.2 OAuth 客户端环境变量

| 环境变量 | 取值 |
|---|---|
| `OAUTH2_GITCODE_CLIENT_ID` | GitCode 应用的 client id |
| `OAUTH2_GITCODE_CLIENT_SECRET` | GitCode 应用的 client secret |
| `OAUTH2_GITCODE_AUTHORIZE_URI` | `https://gitcode.com/oauth/authorize` |
| `OAUTH2_GITCODE_TOKEN_URI` | `https://gitcode.com/oauth/token` |
| `OAUTH2_GITCODE_USER_INFO_URI` | `https://api.gitcode.com/api/v5/user` |

client secret 只从部署的密钥注入，不写入仓库、Compose 文件或命令历史。

## 8. 威胁模型与凭据处理

把用户的 gitcode 会话 cookie 交给服务端，等价于让服务端临时持有该用户的 GitCode 凭据。这是通道 B 的固有代价，只能限制，不能消除。约束如下：

| 风险 | 处理 |
|---|---|
| 平台凭据被带给第三方 | 只读取固定 cookie `GITCODE_ACCESS_TOKEN` 的值并作为 Bearer 发送；平台自己的 `SESSION`、`XSRF-TOKEN` 不会流向 gitcode |
| 第三方请求失败被误判为已登录 | cookie 换用户失败即视为无身份，返回 `401`，不降级为匿名成功，也不假定角色 |
| 凭据泄漏到日志/审计/数据库 | cookie 与 PAT 都不落库、不写日志、不进审计字段；日志只记录请求 ID、provider 与结果 |
| 缓存键反推凭据 | 缓存 key 是 credential 的 SHA-256，不是明文凭据 |
| 同一请求出现两个身份 | 同时带 cookie 与 PAT 且解析出不同用户时，请求失败闭合，返回 `401`；不做"取其一"的猜测 |
| 第三方不可用拖垮平台 | 远程调用 `timeout=3s`，失败按无身份或校验失败处理，并受负面缓存保护 |
| 对 GitCode 的调用被放大 | 正面 TTL 300 秒、负面 TTL 30 秒；GitCode 限流为 400 次/分钟、4000 次/小时 |
| 管理员白名单撤销不及时 | 白名单逐请求评估，移除立即生效；不依赖会话过期或角色版本号 |
| 信任边界被无声扩大 | 通道 B 默认关闭；`cookie.enabled=true` 是一个需要探测证据的显式决定，且可随时关回 |

另外两条不属于本节的既有边界：GitHub 同步使用的是采集侧的系统凭据，与用户身份无关；公开技能的匿名下载不因本文收紧，仍受既有匿名限流保护。

## 9. Phase 0 探测清单

目的只有一个：判断通道 B 能否成立。四项都是候选探测，任一项没有结论都不能作为开启依据。

| 编号 | 探测内容 | 判读 |
|---|---|---|
| P1 | 未登录浏览器访问 `https://gitcode.com`，记录响应头里的 `Set-Cookie` 与浏览器实际保存的 cookie 名 | 已知结论：匿名访问不下发登录 cookie。用于确认"匿名即无身份"，并给出登录后 cookie 名的对照基线 |
| P2 | 只带白名单内 cookie（不带 `Authorization`、`PRIVATE-TOKEN`、`access_token`）请求 `GET https://api.gitcode.com/api/v5/user` | 预期 `401`。若 `401`，说明文档化接口不接受 cookie，通道 B 不能建立在 `api/v5` 上 |
| P3 | 只带白名单内 cookie 请求 `GET https://gitcode.com/api/v1/user/oauth/userInfo`（SPA 自身端点） | 已有测试结果为 `403`（华为 WAF）。需在部署的实际出口 IP、Host 与 Referer 下复测；`403` 说明被边缘设备拒绝，而不是缺少凭据 |
| P4 | 检查登录 cookie 的 `Domain`、`Path`、`Secure`、`SameSite`，确认子域部署的 bootstrap 请求是否真的会携带它们 | cookie 的域作用域覆盖部署子域且 `SameSite` 允许该 POST 时，通道 B 才具备触发条件；否则即使 P3 通过也无法使用 |

通过标准：

- 必须有一次探测在部署的真实网络路径上得到"仅凭允许转发的 cookie 即可稳定换到用户"的结论，才允许把 `cookie.enabled` 置为 `true`、填写 `cookie.names` 与 `cookie.user-endpoint`。
- 探测同时要记录失败形态（`401`、`403`、重定向、空用户）。`403` 与 `401` 的含义不同，不能合并判断。
- 探测过程中不打印、不落盘、不粘贴 cookie 值；只记录 cookie 名、状态码与响应字段名。
- 全部探测都不通过时，保持关闭，按通道 A + 通道 C 上线；这是预期结局之一，不是阻塞项。

### 9.1 已执行的探测结果（2026-11-25，本机出口）

用一次真实登录后的浏览器 cookie 串（其中含 `GITCODE_ACCESS_TOKEN`、`GITCODE_REFRESH_TOKEN` 两个 JWT，`sub=ttttstc`、`objectId=6afffb36ebcfc5505fd571f0`）只读探测，结果如下。cookie 值与令牌都没有落盘或写进仓库。

| 编号 | 请求 | 结果 | 判读 |
|---|---|---|---|
| P2a | `GET api.gitcode.com/api/v5/user` + `Cookie` | `401` | v5 不接受 cookie 身份 |
| P2b | `GET gitcode.com/api/v5/user` + `Cookie` | `403` | 该 Host 的 v5 路径被边缘设备拦截 |
| P3 | `GET gitcode.com/api/v1/user/oauth/userInfo` + `Cookie`(+`Referer`/`Origin`/`X-Requested-With`) | `403` | 华为 WAF 拒绝非浏览器请求，不是缺凭据 |
| P5 | `GET api.gitcode.com/api/v5/user` + `Authorization: Bearer <cookie 里的 GITCODE_ACCESS_TOKEN>` | `401` | **Web 会话 JWT 不是 API 凭据** |
| P5b | 同上，但改用 `PRIVATE-TOKEN` 头或 `?access_token=` | `401` | 三种文档化传法都不认这个 JWT |
| P5c | `GET gitcode.com/api/v1/user/oauth/userInfo` + `Authorization: Bearer <同 JWT>` | `403` | 即便换传递方式，Web 端点仍被 WAF 拦截 |
| P5d | `GET api.gitcode.com/api/v1/user/oauth/userInfo` | `404` | `api.` 主机的 v1 路径不存在 |

**结论：通道 B 在本部署不可成立。** 原因有三层，任一层都足以否决：Web 端点对服务端请求一律 `403`（WAF）；`api/v5` 只认 API 令牌；cookie 里的 `GITCODE_ACCESS_TOKEN` 是 Web 会话 JWT，不是 API 凭据。因此 `skillhub.auth.gitcode.cookie.enabled` 保持 `false`，`GitCodePassiveSessionAuthenticator` 只作为保留实现存在。上线走通道 A（浏览器）+ 通道 C（CLI/Agent 的 PAT）。

另有一条被这次探测证实的可用信息：cookie 里的 `objectId`（如 `6afffb36ebcfc5505fd571f0`）是 24 位十六进制，与 `/api/v5/user` 返回的 `id` 同构，可作为 `identity_binding.subject` 的形态参考；但身份仍只能由 OAuth2 或 PAT 取得。

## 10. 切换与回滚

### 10.1 切换

1. 注册 GitCode 应用，回调地址填 `{baseUrl}/login/oauth2/code/gitcode`，配置 7.2 的环境变量。
2. 设置 `skillhub.auth.legacy-login.enabled=false`：本地密码登录、第三方 OAuth Provider、平台 API Token 与 CLI device flow 同时关闭。
3. 设置 `skillhub.access-policy.mode=PROVIDER_ALLOWLIST`，`allowed-providers` 只留 `gitcode`。
4. 先填好 `skillhub.auth.gitcode.admin-allowlist`，确认至少一名管理员命中。切换后再补会导致管理入口无人可用。
5. 通道 B 保持 `false`，等待 Phase 0 结论。

切换带来的、必须先公告的影响：

- **本地账号会话到期后无法重建。** 切换不主动清除现有会话，已建立的会话在其 TTL（现有默认 8 小时）内仍然有效；到期后本地密码登录已关闭，无法再建立同类会话。用户需要改用 GitCode 登录。
- **私有范围下载收紧。** 此前可由 namespace `ADMIN` / `OWNER` 下载的 `PRIVATE` / `NAMESPACE_ONLY` 技能，现在只有 owner 可下载，其他人拿到 `404`。这会影响审核、交接和运维流程，需要提前通知。
- **Prometheus 抓取需要身份。** `/actuator/prometheus` 不再匿名可访问。

### 10.2 待确认项

本次设计没有定义旧本地账号到 GitCode 身份的绑定迁移。若切换前存在"资源属主是本地账号"的技能、namespace 或其他记录，按本文的设计这些记录不会自动跟随，需要在切换前确认处理方式（显式绑定、所有权转移或接受重新建号）。这一点不能靠"应该会合并"来假设。

### 10.3 回滚

回滚方式是把 `skillhub.auth.legacy-login.enabled` 设回 `true`，恢复本地密码登录、第三方 OAuth、平台 API Token 与 CLI device flow。

需要注意两个边界：

- 该开关只控制登录入口，不撤销第 6 节的下载收紧；下载策略要回滚需要回滚对应的代码变更。
- 回滚后 GitCode 通道仍然可用（配置仍在），不是"二选一"的互斥切换；是否需要同时移除 GitCode 的 registration 由部署方决定。

## 11. 测试点

以下为需要覆盖的行为。本节不声明已经通过的用例；实际结果以测试记录为准。

认证与映射：

- 通道 A 完整走通：授权、回调、token 交换（表单 + `client_secret_post`）、用户接口、建立 Spring Session。
- 用户改名后再次登录，`subject` 不变，仍是同一个平台用户（用 `id` 而不是 `login` 做键）。
- 同一用户先用通道 A 登录、再用通道 C 的 PAT 调用，解析为同一个 `user_account`。
- 通道 C：无效/过期 PAT 返回 `401`，且即使同时存在有效会话也不回退。
- 缓存：同一凭据在正面 TTL 内不重复请求 GitCode；失败结果在负面 TTL 内同样命中；缓存 key 为 SHA-256，Redis 中不出现明文凭据。
- 准入：`allowed-providers` 只有 `gitcode` 时，其他 provider 的登录被拒绝且不建号。

管理员入口：

- 匿名访问 `/api/v1/admin/**` 返回 `401`。
- 已认证但不在 `admin-allowlist` 的调用者返回 `403`。
- 在白名单但缺少对应平台角色时，仍按角色判定拒绝。
- 在线移除白名单成员后，其下一个请求即失去管理员入口。
- `/actuator/prometheus` 的匿名访问被拒绝。

下载：

- `PUBLIC` 技能匿名下载成功，匿名限流仍然生效。
- `PRIVATE` 与 `NAMESPACE_ONLY`：owner 可下载；匿名、普通成员、namespace `ADMIN` / `OWNER` 均返回 `404`（不是 `403`）。
- namespace `ADMIN` / `OWNER` 仍可预览待审版本（`VisibilityChecker.canAccess` 未变），但对同一版本发起下载得到 `404`。
- 例外状态不被放开：`SCANNING`、`SCAN_FAILED`、`YANKED` 与未就绪版本仍不放行。

通道 B：

- 关闭时 `POST /api/v1/auth/session/bootstrap` 返回 `403`。
- 开启但 provider 不受支持返回 `400`；开启但没有有效外部会话返回 `401`。
- 只转发白名单内的 cookie 名，平台 `SESSION`、`XSRF-TOKEN` 不出现在发往 gitcode 的请求里。
- 同一请求同时带 cookie 与 PAT 且解析出不同用户时返回 `401`。

凭据处理：

- 日志与审计中不出现 cookie 值、PAT 明文与 access_token。
- GitCode 超时（`3s`）时请求按失败处理，不长时间挂起，不把超时当成功。

## 相关文档

- [认证与授权设计](./03-authentication-design.md)：准入策略、统一会话、Provider 扩展约束
- [认证扩展与私有 SSO](./11-auth-extensibility-and-private-sso.md) 与 [私有 SSO 集成手册](./12-private-sso-integration-playbook.md)：`PassiveSessionAuthenticator` 的 SPI 与实现步骤
- [领域模型](./02-domain-model.md)：`identity_binding` 表结构
- [API 设计](./06-api-design.md)：下载、限流与统一响应结构
- [技能生命周期](./14-skill-lifecycle.md)：版本状态与下载授权的关系
- [可见范围指南](./skill-visibility.md)：范围调整与预签名地址
- [GitCode 导入](./30-gitcode-import.md)：与账号登录无关的仓库导入路径
