#!/usr/bin/env bash
# =============================================================================
# SkillHub —— OBS 桶初始化与最小化配置（华为云 CCE 部署前置）
#
# 【为什么需要本脚本】
# SkillHub 的 OBS 存储适配器（provider=obs）使用 **OBS 原生协议**：
#   * 签名：OBS 自己的 HMAC-SHA1（不是 AWS SigV4），临时凭证还要在 URL 上带
#     x-obs-security-token
#   * 寻址：虚拟主机式 <bucket>.obs.<region>.myhuaweicloud.com
#   * 配置：endpoint=https://obs.<region>.myhuaweicloud.com、region=<region>、
#     预签名有效期默认 PT10M
# 并且 autoCreateBucket=false —— **桶必须预先创建**，应用既不建桶也不改桶配置。
# 因此桶的创建与桶级配置是本脚本的职责，应用只做对象级读写。
#
# 【本脚本做什么】
#   1. 幂等创建**私有**桶（已存在则只核对可访问性与区域，不重建、不清空）
#   2. 桶级最小必要配置：
#      * 生命周期：清理未完成的分段上传（必需，防止隐藏的存储泄漏）
#      * 生命周期：诊断前缀 skillhub-diagnostics/ 过期（可选 --expire-diagnostics）
#      * 版本控制（可选 --versioning）
#      * CORS（可选 --cors，仅当浏览器直连 OBS 时才需要）
#   3. 做完后请用同目录的 verify-obs.sh 做小对象往返与权限自检
#
# 【幂等性（重复执行安全）】
#   * 桶：先 obsutil ls 探测；存在 → 只核对区域与可访问性，不执行 mb。
#   * 版本控制：先 get，已 Enabled 则跳过。
#   * 生命周期：先 get，已含 AbortIncompleteMultipartUpload 规则则跳过；
#     桶上若已有**其它**生命周期规则，本脚本不覆盖（避免清掉别人配的规则），
#     需要整份替换时必须显式加 --force-lifecycle（会在输出里提示将被替换）。
#   * CORS：先 get，已包含目标 Origin 则跳过。
#   * 任何一步都不删除对象、不删除桶、不改桶 ACL、不写桶策略、不轮换密钥。
#
# 【凭证处理（不污染他人配置，且不与子命令参数冲突）】
#   * 默认（--creds=isolated）：在本次运行的临时目录里建一个隔离 HOME，写入
#     <临时HOME>/.obsutilconfig（权限 0600），调用 obsutil 时用
#     `env HOME=<临时HOME>` 让它只读这份配置；退出时整个临时目录删除。
#     因此**默认绝不写**真实的 ~/.obsutilconfig，不污染同机其它使用者/其它工具。
#   * 为什么不直接传 `-config=<临时配置文件>`：obsutil 的 lifecycle/cors/versioning
#     子命令自身就用 `-config=<xml>` 传配置正文（见下方参数说明），再用 -config
#     传凭证会与正文参数**冲突**。因此改用「临时 HOME + 默认配置文件路径」这种
#     文档化的隔离方式，两条路径互不干扰。
#   * --creds=cli：完全不落地密钥文件，改用 obsutil 的 -i=/-k=/-t= 命令行参数
#     （密钥会出现在本机进程列表里，只建议在受控构建/跳板机上使用）。
#   * --config-file <路径>：复用已有的 obsutil 配置文件（**复制**进临时 HOME 使用，
#     原文件只读，不会被修改）。
#   * --write-config：显式把配置写到真实的 ~/.obsutilconfig（会影响该用户后续所有
#     obsutil 使用；会先备份旧文件）。默认不发生。
#   * 输出中不打印 AK/SK/token；命令回显做了脱敏。
#
# 【obsutil 参数准确性说明（请务必读）】
#   obsutil 各版本的子命令与参数集合有差异。本脚本用到的关键形态：
#     obsutil mb         obs://<bucket> -location=<region>
#     obsutil lifecycle  obs://<bucket> -method=get|put -config=<xml>
#     obsutil cors       obs://<bucket> -method=get|put -config=<xml>
#     obsutil versioning obs://<bucket> -method=get|put -config=<xml>
#     通用参数：-i=<ak> / -k=<sk> / -t=<token> / -e=<endpoint>
#     默认配置文件：~/.obsutilconfig（本脚本通过临时 HOME 隔离它）
#   上面这些形态均标注了「需按所用 obsutil 版本核对」。脚本在真正执行前会用
#   obsutil help 做**子命令探测**：
#     * 探测到不支持（例如老版本没有 versioning/cors 子命令）→ 不静默跳过，
#       而是打印明确的手工处理指引，并把该步计入「未完成项」，最终以非零退出。
#     * 探测不到 help（无法判断）→ 乐观尝试执行，失败时打印原始错误与核对指引。
#   --dry-run 会打印将要执行的完整命令，便于人工逐条复核后再真正运行。
#
# 【用法】
#   export OBS_ENDPOINT=https://obs.cn-north-4.myhuaweicloud.com
#   export OBS_REGION=cn-north-4
#   export OBS_BUCKET=skillhub-prod-obs
#   export OBS_AK=<管理员 AK>  OBS_SK=<管理员 SK>
#   ./deploy/cce/obs/setup-bucket.sh --dry-run        # 先看命令
#   ./deploy/cce/obs/setup-bucket.sh --versioning
# =============================================================================

set -euo pipefail

# 固定 C locale：在 zh_CN.UTF-8 等非 C locale 下，bash 的 [[ =~ ]] 会按排序规则
# 解释 [a-z] 这类范围，实测会把大写 'S' 匹配进 [a-z]，从而**绕过**桶名的
# "只能小写"校验。本脚本不做本地化排序，固定 C locale 才能保证校验确定。
export LC_ALL=C

readonly SCRIPT_NAME="$(basename -- "${BASH_SOURCE[0]}")"

# 诊断/自检前缀：verify-obs.sh 只在这里写临时对象；生命周期可选在此前缀上做过期
readonly DIAG_PREFIX="skillhub-diagnostics/"
readonly LIFECYCLE_ID_MPU="skillhub-abort-incomplete-multipart"
readonly LIFECYCLE_ID_DIAG="skillhub-expire-diagnostics"

