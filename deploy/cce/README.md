# SkillHub on 华为云 CCE 部署指南

本目录是 SkillHub 部署到**华为云 CCE（Cloud Container Engine）**的完整配置与脚本：镜像用
**SWR**，技能包等对象存 **OBS**（原生 OBS 适配器，`provider=obs`），数据库与缓存用托管
**RDS for PostgreSQL 16 / DCS for Redis**，入口用 **CCE NGINX Ingress 插件**。

> 本目录是**示例与操作脚本**：所有 `<...>` / `REPLACE-ME` 都是占位值，部署前必须替换；
> 真实凭证只放 Kubernetes Secret，禁止提交到 Git。

## 与仓库内其它部署路径的关系

| 路径 | 适用 | 与 CCE 的关系 |
|---|---|---|
| **本目录 + Helm**（`charts/skillhub`） | CCE + SWR + OBS + 托管库 | **推荐主路径** |
| `deploy/k8s/`（Kustomize） | 通用 K8s | 未透传 S3/OBS 环境变量与 Secret 键，S3/OBS 需自行补 patch |
| `compose.release.yml` + `deploy/storage/*`（单机 Compose） | 单机/自建对象存储 | 本地对象存储（Silo/RustFS）不用于 CCE |

同一实例只选一条路径，不要叠加。本目录的 values **只用于 Helm 路径**。

## 目标架构

```
                       ┌──────────────────────────── CCE 集群 ────────────────────────────┐
  用户/CLI ──HTTPS──►  │  ELB ──► ingress-nginx（NGINX Ingress 插件, ingressClassName=nginx）│
                       │                 │ /api /oauth2 /login/oauth2 /.well-known         │
                       │                 ▼                                                 │
                       │        ┌─────────────────┐   ┌──────────────┐   ┌──────────────┐    │
                       │        │ skillmall-server │   │ skillhub-web │   │skillhub-scanner│ │
                       │        │  :8080  (Java)  │   │   :80 Nginx  │   │   :8000        │ │
                       │        └────┬────┬───┬───┘   └──────────────┘   └──────────────┘    │
                       │             │    │   │                                            │
                       └─────────────┼────┼───┼────────────────────────────────────────────┘
                                     │    │   └──► OBS（技能包/头像等：native obs provider）
                                     │    └──────► DCS for Redis（会话、队列、缓存）
                                     └───────────► RDS for PostgreSQL 16（业务库，Flyway 迁移）

  制品：SWR（skillmall-server / skillmall-web / skillmall-scanner / busybox）
  凭证：CCE 命名空间自动创建的 default-secret（拉镜像）+ skillhub-app-secrets（应用密钥）
```

OBS 模式（`obs.enabled=true`）下：**不创建 PVC**、Server 用 `RollingUpdate`、
ConfigMap 置 `skillhub-storage-provider=obs`。

## 外部依赖清单

### 必须（缺一不可）

