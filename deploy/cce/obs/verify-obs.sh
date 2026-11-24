#!/usr/bin/env bash
# =============================================================================
# SkillHub —— OBS 连通性与最小权限自检（部署后/上线前跑一次）
#
# 【验证什么】
#   1. 小对象往返：写入 4KB 随机内容 → 读回 → **逐字节比对** → 列出前缀
#   2. 删除：只删本次自己的对象 → 确认已删除
#   3. 可选 --check-policy：
#      * 匿名读被拒绝：不带任何凭证直接 GET 对象 URL，期望 403
#        （证明桶是私有的；应用只通过预签名 URL 授权下载）
#      * 越权桶被拒绝：用同一凭证访问 OBS_FORBIDDEN_BUCKET，期望被拒绝
#        （证明 IAM 策略把权限限定在目标桶，见 iam-policy-skillhub-app.json）
#
# 【安全边界（很重要）】
#   * 只在 skillhub-diagnostics/<本次生成的 uuid>/ 前缀下写对象；
#     绝不枚举、绝不清理整桶，也绝不删除别人的对象。
#   * 无论成功失败，退出前都尝试清理本次 uuid 前缀下的残留对象（trap）。
#   * 不打印 AK/SK/token，也不打印任何带签名的 URL（本脚本只访问无签名的
#     私有对象 URL，用于验证"匿名访问必须被拒绝"）。
#   * 不验证预签名 URL 往返：那是应用侧 OBS HMAC-SHA1 签名的产物，
#     本脚本不复制该签名逻辑（见文末"未覆盖项"）。
#
# 【为什么用 obsutil 而不是 curl 做读写】
#   SkillHub 的 OBS 适配器走 OBS 原生协议（HMAC-SHA1 + 虚拟主机寻址），
#   而 obsutil 是华为云官方同协议客户端，用它做管理面自检最接近真实链路，
#   又不用在脚本里重新实现签名。匿名访问/越权访问这两个"负面"检查才用 curl。
#
# 【obsutil 参数说明】
#   用到的形态：cp <src> <obs://bucket/key> [-f]、ls <obs://...> [-limit=]、
#   rm <obs://...> [-r] [-f]。不同版本略有差异，失败时会打印原始错误，
#   并提示用 `obsutil help cp|ls|rm` 核对。
#
# 【用法】
#   export OBS_ENDPOINT=https://obs.cn-north-4.myhuaweicloud.com
#   export OBS_REGION=cn-north-4
#   export OBS_BUCKET=skillhub-prod-obs
#   export OBS_AK=... OBS_SK=...
#   ./deploy/cce/obs/verify-obs.sh                      # 往返自检
#   ./deploy/cce/obs/verify-obs.sh --check-policy       # 往返 + 权限负面检查
#   OBS_FORBIDDEN_BUCKET=other-bucket ./deploy/cce/obs/verify-obs.sh --check-policy
#   ./deploy/cce/obs/verify-obs.sh --dry-run            # 只打印将执行的命令
# =============================================================================

set -euo pipefail

# 固定 C locale（与 setup-bucket.sh 同理由：避免 [[ =~ ]] 的范围匹配受
# 中文 locale 的排序规则影响；本脚本不做本地化排序）
export LC_ALL=C

readonly SCRIPT_NAME="$(basename -- "${BASH_SOURCE[0]}")"
readonly DIAG_PREFIX="skillhub-diagnostics/"
readonly PAYLOAD_NAME="payload.bin"
readonly PAYLOAD_BYTES=4096

OBS_ENDPOINT="${OBS_ENDPOINT:-}"
OBS_REGION="${OBS_REGION:-}"
OBS_BUCKET="${OBS_BUCKET:-}"
OBS_AK="${OBS_AK:-}"
OBS_SK="${OBS_SK:-}"
OBS_SESSION_TOKEN="${OBS_SESSION_TOKEN:-}"
OBSUTIL_BIN="${OBSUTIL_BIN:-obsutil}"
OBS_FORBIDDEN_BUCKET="${OBS_FORBIDDEN_BUCKET:-}"

