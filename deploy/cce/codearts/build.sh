#!/usr/bin/env bash
# =============================================================================
# SkillMall —— 华为云 CodeArts「编译构建」入口
# =============================================================================
# 这层只做「CodeArts 变量 → build-images.sh 变量」的映射与前置校验，
# 不重复实现构建逻辑：真正的构建/推送永远是 deploy/cce/build-images.sh，
# 保证本地、CI、CodeArts 三条路径产出一致（同一份 Dockerfile 与同一条 buildx 命令）。
#
# 在 CodeArts 编译构建任务里新增一个「执行shell命令」步骤，内容：
#     bash deploy/cce/codearts/build.sh
# 并在任务参数里配置 SWR_REGION / SWR_ORGANIZATION / SWR_AK / SWR_SK（后两者设为私密参数）。
#
# 用法：bash deploy/cce/codearts/build.sh [选项]
#   --tag <标签>         等价 TAG=<标签>
#   --platforms <列表>   等价 PLATFORMS=<列表>（默认 linux/amd64）
#   --only <a,b>         只构建指定组件：server,web,scanner,github-sync
#   --dry-run            只打印将执行的命令（登录命令脱敏）
#   --no-push            不推送（仅单平台）
#   -h, --help           显示帮助
#
# 环境变量（CodeArts 任务参数或运行环境注入）：
#   SWR_REGION          必填。默认 cn-north-4。
#   SWR_ORGANIZATION    必填。SWR 组织（命名空间）。
#   SWR_AK / SWR_SK     可选但推荐。SWR 登录用；密码是派生的登录密钥，不是 SK。
#   TAG                 可选。默认按 BUILD_NUMBER / COMMIT_ID / git 顺序推导。
#   PLATFORMS           可选。默认 linux/amd64。
#   MAVEN_SETTINGS      可选。Maven settings.xml 路径（内网仓库时才需要）。
#   IMAGE_SOURCE        可选。镜像 source 标签；默认取 CodeArts 仓库地址（若提供）。
#   BUILD_PROVENANCE    可选。默认 false（SWR 对 attestation 子清单兼容性差）。
#   CODEARTS_IMAGES_FILE 可选。镜像清单输出路径，默认 dist/codearts-images.txt，
#                       供后续流水线步骤（如 helm 部署）读取实际构建出的镜像引用。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../../.." >/dev/null 2>&1 && pwd)"
BUILD_SCRIPT="${SCRIPT_DIR}/../build-images.sh"
readonly SCRIPT_DIR REPO_ROOT BUILD_SCRIPT

DRY_RUN=0
NO_PUSH=0
ONLY_ARG=""
PLATFORMS_ARG=""
TAG_ARG=""

info() { printf '%s\n' "$*"; }
warn() { printf '警告: %s\n' "$*" >&2; }
die() { printf '错误: %s\n' "$*" >&2; exit 1; }

usage() {
  sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG_ARG="${2:?--tag 需要参数}"; shift 2 ;;
    --tag=*) TAG_ARG="${1#*=}"; shift ;;
    --platforms) PLATFORMS_ARG="${2:?--platforms 需要参数}"; shift 2 ;;
    --platforms=*) PLATFORMS_ARG="${1#*=}"; shift ;;
    --only) ONLY_ARG="${2:?--only 需要参数}"; shift 2 ;;
    --only=*) ONLY_ARG="${1#*=}"; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --no-push) NO_PUSH=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "未知参数：$1（-h 查看帮助）" ;;
  esac
done

# ---------------------------------------------------------------------------
# 1. 必填校验（缺失就尽早失败，不要等 buildx 报错）
# ---------------------------------------------------------------------------
[ -f "$BUILD_SCRIPT" ] || die "找不到构建脚本：$BUILD_SCRIPT（请在仓库内执行）"
[ -f "${REPO_ROOT}/server/Dockerfile" ] || die "当前目录不像仓库根目录：${REPO_ROOT}"

