#!/bin/bash
# Runs 10 seeds sequentially for CPU optimization validation.
set -e
cd /home/jba/Code/BoA
RUN_DIR=RUN_LOGS
DATE=2026-05-30
echo "mode seed bindEvents meanBoundMotors glidingVelocity wall_sec" > $RUN_DIR/${DATE}_cpuopt-validation.txt
for SEED in 1 2 3 4 5 6 7 8 9 10; do
    echo "[$(date +%H:%M:%S)] Starting seed $SEED" | tee -a .last_run_status
    LOG=$RUN_DIR/${DATE}_cpuopt_cpu_seed${SEED}.log
    START=$(date +%s)
    java --enable-preview -Xmx800M -cp "libs/*:." BoxOfActin -r \
        -pf ParameterFiles/glidingAssay500_val \
        -seed $SEED -3js /tmp/cpuopt_seed${SEED} > $LOG 2>&1
    END=$(date +%s)
    WALL=$((END-START))
    BE=$(grep "bindEvents=" $LOG | head -1 | sed 's/.*bindEvents=//' | tr -d '\r')
    MB=$(grep "meanBoundMotors=" $LOG | head -1 | sed 's/.*meanBoundMotors=//' | tr -d '\r')
    GV=$(grep "glidingVelocity=" $LOG | head -1 | sed 's/.*glidingVelocity=//' | tr -d '\r')
    echo "cpu $SEED $BE $MB $GV $WALL" >> $RUN_DIR/${DATE}_cpuopt-validation.txt
    echo "[$(date +%H:%M:%S)] Done seed $SEED: bindEvents=$BE meanBoundMotors=$MB glidingVelocity=$GV wall=${WALL}s" | tee -a .last_run_status
    rm -rf /tmp/cpuopt_seed${SEED}
done
echo "[$(date +%H:%M:%S)] All 10 seeds complete." | tee -a .last_run_status