# ---- 环境变量（全部可用环境提供，命令行选项覆盖行为而非取值） ----------------
OBS_ENDPOINT="${OBS_ENDPOINT:-}"
OBS_REGION="${OBS_REGION:-}"
OBS_BUCKET="${OBS_BUCKET:-}"
OBS_AK="${OBS_AK:-}"
OBS_SK="${OBS_SK:-}"
OBS_SESSION_TOKEN="${OBS_SESSION_TOKEN:-}"
OBSUTIL_BIN="${OBSUTIL_BIN:-obsutil}"
OBS_CORS_ORIGINS="${OBS_CORS_ORIGINS:-}"
OBS_DIAG_RETENTION_DAYS="${OBS_DIAG_RETENTION_DAYS:-7}"
OBSUTIL_CONFIG="${OBSUTIL_CONFIG:-}"
if [[ -z "$OBSUTIL_CONFIG" && -n "${HOME:-}" ]]; then
  OBSUTIL_CONFIG="${HOME}/.obsutilconfig"
fi

# ---- 运行态 -----------------------------------------------------------------
DRY_RUN=0
DO_CORS=0
DO_VERSIONING=0
EXPIRE_DIAG=0
FORCE_LIFECYCLE=0
WRITE_CONFIG=0
CREDS_MODE="isolated"        # isolated（临时 HOME 隔离）| cli（命令行传参）
CONFIG_FILE_IN=""            # --config-file 指定的既有配置（复制后使用）
ENDPOINT_HOST=""             # obsutil 用的 endpoint（不带 scheme）
REAL_HOME="${HOME:-}"        # 调用者真实的 HOME（--write-config 才会用到）
WORKDIR=""                   # 本次运行的临时目录（XML/配置文件都放这里）
ISOLATED_HOME=""             # 隔离用的临时 HOME（为空表示不做隔离）
CRED_DISPLAY=""              # 供回显用的脱敏凭证描述
OBSUTIL_CRED_ARGS=()         # 实际传给 obsutil 的凭证参数（cli 模式才非空）
ENDPOINT_ARGS=()             # 实际传给 obsutil 的 endpoint 参数
OBSUTIL_HELP=""              # 缓存的 obsutil help 输出（用于子命令探测）
LAST_OUT=""                  # 最近一次 obsutil 的输出/退出码
LAST_RC=0
PENDINGS=()                  # 需要人工补做的项（最终非零退出）

# ---- 输出 -------------------------------------------------------------------
info() { printf '%s\n' "$*"; }
warn() { printf '警告: %s\n' "$*" >&2; }
die() { printf '错误: %s\n' "$*" >&2; exit 1; }
pending() { PENDINGS+=("$*"); printf '未完成: %s\n' "$*" >&2; }

usage() {
  cat <<'EOF'
用法: setup-bucket.sh [选项]

为 SkillHub 在华为云 OBS 上准备存储桶：幂等创建私有桶 + 桶级最小必要配置。
应用侧（provider=obs）使用 OBS 原生协议且不建桶，所以桶必须先由管理员创建。
本脚本可重复执行：已存在的桶只做核对，不重建、不清空、不轮换密钥、不改 ACL。

环境变量:
  OBS_ENDPOINT    OBS 服务端点，如 https://obs.cn-north-4.myhuaweicloud.com
                  （会去掉 scheme，obsutil 只接受主机名；compatible 端点形如
                   obs.<region>.myhuaweicloud.com）
  OBS_REGION     桶所在区域，如 cn-north-4（桶创建后区域不可改）。
                  留空时尝试从 OBS_ENDPOINT 推导；推导失败则建桶时不带 -location
                  （届时区域由 endpoint 决定，需按所用 obsutil 版本核对行为）。
  OBS_BUCKET     桶名（全局唯一、3~63 字符、小写字母/数字/连字符/点，
                  首尾必须是字母或数字；创建后不可改名）
  OBS_AK         管理员 AK（用于建桶与桶配置；应用应使用更小的只读/读写策略）
  OBS_SK         管理员 SK
  OBS_SESSION_TOKEN 可选：使用临时凭证（IAM 委托/STS）时的 security token
  OBSUTIL_BIN    obsutil 可执行文件，默认 obsutil
  OBS_CORS_ORIGINS  --cors 时必填，逗号分隔的允许来源，
                  如 https://skillhub.example.com,https://skillhub-test.example.com
  OBS_DIAG_RETENTION_DAYS  --expire-diagnostics 时诊断前缀的保留天数，默认 7
  OBSUTIL_CONFIG --write-config 的目标路径，默认 ~/.obsutilconfig

选项:
  --dry-run             只打印将要执行的命令与分支判断，不创建桶、不写任何配置
                        （本机没有 obsutil 也能跑通，用于上线前人工复核；
                         仅会在系统临时目录生成待执行的 XML 供人工查看，退出即删）
  --versioning          开启桶版本控制（幂等：已 Enabled 则跳过）
  --cors                配置 CORS（浏览器直连 OBS 时才需要；需 OBS_CORS_ORIGINS）
  --expire-diagnostics [天数]
                        为诊断前缀 skillhub-diagnostics/ 增加过期规则，
                        天数缺省取 OBS_DIAG_RETENTION_DAYS（默认 7）
  --force-lifecycle     允许用本脚本的生命周期配置**整份替换**桶上已有生命周期规则
                        （默认遇到已有其它规则时只警告、不覆盖）
  --creds=isolated|cli  凭证传递方式。默认 isolated：临时 HOME + 0600 的
                        .obsutilconfig 隔离，不动真实 ~/.obsutilconfig，退出即删。
                        cli：用 -i/-k/-t 命令行参数，完全不落地密钥文件
                        （密钥会出现在本机进程列表里，仅建议在受控机器使用）。
  --config-file <路径>  复用已有 obsutil 配置文件：复制进临时 HOME 使用，原文件不改。
                        此时可不传 AK/SK。
  --write-config        把本次配置写入 OBSUTIL_CONFIG（默认 ~/.obsutilconfig）。
                        会影响该用户之后所有 obsutil 使用；会先备份旧文件。
  -h, --help            显示本帮助

退出码:
  0  所有被请求的步骤都成功或已满足（--dry-run 总是 0）
  1  出错（凭证/网络/权限/桶不可用），或存在"未完成项"（见输出的未完成清单）

典型流程:
  # 1) 先看命令（不需要 obsutil，也不写任何文件）
  OBS_ENDPOINT=https://obs.cn-north-4.myhuaweicloud.com OBS_REGION=cn-north-4 \
  OBS_BUCKET=skillhub-prod-obs OBS_AK=... OBS_SK=... \
  ./deploy/cce/obs/setup-bucket.sh --dry-run

  # 2) 真正执行（含版本控制与诊断前缀过期）
  ./deploy/cce/obs/setup-bucket.sh --versioning --expire-diagnostics 7

  # 3) 连通性与最小权限自检（匿名读应被拒、越权桶应被拒）
  OBS_FORBIDDEN_BUCKET=other-bucket ./deploy/cce/obs/verify-obs.sh --check-policy

说明:
  * 新建桶默认是私有的；本脚本不修改桶 ACL，也不写桶策略。
  * 应用侧的最小权限 IAM 自定义策略见同目录 iam-policy-skillhub-app.json，
    在 IAM 控制台创建策略并授权给应用使用的 IAM 用户/委托即可，与本脚本无关。
  * obsutil 子命令（mb/lifecycle/cors/versioning）的参数随版本有差异；脚本注释
    逐处标注了「需按所用 obsutil 版本核对」，执行失败时会打印核对指引。
EOF
}

