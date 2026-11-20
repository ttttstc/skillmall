#!/usr/bin/env bash
# =============================================================================
# SkillHub on Huawei Cloud CCE —— 部署前置检查
# =============================================================================
# 只做只读检查（--net-check 例外：会在集群里起一个一次性 Pod 做连通性探测）。
# 不创建/修改任何 SkillHub 资源，不读取 Secret 的明文值。
#
# 用法：
#   deploy/cce/preflight.sh [选项]
#
# 选项：
#   -n, --namespace <name>   目标命名空间（默认 skillhub）
#   -r, --release <name>     Helm release 名（默认 skillhub）
#   -f, --values <file>      从 values 文件里粗读 OBS/RDS/DCS 主机用于探测（可重复）
#       --incluster-db       计划使用 chart 内置 PostgreSQL/Redis（会额外检查 csi-disk）
#       --net-check          在集群内起一次性 Pod 探测 OBS/RDS/DCS/元数据服务可达性
#       --check-obs          检查 OBS 桶是否可访问（需要 obsutil 与 OBS_* 环境变量）
#       --skip-secret        跳过 Secret 键检查
#   -h, --help               显示本帮助
#
# 环境变量：
#   PREFLIGHT_IMAGE          连通性探测用的镜像（默认 busybox:1.37）。
#                            CCE 节点通常无法直连 docker.io，请改填 SWR 里的镜像，
#                            例如 swr.cn-north-4.myhuaweicloud.com/<org>/busybox:1.37
#   SKILLHUB_SECRET_NAME     应用 Secret 名（默认 skillhub-app-secrets）
#   OBS_ENDPOINT / OBS_BUCKET / RDS_HOST / DCS_HOST
#                            不传 --values 时可用于 --net-check / --check-obs
#
# 退出码：0 全部通过（可能有 WARN）；1 有 FAIL。
# =============================================================================
set -euo pipefail

NAMESPACE="skillhub"
RELEASE="skillhub"
VALUES_FILES=()
INCLUSTER_DB=0
NET_CHECK=0
CHECK_OBS=0
SKIP_SECRET=0
SECRET_NAME="${SKILLHUB_SECRET_NAME:-skillhub-app-secrets}"
PREFLIGHT_IMAGE="${PREFLIGHT_IMAGE:-busybox:1.37}"

PASS=0
WARN=0
FAIL=0

usage() {
  sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

log_pass() { PASS=$((PASS + 1)); printf '  [PASS] %s\n' "$*"; }
log_warn() { WARN=$((WARN + 1)); printf '  [WARN] %s\n' "$*"; }
log_fail() { FAIL=$((FAIL + 1)); printf '  [FAIL] %s\n' "$*"; }
log_info() { printf '  [INFO] %s\n' "$*"; }
section() { printf '\n== %s ==\n' "$*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    -n | --namespace) NAMESPACE="${2:?--namespace 需要参数}"; shift 2 ;;
    -r | --release) RELEASE="${2:?--release 需要参数}"; shift 2 ;;
    -f | --values) VALUES_FILES+=("${2:?--values 需要参数}"); shift 2 ;;
    --incluster-db) INCLUSTER_DB=1; shift ;;
    --net-check) NET_CHECK=1; shift ;;
    --check-obs) CHECK_OBS=1; shift ;;
    --skip-secret) SKIP_SECRET=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "未知参数：$1（-h 查看帮助）" >&2; exit 2 ;;
  esac
done

# 从 values 文件里粗读主机名，仅用于连通性探测；不作为配置生效的证据。
read_from_values() {
  local key="$1"
  local file
  for file in "${VALUES_FILES[@]}"; do
    [ -f "$file" ] || continue
    sed -n "s/^[[:space:]]*${key}:[[:space:]]*[\"']\{0,1\}\([^\"'#]*\)[\"']\{0,1\}.*/\1/p" "$file" | head -1
  done
}

host_of() {
  local url="$1"
  url="${url#http://}"
  url="${url#https://}"
  printf '%s' "${url%%/*}" | sed 's/:.*$//'
}

section "工具"
if command -v kubectl >/dev/null 2>&1; then
  log_pass "kubectl：$(command -v kubectl)"
else
  log_fail "缺少 kubectl"
fi
if command -v helm >/dev/null 2>&1; then
  log_pass "helm：$("$(command -v helm)" version --short 2>/dev/null || echo '版本未知')"
else
  log_warn "缺少 helm：安装 chart 需要它（也可用预先打包的 tgz + helm）"
