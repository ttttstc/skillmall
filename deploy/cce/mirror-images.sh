#!/usr/bin/env bash
# =============================================================================
# SkillHub —— 第三方运行时镜像同步到 SWR（CCE）
#
# 本脚本不重复实现同步逻辑：它把 SWR 地址翻译成既有脚本要求的
# MIRROR_REGISTRY / MIRROR_NAMESPACE，再调用 scripts/mirror-runtime-images.sh
# （regctl 实现，会复制多平台 index 下全部可运行子清单）。
#
# 用法（可在任意目录执行）：
#   SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub ./deploy/cce/mirror-images.sh --dry-run
#   SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub ./deploy/cce/mirror-images.sh
#   SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub SWR_AK=... SWR_SK=... \
#     ./deploy/cce/mirror-images.sh --map deploy/cce/runtime-mirror-images.cce.txt
#
# 说明：
#   * 默认映射文件 deploy/cce/runtime-mirror-images.cce.txt，格式与
#     deploy/runtime-mirror-images.txt 一致（每行「源镜像 目标镜像」）。
#   * 国内节点直连 docker.io 不可靠（限流/概率失败），所以推荐本脚本的
#     「拉取 → 重打 tag → 推送 SWR」而不是让集群直连 Docker Hub。
#   * 镜像同步是**发布操作**（写入 SWR）。验收要比对目标 manifest digest 与平台列表，
#     不能只看退出码；多平台 index 会被重建，digest 可能与源不同。
#   * regctl 复用本机 docker 凭证（~/.docker/config.json）：需要时先 docker login SWR
#     （可给 SWR_AK/SWR_SK，密码是登录密钥 hex(HMAC-SHA256(key=SK, msg=AK))，需 openssl）。
#   * 不自动下载执行远程脚本：regctl 需自行按校验和安装（见 --help）。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
readonly SCRIPT_DIR REPO_ROOT

readonly MIRROR_HELPER="${REPO_ROOT}/scripts/mirror-runtime-images.sh"
readonly DEFAULT_MAPPING="${REPO_ROOT}/deploy/cce/runtime-mirror-images.cce.txt"

DRY_RUN=0
MAPPING_FILE=""
REGISTRY=""
NAMESPACE=""
SOURCES=()
TARGETS=()
DIGEST_WARNINGS=0

info() { printf '%s\n' "$*"; }
warn() { printf '警告: %s\n' "$*" >&2; }
die() { printf '错误: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
用法: mirror-images.sh [选项]

把第三方运行时镜像同步（拉取 → 重打 tag → 推送）到华为云 SWR：
  <registry>/<organization>/<目标镜像>

环境变量:
  SWR_REGION          必填（除非给 SWR_REGISTRY）。SWR 区域，例如 cn-north-4。
  SWR_ORGANIZATION    必填。SWR 组织（命名空间），例如 skillhub。
  SWR_REGISTRY        可选。覆盖仓库地址，默认 swr.<SWR_REGION>.myhuaweicloud.com。
  SWR_AK / SWR_SK     可选。需要在本脚本内 docker login SWR 时提供（regctl 会复用该凭证）。
                      密码是登录密钥 hex(HMAC-SHA256(key=SK, msg=AK))，需要 openssl，
                      不落盘、不回显；未提供时沿用本机已有 docker 凭证。

选项:
  --map <文件>        指定映射文件，默认 deploy/cce/runtime-mirror-images.cce.txt。
  --dry-run           只校验映射文件并打印将执行的命令，不登录、不拉取、不推送。
  -h, --help          显示本帮助。

依赖:
  * regctl：脚本不会自动下载执行。请按校验和固定安装，例如
      https://github.com/regclient/regclient/releases （regctl-<os>-<arch>，核对 SHA256）
      或 go install github.com/regclient/regclient/cmd/regctl@<固定版本>
    安装后确认：regctl version
  * python3：scripts/mirror-runtime-images.sh 用它选择 index 描述符。
  * docker login 凭证（见上）。
  * 集群拉取：CCE 每个命名空间会自动创建 default-secret（dockerconfigjson）用于拉取 SWR 镜像，
    它会轮换/过期；不要手工创建或复制 pull secret，Helm 侧 imagePullSecrets 指向它即可。

验收（发布操作不能只看退出码）:
  regctl manifest head <registry>/<org>/<目标镜像>
  docker buildx imagetools inspect <registry>/<org>/<目标镜像>   # 核对平台列表
  多平台 index 会被重建，目标 digest 可能与源不同；按 digest 引用时以实测值为准。

示例:
  SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub ./deploy/cce/mirror-images.sh --dry-run
  SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub ./deploy/cce/mirror-images.sh
  SWR_REGION=cn-north-4 SWR_ORGANIZATION=skillhub SWR_AK=<AK> SWR_SK=<SK> \
    ./deploy/cce/mirror-images.sh --map deploy/cce/runtime-mirror-images.cce.txt
EOF
}

