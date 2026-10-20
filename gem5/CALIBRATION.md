# Calibration log

Each entry: the setup, what changed, the RTL-vs-gem5 table. Regenerate a table
with `gem5/calibrate.sh -c CONFIG` (add `-f` after RTL changes).

## 2026-10-20: bare mode, `large`, untuned

Same config as below, but gem5 runs the RTL's own bare-metal ELFs (`run.sh -B`,
gem5 full-system bare-metal mode). ROI instructions now match the RTL exactly
(insts ratio 1.000 on all 9). Dhrystone moved most (ratio 0.930 → 1.101,
mpki 3.17 → 0.17), since it now runs riscv-tests' runtime instead of glibc and
`clock()`. The other outliers are unchanged, so the observations below still
hold.

```
bench          cycles    instret    ipc   mpki   rtl ipc ipc ratio insts ratio
dhrystone       96830     185531  1.916   0.17     1.740     1.101       1.000
median           5195       4000  0.770  32.04     0.813     0.947       1.000
memcpy           9842       5525  0.561   0.54     0.595     0.944       1.000
multiply        27679      24100  0.871  23.61     2.377     0.366       1.000
qsort          259713     122918  0.473  79.43     0.543     0.872       1.000
rsort          119378     146576  1.228   0.13     1.866     0.658       1.000
spmv            26692      28804  1.079  15.35     1.341     0.805       1.000
towers           2883       3782  1.312   8.47     0.910     1.441       1.000
vvadd            1263       2416  1.913   0.83     1.910     1.002       1.000
geomean ipc 1.014, geomean ipc ratio vs rtl 0.853
```

## 2026-10-19: first run, `large`, untuned

SE mode, in Docker. SE-mode cycle counts depend on the binary's host path
(docs/GEM5.md §3), so these exact numbers only reproduce under `/work/...`.

Setup: gem5 v25.1.0.1, `gem5/configs/boom.py` as first written (stage delays
are first guesses), memory latency 20 cycles on both sides, RTL seed 1,
benchmarks built with GCC 13 (container) for both sides.

```
bench          cycles    instret    ipc   mpki   rtl ipc ipc ratio insts ratio
dhrystone      103537     167603  1.619   3.17     1.740     0.930       0.903
median           5229       3991  0.763  33.58     0.813     0.939       0.998
memcpy           8344       5515  0.661   0.54     0.595     1.111       0.998
multiply        27773      24091  0.867  23.29     2.377     0.365       1.000
qsort          260971     122921  0.471  78.82     0.543     0.868       1.000
rsort          121630     146602  1.205   0.14     1.866     0.646       1.000
spmv            25909      28795  1.111  14.00     1.341     0.829       1.000
towers           3010       3743  1.244   6.41     0.910     1.366       0.990
vvadd            1230       2407  1.957   1.25     1.910     1.025       0.996
geomean ipc 1.009, geomean ipc ratio vs rtl 0.849
```

(cycles/instret/ipc/mpki are gem5's; mpki = conditional mispredicts per 1000
instructions.)

Observations:

* Instruction counts match (ratio ≈ 1) except dhrystone (0.90): glibc's string
  functions and `clock()` vs riscv-tests' own runtime and `mcycle`.
* **multiply (0.37)** is the biggest outlier, with gem5 mispredicting 23 times
  per 1000 instructions. Swapping in gem5's full-size default LTAGE only gets
  that to 18 (IPC 0.97), so table size isn't the cause. The RTL doesn't
  report mispredicts yet. Add that before tuning the predictor (BOOM's commit
  trace or perf counters).
* **rsort (0.65)** mispredicts almost never (0.14), so the gap is in the memory
  path: load-use latency, store-to-load forwarding, or gem5's store-set
  memory dependence prediction vs BOOM's speculate-and-replay.
* **towers (1.37)** is faster in gem5. It's short (3K cycles), so start-up
  effects weigh a lot.

Next steps, in order: report mispredicts (and D$ misses) from the RTL so both
sides can be compared stat by stat. Then calibrate the mispredict penalty
(stage delays) on a microbenchmark. Then the D$ latencies (rsort, memcpy). Add
longer workloads (CoreMark) before trusting differences under ~10%.