# ---- 参数解析 ---------------------------------------------------------------
parse_args() {
  while (($# > 0)); do
    case "$1" in
      --dry-run) DRY_RUN=1; shift ;;
      --versioning) DO_VERSIONING=1; shift ;;
      --cors) DO_CORS=1; shift ;;
      --expire-diagnostics)
        EXPIRE_DIAG=1
        shift
        if (($# > 0)) && [[ "$1" =~ ^[0-9]+$ ]]; then
          OBS_DIAG_RETENTION_DAYS="$1"
          shift
        fi
        ;;
      --expire-diagnostics=*)
        EXPIRE_DIAG=1
        OBS_DIAG_RETENTION_DAYS="${1#*=}"
        shift
        ;;
      --force-lifecycle) FORCE_LIFECYCLE=1; shift ;;
      --creds)
        (($# >= 2)) || die "--creds 需要一个参数（config 或 cli）"
        CREDS_MODE="$2"; shift 2
        ;;
      --creds=*) CREDS_MODE="${1#*=}"; shift ;;
      --config-file)
        (($# >= 2)) || die "--config-file 需要一个路径参数"
        CONFIG_FILE_IN="$2"; shift 2
        ;;
      --config-file=*) CONFIG_FILE_IN="${1#*=}"; shift ;;
      --write-config) WRITE_CONFIG=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "未知参数: $1（用 --help 查看用法）" ;;
    esac
  done
  case "$CREDS_MODE" in
    isolated|cli) ;;
    *) die "--creds 只支持 isolated 或 cli，收到: ${CREDS_MODE}" ;;
  esac
  [[ "$OBS_DIAG_RETENTION_DAYS" =~ ^[0-9]+$ ]] \
    || die "OBS_DIAG_RETENTION_DAYS 必须是整数天数，收到: ${OBS_DIAG_RETENTION_DAYS}"
}

# ---- 清理 -------------------------------------------------------------------
cleanup() {
  local rc=$?
  if [[ -n "$WORKDIR" && -d "$WORKDIR" ]]; then
    rm -rf -- "$WORKDIR"
  fi
  return $rc
}

# ---- 输入校验 ---------------------------------------------------------------
normalize_endpoint() {
  local ep="$OBS_ENDPOINT"
  ep="${ep#http://}"
  ep="${ep#https://}"
  ep="${ep%%/*}"
  ep="${ep%/}"
  ENDPOINT_HOST="$ep"
}

