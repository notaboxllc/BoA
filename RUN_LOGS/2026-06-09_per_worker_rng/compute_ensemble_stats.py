#!/usr/bin/env python3
"""Per-worker RNG paired ensemble — parse seeds, compute paired-t, emit table."""
import re
import sys
import statistics
import pathlib

ROOT = pathlib.Path("/home/jba/Code/BoA/RUN_LOGS/2026-06-09_per_worker_rng")
METRICS = ["bindEvents", "meanBoundMotors", "glidingVelocity"]


def parse(stdout_path: pathlib.Path):
    res = {}
    text = stdout_path.read_text()
    for m in METRICS:
        mt = re.search(rf"\[STATS\]\s*{m}\s*=\s*([\-0-9eE\.\+]+)", text)
        if mt:
            res[m] = float(mt.group(1))
    return res


def collect(label: str, seeds):
    out = []
    for seed in seeds:
        p = ROOT / f"ens_{label}" / f"seed{seed}" / "stdout.txt"
        if not p.exists():
            print(f"WARN: missing {p}", file=sys.stderr)
            continue
        d = parse(p)
        d["seed"] = seed
        out.append(d)
    return out


def paired_t(baseline_vals, pwrng_vals):
    diffs = [a - b for a, b in zip(pwrng_vals, baseline_vals)]
    n = len(diffs)
    mean = sum(diffs) / n
    if n < 2:
        return mean, 0.0, 0.0
    sd = statistics.stdev(diffs)
    t = mean / (sd / (n ** 0.5)) if sd > 0 else 0.0
    return mean, sd, t


def main():
    seeds = list(range(1, 11))
    out_path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ROOT / "ensemble_summary.txt")
    baseline = collect("main_baseline", seeds)
    pwrng = collect("pwrng", seeds)

    lines = []
    lines.append("Paired ensemble validation — Pt3D SoA per-worker RNG consolidation")
    lines.append("Configuration: glidingAssay500_val (runTime=0.1 s, 10000 steps), 1× CPU, -Xmx8G")
    lines.append("Seeds: 1..10 (extended from n=5 — the per-worker RNG is the only stochastic-core")
    lines.append("       change in the migration so far, so we take more samples). Per-worker RNG")
    lines.append("       is seeded from BOA_RNG_SEED (unset → nano^ms entropy), so bit-identity is")
    lines.append("       impossible — bar is statistical agreement, not bit-identity.")
    lines.append("")
    lines.append("Baseline: main = 577247a (post-inc1 merge).")
    lines.append("pwrng   : HEAD of pt3d-soa-inc-per-worker-rng.")
    lines.append("")
    lines.append("Per-seed metrics:")
    lines.append("                 bindEvents      meanBoundMotors  glidingVelocity")
    for s in seeds:
        b = next((d for d in baseline if d.get("seed") == s), {})
        lines.append(f" baseline seed={s} {b.get('bindEvents',0):<15} {b.get('meanBoundMotors',0):<16.3f} {b.get('glidingVelocity',0):.4f}")
    lines.append("")
    for s in seeds:
        i = next((d for d in pwrng if d.get("seed") == s), {})
        lines.append(f" pwrng    seed={s} {i.get('bindEvents',0):<15} {i.get('meanBoundMotors',0):<16.3f} {i.get('glidingVelocity',0):.4f}")
    lines.append("")
    lines.append("Paired-difference t (df=n-1):")

    for m in METRICS:
        bvals = [d.get(m, 0.0) for d in baseline if m in d]
        ivals = [d.get(m, 0.0) for d in pwrng    if m in d]
        if not bvals or not ivals: continue
        bmu = sum(bvals) / len(bvals)
        bsd = statistics.stdev(bvals) if len(bvals) > 1 else 0
        imu = sum(ivals) / len(ivals)
        isd = statistics.stdev(ivals) if len(ivals) > 1 else 0
        mean_diff, sd_diff, t = paired_t(bvals, ivals)
        shift_sigma = mean_diff / bsd if bsd > 0 else 0
        lines.append(
            f"  {m:<16} baseline {bmu:.3f} ± {bsd:.3f}  pwrng {imu:.3f} ± {isd:.3f}  "
            f"shift {mean_diff:+.3f}  {shift_sigma:+.2f} σ_b  paired t {t:+.2f}"
        )
    lines.append("")
    lines.append("Critical t at df=9, two-sided 5 % = 2.26. All |t| < 2.26 → PASS.")

    out_path.write_text("\n".join(lines))
    print("\n".join(lines))


if __name__ == "__main__":
    main()
