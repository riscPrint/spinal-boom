#!/usr/bin/env python3
"""Summarize gem5 BOOM runs, and compare them with the RTL.

  summarize.py gem5/results/gem5-large            # writes gem5-large.csv, prints a table
  summarize.py gem5/results/gem5-large --rtl gem5/results/rtl-large.csv
  summarize.py gem5/results/gem5-large-rob128 --base gem5/results/gem5-large

Each run's m5out/stats.txt holds one dump per m5_dump_stats(). The benchmark
calls it at the end of its region of interest (setStats(0)), after a stats
reset at the start, so the first dump is exactly the ROI.

Bare-metal runs (boom.py on gem5/bench/build/bare) also leave bare.json: the
exit code and the program's own mcycle/minstret ROI deltas (bench_stats), the
same numbers the RTL reports. Those replace cycles/instret/ipc; the other
columns come from the stats dump, whose window starts and ends at the first
instruction of setStats (a few instructions off the minstret reads).
"""
import json
import argparse
import csv
import os
import sys

# (column, stat names to try in order). Names drift between gem5 versions.
STATS = [
    ("cycles", ["system.cpu.numCycles"]),
    ("instret", ["system.cpu.commitStats0.numInsts", "system.cpu.thread_0.numInsts",
                 "system.cpu.committedInsts", "simInsts"]),
    ("cond_mispred", ["system.cpu.branchPred.condIncorrect"]),
    ("cond_pred", ["system.cpu.branchPred.condPredicted"]),
    ("dcache_miss", ["system.cpu.dcache.overallMisses::total", "system.cpu.dcache.demandMisses::total"]),
    ("icache_miss", ["system.cpu.icache.overallMisses::total", "system.cpu.icache.demandMisses::total"]),
    ("rob_full", ["system.cpu.rename.ROBFullEvents"]),
    ("iq_full", ["system.cpu.rename.IQFullEvents"]),
    ("lq_full", ["system.cpu.rename.LQFullEvents"]),
    ("sq_full", ["system.cpu.rename.SQFullEvents"]),
    ("regs_full", ["system.cpu.rename.fullRegistersEvents"]),
]


def first_dump(path):
    stats, started = {}, False
    with open(path) as f:
        for line in f:
            if line.startswith("---------- Begin"):
                started = True
                continue
            if line.startswith("---------- End"):
                break
            if started and line.strip() and not line.startswith("#"):
                parts = line.split()
                try:
                    stats[parts[0]] = float(parts[1])
                except (IndexError, ValueError):
                    pass
    return stats


def pick(stats, names):
    for n in names:
        if n in stats:
            return stats[n]
    return None


def load_gem5(d):
    rows = {}
    for b in sorted(os.listdir(d)):
        st = os.path.join(d, b, "stats.txt")
        if not os.path.isfile(st):
            continue
        s = first_dump(st)
        r = {k: pick(s, names) for k, names in STATS}
        if r["cycles"] and r["instret"]:
            if r["cond_mispred"] is not None:
                r["mpki"] = 1000 * r["cond_mispred"] / r["instret"]
            bj = os.path.join(d, b, "bare.json")
            if os.path.isfile(bj):
                with open(bj) as f:
                    bare = json.load(f)
                r["exit_code"] = bare["exit_code"]
                if bare.get("roi") == "setStats" and "bench_stats" in bare:
                    r["cycles"] = bare["bench_stats"]["cycles"]
                    r["instret"] = bare["bench_stats"]["instret"]
                if bare["exit_code"] != 0:
                    print(f"warning: {b} exited with code {bare['exit_code']}", file=sys.stderr)
            r["ipc"] = r["instret"] / r["cycles"]
            rows[b] = r
    return rows


def load_csv(path):
    with open(path) as f:
        return {r["bench"]: {k: float(v) for k, v in r.items() if k != "bench" and v not in ("", "None")}
                for r in csv.DictReader(f)}


def geomean(xs):
    xs = [x for x in xs if x and x > 0]
    if not xs:
        return float("nan")
    p = 1.0
    for x in xs:
        p *= x
    return p ** (1 / len(xs))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dir")
    ap.add_argument("--rtl", help="RTL CSV from BenchmarksSpec (gem5/results/rtl-CONFIG.csv)")
    ap.add_argument("--base", help="another gem5 results dir to compare against")
    a = ap.parse_args()

    rows = load_gem5(a.dir)
    if not rows:
        sys.exit(f"no finished runs in {a.dir}")
    cols = ["cycles", "instret", "ipc", "mpki"] + [k for k, _ in STATS[4:]]
    if any("exit_code" in r for r in rows.values()):
        cols.append("exit_code")
    out_csv = a.dir.rstrip("/") + ".csv"
    with open(out_csv, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["bench"] + cols)
        for b, r in rows.items():
            w.writerow([b] + [r.get(c) for c in cols])

    ref, ref_name = None, None
    if a.rtl and os.path.isfile(a.rtl):
        ref, ref_name = load_csv(a.rtl), "rtl"
    elif a.base:
        ref, ref_name = {b: r for b, r in load_gem5(a.base).items()}, "base"

    hdr = f"{'bench':<10} {'cycles':>10} {'instret':>10} {'ipc':>6} {'mpki':>6}"
    if ref:
        hdr += f" {ref_name + ' ipc':>9} {'ipc ratio':>9} {'insts ratio':>11}"
    print(hdr)
    ratios = []
    for b, r in rows.items():
        line = f"{b:<10} {r['cycles']:>10.0f} {r['instret']:>10.0f} {r['ipc']:>6.3f} {r.get('mpki', float('nan')):>6.2f}"
        if ref and b in ref:
            ri = ref[b]["ipc"]
            ratio = r["ipc"] / ri
            ratios.append(ratio)
            line += f" {ri:>9.3f} {ratio:>9.3f} {r['instret'] / ref[b]['instret']:>11.3f}"
        print(line)
    print(f"geomean ipc {geomean([r['ipc'] for r in rows.values()]):.3f}"
          + (f", geomean ipc ratio vs {ref_name} {geomean(ratios):.3f}" if ratios else ""))
    print(f"wrote {out_csv}")


if __name__ == "__main__":
    main()
