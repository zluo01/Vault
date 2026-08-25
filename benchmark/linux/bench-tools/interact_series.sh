#!/bin/bash
# Stage-2 interaction series: 1 warmup/config (discarded) + 25 rounds x 4 configs,
# one OBS recording. Mouse must remain untouched for the duration (~80 min).
# Outputs land in ../bench-results.
set -u
cd "$(dirname "$0")/../bench-results" || { mkdir -p "$(dirname "$0")/../bench-results" && cd "$(dirname "$0")/../bench-results"; }
T=../analysis-trace.log
ARMS="vault vaultaot mediadb mediadbw"

R=$(python3 ../bench-tools/obs_record.py start)
echo "$(date -Is) [iseries] OBS: $R" >> $T
echo "$R" | grep -q recording || { echo "$(date -Is) [iseries] OBS FAILED" >> $T; exit 1; }
sleep 2

for a in $ARMS; do
  python3 ../bench-tools/interact2.py $a warmup > /dev/null 2>&1
  echo "$(date -Is) [iseries] warmup $a done" >> $T
  sleep 1
done

for i in $(seq -w 0 24); do
  for a in $ARMS; do
    python3 ../bench-tools/interact2.py $a r$i > /dev/null 2>&1
    ab=$(python3 -c "import json;print(json.load(open('${a}_inter2_r$i.json')).get('aborted'))" 2>/dev/null)
    echo "$(date -Is) [iseries] ${a}_r$i done aborted=$ab" >> $T
    if [ "$ab" != "None" ]; then
      sleep 2
      python3 ../bench-tools/interact2.py $a r$i > /dev/null 2>&1
      echo "$(date -Is) [iseries] ${a}_r$i RETRIED" >> $T
    fi
    sleep 1
  done
done

S=$(python3 ../bench-tools/obs_record.py stop)
echo "$(date -Is) [iseries] OBS stopped: $S" >> $T
V=$(echo "$S" | python3 -c "import sys,json;print(json.load(sys.stdin)['file'])")
echo "VIDEO=$V" > interact_session.env
python3 ../bench-tools/interact_video.py "$V" \
  vault_inter2_r*.json vaultaot_inter2_r*.json \
  mediadb_inter2_r*.json mediadbw_inter2_r*.json > interact_results.jsonl 2>&1
echo "$(date -Is) [iseries] ISERIES-DONE lines: $(wc -l < interact_results.jsonl)" >> $T