DRY_RUN=0
CHECK_POLICY=0
CREDS_MODE="isolated"

ENDPOINT_HOST=""
BUCKET_HOST=""
WORKDIR=""
ISOLATED_HOME=""
CRED_DISPLAY=""
OBSUTIL_CRED_ARGS=()
ENDPOINT_ARGS=()
RUN_ID=""
RUN_PREFIX=""
KEY=""
LOCAL_UP=""
LOCAL_DOWN=""
LAST_OUT=""

PASSED=()
FAILED=()
SKIPPED=()

info() { printf '%s\n' "$*"; }
warn() { printf '警告: %s\n' "$*" >&2; }
die() { printf '错误: %s\n' "$*" >&2; exit 1; }
pass() { PASSED+=("$1"); printf '  [PASS] %s\n' "$1"; }
fail() { FAILED+=("$1"); printf '  [FAIL] %s\n' "$1"; }
skip() { SKIPPED+=("$1"); printf '  [SKIP] %s\n' "$1"; }

usage() {
  cat <<'EOF'
用法: verify-obs.sh [选项]

对 SkillHub 使用的 OBS 桶做连通性与最小权限自检。只操作
skillhub-diagnostics/<本次 uuid>/ 前缀，退出前清理本次写入的对象。

环境变量:
  OBS_ENDPOINT    OBS 端点，如 https://obs.cn-north-4.myhuaweicloud.com
  OBS_REGION     区域（用于拼虚拟主机式对象 URL）。留空时从 endpoint 推导。
  OBS_BUCKET     桶名
  OBS_AK         应用凭证 AK（建议就用应用实际使用的那个 AK，验证的才是真权限）
  OBS_SK         应用凭证 SK
  OBS_SESSION_TOKEN 可选：临时凭证（IAM 委托/STS）的 security token
  OBSUTIL_BIN    obsutil 可执行文件，默认 obsutil
  OBS_FORBIDDEN_BUCKET 可选：--check-policy 时额外验证"该桶必须不可访问"，
                        用于确认 IAM 策略没有放开到别的桶

选项:
  --check-policy  额外做两个负面检查：
                  1) 匿名 GET 本次写入的对象 URL 必须被拒绝（期望 403）
                  2) 若给了 OBS_FORBIDDEN_BUCKET，该桶必须不可访问
  --dry-run       只打印将执行的命令与期望结果，不写、不读、不删任何对象
                  （本机没有 obsutil 也能跑通）
  --creds=isolated|cli  凭证方式，默认 isolated（临时 HOME + 0600 配置，退出即删）；
                  cli 用 -i/-k/-t 参数，不落地密钥文件
  -h, --help      显示本帮助

退出码:
  0  全部检查通过（或 --dry-run）
  1  有检查失败（会逐项打印 PASS/FAIL）

覆盖与未覆盖:
  覆盖：写/读/删/列（对象面）、匿名读被拒、越权桶被拒。
  未覆盖：预签名 URL（PT10M）的生成与过期行为 —— 那是应用侧 OBS 原生签名
          （HMAC-SHA1 + x-obs-security-token）的产物，本脚本不复制签名逻辑；
          如需验证请用应用或 SDK 生成 URL 后自行 curl。
EOF
}

parse_args() {
  while (($# > 0)); do
    case "$1" in
      --check-policy) CHECK_POLICY=1; shift ;;
      --dry-run) DRY_RUN=1; shift ;;
      --creds)
        (($# >= 2)) || die "--creds 需要一个参数（isolated 或 cli）"
        CREDS_MODE="$2"; shift 2
        ;;
      --creds=*) CREDS_MODE="${1#*=}"; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "未知参数: $1（用 --help 查看用法）" ;;
    esac
  done
  case "$CREDS_MODE" in
    isolated|cli) ;;
    *) die "--creds 只支持 isolated 或 cli，收到: ${CREDS_MODE}" ;;
  esac
}

