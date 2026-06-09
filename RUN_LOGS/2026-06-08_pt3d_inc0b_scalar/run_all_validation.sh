#!/usr/bin/env bash
set -u
REPO="/home/jba/Code/BoA"
RUN="$REPO/RUN_LOGS/2026-06-08_pt3d_inc0b_scalar/run_validation.sh"
echo "[$(date +%H:%M)] validation ensemble begin (5 seeds × 2 binaries, 1× CPU glidingAssay500_val)" >> "$REPO/.last_run_status"
for s in 1 2 3 4 5; do
  bash "$RUN" main_baseline "$s" /tmp/main_baseline
  bash "$RUN" scalar        "$s" /tmp/scalar_brown
done
echo "[$(date +%H:%M)] validation ensemble done" >> "$REPO/.last_run_status"
