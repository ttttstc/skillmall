#!/usr/bin/env bash
# =============================================================================
# SkillHub —— 华为云 CCE 镜像构建与推送（SWR）
#
# 构建 4 个镜像并推送到 SWR：
#   swr.<region>.myhuaweicloud.com/<organization>/<repository>:<tag>
#    repository = skillmall-server | skillmall-web | skillmall-scanner | skillhub-github-sync
# 仓库名与 charts/skillhub/templates/*-deployment.yaml 拼出的镜像名一致。
#
# 示例（可在任意目录执行，脚本会切到仓库根目录）：
#   SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub ./deploy/cce/build-images.sh --dry-run
#   SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub TAG=v0.2.14 \
#     ./deploy/cce/build-images.sh --only server,web
#   SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub SWR_AK=... SWR_SK=... \
#     ./deploy/cce/build-images.sh --platforms linux/amd64
#
# 凭证与安全：
#   * SWR 的 docker login 密码不是 SK，而是「登录密钥」：
#       hex(HMAC-SHA256(key = SK, msg = AK))
#     由 openssl 在内存中派生，通过 --password-stdin 交给 docker login，
#     不落盘、不回显；--dry-run 只打印脱敏命令。缺少 openssl 时直接报错，不静默失败。
#   * 不要用 bash -x 运行本脚本（会把变量展开到终端）。
#
# 拉取镜像：CCE 会在每个命名空间自动创建 default-secret（kubernetes.io/dockerconfigjson）
# 用于拉取 SWR 镜像，且会轮换/过期；不要自己创建或复制 pull secret，
# Helm 侧 imagePullSecrets 指向 default-secret 即可（见 deploy/cce/values-cce.yaml）。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
readonly SCRIPT_DIR REPO_ROOT

readonly -a ALL_COMPONENTS=(server web scanner github-sync)

DRY_RUN=0
PUSH=1
ONLY_ARG=""
TAG_ARG=""
PLATFORMS_ARG=""
PLATFORMS=""
PLATFORM_COUNT=0
OUTPUT_FLAG=""
REGISTRY=""
TAG="${TAG:-}"
MAVEN_SETTINGS_FILE="${MAVEN_SETTINGS:-}"
WEB_VITE_BASE_PATH_VALUE="${WEB_VITE_BASE_PATH:-}"
IMAGE_SOURCE="${IMAGE_SOURCE:-https://github.com/iflytek/skillhub}"
PROVENANCE="${BUILD_PROVENANCE:-false}"
IMAGE_REVISION=""
IMAGE_REFERENCE=""
COMPONENTS=()
BUILD_ARGS=()

