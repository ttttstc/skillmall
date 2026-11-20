#!/usr/bin/env bash
# =============================================================================
# SkillHub —— Helm chart 打包（生成自包含 tgz 到 dist/）
#
# chart 依赖 Bitnami 子 chart（oci://registry-1.docker.io/bitnamicharts，
# postgresql 18.6.10 / redis 25.5.3，仓库未 vendor），必须先取到子 chart 才能
# 得到可离线安装的自包含包。两种取法：
#   * 在线：helm dependency build（按 Chart.lock 固定版本）
#   * 离线：--offline <dir>，把已下载的 postgresql-18.6.10.tgz / redis-25.5.3.tgz
#     复制进 charts/skillhub/charts/ 再打包，规避 docker.io 不可达
#
# 本脚本**不**执行 helm push / OCI 推送：发布到仓库必须走既有发布流程并确认目标。
#
# 用法（可在任意目录执行）：
#   ./deploy/cce/package-chart.sh
#   ./deploy/cce/package-chart.sh --offline ./.cache/bitnami-charts
#   ./deploy/cce/package-chart.sh --dry-run
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." >/dev/null 2>&1 && pwd)"
readonly SCRIPT_DIR REPO_ROOT

readonly CHART_DIR="${REPO_ROOT}/charts/skillhub"
readonly CHART_YAML="${CHART_DIR}/Chart.yaml"
readonly CHART_LOCK="${CHART_DIR}/Chart.lock"
readonly CHART_DEPS_DIR="${CHART_DIR}/charts"

DIST_DIR="${REPO_ROOT}/dist"
OFFLINE_DIR=""
DRY_RUN=0
KEEP_DEPS=0
NAME=""
VERSION=""
CREATED_DEPS=()