| 依赖 | 形态/版本 | 用途 | 说明与验证方式 |
|---|---|---|---|
| CCE 集群 | Kubernetes ≥ 1.24，节点可调度 2×2Gi 以上 | 运行时 | `deploy/cce/preflight.sh` 检查连通性与命名空间 |
| CCE NGINX Ingress 插件 | ingress-nginx（`ingressClassName: nginx`） | 对外入口 | **不要用 ELB Ingress**：`kubernetes.io/elb.member_timeout` 上限 300s，扫描最长约 900s 会 504；且 CCE 不写 `ingressClassName` 时默认落到 `cce`(ELB) |
| ELB | 由 NGINX Ingress 插件的 LoadBalancer Service 自动/手动创建 | 承接公网流量 | 与插件一起配置，注意带宽/规格与原地址 |
| SWR 组织 | 与 CCE 同区域，如 `swr.cn-north-4.myhuaweicloud.com/<org>` | 存镜像 | 登录用**派生登录密钥**（`HMAC-SHA256(key=SK, msg=AK)` 的 hex），不是 SK 本身；脚本已处理 |
| OBS 桶 | 与 CCE 同区域，**预创建**（应用不建桶） | 技能包、头像等对象 | 桶名全局唯一且创建后不可改；`deploy/cce/obs/setup-bucket.sh` + `obs/verify-obs.sh` |
| 华为云凭证 | ① IAM 委托 + 元数据服务临时凭证（推荐）或 ② 静态 AK/SK 放 Secret | 访问 OBS | Pod 内需可达 `169.254.169.254`；不可达则用 `obs.credentialMode: static` |
| RDS for PostgreSQL | 16（内网地址，端口 5432） | 业务库 | 启动即执行 Flyway 迁移；账号需建表权限；`max_connections` ≥ 连接池 × 最大副本数 |
| DCS for Redis | 5.0/6.0/7.0，**Proxy 集群/主备**优先 | 会话、队列、缓存 | Proxy 模式客户端无需特殊配置；Cluster 模式必须改用 `externalRedis.cluster.nodes`，且仅 db 0 |
| TLS 证书 | 自有证书导入 CCE 或前置 ELB 证书 | HTTPS | `ingress.tls[].secretName` 需预先存在；TLS 存在时 chart 自动把 Session Cookie 置 Secure |
| 域名 + DNS | 指向 ELB 地址 | 访问入口 | `publicBaseUrl` 与 `ingress.hosts[].host` 必须一致 |
| 时间同步 | 节点 NTP 正常 | 签名/证书 | OBS 签名与 TLS 都对时间偏移敏感 |

### 可选

| 依赖 | 触发条件 | 说明 |
|---|---|---|
| metrics-server / CCE 云原生监控 | 启用 HPA（本 values 默认开启） | 缺失时 HPA 拿不到指标；可把 `autoscaling.enabled` 置 false |
| CCE 容器存储插件（Everest） | 使用内置 Bitnami PG/Redis（`values-cce-incluster-db.yaml`） | 需要 `csi-disk`（EVS，仅 RWO）；OBS 主路径不需要任何 StorageClass |
| GitHub OAuth / 公开仓库采集（github-sync） | 需要 GitHub 登录或采集 | **本路径默认关闭 github-sync**：其暂存上传只实现了 local/s3，`provider=obs` 时开票据会抛 `Unsupported GitHub sync staging storage provider` |
| 飞书 / 钉钉 OAuth | 企业单点登录 | 需在对应平台登记回调 `https://<域名>/login/oauth2/code/<provider>` |
| LLM 网关 | 扫描器的 LLM 辅助审核 | 通过 `secrets.scannerLlmApiKey/-BaseUrl/-Model` 注入；不配则跳过 LLM 环节 |
| AOM / LTS | 集中日志与监控 | 应用输出 stdout，可用 CCE 日志采集插件接入 |
| ACR/镜像加速 | 构建机拉基础镜像 | 基础镜像来自 docker.io（temurin/node/nginx），国内建议镜像加速或预拉取 |

> 出网提示：运行期只需要访问 OBS/RDS/DCS（建议同 VPC 内网）。**无需**访问 docker.io——
> 镜像从 SWR 拉取；`default-secret` 由 CCE 自动创建并轮换，不要自建或复制。

## 目录内容

```
deploy/cce/
├── README.md                          # 本文件
├── preflight.sh                       # 前置检查（工具/集群/IngressClass/Secret/连通性）
├── values-cce.yaml                    # 主路径 values（SWR + OBS + RDS/DCS + ingress）
├── values-cce-incluster-db.yaml       # 可选：内置 Bitnami PG/Redis 覆盖片段
├── secrets-cce.example.yaml           # 预创建 Secret 的键清单与占位值
├── build-images.sh                    # 构建并推送 3 个应用镜像到 SWR
├── mirror-images.sh                   # 第三方镜像镜像化到 SWR（busybox 等）
├── runtime-mirror-images.cce.txt      # 镜像映射清单
├── package-chart.sh                   # 打包自包含 chart（vendor 子 chart）
└── obs/
    ├── setup-bucket.sh                # 创建/核对 OBS 桶、生命周期、可选 CORS
    ├── verify-obs.sh                  # 小对象往返 + 私有性自检
    ├── iam-policy-skillhub-app.json   # 应用 AK 的最小权限 IAM 策略
    ├── obsutil.conf.example           # obsutil 配置模板
    └── README.md                      # OBS 侧说明
```

