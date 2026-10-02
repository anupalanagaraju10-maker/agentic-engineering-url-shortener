#!/usr/bin/env bash
# Exports the runtime evidence of one live workflow run (T126; FR-OBS-001, NFR-006).
#
#   scripts/export-run.sh <scenario> <run-id> [base-url]
#   e.g. scripts/export-run.sh scn-a d76be1a7-7e1d-4338-8fd4-c47535786b1e
#
# Writes the run, its audit events, decision lineage, final report and the demonstration metrics, exactly as
# served by the running application, into docs/scenarios/<scenario>/. The files are runtime output: never edit
# them by hand (T127).
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <scenario> <run-id> [base-url]" >&2
  exit 2
fi

scenario="$1"
run_id="$2"
base="${3:-http://localhost:8080}"
out="docs/scenarios/${scenario}"
mkdir -p "${out}"

fetch() {
  # -f: fail on HTTP errors, so a missing run or report never produces an empty evidence file
  curl -sSf "$1" -o "$2"
  echo "  ${2}"
}

echo "Exporting run ${run_id} from ${base} into ${out}/"
fetch "${base}/api/workflows/${run_id}"           "${out}/run.json"
fetch "${base}/api/workflows/${run_id}/events"    "${out}/events.json"
fetch "${base}/api/workflows/${run_id}/decisions" "${out}/decisions.json"
fetch "${base}/api/workflows/${run_id}/report"    "${out}/report.json"
fetch "${base}/api/metrics/workflows?faultInjected=false" "${out}/metrics-not-injected.json"
fetch "${base}/api/metrics/workflows?faultInjected=true"  "${out}/metrics-injected.json"
date -u +"exported %Y-%m-%dT%H:%M:%SZ from ${base}, run ${run_id}" > "${out}/EXPORTED.txt"
echo "Done."