cleanup() {
  local rc=$?
  # 只清理本次 uuid 前缀；条件严格限定，避免误删
  if ((DRY_RUN == 0)) && [[ -n "$RUN_PREFIX" && -n "$RUN_ID" ]] && command -v "$OBSUTIL_BIN" >/dev/null 2>&1; then
    set +e
    run_obsutil rm "obs://${OBS_BUCKET}/${RUN_PREFIX}" -r -f >/dev/null 2>&1
    set -e
  fi
  if [[ -n "$WORKDIR" && -d "$WORKDIR" ]]; then
    rm -rf -- "$WORKDIR"
  fi
  return $rc
}

# ---- 基础校验 ---------------------------------------------------------------
setup_env() {
  [[ -n "$OBS_BUCKET" ]] || die "缺少环境变量 OBS_BUCKET"
  [[ -n "$OBS_ENDPOINT" ]] || die "缺少环境变量 OBS_ENDPOINT（例如 https://obs.cn-north-4.myhuaweicloud.com）"

  ENDPOINT_HOST="$OBS_ENDPOINT"
  ENDPOINT_HOST="${ENDPOINT_HOST#http://}"
  ENDPOINT_HOST="${ENDPOINT_HOST#https://}"
  ENDPOINT_HOST="${ENDPOINT_HOST%%/*}"
  [[ -n "$ENDPOINT_HOST" ]] || die "OBS_ENDPOINT 解析后为空: ${OBS_ENDPOINT}"

  if [[ -z "$OBS_REGION" && "$ENDPOINT_HOST" =~ ^obs\.([a-z0-9-]+)\.myhuaweicloud\.com$ ]]; then
    OBS_REGION="${BASH_REMATCH[1]}"
  fi
  if [[ -z "$OBS_REGION" ]]; then
    warn "未确定区域（OBS_REGION 为空且无法从 endpoint 推导）；--check-policy 的匿名读检查将跳过"
  fi

  if [[ -z "$OBS_AK" || -z "$OBS_SK" ]]; then
    ((DRY_RUN == 1)) || die "缺少 OBS_AK / OBS_SK（本脚本需要用真实凭证做往返自检）"
  else
    info "AK: ${OBS_AK:0:4}****（已脱敏）"
  fi

  # 虚拟主机式寻址：<bucket>.obs.<region>.myhuaweicloud.com（与适配器一致）
  BUCKET_HOST="${OBS_BUCKET}.${ENDPOINT_HOST}"
  info "桶: ${OBS_BUCKET}"
  info "端点: ${ENDPOINT_HOST}"
  info "区域: ${OBS_REGION:-<未确定>}"
  info "对象 URL 形态: https://${BUCKET_HOST}/<key>"
}

require_obsutil() {
  if ! command -v "$OBSUTIL_BIN" >/dev/null 2>&1 && [[ ! -x "$OBSUTIL_BIN" ]]; then
    ((DRY_RUN == 1)) && { warn "未找到 obsutil（${OBSUTIL_BIN}）—— --dry-run 不需要它"; return 0; }
    cat >&2 <<EOF
错误: 未找到 obsutil（${OBSUTIL_BIN}）。
安装方式见同目录 README.md，或用 OBSUTIL_BIN=/path/to/obsutil 指定。
EOF
    exit 1
  fi
  local v
  v="$(run_obsutil version 2>&1 | tr '\n' ' ' || true)"
  info "obsutil: ${v:-已找到（${OBSUTIL_BIN}）}"
}

run_obsutil() {
  if [[ -n "$ISOLATED_HOME" ]]; then
    env HOME="$ISOLATED_HOME" USERPROFILE="$ISOLATED_HOME" "$OBSUTIL_BIN" "$@"
  else
    "$OBSUTIL_BIN" "$@"
  fi
}

redact() {
  local a
  local -a out=()
  for a in "$@"; do
    case "$a" in
      -i=*|-k=*|-t=*) out+=("${a:0:3}***") ;;
      *) out+=("$a") ;;
    esac
  done
  printf '%s' "${out[*]}"
}