validate_bucket_name() {
  local b="$OBS_BUCKET"
  [[ -n "$b" ]] || die "缺少环境变量 OBS_BUCKET"
  if [[ ${#b} -lt 3 || ${#b} -gt 63 ]]; then
    die "桶名长度必须在 3~63 之间（当前 ${#b}）: ${b}"
  fi
  if [[ ! "$b" =~ ^[a-z0-9][a-z0-9.-]*[a-z0-9]$ ]]; then
    die "桶名只能包含小写字母/数字/连字符/点，且首尾必须是字母或数字: ${b}"
  fi
  case "$b" in
    *..*) die "桶名不允许出现连续的 '..': ${b}" ;;
    *.-*|*-.*) die "桶名中点与连字符不能相邻: ${b}" ;;
    xn--*) die "桶名不能以 xn-- 开头: ${b}" ;;
  esac
  if [[ "$b" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
    die "桶名不能是 IP 地址形式: ${b}"
  fi
  # 桶名全局唯一且创建后不可修改 —— 这里给出明确提示，避免误用在多环境之间
  info "桶名: ${b}（OBS 桶名全局唯一，且创建后无法改名；跨环境请使用不同桶名）"
}

derive_region() {
  if [[ -n "$OBS_REGION" ]]; then
    return 0
  fi
  # 从 obs.<region>.myhuaweicloud.com 推导（compatible 端点形态）
  if [[ "$ENDPOINT_HOST" =~ ^obs\.([a-z0-9-]+)\.myhuaweicloud\.com$ ]]; then
    OBS_REGION="${BASH_REMATCH[1]}"
    info "区域: ${OBS_REGION}（由 OBS_ENDPOINT 推导）"
  else
    warn "未能从 endpoint 推导区域（${ENDPOINT_HOST:-未设置}）；建桶时不会带 -location，"
    warn "区域将由 endpoint 决定。请按所用 obsutil 版本核对该行为，或显式设置 OBS_REGION。"
  fi
}

check_required_inputs() {
  if [[ -z "$CONFIG_FILE_IN" ]]; then
    [[ -n "$OBS_ENDPOINT" ]] || die "缺少环境变量 OBS_ENDPOINT（例如 https://obs.cn-north-4.myhuaweicloud.com）"
  else
    [[ -f "$CONFIG_FILE_IN" ]] || die "--config-file 指向的文件不存在: ${CONFIG_FILE_IN}"
  fi

  validate_bucket_name

  if [[ -n "$OBS_ENDPOINT" ]]; then
    normalize_endpoint
    [[ -n "$ENDPOINT_HOST" ]] || die "OBS_ENDPOINT 解析后为空，请检查: ${OBS_ENDPOINT}"
    [[ "$ENDPOINT_HOST" != *"://"* ]] || die "OBS_ENDPOINT 不应包含 scheme: ${OBS_ENDPOINT}"
    info "端点: ${ENDPOINT_HOST}"
  fi
  derive_region

  # 凭证来源：--config-file / 环境变量 AK+SK / --write-config
  if [[ -z "$CONFIG_FILE_IN" ]]; then
    if [[ -z "$OBS_AK" || -z "$OBS_SK" ]]; then
      if ((DRY_RUN == 1)) || ((WRITE_CONFIG == 1)); then
        : # dry-run 允许缺凭证（只打印命令）；--write-config 后面会单独校验
      else
        die "缺少 OBS_AK / OBS_SK（或改用 --config-file 指向已有 obsutil 配置文件）"
      fi
    fi
    if [[ -n "$OBS_AK" ]]; then
      # 只显示前 4 位，绝不打印完整 AK/SK
      info "AK: ${OBS_AK:0:4}****（已脱敏，完整值不会出现在输出中）"
    fi
    if [[ -n "$OBS_SESSION_TOKEN" ]]; then
      info "凭证类型: 临时凭证（含 security token）"
    else
      info "凭证类型: 永久 AK/SK"
    fi
  else
    info "配置来源: --config-file ${CONFIG_FILE_IN}（只读复用，不修改该文件）"
  fi

  # 可选开关的前置校验：宁可在这里失败，也不要建完桶再报错
  if ((DO_CORS == 1)) && [[ -z "$OBS_CORS_ORIGINS" ]]; then
    die "--cors 需要 OBS_CORS_ORIGINS（逗号分隔），例如 OBS_CORS_ORIGINS=https://skillhub.example.com"
  fi

  if ((WRITE_CONFIG == 1)); then
    [[ -n "$OBSUTIL_CONFIG" ]] || die "--write-config 需要 OBSUTIL_CONFIG 或 HOME 环境变量"
    [[ -n "$OBS_AK" && -n "$OBS_SK" ]] || die "--write-config 需要 OBS_AK / OBS_SK"
    info "--write-config: 将写入 ${OBSUTIL_CONFIG}（会先备份旧文件；影响该用户后续 obsutil 调用）"
  fi
}

require_obsutil() {
  if ! command -v "$OBSUTIL_BIN" >/dev/null 2>&1 && [[ ! -x "$OBSUTIL_BIN" ]]; then
    if ((DRY_RUN == 1)); then
      warn "未找到 obsutil（${OBSUTIL_BIN}）—— --dry-run 不需要它，下面只打印命令。"
      return 0
    fi
    cat >&2 <<EOF
错误: 未找到 obsutil（${OBSUTIL_BIN}）。

请先安装官方 obsutil（见同目录 README.md 的"安装 obsutil"一节），常见方式：
  * Linux x86_64：
      curl -L -o obsutil.tar.gz \\
        https://obs-community.obs.cn-north-1.myhuaweicloud.com/obsutil/current/obsutil_linux_amd64.tar.gz
      tar -xzf obsutil.tar.gz && ./obsutil version
  * 已有包时：export OBSUTIL_BIN=/path/to/obsutil
确认安装：${OBSUTIL_BIN} version
EOF
    exit 1
  fi
  local v
  v="$(run_obsutil version 2>&1 | tr '\n' ' ' || true)"
  info "obsutil: ${v:-已找到（${OBSUTIL_BIN}）}"
}

# ---- obsutil 调用封装（脱敏回显 + dry-run 短路） ---------------------------
redact() {
  local a
  local -a out=()
  for a in "$@"; do
    case "$a" in
      -i=*|-k=*|-t=*|--ak=*|--sk=*) out+=("${a:0:3}***") ;;
      *) out+=("$a") ;;
    esac
  done
  printf '%s' "${out[*]}"
}

# 统一执行入口：isolated 模式下用 env 覆盖 HOME/USERPROFILE，让 obsutil 只读临时
# 目录里的 .obsutilconfig（obsutil 的默认配置文件路径就是 <HOME>/.obsutilconfig）。
# 这样既不动真实 ~/.obsutilconfig，也不会占用子命令的 -config 参数。
run_obsutil() {
  if [[ -n "$ISOLATED_HOME" ]]; then
    env HOME="$ISOLATED_HOME" USERPROFILE="$ISOLATED_HOME" "$OBSUTIL_BIN" "$@"
  else
    "$OBSUTIL_BIN" "$@"
  fi
}

obs_run() {
  local -a full=("$@")
  local printed
  printed="$(redact "${full[@]}")"
  printf '  $ %s %s%s\n' "$OBSUTIL_BIN" "$printed" "${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
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
  LAST_RC=$rc
  if [[ -n "$out" ]]; then
    printf '%s\n' "$out" | sed 's/^/    | /'
  fi
  return $rc
}

# obsutil 子命令探测：能读到 help 就按 help 判断，读不到则乐观尝试
subcmd_supported() {
  local sub="$1"
  if [[ -z "$OBSUTIL_HELP" ]]; then
    OBSUTIL_HELP="$(run_obsutil help 2>&1 || true)"
  fi
  if [[ -z "$OBSUTIL_HELP" ]]; then
    return 0
  fi
  printf '%s\n' "$OBSUTIL_HELP" | grep -qw -- "$sub"
}

hint_manual() {
  printf '%s\n' "$*" >&2
}

# ---- 凭证准备 ---------------------------------------------------------------
# 三个要点：
#   1. 默认 isolated：密钥只出现在临时目录（0600），退出即删，不写真实 HOME。
#   2. cli：连文件都不写，全走 -i/-k/-t 参数。
#   3. 无论哪种方式，AK/SK 都不会被打印；命令回显经过 redact 脱敏。
isolate_home() {
  [[ -n "$ISOLATED_HOME" ]] && return 0
  [[ -n "$WORKDIR" ]] || die "内部错误：临时目录尚未创建"
  mkdir -p -- "${WORKDIR}/home"
  chmod 700 -- "${WORKDIR}/home" 2>/dev/null || true
  ISOLATED_HOME="${WORKDIR}/home"
}

