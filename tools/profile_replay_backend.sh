#!/usr/bin/env bash
set -euo pipefail

ARCHIVE_DATE="${ARCHIVE_DATE:-2026-06-15}"
ARCHIVE_FILE="${ARCHIVE_FILE:-Juniper_Nebulizer_v9_0.4n_0.15mm_PP_Prusa_MK4S__job-231_run-20260615-160941-316.edn}"
BASE_URL="${BASE_URL:-http://localhost:9632}"
OUT_DIR="${OUT_DIR:-target/profiles/replay-backend-$(date -u +%Y%m%dT%H%M%SZ)}"
RECORDING_NAME="${RECORDING_NAME:-prusa-replay-backend}"
JFR_SETTINGS="${JFR_SETTINGS:-profile}"
ITERATIONS="${ITERATIONS:-1}"

mkdir -p "$OUT_DIR"

if ! command -v tmux >/dev/null 2>&1; then
  echo "tmux is required to find the dev-service backend process" >&2
  exit 1
fi

backend_pane_pid="$(
  tmux list-panes -t prusa-telemetry-dev -a -F '#{window_name} #{pane_pid}' \
    | awk '$1 == "backend" {print $2; exit}'
)"

if [[ -z "${backend_pane_pid}" ]]; then
  echo "Could not find backend pane in tmux session prusa-telemetry-dev" >&2
  exit 1
fi

backend_pid="$(
  pgrep -P "$backend_pane_pid" -f '(^|/)java([[:space:]]|$)' \
    | head -n 1
)"

if [[ -z "${backend_pid}" ]]; then
  echo "Could not find backend JVM child of pane PID ${backend_pane_pid}" >&2
  exit 1
fi

url="${BASE_URL}/api/telemetry-file-raw/${ARCHIVE_DATE}/${ARCHIVE_FILE}"
jfr_file="${OUT_DIR}/backend.jfr"
curl_metrics="${OUT_DIR}/curl-metrics.txt"
heap_before="${OUT_DIR}/heap-before.txt"
heap_after="${OUT_DIR}/heap-after.txt"
thread_after="${OUT_DIR}/thread-after.txt"

echo "Backend pane PID: ${backend_pane_pid}" | tee "${OUT_DIR}/backend-target.txt"
echo "Backend JVM PID:  ${backend_pid}" | tee -a "${OUT_DIR}/backend-target.txt"
echo "URL:              ${url}" | tee -a "${OUT_DIR}/backend-target.txt"
echo "Iterations:       ${ITERATIONS}" | tee -a "${OUT_DIR}/backend-target.txt"

jcmd "$backend_pid" GC.heap_info >"$heap_before" 2>&1 || true
jcmd "$backend_pid" JFR.stop name="$RECORDING_NAME" >/dev/null 2>&1 || true
jcmd "$backend_pid" JFR.start name="$RECORDING_NAME" settings="$JFR_SETTINGS" filename="$jfr_file" >/dev/null

: >"$curl_metrics"
for iteration in $(seq 1 "$ITERATIONS"); do
  echo "iteration=${iteration}" | tee -a "$curl_metrics"
  curl -fsS -o /dev/null \
    -w $'http_code=%{http_code}\nsize_download=%{size_download}\nspeed_download=%{speed_download}\ntime_starttransfer=%{time_starttransfer}\ntime_total=%{time_total}\n' \
    "$url" | tee -a "$curl_metrics"
done

jcmd "$backend_pid" JFR.stop name="$RECORDING_NAME" filename="$jfr_file" >/dev/null
jcmd "$backend_pid" GC.heap_info >"$heap_after" 2>&1 || true
jcmd "$backend_pid" Thread.print >"$thread_after" 2>&1 || true

if command -v jfr >/dev/null 2>&1; then
  jfr summary "$jfr_file" >"${OUT_DIR}/backend-jfr-summary.txt" 2>&1 || true
  jfr view hot-methods "$jfr_file" >"${OUT_DIR}/backend-hot-methods.txt" 2>&1 || true
  jfr view allocation-by-class "$jfr_file" >"${OUT_DIR}/backend-allocation-by-class.txt" 2>&1 || true
fi

echo "Backend replay profile written to ${OUT_DIR}"