fi
for tool in obsutil regctl docker; do
  if command -v "$tool" >/dev/null 2>&1; then
    log_pass "可选工具 $tool：$(command -v "$tool")"
  else
    log_warn "可选工具 $tool 未安装（按需：OBS 初始化 / 镜像同步 / 本地构建）"
  fi
done

if [ "$FAIL" -gt 0 ]; then
  section "结论"
  printf '  前置工具缺失，后续检查跳过。\n'
  exit 1
fi

section "集群连接"
if kubectl version --request-timeout=8s >/dev/null 2>&1; then
  local_ctx="$(kubectl config current-context 2>/dev/null || echo '未知')"
  log_pass "kubectl 可连接集群，context=${local_ctx}"
else
  log_fail "kubectl 无法连接集群（检查 kubeconfig / CCE 集群状态）"
  section "结论"
  printf '  无法继续检查。\n'
  exit 1
fi

section "命名空间"
if kubectl get namespace "$NAMESPACE" >/dev/null 2>&1; then
  log_pass "命名空间 $NAMESPACE 存在"
else
  log_fail "命名空间 $NAMESPACE 不存在：kubectl create namespace $NAMESPACE"
fi

section "Ingress"
ingress_classes="$(kubectl get ingressclass -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true)"
if printf '%s' "$ingress_classes" | grep -qx 'nginx'; then
  log_pass "存在 IngressClass nginx（CCE NGINX Ingress 插件）"
else
  log_fail "未找到 IngressClass nginx。请在 CCE 安装「NGINX Ingress 控制器」插件；若只用 ELB Ingress(cce)，扫描类长请求会因 member_timeout 上限 300s 而 504"
fi
if printf '%s' "$ingress_classes" | grep -qx 'cce'; then
  log_info "同时存在 IngressClass cce（ELB Ingress）。注意：不显式写 ingressClassName 时 CCE 默认落到 cce"
fi

section "镜像拉取"
if kubectl -n "$NAMESPACE" get secret default-secret >/dev/null 2>&1; then
  log_pass "命名空间内存在 CCE 自动创建的 default-secret（SWR 拉取凭证）"
else
  log_warn "未找到 default-secret：确认这是 CCE 集群且命名空间已创建；不要手工复制该 Secret（会被轮换失效）"
fi

section "弹性伸缩"
if kubectl get apiservice v1beta1.metrics.k8s.io >/dev/null 2>&1; then
  log_pass "metrics API 可用（HPA 依赖它）"
else
  log_warn "metrics API 不可用：server/web/scanner 的 HPA 会拿不到指标；按需安装 CCE 指标采集/云原生监控插件或把 autoscaling.enabled 置 false"
fi

section "存储类"
if [ "$INCLUSTER_DB" -eq 1 ]; then
  scs="$(kubectl get storageclass -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true)"
  if printf '%s' "$scs" | grep -qx 'csi-disk'; then
    log_pass "存在 StorageClass csi-disk（EVS，内置 PostgreSQL/Redis 需要）"
  else
    log_fail "未找到 csi-disk：内置数据库 PVC 会 Pending"
  fi
  log_info "csi-disk 仅支持 ReadWriteOnce；内置库副本数不要超过 1 个写节点"
else
  log_info "本次按托管 RDS/DCS 检查，跳过 StorageClass"
fi

section "应用 Secret"
if [ "$SKIP_SECRET" -eq 1 ]; then
  log_info "已跳过"
elif kubectl -n "$NAMESPACE" get secret "$SECRET_NAME" >/dev/null 2>&1; then
  keys="$(kubectl -n "$NAMESPACE" get secret "$SECRET_NAME" -o jsonpath='{range $k,$v := .data}{$k}{"\n"}{end}' 2>/dev/null || true)"
  # chart 中对 server 非 optional 的引用 + 托管数据库路径所需键
  required_keys="spring-datasource-password skillhub-download-anon-cookie-secret bootstrap-admin-password redis-password"
  for key in $required_keys; do
    if printf '%s' "$keys" | grep -qx "$key"; then
      log_pass "Secret 键存在：$key"
    else
      log_fail "Secret 缺少键：$key（只检查存在性，不读取内容）"
    fi
  done
  for key in skillhub-storage-obs-access-key skillhub-storage-obs-secret-key oauth2-github-client-id scanner-llm-api-key; do
    if printf '%s' "$keys" | grep -qx "$key"; then
      log_info "可选键存在：$key"
    fi
  done
else
  log_fail "Secret $SECRET_NAME 不存在（先按 deploy/cce/secrets-cce.example.yaml 预创建）"
fi