## 部署步骤

以下命令在仓库根目录执行。所有 `SWR_REGION/SWR_ORGANIZATION` 按实际替换。

### 0. 前置检查

```bash
deploy/cce/preflight.sh --namespace skillhub --values deploy/cce/values-cce.yaml
# 需要验证 Pod 到 OBS/RDS/DCS/元数据服务的可达性时（会在集群起一次性 Pod）：
deploy/cce/preflight.sh --namespace skillhub --values deploy/cce/values-cce.yaml --net-check
```

`--net-check` 默认用 `busybox:1.37`；CCE 节点通常拉不到 docker.io，请先镜像化并指定：

```bash
export PREFLIGHT_IMAGE=swr.cn-north-4.myhuaweicloud.com/<org>/busybox:1.37
```

### 1. 初始化 OBS 桶与权限

```bash
export OBS_ENDPOINT=https://obs.cn-north-4.myhuaweicloud.com
export OBS_REGION=cn-north-4
export OBS_BUCKET=<你的桶名>
deploy/cce/obs/setup-bucket.sh --dry-run          # 先看将执行的命令
deploy/cce/obs/setup-bucket.sh --versioning       # 真实执行（幂等）
deploy/cce/obs/verify-obs.sh                      # 小对象往返 + 匿名读拒绝
```

IAM 侧：为应用创建专用 AK（或配置 IAM 委托），授予
`deploy/cce/obs/iam-policy-skillhub-app.json` 的桶内最小权限；**不要**复用账号级全权限 AK。

### 2. 构建并推送镜像

> 在**华为云 CodeArts** 上构建时，用薄封装入口 [`deploy/cce/codearts/build.sh`](codearts/build.sh)，
> 配置说明见 [codearts/README.md](codearts/README.md)；它最终调用的仍是下面这个脚本。
> 产物镜像名为 `skillmall-{server,web,scanner}`（采集服务仍是 `skillhub-github-sync`）。

```bash
export SWR_REGION=cn-north-4 SWR_ORGANIZATION=<org> TAG=v0.2.14
./deploy/cce/build-images.sh --dry-run
./deploy/cce/build-images.sh                       # server/web/scanner
# GitHub 采集服务（可选，本路径不建议开启）：
./deploy/cce/build-images.sh --only github-sync
```

OBS 存储是后端新增能力，**必须用本分支代码构建的 server 镜像**，历史发布镜像不支持 `provider=obs`。

第三方镜像（busybox，内置库路径还需要 Bitnami PG/Redis）镜像化到 SWR：

```bash
SWR_REGION=cn-north-4 SWR_ORGANIZATION=<org> ./deploy/cce/mirror-images.sh --dry-run
SWR_REGION=cn-north-4 SWR_ORGANIZATION=<org> ./deploy/cce/mirror-images.sh
```

验收应比较目标 manifest digest，而不是只看推送成功。

> **digest 会变**：`scripts/mirror-runtime-images.sh` 对多平台 index 使用
> `regctl index create` 重建（并丢弃 attestation 子清单），因此镜像同步后 digest
> 与 docker.io 原值**不同**。`values-cce-incluster-db.yaml` 因此按映射表的目标 tag
> 引用并清空 `digest`；若要求可复现的不可变引用，同步后用
> `regctl manifest head swr.<REGION>.myhuaweicloud.com/<ORG>/bitnami/postgresql:<tag>`
> 读出真实 digest 再回填到 values。

### 3. 创建应用 Secret

```bash
cp deploy/cce/secrets-cce.example.yaml /tmp/skillhub-app-secrets.yaml   # 不要提交到仓库
# 编辑 /tmp/skillhub-app-secrets.yaml：替换所有占位值
kubectl create namespace skillhub --dry-run=client -o yaml | kubectl apply -f -
kubectl -n skillhub apply -f /tmp/skillhub-app-secrets.yaml
```

