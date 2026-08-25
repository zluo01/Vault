#!/bin/bash
# Stage-3 idle-footprint series: 1 warmup/config + 25 runs x 4 configs, no video.
# Outputs land in ../bench-results.
set -u
cd "$(dirname "$0")/../bench-results" || { mkdir -p "$(dirname "$0")/../bench-results" && cd "$(dirname "$0")/../bench-results"; }
T=../analysis-trace.log
ARMS="vault vaultch mediadb mediadbw"

for a in $ARMS; do
  python3 ../bench-tools/resource2.py $a warmup > /dev/null 2>&1
  echo "$(date -Is) [rseries] warmup $a done" >> $T
  sleep 1
done

for i in $(seq -w 0 24); do
  for a in $ARMS; do
    python3 ../bench-tools/resource2.py $a r$i > /dev/null 2>&1
    echo "$(date -Is) [rseries] ${a}_r$i done" >> $T
    sleep 1
  done
done
echo "$(date -Is) [rseries] RSERIES-DONE" >> $T