info() { printf '%s\n' "$*"; }
warn() { printf '警告: %s\n' "$*" >&2; }
die() { printf '错误: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
用法: package-chart.sh [选项]

把 charts/skillhub 打包成自包含的 Helm chart tgz，输出到 dist/（默认）。
打包前会确保 Bitnami 子 chart（postgresql 18.6.10 / redis 25.5.3）已就位；
缺失时拒绝打包，避免产出缺少依赖、安装即失败的 chart。

选项:
  --offline <目录>    离线模式：从 <目录> 复制已下载的子 chart 包
                      （postgresql-18.6.10.tgz、redis-25.5.3.tgz）到
                      charts/skillhub/charts/ 后再打包，不访问 docker.io。
  --dist <目录>       输出目录，默认 dist/（相对于当前目录，会被解析成绝对路径）。
  --dry-run           只打印将执行的命令与预期产物，不下载、不复制、不打包。
  --keep-deps         离线模式打包后保留复制进 charts/skillhub/charts/ 的 tgz
                      （默认会清理本次复制且原本不存在的文件）。
  -h, --help          显示本帮助。

典型流程:
  # 在线（能访问 registry-1.docker.io）
  ./deploy/cce/package-chart.sh

  # 出口受限：先在可上网的机器拉取子 chart
  helm pull oci://registry-1.docker.io/bitnamicharts/postgresql --version 18.6.10 -d ./offline
  helm pull oci://registry-1.docker.io/bitnamicharts/redis --version 25.5.3 -d ./offline
  ./deploy/cce/package-chart.sh --offline ./offline

说明:
  * 依赖版本以 charts/skillhub/Chart.lock 为准（缺失时退回 Chart.yaml）。
  * 子 chart 包会落到 charts/skillhub/charts/*.tgz，该路径已在 .gitignore 中。
  * 不执行 helm push；OCI 发布属于单独的发布动作，需要明确目标与授权。
EOF
}

chart_name() { awk '/^name:/ {print $2; exit}' "$CHART_YAML"; }
chart_version() { awk '/^version:/ {print $2; exit}' "$CHART_YAML"; }

# 输出「名字 版本」两列，优先用 Chart.lock（锁定版本），否则退回 Chart.yaml 的 dependencies。
chart_dependencies() {
  if [[ -f "$CHART_LOCK" ]]; then
    awk '
      /^- name:/ { name = $3 }
      /^  version:/ { if (name != "") { v = $2; gsub(/"/, "", v); print name, v } }
    ' "$CHART_LOCK"
  else
    awk '
      /^  - name:/ { name = $3 }
      /^    version:/ { if (name != "") { v = $2; gsub(/"/, "", v); print name, v } }
    ' "$CHART_YAML"
  fi
}

required_list() {
  chart_dependencies | awk '{ printf "%s-%s.tgz ", $1, $2 }'
}

parse_args() {
  while (($# > 0)); do
    case "$1" in
      --offline)
        (($# >= 2)) || die "--offline 需要一个目录参数"
        OFFLINE_DIR="$2"
        shift 2
        ;;
      --offline=*) OFFLINE_DIR="${1#*=}"; shift ;;
      --dist)
        (($# >= 2)) || die "--dist 需要一个目录参数"
        DIST_DIR="$2"
        shift 2
        ;;
      --dist=*) DIST_DIR="${1#*=}"; shift ;;
      --dry-run) DRY_RUN=1; shift ;;
      --keep-deps) KEEP_DEPS=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "未知参数: $1（用 --help 查看用法）" ;;
    esac
  done
}

resolve_paths() {
  DIST_DIR="${DIST_DIR%/}"
  [[ -n "$DIST_DIR" ]] || die "--dist 不能为空"
  if [[ "$DIST_DIR" != /* ]]; then
    DIST_DIR="${PWD}/${DIST_DIR}"
  fi
  if [[ -n "$OFFLINE_DIR" ]]; then
    OFFLINE_DIR="${OFFLINE_DIR%/}"
    [[ -n "$OFFLINE_DIR" ]] || die "--offline 不能为空"
    if [[ "$OFFLINE_DIR" != /* ]]; then
      OFFLINE_DIR="${PWD}/${OFFLINE_DIR}"
    fi
  fi
}

require_helm() {
  if ! command -v helm >/dev/null 2>&1; then
    cat >&2 <<'EOF'
错误: 未找到 helm 命令，无法打包 chart。

请安装 Helm 3 后重试：
  * Windows (winget): winget install Helm.Helm
  * Windows (scoop): scoop install helm
  * macOS: brew install helm
  * Linux / WSL: https://helm.sh/docs/intro/install/ （下载二进制后核对 SHA256）
确认安装：helm version
EOF
    exit 1
  fi
  local v
  v="$(helm version --short 2>/dev/null || helm version 2>/dev/null || true)"
  info "helm: ${v:-已找到}"
}

prepare_offline_deps() {
  local name version src dst
  [[ -d "$OFFLINE_DIR" ]] || die "--offline 指向的目录不存在: ${OFFLINE_DIR}"
  info ""
  info "离线模式：从 ${OFFLINE_DIR} 复制子 chart 到 ${CHART_DEPS_DIR}/"
  if ((DRY_RUN == 0)); then
    mkdir -p -- "$CHART_DEPS_DIR"
  fi
  while read -r name version; do
    [[ -n "${name:-}" ]] || continue
    src="${OFFLINE_DIR}/${name}-${version}.tgz"
    dst="${CHART_DEPS_DIR}/${name}-${version}.tgz"
    if [[ ! -f "$src" ]]; then
      die "离线目录缺少 ${name}-${version}.tgz。需要这些文件: $(required_list)
请在可访问网络的环境下载后重试，例如：
  helm pull oci://registry-1.docker.io/bitnamicharts/${name} --version ${version} -d ${OFFLINE_DIR}"
    fi
    if [[ -f "$dst" ]]; then
      info "  已存在，跳过: charts/${name}-${version}.tgz"
      continue
    fi
    if ((DRY_RUN == 1)); then
      info "  [dry-run] cp ${src} -> charts/${name}-${version}.tgz"
      continue
    fi
    cp -- "$src" "$dst"
    CREATED_DEPS+=("$dst")
    info "  已复制: charts/${name}-${version}.tgz"
  done < <(chart_dependencies)
}

run_dependency_build() {
  if ((DRY_RUN == 1)); then
    info ""
    info "[dry-run] helm dependency build \"${CHART_DIR}\""
    return 0
  fi
  info ""
  info "执行: helm dependency build ${CHART_DIR}（按 Chart.lock 固定版本）"
  if ! helm dependency build "$CHART_DIR"; then
    {
      printf '\n错误: helm dependency build 失败。可执行排查：\n'
      printf '  1. 确认 registry-1.docker.io（Bitnami OCI 仓库）可达，国内网络常见超时/限流\n'
      printf '  2. 需要代理时：HTTPS_PROXY=<proxy> helm dependency build %s\n' "$CHART_DIR"
      printf '  3. 已有子 chart 包时改用离线模式：%s --offline <目录>\n' "$(basename -- "${BASH_SOURCE[0]}")"
      printf '     目录内需要：%s\n' "$(required_list)"
      printf '  4. 核对 Chart.lock 与 Chart.yaml 的依赖版本是否一致\n\n'
    } >&2
    exit 1
  fi
}

verify_deps() {
  local name version missing=0
  while read -r name version; do
    [[ -n "${name:-}" ]] || continue
    if [[ ! -f "${CHART_DEPS_DIR}/${name}-${version}.tgz" ]]; then
      printf '缺少子 chart: charts/%s-%s.tgz\n' "$name" "$version" >&2
      missing=1
    fi
  done < <(chart_dependencies)
  ((missing == 0))
}

package_chart() {
  local output expected
  expected="${DIST_DIR}/${NAME}-${VERSION}.tgz"
  mkdir -p -- "$DIST_DIR"
  info ""
  info "执行: helm package ${CHART_DIR} --destination ${DIST_DIR}"
  if ! output="$(helm package "$CHART_DIR" --destination "$DIST_DIR" 2>&1)"; then
    printf '%s\n' "$output" >&2
    die "helm package 失败（见上面的 helm 输出）"
  fi
  printf '%s\n' "$output"
  [[ -f "$expected" ]] || die "helm package 结束但未找到预期产物: ${expected}"
  info "产物: ${expected}（$(du -h -- "$expected" | awk '{print $1}')）"
}

cleanup_offline_deps() {
  local f
  ((${#CREATED_DEPS[@]} > 0)) || return 0
  if ((KEEP_DEPS == 1)); then
    info "保留本次复制的 ${#CREATED_DEPS[@]} 个子 chart（--keep-deps）"
    return 0
  fi
  for f in "${CREATED_DEPS[@]}"; do
    rm -f -- "$f"
  done
  info "已清理本次复制的 ${#CREATED_DEPS[@]} 个子 chart（charts/*.tgz 已在 .gitignore，--keep-deps 可保留）"
}

main() {
  parse_args "$@"
  require_helm
  [[ -f "$CHART_YAML" ]] || die "找不到 chart 定义: ${CHART_YAML}"
  if [[ ! -f "$CHART_LOCK" ]]; then
    warn "缺少 Chart.lock，将按 Chart.yaml 的依赖声明打包（版本可能漂移）"
  fi
  resolve_paths

  NAME="$(chart_name)"
  VERSION="$(chart_version)"
  [[ -n "$NAME" ]] || die "无法从 Chart.yaml 读取 name"
  [[ -n "$VERSION" ]] || die "无法从 Chart.yaml 读取 version"

  local -a deps=()
  mapfile -t deps < <(chart_dependencies)
  ((${#deps[@]} > 0)) || die "未能解析出 chart 依赖（Chart.lock / Chart.yaml 的 dependencies）"

  info "Chart: ${CHART_DIR}（${NAME}-${VERSION}）"
  info "子 chart 依赖:"
  local dep
  for dep in "${deps[@]}"; do
    info "  ${dep}"
  done

  if [[ -n "$OFFLINE_DIR" ]]; then
    prepare_offline_deps
  else
    run_dependency_build
  fi

  if ((DRY_RUN == 1)); then
    info ""
    info "[dry-run] helm package \"${CHART_DIR}\" --destination \"${DIST_DIR}\""
    info "预期产物: ${DIST_DIR}/${NAME}-${VERSION}.tgz"
    info ""
    info "以上为 --dry-run：未下载依赖、未复制、未打包、未推送（本脚本从不推送 OCI）。"
    return 0
  fi

  if ! verify_deps; then
    die "子 chart 依赖不完整，拒绝打包（避免生成安装即失败的 chart）。在线模式请检查 helm dependency build 输出，离线模式请检查 --offline 目录"
  fi

  package_chart
  cleanup_offline_deps

  info ""
  info "完成。产物路径: ${DIST_DIR}/${NAME}-${VERSION}.tgz"
  info "本脚本不执行 helm push / OCI 推送；发布到仓库需走既有发布流程并确认目标与授权。"
}

main "$@"
