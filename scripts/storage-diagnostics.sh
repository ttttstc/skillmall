#!/usr/bin/env bash
# Source-checkout diagnostic; writes only a random diagnostic prefix in a prepared bucket.
set -euo pipefail
umask 077
full=false
deadline=180s
if [[ "${1:-}" == --full && $# -eq 1 ]]; then
  full=true
  deadline=420s
elif (($#)); then
  echo "Usage: $0 [--full]" >&2
  exit 2
fi
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v timeout >/dev/null || { echo "ERROR: GNU timeout is required" >&2; exit 2; }
# provider=s3 (default) uses the SigV4 contract test; provider=obs uses the native OBS contract test,
# because OBS presigned URLs are not SigV4 and the S3 probe would misjudge them.
provider="${SKILLHUB_STORAGE_PROVIDER:-s3}"
case "$provider" in
  s3)
    : "${SKILLHUB_STORAGE_S3_ENDPOINT:?A reachable S3 endpoint is required}"
    : "${SKILLHUB_STORAGE_S3_BUCKET:?A prepared private bucket is required}"
    test_class=S3StorageContractTest
    contract_flag=-Dskillhub.storage.contract=true
    full_flag=-Dskillhub.storage.contract.full="$full"
    ;;
  obs)
    : "${SKILLHUB_STORAGE_OBS_ENDPOINT:?A reachable OBS endpoint is required}"
    : "${SKILLHUB_STORAGE_OBS_BUCKET:?A prepared private OBS bucket is required}"
    test_class=ObsStorageContractTest
    contract_flag=-Dskillhub.storage.obs.contract=true
    full_flag=-Dskillhub.storage.obs.contract.full="$full"
    ;;
  *)
    echo "ERROR: unsupported storage provider '$provider' (expected s3 or obs)" >&2
    exit 2
    ;;
esac
log_dir="${SKILLHUB_STORAGE_DIAGNOSTICS_LOG_DIR:-$REPO_ROOT/.dev/storage-diagnostics}"
mkdir -p "$log_dir"
log_file="$(mktemp "$log_dir/run-XXXXXXXX.log")"
cd "$REPO_ROOT/server"
# Raw SDK/network errors may contain signed URLs; retain them only in the private log.
if timeout --signal=TERM --kill-after=15s "$deadline" ./mvnw -pl skillhub-storage -am \
  -Dtest="$test_class" -Dsurefire.failIfNoSpecifiedTests=false \
  "$contract_flag" "$full_flag" test >"$log_file" 2>&1; then
  echo "Storage diagnostic passed (objects, metadata, signatures, privacy, deletion)."
  echo "Private log: $log_file"
else
  result=$?
  echo "Storage diagnostic failed (exit $result). Check the private log; do not publish it unredacted." >&2
  echo "Private log: $log_file" >&2
  exit "$result"
fi