[ -n "${SWR_ORGANIZATION:-}" ] || die "缺少 SWR_ORGANIZATION（例如组织名 skillhub）。请在 CodeArts 任务参数里配置"
SWR_REGION="${SWR_REGION:-cn-north-4}"
SWR_REGISTRY="${SWR_REGISTRY:-swr.${SWR_REGION}.myhuaweicloud.com}"

# AK/SK 必须成对出现，避免"配了 AK 忘了 SK"导致登录用错凭证却继续往下跑
if { [ -n "${SWR_AK:-}" ] && [ -z "${SWR_SK:-}" ]; } || { [ -z "${SWR_AK:-}" ] && [ -n "${SWR_SK:-}" ]; }; then
  die "SWR_AK 与 SWR_SK 必须同时提供（CodeArts 上请把它们设为私密参数）"
fi

# ---------------------------------------------------------------------------
# 2. TAG：显式 > CodeArts 构建号/提交 > git > 时间戳
#    CodeArts 各版本暴露的变量名不一致，这里按常见候选顺序探测；
#    生产发布建议显式传 --tag，让镜像标签可追溯到发布单。
# ---------------------------------------------------------------------------
resolve_tag() {
  if [ -n "$TAG_ARG" ]; then printf '%s' "$TAG_ARG"; return; fi
  if [ -n "${TAG:-}" ]; then printf '%s' "$TAG"; return; fi

  local short_sha=""
  for candidate in "${CODEARTS_COMMIT_ID:-}" "${COMMIT_ID:-}" "${GIT_COMMIT:-}"; do
    if [ -n "$candidate" ]; then short_sha="${candidate:0:8}"; break; fi
  done
  if [ -z "$short_sha" ] && command -v git >/dev/null 2>&1 && git -C "$REPO_ROOT" rev-parse --short=8 HEAD >/dev/null 2>&1; then
    short_sha="$(git -C "$REPO_ROOT" rev-parse --short=8 HEAD)"
  fi

  if [ -n "${BUILD_NUMBER:-}" ]; then
    if [ -n "$short_sha" ]; then printf '%s-%s' "$short_sha" "$BUILD_NUMBER"
    else printf 'codearts-%s' "$BUILD_NUMBER"; fi
    return
  fi
  if [ -n "$short_sha" ]; then printf '%s' "$short_sha"; return; fi

  printf 'codearts-%s' "$(date -u +%Y%m%d%H%M%S)"
}

TAG_VALUE="$(resolve_tag)"
PLATFORMS_VALUE="${PLATFORMS_ARG:-${PLATFORMS:-linux/amd64}}"

# ---------------------------------------------------------------------------
# 3. 构建环境自检（CodeArts 常见坑：构建镜像里没有 docker daemon / buildx）
# ---------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "构建环境没有 docker。CodeArts 上请选择带 Docker 的构建镜像，或改用控制台的镜像构建步骤"
if ! docker info >/dev/null 2>&1; then
  if [ "$DRY_RUN" -eq 1 ]; then
    warn "docker daemon 不可用，但当前是 --dry-run：只校验参数与命令展开，不做真实构建"
  else
    die "docker daemon 不可用（docker info 失败）。CodeArts 的普通构建容器默认没有 daemon：
  * 方案 A：使用 CodeArts 提供的镜像构建能力/特权构建环境；
  * 方案 B：在构建步骤里挂载 docker socket 或启用 docker-in-docker；
  * 方案 C：把 docker buildx 换成 kaniko/buildah（需要改本脚本的构建器，见 codearts/README.md）。"
  fi
fi
if ! docker buildx version >/dev/null 2>&1; then
  if [ "$DRY_RUN" -eq 1 ]; then
    warn "docker buildx 不可用，但当前是 --dry-run：真实构建前需要安装 buildx 插件"
  else
    die "docker buildx 不可用：需要 buildx 插件（安装插件或 docker buildx create --use）"
  fi
fi

