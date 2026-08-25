#!/bin/bash
# Stage-1 startup series: 1 warmup/config (discarded) + 25 rounds x 4 configs,
# one OBS recording. Outputs land in ../bench-results. See README.md.
set -u
cd "$(dirname "$0")/../bench-results" || { mkdir -p "$(dirname "$0")/../bench-results" && cd "$(dirname "$0")/../bench-results"; }
T=../analysis-trace.log
ARMS="vault vaultaot mediadb mediadbw"

R=$(python3 ../bench-tools/obs_record.py start)
echo "$(date -Is) [pseries] OBS: $R" >> $T
echo "$R" | grep -q recording || { echo "$(date -Is) [pseries] OBS FAILED" >> $T; exit 1; }
sleep 2

for a in $ARMS; do
  python3 ../bench-tools/launch_percep.py $a warmup > /dev/null 2>&1
  echo "$(date -Is) [pseries] warmup $a done" >> $T
  sleep 1
done

for i in $(seq -w 0 24); do
  for a in $ARMS; do
    python3 ../bench-tools/launch_percep.py $a r$i > /dev/null 2>&1
    echo "$(date -Is) [pseries] ${a}_r$i done" >> $T
    sleep 1
  done
done

S=$(python3 ../bench-tools/obs_record.py stop)
echo "$(date -Is) [pseries] OBS stopped: $S" >> $T
V=$(echo "$S" | python3 -c "import sys,json;print(json.load(sys.stdin)['file'])")
echo "VIDEO=$V" > percep_session.env
python3 ../bench-tools/percep_video.py "$V" \
  vault_pstart_r*.json vaultaot_pstart_r*.json \
  mediadb_pstart_r*.json mediadbw_pstart_r*.json > percep_results.jsonl 2>&1
echo "$(date -Is) [pseries] PSERIES-DONE analysis lines: $(wc -l < percep_results.jsonl)" >> $T