obs_run() {
  local -a full=("$@")
  printf '  $ %s %s%s\n' "$OBSUTIL_BIN" "$(redact "${full[@]}")" "${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
  if ((DRY_RUN == 1)); then
    info "    [dry-run] 未执行"
    return 0
  fi
  local out rc
  set +e
  out="$(run_obsutil "${full[@]}" "${OBSUTIL_CRED_ARGS[@]}" "${ENDPOINT_ARGS[@]}" 2>&1)"
  rc=$?
  set -e
  LAST_OUT="$out"
  if [[ -n "$out" ]]; then
    printf '%s\n' "$out" | sed 's/^/    | /'
  fi
  return $rc
}

# ---- 凭证 -------------------------------------------------------------------
prepare_credentials() {
  isolate_home_if_needed() {
    [[ -n "$ISOLATED_HOME" ]] && return 0
    mkdir -p -- "${WORKDIR}/home"
    chmod 700 -- "${WORKDIR}/home" 2>/dev/null || true
    ISOLATED_HOME="${WORKDIR}/home"
  }

  if ((DRY_RUN == 1)); then
    # dry-run 绝不落地任何含密钥的文件
    case "$CREDS_MODE" in
      cli) CRED_DISPLAY="[-i=*** -k=***]" ;;
      *) CRED_DISPLAY="[env HOME=<临时目录>（0600 .obsutilconfig）]" ;;
    esac
    info "凭证方式: dry-run 不写任何凭证文件（真实执行时按 --creds 处理）"
    if [[ -n "$ENDPOINT_HOST" ]]; then
      ENDPOINT_ARGS=("-e=${ENDPOINT_HOST}")
    fi
    return 0
  fi

  case "$CREDS_MODE" in
    cli)
      local -a args=("-i=${OBS_AK}" "-k=${OBS_SK}")
      if [[ -n "$OBS_SESSION_TOKEN" ]]; then
        args+=("-t=${OBS_SESSION_TOKEN}")
      fi
      OBSUTIL_CRED_ARGS=("${args[@]}")
      CRED_DISPLAY="[-i=*** -k=***]"
      info "凭证方式: 命令行传参（不落地文件）"
      ;;
    isolated)
      isolate_home_if_needed
      local path="${ISOLATED_HOME}/.obsutilconfig" old_umask
      old_umask="$(umask)"
      umask 077
      {
        printf 'ak=%s\n' "$OBS_AK"
        printf 'sk=%s\n' "$OBS_SK"
        if [[ -n "$OBS_SESSION_TOKEN" ]]; then
          printf 'token=%s\n' "$OBS_SESSION_TOKEN"
        fi
        printf 'endpoint=%s\n' "$ENDPOINT_HOST"
      } >"$path"
      umask "$old_umask"
      chmod 600 -- "$path" 2>/dev/null || true
      local mode
      mode="$(stat -c '%a' "$path" 2>/dev/null || echo '')"
      if [[ -n "$mode" && "$mode" != "600" ]]; then
        warn "配置文件权限为 ${mode}（期望 600）：当前文件系统可能不支持 Unix 权限，"
        warn "  在此类平台请改用 --creds=cli（密钥不落地为文件）。"
      fi
      CRED_DISPLAY="[env HOME=<临时目录>（0600 .obsutilconfig）]"
      info "凭证方式: 临时 HOME 隔离（退出即删；不写真实 ~/.obsutilconfig）"
      ;;
  esac

  if [[ -n "$ENDPOINT_HOST" ]]; then
    ENDPOINT_ARGS=("-e=${ENDPOINT_HOST}")
  fi
}

# ---- 工具 -------------------------------------------------------------------
new_uuid() {
  if [[ -r /proc/sys/kernel/random/uuid ]]; then
    cat /proc/sys/kernel/random/uuid
    return 0
  fi
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
    return 0
  fi
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 16
    return 0
  fi
  od -An -tx1 -N16 /dev/urandom | tr -d ' \n'
}