info() { printf '%s\n' "$*"; }
warn() { printf '警告: %s\n' "$*" >&2; }
die() { printf '错误: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
用法: build-images.sh [选项]

构建 SkillHub 镜像并推送到华为云 SWR：
  swr.<region>.myhuaweicloud.com/<organization>/<repository>:<tag>

环境变量:
  SWR_REGION          必填（除非给 SWR_REGISTRY）。SWR 区域，例如 cn-north-4。
  SWR_ORGANIZATION    必填。SWR 组织（命名空间），例如 skillhub。
  SWR_REGISTRY        可选。覆盖仓库地址，默认 swr.<SWR_REGION>.myhuaweicloud.com。
  TAG                 可选。镜像标签，默认 git describe --tags --always --dirty。
  PLATFORMS           可选。目标平台，逗号分隔，默认 linux/amd64。
  SWR_AK / SWR_SK     可选。SWR 访问密钥；两者同时提供时执行
                      docker login -u <region>@<AK> --password-stdin <registry>。
                      密码是登录密钥 hex(HMAC-SHA256(key=SK, msg=AK))，需要 openssl，
                      不落盘、不回显。未提供时沿用本机已有 docker 凭证（先手工 docker login）。
  MAVEN_SETTINGS      可选。server 用的 Maven settings.xml 路径，以
                      --secret id=maven_settings,src=<文件> 传给构建（server/Dockerfile 已支持）。
  WEB_VITE_BASE_PATH  可选。web 的 VITE_BASE_PATH 构建参数；不设则保留镜像内占位值。
  IMAGE_SOURCE        可选。镜像 org.opencontainers.image.source 标签，默认仓库地址。
  BUILD_PROVENANCE    可选。buildx provenance（证明）开关，默认 false：
                      部分镜像仓库（含 SWR 的某些形态）对 attestation 子清单兼容性差，
                      与 scripts/mirror-runtime-images.sh 丢弃 attestation 的口径一致。
                      设 true 保留默认证明；设 skip 则不传该参数（旧版 buildx 可用）。

选项:
  --only <a,b>        只构建指定组件，逗号分隔：server,web,scanner,github-sync。
  --tag <标签>        等价于 TAG=<标签>。
  --platforms <列表>  等价于 PLATFORMS=<列表>。
  --dry-run           只打印将执行的命令（登录命令脱敏），不登录、不构建、不推送。
  --no-push           不推送，只把镜像加载到本地 docker（仅单平台可用）。
  -h, --help          显示本帮助。

说明:
  * CCE 每个命名空间会自动创建 default-secret（kubernetes.io/dockerconfigjson）
    用于拉取 SWR 镜像，且会轮换/过期：不要手工创建或复制 pull secret，
    Helm 侧把 imagePullSecrets 指向 default-secret 即可（见 deploy/cce/values-cce.yaml）。
  * 环境里没有 openssl 时无法派生 SWR 登录密钥，脚本会直接报错，不会用 SK 直接当密码。

构建口径:
  * docker buildx build；多平台必须 --push，单平台且 --no-push 时用 --load。
  * 上下文与 Dockerfile：
      server       上下文 server/          Dockerfile server/Dockerfile
      web          上下文 web/             Dockerfile web/Dockerfile
      scanner      上下文 scanner/         Dockerfile scanner/Dockerfile
      github-sync  上下文 github-sync-go/  Dockerfile github-sync-go/Dockerfile
  * 任一镜像构建失败立即停止，并打印可执行的诊断建议。

示例:
  SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub ./deploy/cce/build-images.sh --dry-run
  SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub TAG=v0.2.14 ./deploy/cce/build-images.sh
  SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub ./deploy/cce/build-images.sh --only server
  SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub ./deploy/cce/build-images.sh \
    --platforms linux/amd64,linux/arm64
  SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub SWR_AK=<AK> SWR_SK=<SK> \
    ./deploy/cce/build-images.sh --tag manual-$(date +%Y%m%d)
EOF
}

component_context() {
  case "$1" in
    server) printf '%s' 'server' ;;
    web) printf '%s' 'web' ;;
    scanner) printf '%s' 'scanner' ;;
    github-sync) printf '%s' 'github-sync-go' ;;
    *) return 1 ;;
  esac
}

component_dockerfile() {
  case "$1" in
    server) printf '%s' 'server/Dockerfile' ;;
    web) printf '%s' 'web/Dockerfile' ;;
    scanner) printf '%s' 'scanner/Dockerfile' ;;
    github-sync) printf '%s' 'github-sync-go/Dockerfile' ;;
    *) return 1 ;;
  esac
}

component_repository() {
  case "$1" in
    server) printf '%s' 'skillmall-server' ;;
    web) printf '%s' 'skillmall-web' ;;
    scanner) printf '%s' 'skillmall-scanner' ;;
    github-sync) printf '%s' 'skillhub-github-sync' ;;
    *) return 1 ;;
  esac
}

image_reference() {
  printf '%s/%s/%s:%s' \
    "$REGISTRY" "${SWR_ORGANIZATION}" "$(component_repository "$1")" "$TAG"
}

require_command() { command -v "$1" >/dev/null 2>&1; }

print_command() {
  printf '+'
  printf ' %q' "$@"
  printf '\n'
}

parse_args() {
  while (($# > 0)); do
    case "$1" in
      --only)
        (($# >= 2)) || die "--only 需要一个参数，例如 --only server,web"
        ONLY_ARG="$2"
        shift 2
        ;;
      --only=*) ONLY_ARG="${1#*=}"; shift ;;
      --tag)
        (($# >= 2)) || die "--tag 需要一个参数"
        TAG_ARG="$2"
        shift 2
        ;;
      --tag=*) TAG_ARG="${1#*=}"; shift ;;
      --platforms)
        (($# >= 2)) || die "--platforms 需要一个参数，例如 linux/amd64"
        PLATFORMS_ARG="$2"
        shift 2
        ;;
      --platforms=*) PLATFORMS_ARG="${1#*=}"; shift ;;
      --dry-run) DRY_RUN=1; shift ;;
      --no-push) PUSH=0; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "未知参数: $1（用 --help 查看用法）" ;;
    esac
  done
}