# ---------------------------------------------------------------------------
# 集群内连通性探测：本机通不代表 Pod 通（安全组、NetworkPolicy、跨 VPC）
# ---------------------------------------------------------------------------
if [ "$NET_CHECK" -eq 1 ]; then
  section "集群内连通性（一次性 Pod：$PREFLIGHT_IMAGE）"
  obs_host="${OBS_ENDPOINT:-$(read_from_values 'endpoint')}"
  obs_host="$(host_of "${obs_host:-}")"
  rds_host="${RDS_HOST:-$(read_from_values 'host')}"
  dcs_host="${DCS_HOST:-}"
  if [ -z "$dcs_host" ] && [ ${#VALUES_FILES[@]} -gt 0 ]; then
    # values 里 externalDatabase/externalRedis 都有 host 键，第二次出现通常是 Redis
    dcs_host="$(for f in "${VALUES_FILES[@]}"; do sed -n "s/^[[:space:]]*host:[[:space:]]*[\"']\{0,1\}\([^\"'#]*\)[\"']\{0,1\}.*/\1/p" "$f"; done | sed -n '2p')"
  fi

  probe_targets=""
  [ -n "$obs_host" ] && probe_targets="${probe_targets}${obs_host}:443:OBS "
  [ -n "$rds_host" ] && probe_targets="${probe_targets}${rds_host}:5432:RDS "
  [ -n "$dcs_host" ] && probe_targets="${probe_targets}${dcs_host}:6379:DCS "

  if [ -z "$probe_targets" ]; then
    log_warn "没有可探测的目标：用 --values 或 OBS_ENDPOINT/RDS_HOST/DCS_HOST 提供"
  else
    log_info "探测目标：$probe_targets"
    probe_pod="skillhub-preflight-$RANDOM"
    probe_script='
for spec in $PROBE_TARGETS; do
  host=$(echo "$spec" | cut -d: -f1); port=$(echo "$spec" | cut -d: -f2); label=$(echo "$spec" | cut -d: -f3)
  if nc -z -w 3 "$host" "$port" 2>/dev/null; then echo "PROBE_OK $label $host:$port"; else echo "PROBE_FAIL $label $host:$port"; fi
done
if wget -q -T 3 -O /dev/null http://169.254.169.254/openstack/latest/securitykey 2>/dev/null; then echo "PROBE_OK METADATA 169.254.169.254:80"; else echo "PROBE_FAIL METADATA 169.254.169.254:80"; fi
'
    set +e
    output="$(kubectl -n "$NAMESPACE" run "$probe_pod" --image="$PREFLIGHT_IMAGE" --restart=Never --rm -i --quiet \
      --env="PROBE_TARGETS=$probe_targets" --command -- sh -c "$probe_script" 2>&1)"
    probe_status=$?
    set -e
    if [ $probe_status -ne 0 ] && [ -z "$output" ]; then
      log_warn "探测 Pod 未能运行（镜像拉取失败？）。设置 PREFLIGHT_IMAGE 为 SWR 中的镜像后重试"
    else
      printf '%s\n' "$output" | sed 's/^/    /'
      printf '%s' "$output" | grep -q 'PROBE_FAIL' && log_fail "存在不可达目标（检查安全组/对等连接/NetworkPolicy/是否同 VPC）" || log_pass "所有目标可达"
      printf '%s' "$output" | grep -q 'PROBE_FAIL METADATA' && log_warn "元数据服务不可达：obs.credentialMode 需改为 static 并提供 AK/SK，或为集群配置 IAM 委托与元数据通路"
    fi
  fi
fi

if [ "$CHECK_OBS" -eq 1 ]; then
  section "OBS 桶可访问性（管理员凭据）"
  if command -v obsutil >/dev/null 2>&1 && [ -n "${OBS_ENDPOINT:-}" ] && [ -n "${OBS_BUCKET:-}" ]; then
    if obsutil ls "obs://${OBS_BUCKET}" -e="$OBS_ENDPOINT" >/dev/null 2>&1; then
      log_pass "OBS 桶 ${OBS_BUCKET} 可列出"
    else
      log_fail "无法列出 OBS 桶 ${OBS_BUCKET}（检查桶名/区域/凭据/桶策略）"
    fi
  else
    log_warn "跳过：需要 obsutil 与 OBS_ENDPOINT/OBS_BUCKET（以及当前会话的 OBS 凭据）"
  fi
fi

section "结论"
printf '  PASS=%d WARN=%d FAIL=%d\n' "$PASS" "$WARN" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf '  存在阻塞项，先修复 FAIL 再部署。\n'
  exit 1
fi
printf '  无阻塞项。提醒：本脚本只检查环境就绪，不能证明 OBS 读写、扫描链路或业务闭环可用。\n'