require_command() { command -v "$1" >/dev/null 2>&1; }

regctl_hint() {
  cat >&2 <<'EOF'
未找到 regctl。镜像同步依赖 regctl（仓库发布流程使用按校验和固定的二进制），
请先安装，脚本不会自动下载执行远程脚本：
  * 下载对应平台二进制并核对发布页 SHA256：
      https://github.com/regclient/regclient/releases
  * 或从源码安装固定版本：
      go install github.com/regclient/regclient/cmd/regctl@<固定版本>
安装后确认：regctl version
EOF
}

python3_hint() {
  cat >&2 <<'EOF'
未找到 python3。scripts/mirror-runtime-images.sh 需要 python3 选择多平台 index 描述符，
请先安装 python3（WSL/Ubuntu: apt-get install python3；Git Bash 可用 Windows 版 Python 并确保 python3 在 PATH）。
EOF
}

parse_args() {
  while (($# > 0)); do
    case "$1" in
      --map)
        (($# >= 2)) || die "--map 需要一个文件路径"
        MAPPING_FILE="$2"
        shift 2
        ;;
      --map=*) MAPPING_FILE="${1#*=}"; shift ;;
      --dry-run) DRY_RUN=1; shift ;;
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
}

resolve_namespace() {
  [[ -n "${SWR_ORGANIZATION:-}" ]] || die "缺少 SWR_ORGANIZATION（SWR 组织名，例如 skillhub）"
  [[ "$SWR_ORGANIZATION" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
    || die "SWR_ORGANIZATION 只允许字母、数字与 . _ -（不能含 /）: ${SWR_ORGANIZATION}"
  NAMESPACE="$SWR_ORGANIZATION"
}

resolve_mapping_file() {
  local candidate
  if [[ -z "$MAPPING_FILE" ]]; then
    MAPPING_FILE="$DEFAULT_MAPPING"
  else
    candidate="$MAPPING_FILE"
    if [[ "$candidate" != /* && ! -f "$candidate" && -f "${REPO_ROOT}/${candidate}" ]]; then
      candidate="${REPO_ROOT}/${candidate}"
    fi
    MAPPING_FILE="$candidate"
  fi
  [[ -f "$MAPPING_FILE" ]] || die "找不到映射文件: ${MAPPING_FILE}"
}

# 解析并校验映射文件；只读检查，不访问网络。
validate_mapping() {
  local line line_no=0 source target extra seen=' ' duplicates=0 total=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_no=$((line_no + 1))
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*$ ]] && continue
    [[ "$line" =~ ^[[:space:]]*# ]] && continue

    read -r source target extra <<<"$line"
    [[ -n "${source:-}" ]] || die "映射文件第 ${line_no} 行无法解析: ${line}"
    [[ -n "${target:-}" ]] || die "映射文件第 ${line_no} 行缺少目标镜像: ${line}"
    [[ -z "${extra:-}" ]] || die "映射文件第 ${line_no} 行有多余列（只允许「源镜像 目标镜像」两列）: ${line}"

    if [[ "$seen" == *" ${target} "* ]]; then
      warn "映射文件第 ${line_no} 行目标重复: ${target}（后面的行会覆盖同名 tag）"
      duplicates=$((duplicates + 1))
    else
      seen+="${target} "
    fi

    if [[ "$source" != *"@sha256:"* ]]; then
      warn "第 ${line_no} 行源镜像未固定 digest（${source}）：tag 会漂移，发布不可复现；建议补 @sha256:<digest>"
      DIGEST_WARNINGS=$((DIGEST_WARNINGS + 1))
    fi

    SOURCES+=("$source")
    TARGETS+=("$target")
    total=$((total + 1))
  done <"$MAPPING_FILE"

  ((total > 0)) || die "映射文件没有任何有效条目: ${MAPPING_FILE}"
  info "映射文件: ${MAPPING_FILE}（${total} 条，其中 ${DIGEST_WARNINGS} 条未固定 digest）"
  if ((duplicates > 0)); then
    warn "有 ${duplicates} 条目标重名，请确认是否本意"
  fi
}

swr_login() {
  local ak="${SWR_AK:-}" sk="${SWR_SK:-}" login_password username
  if [[ -z "$ak" && -z "$sk" ]]; then
    info "未设置 SWR_AK/SWR_SK：regctl 复用本机已有 docker 凭证（需事先 docker login ${REGISTRY}）"
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

  if ! require_command openssl; then
    die "无法生成 SWR 登录密钥：缺少 openssl。请安装 openssl，或省略 SWR_AK/SWR_SK 并先手工 docker login ${REGISTRY}"
  fi
  login_password="$(printf '%s' "$ak" | openssl dgst -binary -sha256 -hmac "$sk" | od -An -vtx1 | tr -d ' \n')"
  [[ -n "$login_password" ]] || die "SWR 登录密钥派生结果为空：请检查 SWR_AK/SWR_SK"
  if ! printf '%s' "$login_password" | docker login -u "$username" --password-stdin "$REGISTRY" >/dev/null; then
    unset login_password
    die "docker login 失败（SWR 拒绝）。请核对 SWR_AK/SWR_SK、SWR_REGION 与组织 ${NAMESPACE} 的权限"
  fi
  unset login_password
  info "已登录 ${REGISTRY}（用户 ${SWR_REGION}@***，AK/SK 未回显）"
}

print_dry_run() {
  local i
  info "目标前缀: ${REGISTRY}/${NAMESPACE}"
  info "将同步（源 → 目标）:"
  for ((i = 0; i < ${#SOURCES[@]}; i++)); do
    info "  ${SOURCES[$i]}"
    info "    → ${REGISTRY}/${NAMESPACE}/${TARGETS[$i]}"
  done
  info ""
  swr_login
  info ""
  info "将执行:"
  info "  MIRROR_REGISTRY=\"${REGISTRY}\" MIRROR_NAMESPACE=\"${NAMESPACE}\" \\"
  info "    bash scripts/mirror-runtime-images.sh \"${MAPPING_FILE}\""
  info ""
  info "以上为 --dry-run 结果：未登录、未拉取、未推送。"
}

run_sync() {
  if ! require_command regctl; then
    regctl_hint
    die "缺少 regctl，无法执行镜像同步"
  fi
  if ! require_command python3; then
    python3_hint
    die "缺少 python3，无法执行镜像同步"
  fi
  [[ -f "$MIRROR_HELPER" ]] || die "找不到同步实现脚本: ${MIRROR_HELPER}"

  swr_login

  cd -- "$REPO_ROOT"
  info ""
  info "开始同步（regctl：复制 index 下全部可运行平台，跳过 attestation 子清单）"
  if ! MIRROR_REGISTRY="$REGISTRY" MIRROR_NAMESPACE="$NAMESPACE" \
      bash "$MIRROR_HELPER" "$MAPPING_FILE"; then
    die "镜像同步失败：见上面的 regctl 输出。常见原因：源镜像/平台不存在、无推送权限、目标仓库被占用或网络不可达"
  fi

  info ""
  info "同步完成。请做发布验收（不能只看退出码）："
}

print_verification() {
  local i
  for ((i = 0; i < ${#TARGETS[@]}; i++)); do
    info "  regctl manifest head ${REGISTRY}/${NAMESPACE}/${TARGETS[$i]}"
  done
  info "  docker buildx imagetools inspect <目标镜像>   # 与源对比平台列表"
  info "  多平台 index 会被重建，digest 可能与源不同；按 digest 引用时以实测值为准。"
}

main() {
  parse_args "$@"
  resolve_registry
  resolve_namespace
  resolve_mapping_file
  validate_mapping

  if ((DRY_RUN == 1)); then
    print_dry_run
    return 0
  fi

  run_sync
  print_verification
}

main "$@"