# 写 obsutil 配置文件（0600）。字段名按 obsutil 常规配置（ak/sk/token/endpoint）。
# 若所用版本要求分节或字段名不同（如 securityToken/security_token），可改用
# --creds=cli，或直接执行 `obsutil config -i=... -k=... -e=...` 让工具自己写格式。
# —— 需按所用 obsutil 版本核对。
write_config_file() {
  local path="$1" old_umask
  old_umask="$(umask)"
  umask 077
  {
    printf 'ak=%s\n' "$OBS_AK"
    printf 'sk=%s\n' "$OBS_SK"
    if [[ -n "$OBS_SESSION_TOKEN" ]]; then
      printf 'token=%s\n' "$OBS_SESSION_TOKEN"
    fi
    if [[ -n "$ENDPOINT_HOST" ]]; then
      printf 'endpoint=%s\n' "$ENDPOINT_HOST"
    fi
  } >"$path"
  umask "$old_umask"
  chmod 600 -- "$path" 2>/dev/null || true
  verify_config_perms "$path"
}

# 校验配置文件的权限确实收紧到 600。
# 说明：在 Linux 上 umask 077 + chmod 600 生效；但在 Windows NTFS（MSYS/Git Bash
# 以 noacl 挂载）等文件系统上 chmod 是空操作，stat 会报 644。这类平台应当改用
# --creds=cli（完全不落地密钥文件），因此这里不静默通过，而是明确告警。
verify_config_perms() {
  local path="$1" mode
  mode="$(stat -c '%a' "$path" 2>/dev/null || stat -f '%Lp' "$path" 2>/dev/null || echo '')"
  if [[ -z "$mode" ]]; then
    return 0
  fi
  if [[ "$mode" != "600" ]]; then
    warn "配置文件 ${path} 的权限是 ${mode}（期望 600）"
    warn "  当前文件系统可能不支持 Unix 权限（例如 Windows NTFS 以 noacl 方式挂载）。"
    warn "  在此类平台上请改用 --creds=cli（密钥不落地为文件），或自行确认该路径的访问控制。"
  fi
}

prepare_credentials() {
  # 1) 复用既有配置文件：复制进临时 HOME，原文件只读（dry-run 不复制，见第 2 段）
  if [[ -n "$CONFIG_FILE_IN" ]] && ((DRY_RUN == 0)); then
    isolate_home
    write_copy_of_config "$CONFIG_FILE_IN" "${ISOLATED_HOME}/.obsutilconfig"
    CRED_DISPLAY="[env HOME=<临时目录>（0600 .obsutilconfig）]"
    info "凭证方式: 复用 ${CONFIG_FILE_IN}（复制进临时 HOME，原文件不改动）"
    if [[ -n "$ENDPOINT_HOST" ]]; then
      ENDPOINT_ARGS=("-e=${ENDPOINT_HOST}")
    fi
    return 0
  fi

  # 2) dry-run：绝不落地任何含密钥的文件，只给出回显描述
  if ((DRY_RUN == 1)); then
    case "$CREDS_MODE" in
      cli) CRED_DISPLAY="[-i=*** -k=***]" ;;
      *) CRED_DISPLAY="[env HOME=<临时目录>（0600 .obsutilconfig）]" ;;
    esac
    if [[ -n "$CONFIG_FILE_IN" ]]; then
      info "凭证方式: 复用 ${CONFIG_FILE_IN}（真实执行时会复制进临时 HOME，原文件只读）"
    elif ((WRITE_CONFIG == 1)); then
      CRED_DISPLAY="[使用 ${OBSUTIL_CONFIG}]"
      info "[dry-run] 将写入用户级配置 ${OBSUTIL_CONFIG}（0600，会先备份旧文件）—— 本次未写"
    else
      info "凭证方式: dry-run 不写任何凭证文件（真实执行时按 --creds 处理）"
    fi
    if [[ -n "$ENDPOINT_HOST" ]]; then
      ENDPOINT_ARGS=("-e=${ENDPOINT_HOST}")
    fi
    return 0
  fi

  # 3) 显式 --write-config：写真实 HOME（会备份旧文件），本次直接复用该文件
  if ((WRITE_CONFIG == 1)); then
    write_global_config
    CRED_DISPLAY="[使用 ${OBSUTIL_CONFIG}]"
    if [[ -n "$ENDPOINT_HOST" ]]; then
      ENDPOINT_ARGS=("-e=${ENDPOINT_HOST}")
    fi
    return 0
  fi

  # 4) 常规：isolated（默认）或 cli
  case "$CREDS_MODE" in
    cli)
      local -a args=("-i=${OBS_AK}" "-k=${OBS_SK}")
      if [[ -n "$OBS_SESSION_TOKEN" ]]; then
        args+=("-t=${OBS_SESSION_TOKEN}")
      fi
      OBSUTIL_CRED_ARGS=("${args[@]}")
      CRED_DISPLAY="[-i=*** -k=***]"
      if [[ -n "$OBS_SESSION_TOKEN" ]]; then
        CRED_DISPLAY="${CRED_DISPLAY%]} -t=***]"
      fi
      info "凭证方式: 命令行传参（-i/-k/-t，不落地任何文件）"
      ;;
    isolated)
      isolate_home
      write_config_file "${ISOLATED_HOME}/.obsutilconfig"
      CRED_DISPLAY="[env HOME=<临时目录>（0600 .obsutilconfig）]"
      info "凭证方式: 临时 HOME 隔离（0600 .obsutilconfig，退出即删；不写真实 ~/.obsutilconfig）"
      ;;
  esac

  if [[ -n "$ENDPOINT_HOST" ]]; then
    ENDPOINT_ARGS=("-e=${ENDPOINT_HOST}")
  fi
}

write_copy_of_config() {
  local src="$1" dst="$2"
  [[ -r "$src" ]] || die "无法读取配置文件: ${src}"
  cp -- "$src" "$dst"
  chmod 600 -- "$dst" 2>/dev/null || true
  verify_config_perms "$dst"
}

write_global_config() {
  [[ -n "$OBSUTIL_CONFIG" ]] || die "无法确定全局配置路径（OBSUTIL_CONFIG 与 HOME 都为空）"
  mkdir -p -- "$(dirname -- "$OBSUTIL_CONFIG")"
  if [[ -f "$OBSUTIL_CONFIG" ]]; then
    local bak="${OBSUTIL_CONFIG}.bak.$(date +%Y%m%d%H%M%S)"
    cp -- "$OBSUTIL_CONFIG" "$bak"
    warn "已备份原有配置到 ${bak}（--write-config 会覆盖 ${OBSUTIL_CONFIG}）"
  fi
  write_config_file "$OBSUTIL_CONFIG"
  info "已写入 ${OBSUTIL_CONFIG}（权限 600）—— 该用户后续所有 obsutil 调用都会使用它"
}