# ---- 检查项 -----------------------------------------------------------------
check_upload() {
  info ""
  info "== 检查 1：写入 ${PAYLOAD_BYTES} 字节随机对象"
  info "  本地文件: ${LOCAL_UP}"
  info "  对象: obs://${OBS_BUCKET}/${KEY}"
  if ((DRY_RUN == 1)); then
    info "  [dry-run] 生成随机内容: head -c ${PAYLOAD_BYTES} /dev/urandom > ${LOCAL_UP}"
    info "  [dry-run] 上传: $OBSUTIL_BIN cp ${LOCAL_UP} obs://${OBS_BUCKET}/${KEY} -f${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
    info "  期望: 退出码 0"
    return 0
  fi
  if obs_run cp "$LOCAL_UP" "obs://${OBS_BUCKET}/${KEY}" -f; then
    pass "写入对象成功"
  else
    fail "写入对象失败（检查写权限 obs:object:PutObject / 桶是否存在 / 端点区域）"
    return 1
  fi
}

check_download_and_compare() {
  info ""
  info "== 检查 2：读回并逐字节比对"
  if ((DRY_RUN == 1)); then
    info "  [dry-run] 下载: $OBSUTIL_BIN cp obs://${OBS_BUCKET}/${KEY} ${LOCAL_DOWN} -f"
    info "  [dry-run] 比对: cmp -s ${LOCAL_UP} ${LOCAL_DOWN}（不一致则失败）"
    info "  期望: 内容完全一致，sha256 相同"
    return 0
  fi
  if ! obs_run cp "obs://${OBS_BUCKET}/${KEY}" "$LOCAL_DOWN" -f; then
    fail "读回对象失败（检查读权限 obs:object:GetObject）"
    return 1
  fi
  local up_sum down_sum
  if command -v sha256sum >/dev/null 2>&1; then
    up_sum="$(sha256sum "$LOCAL_UP" | awk '{print $1}')"
    down_sum="$(sha256sum "$LOCAL_DOWN" | awk '{print $1}')"
    info "  sha256(本地写入) = ${up_sum}"
    info "  sha256(读回内容) = ${down_sum}"
    if [[ "$up_sum" == "$down_sum" ]]; then
      pass "读回内容与写入逐字节一致（sha256 相同）"
    else
      fail "读回内容与写入不一致（sha256 不同）"
      return 1
    fi
  else
    if cmp -s "$LOCAL_UP" "$LOCAL_DOWN"; then
      pass "读回内容与写入逐字节一致（cmp 相同）"
    else
      fail "读回内容与写入不一致（cmp 不同）"
      return 1
    fi
  fi
  local size
  size="$(wc -c <"$LOCAL_DOWN" | tr -d ' ')"
  if [[ "$size" == "$PAYLOAD_BYTES" ]]; then
    pass "读回长度正确（${size} 字节）"
  else
    fail "读回长度异常：${size} 字节，期望 ${PAYLOAD_BYTES}"
    return 1
  fi
}

check_list_prefix() {
  info ""
  info "== 检查 3：列出本次前缀"
  info "  前缀: obs://${OBS_BUCKET}/${RUN_PREFIX}"
  if ((DRY_RUN == 1)); then
    info "  [dry-run] $OBSUTIL_BIN ls obs://${OBS_BUCKET}/${RUN_PREFIX} -limit=100${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
    info "  期望: 输出包含 ${PAYLOAD_NAME}"
    return 0
  fi
  if ! obs_run ls "obs://${OBS_BUCKET}/${RUN_PREFIX}" -limit=100; then
    fail "列出前缀失败（检查 obs:bucket:ListBucket 权限）"
    return 1
  fi
  if printf '%s' "$LAST_OUT" | grep -q -- "$PAYLOAD_NAME"; then
    pass "列出前缀可见刚写入的对象"
  else
    fail "列出前缀未看到 ${PAYLOAD_NAME}（ListBucket 权限或前缀不一致）"
    return 1
  fi
}