resolve_registry() {
  if [[ -n "${SWR_REGISTRY:-}" ]]; then
    REGISTRY="${SWR_REGISTRY%/}"
    [[ -n "$REGISTRY" ]] || die "SWR_REGISTRY 不能为空"
  else
    [[ -n "${SWR_REGION:-}" ]] || die "缺少 SWR_REGION（例如 cn-north-4），或改用 SWR_REGISTRY 显式给出仓库地址"
    REGISTRY="swr.${SWR_REGION}.myhuaweicloud.com"
  fi
  [[ "$REGISTRY" =~ ^[A-Za-z0-9][A-Za-z0-9./:_-]*$ ]] || die "SWR_REGISTRY 不合法: ${REGISTRY}"
}

resolve_organization() {
  [[ -n "${SWR_ORGANIZATION:-}" ]] || die "缺少 SWR_ORGANIZATION（SWR 组织名，例如 skillhub）"
  [[ "$SWR_ORGANIZATION" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
    || die "SWR_ORGANIZATION 只允许字母、数字与 . _ -（不能含 /）: ${SWR_ORGANIZATION}"
}

default_tag() {
  local tag=''
  if tag="$(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null)" && [[ -n "$tag" ]]; then
    printf '%s' "$tag"
    return 0
  fi
  if tag="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null)" && [[ -n "$tag" ]]; then
    printf '%s' "$tag"
    return 0
  fi
  return 1
}

resolve_tag() {
  TAG="${TAG_ARG:-${TAG:-}}"
  if [[ -z "$TAG" ]]; then
    if ! TAG="$(default_tag)"; then
      die "无法从 git 推导 TAG（当前不是 git 仓库或还没有提交）；请显式传入 TAG 或 --tag"
    fi
  fi
  [[ "$TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] \
    || die "TAG 不合法: ${TAG}（只允许字母、数字与 _ . -，最长 128 字符）"
}

resolve_platforms() {
  local raw="${PLATFORMS_ARG:-${PLATFORMS:-linux/amd64}}"
  local item out='' commas
  local -a items
  IFS=',' read -r -a items <<<"$raw"
  for item in "${items[@]}"; do
    item="$(printf '%s' "$item" | tr -d '[:space:]')"
    [[ -n "$item" ]] || continue
    [[ "$item" =~ ^[A-Za-z0-9_]+/[A-Za-z0-9_]+(/[A-Za-z0-9_.-]+)?$ ]] \
      || die "平台写法不合法: ${item}（示例: linux/amd64）"
    if [[ -z "$out" ]]; then out="$item"; else out="${out},${item}"; fi
  done
  [[ -n "$out" ]] || die "PLATFORMS 至少需要一个平台（默认 linux/amd64）"
  PLATFORMS="$out"
  commas="${out//[^,]/}"
  PLATFORM_COUNT=$((${#commas} + 1))
}

resolve_output_mode() {
  if ((PLATFORM_COUNT > 1)); then
    ((PUSH == 1)) || die "多平台构建必须推送：buildx 无法用 --load 加载多平台镜像。请去掉 --no-push，或把 PLATFORMS 限制为单个平台"
    OUTPUT_FLAG="--push"
  elif ((PUSH == 1)); then
    OUTPUT_FLAG="--push"
  else
    OUTPUT_FLAG="--load"
  fi
}

resolve_maven_settings() {
  local dir base
  [[ -n "$MAVEN_SETTINGS_FILE" ]] || return 0
  [[ -f "$MAVEN_SETTINGS_FILE" ]] || die "MAVEN_SETTINGS 指向的文件不存在: ${MAVEN_SETTINGS_FILE}"
  dir="$(cd -- "$(dirname -- "$MAVEN_SETTINGS_FILE")" >/dev/null 2>&1 && pwd)"
  base="$(basename -- "$MAVEN_SETTINGS_FILE")"
  MAVEN_SETTINGS_FILE="${dir}/${base}"
}

resolve_components() {
  local item candidate valid existing seen=' '
  COMPONENTS=()
  if [[ -z "$ONLY_ARG" ]]; then
    COMPONENTS=("${ALL_COMPONENTS[@]}")
    return 0
  fi
  local -a requested=()
  IFS=',' read -r -a requested <<<"$ONLY_ARG"
  for item in "${requested[@]}"; do
    item="$(printf '%s' "$item" | tr -d '[:space:]')"
    [[ -n "$item" ]] || continue
    valid=0
    for candidate in "${ALL_COMPONENTS[@]}"; do
      if [[ "$item" == "$candidate" ]]; then
        valid=1
        break
      fi
    done
    ((valid == 1)) || die "未知组件: ${item}（可选: ${ALL_COMPONENTS[*]}）"
    existing=0
    if [[ "$seen" == *" ${item} "* ]]; then existing=1; fi
    if ((existing == 0)); then
      COMPONENTS+=("$item")
      seen+="${item} "
    fi
  done
  ((${#COMPONENTS[@]} > 0)) || die "--only 没有给出有效组件"
}

resolve_revision() {
  IMAGE_REVISION=""
  if require_command git; then
    if ! IMAGE_REVISION="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null)"; then
      IMAGE_REVISION=""
    fi
  fi
}

resolve_provenance() {
  case "$PROVENANCE" in
    false|true|skip) ;;
    *) die "BUILD_PROVENANCE 只支持 false / true / skip，收到: ${PROVENANCE}" ;;
  esac
}

check_prerequisites() {
  local component dockerfile context
  for component in "${COMPONENTS[@]}"; do
    dockerfile="$(component_dockerfile "$component")"
    context="$(component_context "$component")"
    [[ -f "${REPO_ROOT}/${dockerfile}" ]] || die "找不到 Dockerfile: ${dockerfile}（仓库根目录 ${REPO_ROOT}）"
    [[ -d "${REPO_ROOT}/${context}" ]] || die "找不到构建上下文目录: ${context}/"
  done

  if ((DRY_RUN == 1)); then
    if ! require_command docker; then
      warn "未找到 docker：--dry-run 仍会打印命令，但真实构建需要 docker CLI + buildx 插件"
    elif ! docker buildx version >/dev/null 2>&1; then
      warn "docker buildx 不可用：真实构建会失败（Docker Desktop 自带，Linux 需安装 buildx 插件）"
    fi
    return 0
  fi

  require_command docker || die "未找到 docker CLI：请安装 Docker 并确认 docker info 可用"
  docker info >/dev/null 2>&1 \
    || die "docker daemon 不可用（docker info 失败）：请启动 Docker Desktop / dockerd，并确认当前用户有访问权限"
  docker buildx version >/dev/null 2>&1 \
    || die "docker buildx 不可用：请安装 buildx 插件（Docker Desktop 自带；Linux 见 https://docs.docker.com/build/install-buildx/）"
  if ((PLATFORM_COUNT > 1)); then
    check_builder_driver || true
  fi
}

check_builder_driver() {
  local driver
  if driver="$(docker buildx inspect --format '{{.Driver}}' 2>/dev/null)"; then
    if [[ "$driver" == "docker" ]]; then
      warn "当前 buildx builder 用的是 docker 驱动，不支持多平台推送。建议先执行："
      warn "  docker buildx create --name skillhub --driver docker-container --use && docker buildx inspect --bootstrap"
      return 1
    fi
    info "buildx driver: ${driver}"
  fi
  return 0
}

# 华为云 SWR：docker login -u <region>@<AK> -p <登录密钥> swr.<region>.myhuaweicloud.com
# 登录密钥 = hex(HMAC-SHA256(key = SK, msg = AK))，只能派生，无法从密钥反推 AK/SK。
derive_login_password() {
  local ak="$1" sk="$2"
  if ! require_command openssl; then
    die "无法生成 SWR 登录密钥：缺少 openssl。请安装 openssl（WSL/Ubuntu: apt-get install openssl；Git Bash 自带；Windows 可用 https://slproweb.com/products/Win32OpenSSL.html），或省略 SWR_AK/SWR_SK 改用本机已有 docker 凭证（先手工 docker login）。本脚本不会用 SK 直接当密码，也不会静默跳过登录。"
  fi
  printf '%s' "$ak" | openssl dgst -binary -sha256 -hmac "$sk" | od -An -vtx1 | tr -d ' \n'
}

swr_login() {
  local ak="${SWR_AK:-}" sk="${SWR_SK:-}" login_password username
  if [[ -z "$ak" && -z "$sk" ]]; then
    info "  未设置 SWR_AK/SWR_SK：沿用本机已有 docker 凭证（需要事先 docker login ${REGISTRY}）"
    return 0
  fi
  [[ -n "$ak" && -n "$sk" ]] || die "SWR_AK 与 SWR_SK 必须同时提供（只给其中一个无法派生登录密钥）"
  [[ -n "${SWR_REGION:-}" ]] || die "使用 SWR_AK/SWR_SK 登录时需要 SWR_REGION：登录用户名是 <region>@<AK>"
  username="${SWR_REGION}@${ak}"

  if ((DRY_RUN == 1)); then
    info "[dry-run] docker login -u \"${SWR_REGION}@***\" --password-stdin \"${REGISTRY}\""
    info "          # 密码 = hex(HMAC-SHA256(key=SK, msg=AK))，不打印；AK/SK 均不回显"
    return 0
  fi

  login_password="$(derive_login_password "$ak" "$sk")"
  [[ -n "$login_password" ]] || die "SWR 登录密钥派生结果为空：请检查 SWR_AK/SWR_SK 是否为有效值"
  if ! printf '%s' "$login_password" | docker login -u "$username" --password-stdin "$REGISTRY" >/dev/null; then
    unset login_password
    die "docker login 失败（SWR 拒绝）。请核对：SWR_AK/SWR_SK 是否有效、SWR_REGION 与 SWR_REGISTRY 是否匹配、账号是否有组织 ${SWR_ORGANIZATION} 的推送权限"
  fi
  unset login_password
  info "  已登录 ${REGISTRY}（用户 ${SWR_REGION}@***，AK/SK 未回显）"
}

build_args() {
  local component="$1" dockerfile context repository image
  dockerfile="$(component_dockerfile "$component")"
  context="$(component_context "$component")"
  repository="$(component_repository "$component")"
  image="$(image_reference "$component")"

  BUILD_ARGS=(docker buildx build)
  BUILD_ARGS+=(--file "$dockerfile")
  BUILD_ARGS+=(--tag "$image")
  BUILD_ARGS+=(--platform "$PLATFORMS")
  if [[ "$PROVENANCE" != "skip" ]]; then
    BUILD_ARGS+=(--provenance "$PROVENANCE")
  fi
  BUILD_ARGS+=("$OUTPUT_FLAG")
  BUILD_ARGS+=(--label "org.opencontainers.image.title=${repository}")
  BUILD_ARGS+=(--label "org.opencontainers.image.version=${TAG}")
  BUILD_ARGS+=(--label "org.opencontainers.image.source=${IMAGE_SOURCE}")
  if [[ -n "$IMAGE_REVISION" ]]; then
    BUILD_ARGS+=(--label "org.opencontainers.image.revision=${IMAGE_REVISION}")
  fi

  case "$component" in
    server)
      if [[ -n "$MAVEN_SETTINGS_FILE" ]]; then
        BUILD_ARGS+=(--secret "id=maven_settings,src=${MAVEN_SETTINGS_FILE}")
      fi
      ;;
    web)
      if [[ -n "$WEB_VITE_BASE_PATH_VALUE" ]]; then
        BUILD_ARGS+=(--build-arg "VITE_BASE_PATH=${WEB_VITE_BASE_PATH_VALUE}")
      fi
      ;;
  esac

  BUILD_ARGS+=("$context")
  IMAGE_REFERENCE="$image"
}

print_failure_hints() {
  local component="$1" image="$2"
  {
    printf '\n'
    printf '构建失败: %s\n' "$image"
    printf '可执行诊断建议（按顺序排查）：\n'
    printf '  1. docker daemon / buildx：docker info；docker buildx version；docker buildx ls\n'
    printf '  2. 多平台需要 docker-container 驱动：\n'
    printf '     docker buildx create --name skillhub --driver docker-container --use\n'
    printf '     docker buildx inspect --bootstrap\n'
    printf '  3. 非 amd64 平台需要 binfmt 模拟：\n'
    printf '     docker run --privileged --rm tonistiigi/binfmt --install all\n'
    printf '  4. 基础镜像拉取失败（docker.io 限流/不可达）：配置 HTTP(S)_PROXY，或先把基础镜像同步到 SWR\n'
    printf '     再同步调整 Dockerfile 的 FROM（本脚本不改 Dockerfile）\n'
    printf '  5. SWR 推送 401/403：核对 SWR_AK/SWR_SK、SWR_REGION（登录名 <region>@<AK>）、组织是否存在且有推送权限\n'
    printf '  6. 磁盘/缓存：docker system df；必要时 docker buildx prune\n'
    case "$component" in
      server)
        printf '  7. server：Maven 依赖下载失败时用 MAVEN_SETTINGS=<settings.xml> 传入私有仓库配置\n'
        ;;
      web)
        printf '  7. web：pnpm 安装失败时配置 npm 镜像或代理；只改前端基路径时用 WEB_VITE_BASE_PATH 复用缓存层\n'
        ;;
      scanner)
        printf '  7. scanner：pip 拉取 cisco-ai-skill-scanner==2.1.0 失败时配置 PIP_INDEX_URL / 代理（Dockerfile 用 --only-binary=:all:）\n'
        ;;
      github-sync)
        printf '  7. github-sync：Go 模块下载失败时在构建环境配置 GOPROXY / 代理（Dockerfile 未暴露 GOPROXY 参数）\n'
        ;;
    esac
    printf '\n'
  } >&2
}

build_image() {
  local component="$1" image started
  build_args "$component"
  image="$IMAGE_REFERENCE"

  if ((DRY_RUN == 1)); then
    print_command "${BUILD_ARGS[@]}"
    return 0
  fi

  info ""
  info "==> [${component}] ${image}"
  info "    Dockerfile: $(component_dockerfile "$component")  上下文: $(component_context "$component")/  平台: ${PLATFORMS}  输出: ${OUTPUT_FLAG}"

  started=$SECONDS
  if ! "${BUILD_ARGS[@]}"; then
    print_failure_hints "$component" "$image"
    return 1
  fi
  info "    ✓ 完成（$((SECONDS - started))s）"
}

print_summary() {
  local component
  info ""
  info "全部完成。"
  for component in "${COMPONENTS[@]}"; do
    info "  $(image_reference "$component")"
  done
  info ""
  if ((PUSH == 1)); then
    info "Helm 取值参考（charts/skillhub）："
    info "  --set images.registry=${REGISTRY}/${SWR_ORGANIZATION} --set images.tag=${TAG}"
    info "  （可先用 deploy/cce/package-chart.sh 打包 chart）"
  else
    info "（--no-push：镜像只在本地 docker 镜像库，未推送 SWR）"
  fi
}

main() {
  parse_args "$@"
  resolve_registry
  resolve_organization
  resolve_tag
  resolve_platforms
  resolve_output_mode
  resolve_provenance
  resolve_maven_settings
  resolve_components
  resolve_revision
  check_prerequisites

  cd -- "$REPO_ROOT"

  info "SkillHub → SWR 镜像构建"
  info "  仓库: ${REGISTRY}"
  info "  组织: ${SWR_ORGANIZATION}"
  info "  标签: ${TAG}"
  info "  平台: ${PLATFORMS}"
  info "  组件: ${COMPONENTS[*]}"
  if ((PUSH == 1)); then
    info "  推送: 是（${OUTPUT_FLAG}）"
  else
    info "  推送: 否（--load 到本地 docker）"
  fi
  if [[ -n "$MAVEN_SETTINGS_FILE" ]]; then
    info "  Maven settings: ${MAVEN_SETTINGS_FILE}（以 --secret 传入，不打印内容）"
  fi

  if ((DRY_RUN == 1)); then
    info ""
    info "  （--dry-run：只打印命令，不登录、不构建、不推送）"
    swr_login
    info ""
    for component in "${COMPONENTS[@]}"; do
      build_image "$component"
    done
    info ""
    info "以上为 dry-run 展开结果。真实执行前请确认 SWR 组织存在、凭证具备推送权限。"
    return 0
  fi

  if ((PUSH == 1)); then
    swr_login
  else
    info "  跳过 SWR 登录（--no-push 不需要凭证）"
  fi

  for component in "${COMPONENTS[@]}"; do
    if ! build_image "$component"; then
      die "组件 ${component} 构建失败，已停止（其余组件未构建）"
    fi
  done

  print_summary
}

main "$@"