# ---- 步骤 1：桶存在性与创建 -------------------------------------------------
classify_ls_failure() {
  # $1 = obsutil 输出，$2 = 退出码
  local out="$1" rc="$2"
  if printf '%s' "$out" | grep -qiE 'access ?denied|403|forbidden'; then
    die "访问被拒绝（403）。请检查 AK/SK 是否有效、是否被授予 OBS 权限、桶是否属于该账号。原始输出见上。"
  fi
  if printf '%s' "$out" | grep -qiE '301|permanentredirect|redirect|region'; then
    die "桶所在区域与当前 endpoint/OBS_REGION 不一致（301/Redirect）。请用桶的真实区域，
例如桶在 cn-north-4 时：OBS_ENDPOINT=https://obs.cn-north-4.myhuaweicloud.com OBS_REGION=cn-north-4"
  fi
  die "探测桶失败（退出码 ${rc}）。原始输出见上；请检查网络、endpoint、凭证。"
}

step_bucket() {
  info ""
  info "== 步骤 1：桶存在性检查（幂等：存在则只核对，不重建、不清空）"

  if ((DRY_RUN == 1)); then
    info "  [dry-run] 先探测桶是否存在（真实执行时会跑下面这条，用退出码判断）："
    info "    $OBSUTIL_BIN ls obs://${OBS_BUCKET} -limit=1${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
    info "  [dry-run] 分支 A —— 不存在（退出码非 0 且非 403/301）→ 创建私有桶："
    if [[ -n "$OBS_REGION" ]]; then
      info "    $OBSUTIL_BIN mb obs://${OBS_BUCKET} -location=${OBS_REGION}${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
    else
      info "    $OBSUTIL_BIN mb obs://${OBS_BUCKET}${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
      info "    （未确定区域，不带 -location；需按所用 obsutil 版本核对区域推导行为）"
    fi
    info "  [dry-run] 分支 B —— 已存在 → 不执行 mb，仅核对可访问性/区域，然后继续后续步骤"
    info "  [dry-run] 说明：OBS 新建桶默认为私有；本脚本不改 ACL、不写桶策略"
    return 0
  fi

  # 真实探测：obsutil ls obs://bucket -limit=1。桶存在→0；不存在→非 0（404）。
  # 需按所用 obsutil 版本核对：若你的版本对空桶返回非 0 之外的语义，请手工核对。
  local out rc exists=0
  set +e
  out="$(run_obsutil ls "obs://${OBS_BUCKET}" -limit=1 "${OBSUTIL_CRED_ARGS[@]}" "${ENDPOINT_ARGS[@]}" 2>&1)"
  rc=$?
  set -e
  if [[ $rc -eq 0 ]]; then
    exists=1
  elif printf '%s' "$out" | grep -qiE 'nosuchbucket|not ?exist|404|non-existing'; then
    exists=0
  else
    classify_ls_failure "$out" "$rc"
  fi

  if ((exists == 1)); then
    info "  桶已存在: obs://${OBS_BUCKET} —— 跳过创建（不重建、不清空、不轮换密钥）"
    printf '%s\n' "$out" | sed 's/^/    | /'
    info "  可访问性核对: 通过（ls 成功；读权限来自本次凭证）"
    return 0
  fi

  info "  桶不存在，创建私有桶:"
  local -a mb_args=("mb" "obs://${OBS_BUCKET}")
  # -location：指定桶所在区域。需按所用 obsutil 版本核对是否支持该参数；
  # 旧版本可能由 endpoint 推导区域（此时可删掉 -location）。
  if [[ -n "$OBS_REGION" ]]; then
    mb_args+=("-location=${OBS_REGION}")
  fi
  if obs_run "${mb_args[@]}"; then
    info "  已创建私有桶: obs://${OBS_BUCKET}（区域 ${OBS_REGION:-由 endpoint 决定}）"
    return 0
  fi
  # 并发/已存在场景：别人刚建好，视为成功
  if printf '%s' "$LAST_OUT" | grep -qiE 'already ?(exists|owned)|BucketAlreadyExists|409'; then
    warn "创建时提示桶已存在（并发创建或同账号已存在），按已存在处理"
    return 0
  fi
  die "创建桶失败。原始输出见上；常见原因：桶名已被他人占用（全局唯一）、AK 无 obs:bucket:CreateBucket 权限、区域写法错误。"
}

# ---- 步骤 2：版本控制（可选） ----------------------------------------------
step_versioning() {
  ((DO_VERSIONING == 1)) || return 0
  info ""
  info "== 步骤 2：版本控制（可选 --versioning）"

  if ((DRY_RUN == 0)) && ! subcmd_supported versioning; then
    pending "版本控制未配置：当前 obsutil 未提供 versioning 子命令"
    hint_manual "  手工处理：OBS 控制台 → 该桶 → 基础配置 → 版本控制 → 开启；"
    hint_manual "  或调用 OBS API PutBucketVersioning（需 obs:bucket:PutBucketVersioning 权限）。"
    return 1
  fi

  if ((DRY_RUN == 1)); then
    info "  [dry-run] 先查询现状（Enabled 则跳过，不重复开启）："
    info "    $OBSUTIL_BIN versioning obs://${OBS_BUCKET} -method=get${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
    info "  [dry-run] 若未开启则写入（XML 见下方说明）："
    info "    $OBSUTIL_BIN versioning obs://${OBS_BUCKET} -method=put -config=${WORKDIR:-<临时目录>}/versioning.xml${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
    info "    XML: <VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>"
    return 0
  fi

  if obs_run versioning "obs://${OBS_BUCKET}" -method=get; then
    if printf '%s' "$LAST_OUT" | grep -qiE '<Status>[[:space:]]*Enabled'; then
      info "  版本控制已开启 —— 跳过"
      add_versioning_note
      return 0
    fi
  fi

  local xml="${WORKDIR}/versioning.xml"
  cat >"$xml" <<'XML'
<VersioningConfiguration>
  <Status>Enabled</Status>
</VersioningConfiguration>
XML
  # 需按所用 obsutil 版本核对：versioning 子命令及其 -method=put -config=<xml> 形态
  obs_run versioning "obs://${OBS_BUCKET}" -method=put -config="$xml" \
    || die "开启版本控制失败。请核对后重试或手工在控制台开启（原始输出见上）。"
  info "  已开启版本控制"
  add_versioning_note
}