check_anonymous_denied() {
  ((CHECK_POLICY == 1)) || return 0
  info ""
  info "== 检查 4：匿名读必须被拒绝（--check-policy）"

  if [[ -z "$OBS_REGION" ]]; then
    skip "无法确定区域，跳过匿名读检查（请设置 OBS_REGION）"
    return 0
  fi

  local url="https://${BUCKET_HOST}/${KEY}"
  if ((DRY_RUN == 1)); then
    info "  [dry-run] 匿名 GET: curl -s -o /dev/null -w '%{http_code}' --max-time 15 ${url}"
    info "  期望: 403（桶私有）；200 表示桶/对象可匿名读 → 严重问题"
    return 0
  fi

  local code
  set +e
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$url" 2>/dev/null)"
  local rc=$?
  set -e
  if [[ $rc -ne 0 || -z "$code" ]]; then
    fail "匿名请求未得到 HTTP 响应（curl 退出码 ${rc}）；请检查网络/域名解析，不要据此判断桶是否私有"
    return 1
  fi
  info "  匿名 GET ${url} → HTTP ${code}"
  case "$code" in
    403) pass "匿名读被拒绝（403），桶是私有的" ;;
    401) pass "匿名读被拒绝（401），桶是私有的" ;;
    200) fail "匿名读返回 200：对象可公开读取！请把桶 ACL/桶策略改回私有" ; return 1 ;;
    404) fail "匿名请求返回 404：可能域名/区域/对象名不对，无法据此判断私有性" ; return 1 ;;
    *) fail "匿名请求返回 HTTP ${code}（期望 403）" ; return 1 ;;
  esac
}

check_forbidden_bucket() {
  ((CHECK_POLICY == 1)) || return 0
  info ""
  info "== 检查 5：越权桶必须不可访问（--check-policy + OBS_FORBIDDEN_BUCKET）"

  if [[ -z "$OBS_FORBIDDEN_BUCKET" ]]; then
    skip "未提供 OBS_FORBIDDEN_BUCKET，跳过越权检查"
    return 0
  fi
  if [[ "$OBS_FORBIDDEN_BUCKET" == "$OBS_BUCKET" ]]; then
    fail "OBS_FORBIDDEN_BUCKET 与 OBS_BUCKET 相同，检查无意义"
    return 1
  fi
  if ((DRY_RUN == 1)); then
    info "  [dry-run] $OBSUTIL_BIN ls obs://${OBS_FORBIDDEN_BUCKET} -limit=1${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
    info "  期望: 非 0 退出（403/404）—— 说明凭证没有越权"
    return 0
  fi
  local rc=0
  set +e
  obs_run ls "obs://${OBS_FORBIDDEN_BUCKET}" -limit=1
  rc=$?
  set -e
  if [[ $rc -eq 0 ]]; then
    fail "能访问 obs://${OBS_FORBIDDEN_BUCKET}：IAM 策略过于宽松，应只允许目标桶 ${OBS_BUCKET}"
    return 1
  fi
  if printf '%s' "$LAST_OUT" | grep -qiE '403|access ?denied|forbidden'; then
    pass "越权桶被拒绝（403），凭证权限被限定在目标桶"
  elif printf '%s' "$LAST_OUT" | grep -qiE '404|nosuchbucket|not ?exist'; then
    pass "越权桶不可访问（404/桶不存在）—— 能证明无法越权访问该桶"
  else
    fail "访问越权桶失败，但原因不像权限拒绝（见上方输出），无法确认是最小权限在生效"
    return 1
  fi
}