if [ -n "${SWR_AK:-}" ] && ! command -v openssl >/dev/null 2>&1; then
  die "缺少 openssl：无法派生 SWR 登录密钥 hex(HMAC-SHA256(SK, AK))。装 openssl，或先手工 docker login 后不传 SWR_AK/SWR_SK"
fi

# CodeArts 的仓库地址可以顺带写进镜像 source 标签，便于溯源
if [ -z "${IMAGE_SOURCE:-}" ]; then
  for candidate in "${CODEARTS_REPO_URL:-}" "${CI_REPOSITORY_URL:-}" "${REPO_URL:-}"; do
    if [ -n "$candidate" ]; then export IMAGE_SOURCE="$candidate"; break; fi
  done
fi

info "=============================================================="
info " SkillMall —— CodeArts 镜像构建"
info "   仓库地址 : ${SWR_REGISTRY}"
info "   组织     : ${SWR_ORGANIZATION}"
info "   标签     : ${TAG_VALUE}"
info "   平台     : ${PLATFORMS_VALUE}"
info "   组件     : ${ONLY_ARG:-server,web,scanner,github-sync}"
info "   凭证     : $([ -n "${SWR_AK:-}" ] && echo 'AK/SK（派生登录密钥）' || echo '沿用已有 docker 凭证')"
info "   构建来源 : ${IMAGE_SOURCE:-默认}"
[ "$DRY_RUN" -eq 1 ] && info "   模式     : DRY-RUN（不登录、不构建、不推送）"
info "=============================================================="

# ---------------------------------------------------------------------------
# 4. 调用唯一实现
# ---------------------------------------------------------------------------
ARGS=()
[ -n "$ONLY_ARG" ] && ARGS+=(--only "$ONLY_ARG")
[ -n "$PLATFORMS_ARG" ] && ARGS+=(--platforms "$PLATFORMS_ARG")
[ "$NO_PUSH" -eq 1 ] && ARGS+=(--no-push)
[ "$DRY_RUN" -eq 1 ] && ARGS+=(--dry-run)

set +e
SWR_REGION="$SWR_REGION" \
SWR_REGISTRY="$SWR_REGISTRY" \
SWR_ORGANIZATION="$SWR_ORGANIZATION" \
TAG="$TAG_VALUE" \
PLATFORMS="$PLATFORMS_VALUE" \
  bash "$BUILD_SCRIPT" --tag "$TAG_VALUE" ${ARGS[@]+"${ARGS[@]}"}
status=$?
set -e

[ "$status" -eq 0 ] || die "镜像构建/推送失败（exit=${status}）。上面的日志里有具体失败组件"

# ---------------------------------------------------------------------------
# 5. 输出镜像清单，供后续流水线步骤（helm 部署）直接消费
# ---------------------------------------------------------------------------
COMPONENTS_DONE="${ONLY_ARG:-server,web,scanner,github-sync}"
images_file="${CODEARTS_IMAGES_FILE:-${REPO_ROOT}/dist/codearts-images.txt}"
if [ "$DRY_RUN" -eq 0 ]; then
  mkdir -p "$(dirname -- "$images_file")"
  : >"$images_file"
  IFS=',' read -r -a _comps <<<"$COMPONENTS_DONE"
  for component in "${_comps[@]}"; do
    case "$component" in
      server) repository="skillmall-server" ;;
      web) repository="skillmall-web" ;;
      scanner) repository="skillmall-scanner" ;;
      github-sync) repository="skillhub-github-sync" ;;
      *) continue ;;
    esac
    printf '%s/%s/%s:%s\n' "$SWR_REGISTRY" "$SWR_ORGANIZATION" "$repository" "$TAG_VALUE" >>"$images_file"
  done
  info ""
  info "镜像清单已写入：${images_file}"
  sed 's/^/  /' "$images_file"
  info ""
  info "提示：helm 部署时把 images.registry 设为 ${SWR_REGISTRY}/${SWR_ORGANIZATION}、images.tag 设为 ${TAG_VALUE}。"
fi