add_versioning_note() {
  info "  注意：开启版本控制后，删除对象只产生 delete marker，历史版本仍占存储；"
  info "        如需删除历史版本，应用凭证还需要 obs:object:DeleteObjectVersion。"
}

# ---- 步骤 3：生命周期（必需：清理未完成分段上传；可选：诊断前缀过期） -----
step_lifecycle() {
  info ""
  info "== 步骤 3：生命周期（清理未完成的分段上传$([[ $EXPIRE_DIAG -eq 1 ]] && printf ' + 诊断前缀过期 %s 天' "$OBS_DIAG_RETENTION_DAYS")）"

  if ((DRY_RUN == 0)) && ! subcmd_supported lifecycle; then
    pending "生命周期未配置：当前 obsutil 未提供 lifecycle 子命令"
    hint_manual "  手工处理：控制台 → 该桶 → 基础配置 → 生命周期，新增规则："
    hint_manual "    * 范围：整个桶（前缀留空），操作：清理未完成的分段上传，天数 1"
    hint_manual "    * 可选：前缀 ${DIAG_PREFIX}，操作：过期删除，天数 ${OBS_DIAG_RETENTION_DAYS}"
    hint_manual "  或调用 OBS API PutBucketLifecycle（需 obs:bucket:PutLifecycleConfiguration）。"
    return 1
  fi

  local xml="${WORKDIR}/lifecycle.xml"
  build_lifecycle_xml "$xml"

  if ((DRY_RUN == 1)); then
    info "  [dry-run] 先读取现有规则："
    info "    $OBSUTIL_BIN lifecycle obs://${OBS_BUCKET} -method=get${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
    info "  [dry-run] 已含 AbortIncompleteMultipartUpload → 跳过；"
    info "            已有其它规则且未加 --force-lifecycle → 只警告、不覆盖；"
    info "            否则写入以下配置："
    sed 's/^/    | /' "$xml"
    info "    $OBSUTIL_BIN lifecycle obs://${OBS_BUCKET} -method=put -config=${xml}${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
    return 0
  fi

  local existing=""
  if obs_run lifecycle "obs://${OBS_BUCKET}" -method=get; then
    existing="$LAST_OUT"
  else
    if printf '%s' "$existing$LAST_OUT" | grep -qiE 'nosuchlifecycle|404'; then
      existing=""
    else
      warn "读取现有生命周期配置失败，将直接写入本脚本配置（原始输出见上）"
      existing=""
    fi
  fi

  if [[ -n "$existing" ]] && printf '%s' "$existing" | grep -qi 'AbortIncompleteMultipartUpload'; then
    info "  已存在「清理未完成分段上传」规则 —— 跳过（幂等）"
    if ((EXPIRE_DIAG == 1)) && ! printf '%s' "$existing" | grep -qi "$DIAG_PREFIX"; then
      warn "  但现有规则未覆盖诊断前缀 ${DIAG_PREFIX} 的过期；如需添加请用 --force-lifecycle（整份替换）"
    fi
    return 0
  fi

  if [[ -n "${existing//[[:space:]]/}" ]] && ((FORCE_LIFECYCLE == 0)); then
    warn "桶上已有其它生命周期规则，本脚本不覆盖（避免清掉他人配置）。"
    warn "现有规则："
    printf '%s\n' "$existing" | sed 's/^/    | /' >&2
    pending "生命周期未配置：桶上已有其它规则，需人工合并"
    hint_manual "  处理方式二选一："
    hint_manual "    a) 在控制台/API 把「清理未完成分段上传（1 天）」合并进现有规则；"
    hint_manual "    b) 确认可以整份替换后重跑：${SCRIPT_NAME} --force-lifecycle"
    return 1
  fi

  # 需按所用 obsutil 版本核对：lifecycle 子命令与 -method=put -config=<xml> 形态
  obs_run lifecycle "obs://${OBS_BUCKET}" -method=put -config="$xml" \
    || die "写入生命周期配置失败（原始输出见上）。可用 \`$OBSUTIL_BIN help lifecycle\` 核对该版本参数。"
  info "  已写入生命周期配置"
}

build_lifecycle_xml() {
  local xml="$1"
  {
    printf '<?xml version="1.0" encoding="UTF-8"?>\n'
    printf '<LifecycleConfiguration>\n'
    printf '  <Rule>\n'
    printf '    <ID>%s</ID>\n' "$LIFECYCLE_ID_MPU"
    printf '    <Prefix></Prefix>\n'
    printf '    <Status>Enabled</Status>\n'
    printf '    <AbortIncompleteMultipartUpload>\n'
    printf '      <DaysAfterInitiation>1</DaysAfterInitiation>\n'
    printf '    </AbortIncompleteMultipartUpload>\n'
    printf '  </Rule>\n'
    if ((EXPIRE_DIAG == 1)); then
      printf '  <Rule>\n'
      printf '    <ID>%s</ID>\n' "$LIFECYCLE_ID_DIAG"
      printf '    <Prefix>%s</Prefix>\n' "$DIAG_PREFIX"
      printf '    <Status>Enabled</Status>\n'
      printf '    <Expiration>\n'
      printf '      <Days>%s</Days>\n' "$OBS_DIAG_RETENTION_DAYS"
      printf '    </Expiration>\n'
      printf '  </Rule>\n'
    fi
    printf '</LifecycleConfiguration>\n'
  } >"$xml"
}