必填键：`spring-datasource-password`、`redis-password`、`bootstrap-admin-password`、
`skillhub-download-anon-cookie-secret`（至少 32 字符随机值）。
`skillhub-storage-obs-access-key/-secret-key` 仅在 `credentialMode=static` 时需要。
`auth-initial-role-grants-json` 保持**键不存在**（给空串会导致启动失败）。

### 4. 打包 chart

chart 依赖 Bitnami `postgresql`/`redis` 子 chart（`oci://registry-1.docker.io/bitnamicharts`），
仓库未 vendor。在**能访问 docker.io OCI** 的机器上打包，再拷贝 tgz：

```bash
./deploy/cce/package-chart.sh                 # 产出 dist/skillhub-<version>.tgz
# 离线机：把已下载的 postgresql-18.6.10.tgz / redis-25.5.3.tgz 放到某目录后
./deploy/cce/package-chart.sh --offline <dir>
```

> 本机若 `helm dependency build` 报 `failed to copy ... production.cloudfront.docker.com`
> 之类错误，是访问 docker.io OCI 被阻断/被重置，不是配置错误。

### 5. 安装

```bash
helm -n skillhub upgrade -i skillhub ./dist/skillhub-<version>.tgz \
  -f deploy/cce/values-cce.yaml \
  --wait --timeout 15m
# 需要内置 PG/Redis 时追加 -f deploy/cce/values-cce-incluster-db.yaml
```

首次启动会执行 Flyway 迁移，这不是无副作用操作：确认目标库、备份策略与回滚方式后再执行。

### 6. 验证（分层，不能互相替代）

```bash
kubectl -n skillhub get pods
kubectl -n skillhub rollout status deployment/skillmall-server --timeout=10m
kubectl -n skillhub logs -l app.kubernetes.io/component=server --tail=100
kubectl -n skillhub get ingress
```

| 层次 | 检查 | 能证明什么 |
|---|---|---|
| 渲染 | `helm template` 无 fail、`helm lint` 通过 | 配置结构自洽，**不证明**部署可用 |
| 健康 | Pod Ready、`/actuator/health`、`/nginx-health` | 依赖连通、进程存活 |
| 存储 | `obs/verify-obs.sh`、发布一个技能包并下载回原始字节 | OBS 读写/签名链路可用 |
| 业务闭环 | 登录 → 发布 → 扫描审核 → 下载固定版本 → 搜索 | 端到端可用 |

> 注意：`scripts/storage-diagnostics.sh` 断言的是 AWS SigV4（`X-Amz-Signature`），
> **不适用于 OBS**（OBS 用 `AccessKeyId/Expires/Signature` 形态）。OBS 侧用
> `deploy/cce/obs/verify-obs.sh` 与上面的业务闭环来验收。

### 7. 升级与回滚

1. 固定镜像 tag，备份 RDS（快照）与 OBS 关键前缀；保存旧版本 values 与 chart tgz。
2. `helm upgrade` 后观察迁移日志；**回退镜像不等于回退数据库**。
3. OBS 中旧对象保持可读；切换存储后端不会迁移对象，需要用对象复制工具单独迁移并校验。
4. 不要用删除 namespace 作为重试手段。

## 关键配置项

