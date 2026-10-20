# gem5 model of BOOM

A gem5 O3 CPU set up like this repo's BOOM presets, for trying
microarchitecture ideas before building them in Spinal. See
[docs/GEM5.md](../docs/GEM5.md) for what the model can and can't tell you.

Nothing in `src/` depends on this directory.

This setup is BOOM-specific. The plan to make it work for any RISC-V core,
with native binaries instead of Docker, is the ARCH row in
[ROADMAP.md](../../ROADMAP.md) and §9 of [docs/GEM5.md](../docs/GEM5.md).

## Layout

| Path | What |
|---|---|
| `Dockerfile`, `docker.sh` | Container (Ubuntu 24.04, gem5 dependencies, RISC-V GCC 13 for Linux and bare metal), now only needed to build the benchmarks. `docker.sh CMD` runs CMD from `/work/spinal-boom`, with `~/fun/saviour` mounted at `/work`. |
| `build.sh` | Clones gem5 v25.1.0.1 into `../ref/gem5` and builds it: natively on macOS (`build_native/RISCV/gem5.opt`), or `build/RISCV/gem5.opt` inside the container |
| `configs/boom.py` | The gem5 config: BOOM presets → O3 parameters, four issue queues, TAGE-L, L1s straight to fixed-latency memory |
| `bench/` | riscv-tests benchmarks. `build/bare` is what both the RTL and gem5 run; `build/linux` is the older SE-mode build |
| `run.sh` | Runs every benchmark on gem5 in parallel and summarizes |
| `calibrate.sh` | RTL vs gem5 on the same benchmarks: builds, runs both, prints the IPC ratio |
| `scripts/summarize.py` | Reads the region-of-interest stats, writes a CSV, compares with the RTL or another gem5 run |
| `results/` | Output (generated) |
| `CALIBRATION.md` | The current RTL-vs-gem5 numbers and what's been tuned |

## First time

```sh
cd spinal-boom
gem5/build.sh                         # macOS: native gem5, ~9 min; prints any missing brew packages
gem5/docker.sh make -C gem5/bench     # benchmark builds (GCC 13 + picolibc in the container)
```

gem5 runs natively; Docker is only needed to build the benchmarks (the host
GCC has no picolibc), and `calibrate.sh` only starts it when they're out of
date. On Linux, or to use the container's gem5, run `gem5/docker.sh
gem5/build.sh` and prefix commands with `gem5/docker.sh`. The image is built
locally on first use (`saviour-gem5:v25.1`); gem5's ghcr.io images refused
pulls here.

## Everyday use

```sh
# Baseline: large preset, the RTL's own ELFs -> gem5/results/gem5-large-bare/
gem5/run.sh -B -c large

# An experiment: tag it, override parameters with --set PATH=VALUE
gem5/run.sh -B -c large -t rob128 -- --set cpu.numROBEntries=128
python3 gem5/scripts/summarize.py gem5/results/gem5-large-bare-rob128 --base gem5/results/gem5-large-bare

# One benchmark, by hand
../ref/gem5/build_native/RISCV/gem5.opt --outdir=gem5/results/x \
  gem5/configs/boom.py --config large gem5/bench/build/bare/qsort.riscv
```

`--set` takes `cpu.*`, `icache.*`, `dcache.*`, `mem.*`, `membus.*` or
`system.*`. The value is a Python expression with gem5's SimObject classes in
scope, e.g. `--set "cpu.instQueues=[IQUnit(numEntries=64)]"` for one unified
queue.

## Calibrating against the RTL

```sh
gem5/calibrate.sh -c large          # host; runs the RTL only if rtl-large.csv is missing
gem5/calibrate.sh -c large -f       # force an RTL rerun (needed after RTL changes or -l)
```

The RTL side is `src/test/scala/boom/sim/BenchmarksSpec.scala`
(`BENCH_CONFIG=large sbt "testOnly boom.sim.BenchmarksSpec"`). It runs the bare
builds with a fixed memory latency (`BENCH_MEM_LATENCY`, default 20 cycles)
instead of the randomized timing used in tests, and reads the ROI counters
from the D$ store stream. It takes roughly 10–20 minutes for `large`; run only
one sbt sim per checkout at a time (they share `simWorkspace/tmp`).

## Things to know

* **Same binary on both sides.** With `-B`, gem5 runs the exact ELFs the RTL
  runs, so the `insts ratio` column should be 1.000. Without `-B` (SE mode),
  gem5 runs the Linux builds, whose instruction counts differ (glibc,
  `clock()`). See docs/GEM5.md §3 for how bare mode starts, exits and finds
  the ROI.
* **Region of interest.** Each benchmark brackets its kernel with
  `setStats(1)`/`setStats(0)`, which read `mcycle`/`minstret` and store the
  deltas to `bench_stats`. The RTL harness reads those stores; gem5 reads
  `bench_stats` after exit (`bare.json` per run) and also resets/dumps its
  stats at the two calls, so the first dump in `stats.txt` is the ROI.
* **Benchmarks.** median, qsort, rsort, towers, vvadd, memcpy, multiply,
  dhrystone, spmv. `mm` runs in bare mode, but has no `setStats` calls and
  isn't in the RTL set yet. These ROIs are short (1K–250K cycles), so cold
  misses weigh a lot; add longer workloads (CoreMark, Embench) before trusting
  small differences.
* **gem5 25.1 quirk.** `commitToFetchDelay` > 1 deadlocks or panics the O3
  CPU. Leave it at 1 and model front-end depth with `fetchToDecodeDelay` and
  `iewToFetchDelay`.
* **Speed.** About 2 seconds for the whole set natively in parallel (about a minute in Docker). gem5 is
  single-threaded per run; `run.sh` runs `nproc - 1` at once.