# ---- 步骤 4：CORS（可选） ---------------------------------------------------
step_cors() {
  ((DO_CORS == 1)) || return 0
  info ""
  info "== 步骤 4：CORS（可选 --cors；仅浏览器直连 OBS 时需要）"

  [[ -n "$OBS_CORS_ORIGINS" ]] \
    || die "--cors 需要 OBS_CORS_ORIGINS（逗号分隔），例如 OBS_CORS_ORIGINS=https://skillhub.example.com"

  if ((DRY_RUN == 0)) && ! subcmd_supported cors; then
    pending "CORS 未配置：当前 obsutil 未提供 cors 子命令"
    hint_manual "  手工处理：控制台 → 该桶 → 基础配置 → CORS，添加允许来源（GET/HEAD），"
    hint_manual "  或调用 OBS API PutBucketCors。允许来源: ${OBS_CORS_ORIGINS}"
    return 1
  fi

  local xml="${WORKDIR}/cors.xml"
  build_cors_xml "$xml"

  if ((DRY_RUN == 1)); then
    info "  [dry-run] 先读取现有 CORS："
    info "    $OBSUTIL_BIN cors obs://${OBS_BUCKET} -method=get${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
    info "  [dry-run] 已包含全部目标 Origin → 跳过；否则写入："
    sed 's/^/    | /' "$xml"
    info "    $OBSUTIL_BIN cors obs://${OBS_BUCKET} -method=put -config=${xml}${CRED_DISPLAY:+ ${CRED_DISPLAY}}"
    return 0
  fi

  local existing=""
  if obs_run cors "obs://${OBS_BUCKET}" -method=get; then
    existing="$LAST_OUT"
  fi
  if [[ -n "$existing" ]]; then
    local origin missing=0
    IFS=',' read -r -a _origins <<<"$OBS_CORS_ORIGINS"
    for origin in "${_origins[@]}"; do
      origin="$(printf '%s' "$origin" | tr -d '[:space:]')"
      [[ -n "$origin" ]] || continue
      if ! printf '%s' "$existing" | grep -qi -- "$origin"; then
        missing=1
      fi
    done
    unset _origins
    if ((missing == 0)); then
      info "  CORS 已覆盖全部目标来源 —— 跳过（幂等）"
      return 0
    fi
    warn "已有 CORS 配置未覆盖全部目标来源；将写入本脚本配置（整份替换该桶 CORS）"
  fi

  # 需按所用 obsutil 版本核对：cors 子命令与 -method=put -config=<xml> 形态
  obs_run cors "obs://${OBS_BUCKET}" -method=put -config="$xml" \
    || die "写入 CORS 失败（原始输出见上）。可用 \`$OBSUTIL_BIN help cors\` 核对该版本参数。"
  info "  已写入 CORS 配置"
}

build_cors_xml() {
  local xml="$1"
  local origin
  local -a origins=()
  IFS=',' read -r -a origins <<<"$OBS_CORS_ORIGINS"
  {
    printf '<?xml version="1.0" encoding="UTF-8"?>\n'
    printf '<CORSConfiguration>\n'
    printf '  <CORSRule>\n'
    for origin in "${origins[@]}"; do
      origin="$(printf '%s' "$origin" | tr -d '[:space:]')"
      [[ -n "$origin" ]] || continue
      printf '    <AllowedOrigin>%s</AllowedOrigin>\n' "$origin"
    done
    printf '    <AllowedMethod>GET</AllowedMethod>\n'
    printf '    <AllowedMethod>HEAD</AllowedMethod>\n'
    printf '    <AllowedHeader>*</AllowedHeader>\n'
    printf '    <ExposeHeader>ETag</ExposeHeader>\n'
    printf '    <MaxAgeSeconds>3600</MaxAgeSeconds>\n'
    printf '  </CORSRule>\n'
    printf '</CORSConfiguration>\n'
  } >"$xml"
}

# ---- 收尾 -------------------------------------------------------------------
summary() {
  info ""
  info "== 汇总"
  info "  桶: obs://${OBS_BUCKET}"
  info "  区域: ${OBS_REGION:-由 endpoint 决定}"
  info "  端点: ${ENDPOINT_HOST:-见 obsutil 配置}"
  info "  版本控制: $([[ $DO_VERSIONING -eq 1 ]] && echo '已请求' || echo '未请求（保持现状）')"
  info "  诊断前缀过期: $([[ $EXPIRE_DIAG -eq 1 ]] && echo "${OBS_DIAG_RETENTION_DAYS} 天" || echo '未请求（保持现状）')"
  info "  CORS: $([[ $DO_CORS -eq 1 ]] && echo '已请求' || echo '未请求（保持现状）')"

  if ((${#PENDINGS[@]} > 0)); then
    info ""
    warn "存在未完成项（详见上方「未完成」提示）："
    local p
    for p in "${PENDINGS[@]}"; do
      printf '  - %s\n' "$p" >&2
    done
    return 1
  fi

  info ""
  info "下一步（本脚本不覆盖）："
  info "  1) 连通性自检（小对象往返 + 匿名读被拒 + 越权桶被拒）："
  info "     ./deploy/cce/obs/verify-obs.sh --check-policy"
  info "  2) 给应用 AK 授权最小权限策略："
  info "     IAM 控制台 → 策略 → 创建自定义策略 → 粘贴 iam-policy-skillhub-app.json"
  info "     （把其中的桶名占位替换为 ${OBS_BUCKET}），再授权给应用的 IAM 用户/委托。"
  info "  3) 应用侧配置：endpoint=https://obs.<region>.myhuaweicloud.com、region=${OBS_REGION:-<region>}、"
  info "     桶 ${OBS_BUCKET}（桶已预创建，应用不建桶）。"
  if ((DRY_RUN == 1)); then
    info ""
    info "以上为 --dry-run：未创建桶、未写任何配置、未修改任何用户级文件。"
  fi
}

main() {
  parse_args "$@"
  info "SkillHub OBS 桶初始化（$([[ $DRY_RUN -eq 1 ]] && echo 'dry-run：只打印命令' || echo '真实执行')）"

  check_required_inputs
  require_obsutil

  # 两种模式都建临时目录：dry-run 也要生成待执行的 XML 供人工复核（退出即删）
  WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/skillhub-obs.XXXXXX")" \
    || die "无法创建临时目录（${TMPDIR:-/tmp}）"
  trap cleanup EXIT INT TERM

  prepare_credentials

  local failed=0
  step_bucket || failed=1
  step_versioning || failed=1
  step_lifecycle || failed=1
  step_cors || failed=1

  # summary 自身也会因「未完成项」返回非 0，这里一并传递，避免被 return 0 吞掉
  if ((failed == 0)); then
    summary || return 1
    return 0
  fi
  summary || true
  return 1
}

main "$@"