| 配置 | 说明 |
|---|---|
| `images.registry` | `swr.<REGION>.myhuaweicloud.com/<ORG>`，三个应用镜像共用前缀 |
| `obs.enabled/bucket/endpoint` | 启用 OBS；桶必须已存在；endpoint 形如 `https://obs.<REGION>.myhuaweicloud.com` |
| `obs.credentialMode` | `auto`（有静态 AK/SK 就用，否则元数据临时凭证）/ `static` / `metadata` |
| `obs.credentialTtl` | 临时凭证刷新周期，必须小于委托凭证有效期（通常 1 小时）；默认 `PT50M` |
| `obs.presignExpiry` | 预签名下载 URL 有效期，默认 `PT10M` |
| `s3.enabled` | 必须为 `false`（与 `obs.enabled` 互斥，渲染会直接报错） |
| `session.cookieSecure` | HTTPS 环境必须 `true` |
| `ingress.className` | `nginx`（**必须显式写**，否则 CCE 默认 `cce`=ELB） |
| `ingress.annotations.proxy-body-size` | `110m`，对应上传上限 110100480 字节，超出返回 413 |
| `ingress.annotations.proxy-read-timeout` | `900`，扫描最长约 900s，ELB Ingress 做不到 |
| `server.dependencyWait.image` | 指向 SWR 的 busybox（CCE 拉不到 docker.io） |
| `imagePullSecrets` | 三组件均为 `default-secret`（CCE 自动创建并轮换，禁止自建） |

## 已知限制与风险

1. **github-sync 在 OBS 模式下不可用**：暂存上传只支持 local/s3。需要时另建一套 s3 provider 部署。
2. **RDS 默认 `sslmode=require`**：链路加密但不校验服务端证书。要做到 `verify-ca`，需要把 CA
   注入容器，而 chart 当前未开放 server 的卷挂载能力（需上游改动）。
3. **OBS 适配器为新代码路径**：本分支之外的镜像不支持 `provider=obs`；上线前必须按第 6 节做业务闭环验收。
4. **元数据临时凭证未在真机验证**：Pod 到 `169.254.169.254` 的可达性、IAM 委托配置、
   预签名 URL 携带 `x-obs-security-token` 的行为都需在目标集群实测。不可达时改 `credentialMode=static`。
5. **Ingress 超时与 buffering 注解**：CCE 只文档化了部分 nginx 注解，`proxy-read-timeout` /
   `proxy-send-timeout` / `proxy-buffering` 属社区原生注解，需在目标集群用大文件与长扫描实测。
6. **HPA 依赖 metrics**：未装指标插件时扩容不会生效（不报错，只是不扩）。
7. **内置库路径**用 `csi-disk`（RWO，单写节点）；跨可用区调度请改用 `csi-disk-topology`。
8. **`obs.publicEndpoint` 是 OBS 服务域名**（如 `https://obs.cn-north-4.myhuaweicloud.com`），
   SDK 会把桶名前置成 `<bucket>.<host>`；本版本不支持把裸 CDN 域名直接当作 publicEndpoint
   （需要 OBS SDK 的 CNAME 模式，尚未开启）。留空表示与 `endpoint` 相同。
9. **预签名 URL 会带显式 `:443`**。若将来在 OBS 模式下启用 GitHub 采集，worker 的主机白名单
   必须写成 `<bucket>.obs.<region>.myhuaweicloud.com:443`（含端口），否则报
   "object-storage host is not allowed"。当前 `GithubSyncUploadAppService` 只认 local/s3，
   需要先改代码才能配合 obs。

## 排障

| 现象 | 先查 |
|---|---|
| Pod `ImagePullBackOff` | SWR 地址/组织/tag；`default-secret` 是否存在（不要自建）；跨账号拉取需另建 docker-registry Secret |
| `CrashLoopBackOff` 且日志含 Flyway 错误 | RDS 地址/账号权限/`sslmode` 与实例是否开 SSL |
| 启动报 `Invalid initial external role grants JSON` | Secret 里有 `auth-initial-role-grants-json` 且为空串 → 删除该键 |
| 上传大文件 413 | ingress `proxy-body-size` 与 scanner `maxUploadSizeBytes` |
| 扫描 504 | 是否误用 ELB Ingress；nginx `proxy-read-timeout` 是否 >= 扫描时长 |
| 下载 403/签名无效 | OBS endpoint/桶/凭证模式；临时凭证是否过期；`x-obs-security-token` 是否随 URL 下发 |
| 页面能开但接口 502 | web 的 `SKILLHUB_API_UPSTREAM` 与 `clusterDomain`（CCE 同样是 `cluster.local`） |
| Cookie 不生效 | `session.cookieSecure=true` 但入口是 HTTP，或 TLS 未正确终止 |