check_delete() {
  info ""
  info "== 检查 6：删除本次对象并确认已删除"
  if ((DRY_RUN == 1)); then
    info "  [dry-run] $OBSUTIL_BIN rm obs://${OBS_BUCKET}/${KEY} -f${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
    info "  [dry-run] 确认: $OBSUTIL_BIN ls obs://${OBS_BUCKET}/${RUN_PREFIX} -limit=100 后不带 ${PAYLOAD_NAME}"
    info "  期望: 删除成功，再列前缀看不到该对象"
    return 0
  fi
  if ! obs_run rm "obs://${OBS_BUCKET}/${KEY}" -f; then
    fail "删除对象失败（检查 obs:object:DeleteObject）"
    return 1
  fi
  pass "删除命令执行成功"

  if ! obs_run ls "obs://${OBS_BUCKET}/${RUN_PREFIX}" -limit=100; then
    # 前缀已空时部分版本会返回非 0，这本身说明对象已不存在
    pass "确认已删除（前缀列表为空）"
    return 0
  fi
  if printf '%s' "$LAST_OUT" | grep -q -- "$PAYLOAD_NAME"; then
    fail "删除后仍能列出 ${PAYLOAD_NAME}，对象未真正删除"
    return 1
  fi
  pass "确认已删除（前缀列表不再包含该对象）"
}

# ---- 汇总 -------------------------------------------------------------------
summary() {
  info ""
  info "================ 结果汇总 ================"
  info "通过: ${#PASSED[@]} 项    失败: ${#FAILED[@]} 项    跳过: ${#SKIPPED[@]} 项"
  local x
  if ((${#FAILED[@]} > 0)); then
    info "失败项："
    for x in "${FAILED[@]}"; do printf '  - %s\n' "$x"; done
  fi
  if ((${#SKIPPED[@]} > 0)); then
    info "跳过项："
    for x in "${SKIPPED[@]}"; do printf '  - %s\n' "$x"; done
  fi
  if ((${#FAILED[@]} == 0)); then
    if ((DRY_RUN == 1)); then
      info "结论: --dry-run 完成（未实际读写 OBS），命令可人工复核。"
    else
      info "结论: PASS —— OBS 连通性与权限自检全部通过。"
      if ((CHECK_POLICY == 0)); then
        info "提示: 未做权限负面检查；建议再跑一次 --check-policy 验证匿名读被拒。"
      fi
    fi
    info "未覆盖: 预签名 URL（PT10M）的生成/过期 —— 属应用侧 OBS 原生签名，"
    info "        本脚本不复制签名逻辑；如需验证请用应用或 SDK 生成后自行 curl。"
    return 0
  fi
  info "结论: FAIL —— 有检查未通过（见上）。"
  return 1
}

main() {
  parse_args "$@"
  info "SkillHub OBS 自检（$([[ $DRY_RUN -eq 1 ]] && echo 'dry-run：只打印命令' || echo '真实执行')）"

  WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/skillhub-verify.XXXXXX")" || die "无法创建临时目录"
  trap cleanup EXIT INT TERM

  setup_env
  require_obsutil
  prepare_credentials

  RUN_ID="$(new_uuid)"
  [[ -n "$RUN_ID" ]] || die "无法生成运行 uuid"
  RUN_PREFIX="${DIAG_PREFIX}${RUN_ID}/"
  KEY="${RUN_PREFIX}${PAYLOAD_NAME}"
  LOCAL_UP="${WORKDIR}/payload.bin"
  LOCAL_DOWN="${WORKDIR}/payload.readback.bin"

  info "本次前缀: ${RUN_PREFIX}（只在这里写/删，退出前清理）"

  if ((DRY_RUN == 0)); then
    head -c "$PAYLOAD_BYTES" /dev/urandom >"$LOCAL_UP" || die "无法生成随机测试内容"
    info "已生成测试对象内容: $(wc -c <"$LOCAL_UP" | tr -d ' ') 字节"
  fi

  # 逐项执行；单项失败不中断后续项（要看到完整结论），但任一失败最终非零退出
  check_upload || true
  check_download_and_compare || true
  check_list_prefix || true
  check_anonymous_denied || true
  check_forbidden_bucket || true
  check_delete || true

  summary
}

main "$@"
