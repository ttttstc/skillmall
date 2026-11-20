# 在华为云 CodeArts 上构建 SkillMall 镜像

本目录是 **CodeArts「编译构建」的入口**。它只是一层薄封装：

```
CodeArts 任务参数 → deploy/cce/codearts/build.sh → deploy/cce/build-images.sh → SWR
```

构建逻辑只有一份实现（`deploy/cce/build-images.sh`），本地、CodeArts、其它 CI 走同一条
`docker buildx build` 命令，避免"CI 上和本地构建结果不一样"。

构建产物（与 chart 模板拼出的镜像名一致）：

| 组件 | SWR 镜像 | 来源 |
|---|---|---|
| 后端 | `swr.<region>.myhuaweicloud.com/<org>/skillmall-server:<tag>` | `server/Dockerfile`（上下文 `server/`） |
| 前端 | `.../skillmall-web:<tag>` | `web/Dockerfile`（上下文 `web/`） |
| 扫描器 | `.../skillmall-scanner:<tag>` | `scanner/Dockerfile`（上下文 `scanner/`） |
| 采集服务（可选） | `.../skillhub-github-sync:<tag>` | `github-sync-go/Dockerfile` |

> 只改了这三个业务镜像的名字；采集服务仍叫 `skillhub-github-sync`，
> Service/Deployment/命名空间/chart 名都保持 `skillhub` 不变（改名不影响运行时资源名）。

## 1. 构建环境要求

| 要求 | 说明 |
|---|---|
| CPU 架构 | `x86_64`（默认只构建 `linux/amd64`；要 arm64 见 `--platforms`） |
| Docker | `docker` + **`docker buildx`** 插件 |
| openssl | 从 AK/SK 派生 SWR 登录密钥时需要 |
| 磁盘 | 至少 10 GB 空闲（Maven 依赖 + 三个镜像层） |

**CodeArts 最大的坑：普通构建容器里没有 docker daemon。** 三种可行姿势：

1. **用 CodeArts 的镜像构建能力**（推荐）：在任务里用平台提供的镜像构建步骤，
   Dockerfile 与上下文按上表填；`deploy/cce/build-images.sh --dry-run` 可先用于核对参数。
2. **特权/DinD 环境**：构建步骤启用 docker-in-docker，或挂载宿主 docker socket，然后直接
   跑本脚本（脚本会先 `docker info` 自检，不满足会立刻失败并给出提示，不会半途出错）。
3. **无 daemon 构建器**：改用 kaniko / buildah。本脚本目前只封装 `docker buildx`，
   用这条路需要替换构建器（建议在 CodeArts 里加一个独立步骤跑 kaniko，
   产物仍是同一个 SWR 仓库名）。

## 2. 任务参数配置

在 CodeArts 构建任务的「参数」里配置（AK/SK 务必设为**私密参数**）：

| 参数 | 必填 | 说明 |
|---|---|---|
| `SWR_ORGANIZATION` | 是 | SWR 组织（命名空间），如 `skillmall` |
| `SWR_REGION` | 否 | 默认 `cn-north-4` |
| `SWR_AK` / `SWR_SK` | 建议 | 两者必须同时给；脚本派生登录密钥 `hex(HMAC-SHA256(SK, AK))`，不落盘、不回显 |
| `TAG` | 否 | 显式标签（发布必填，便于回溯）；不填则按 `BUILD_NUMBER` / 提交号 / git describe 推导 |
| `PLATFORMS` | 否 | 默认 `linux/amd64`；多平台必须推送（buildx 限制） |
| `MAVEN_SETTINGS` | 否 | 内网 Maven 仓库的 `settings.xml` 路径 |
| `BUILD_PROVENANCE` | 否 | 默认 `false`；某些镜像仓库对 attestation 子清单兼容性差 |

## 3. 构建步骤

任务里加一个「执行shell命令」步骤：

```bash
bash deploy/cce/codearts/build.sh
```

常用变体：

```bash
# 只构建后端（调试用）
bash deploy/cce/codearts/build.sh --only server

# 显式标签 + 多架构
bash deploy/cce/codearts/build.sh --tag v1.2.3 --platforms linux/amd64,linux/arm64

# 只校验参数与命令展开（不需要真 docker，CI 前置检查用）
bash deploy/cce/codearts/build.sh --dry-run
```

成功后脚本会写出镜像清单（默认 `dist/codearts-images.txt`，可用 `CODEARTS_IMAGES_FILE` 覆盖）：

```
swr.cn-north-4.myhuaweicloud.com/skillmall/skillmall-server:<tag>
swr.cn-north-4.myhuaweicloud.com/skillmall/skillmall-web:<tag>
swr.cn-north-4.myhuaweicloud.com/skillmall/skillmall-scanner:<tag>
```

后续「部署」步骤可以直接读这个文件，或等价地把 Helm 参数设成：

```bash
--set images.registry=swr.cn-north-4.myhuaweicloud.com/skillmall \
--set images.tag=<tag>
```

## 4. 关于「代码化构建」

CodeArts 支持把构建任务写成仓库内的配置文件（代码化构建）。**本仓库暂未提供该 YAML**：
我没有拿到该格式的权威 schema，不凭印象编写，避免你们照着失效的模板配置。

如果你们的 CodeArts 版本使用代码化构建，把上面的「执行shell命令」内容（即
`bash deploy/cce/codearts/build.sh`）作为对应步骤的内容即可，脚本本身就是唯一事实来源；
需要我补 YAML 的话，给我一份你们实例的官方示例或文档链接即可对齐。

## 5. 常见失败与排查

| 现象 | 原因与处理 |
|---|---|
| `docker daemon 不可用` | 构建容器没有 daemon，见第 1 节三种姿势 |
| `docker buildx 不可用` | 缺 buildx 插件；或旧版 docker 需先 `docker buildx create --use` |
| `缺少 openssl` | 无法派生登录密钥；装 openssl，或先手工 `docker login` 且不传 AK/SK |
| `unauthorized` / 401 推送 | SWR 组织不存在、AK/SK 无推送权限、或用了 SK 当密码（密码必须是派生密钥，脚本已处理） |
| 拉取镜像 401 | CCE 节点用命名空间自动创建的 `default-secret`；改名后**不要**自建 pull secret |
| 多平台构建报 `--load` 不支持 | 多平台只能 `--push`；本地验证请用单平台 |
| TAG 不符合预期 | 显式传 `--tag`；CI 里不要依赖隐式推导 |

## 6. 验收口径

`bash -n` 只能证明语法；**构建成功不等于镜像可用**。建议按顺序：

1. `--dry-run` 核对镜像名、标签、平台、Dockerfile 与上下文；
2. 真实构建后比对 `docker buildx imagetools inspect <镜像>` 的 digest；
3. 用目标镜像跑一次 `kubectl -n skillhub set image` 滚动更新，并确认
   `kubectl -n skillhub rollout status deployment/skillhub-server` 完成；
4. 再做业务闭环（登录 → 发布 → 扫描 → 下载）。
