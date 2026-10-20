#!/usr/bin/env bash
# Compare the gem5 model against the RTL on the benchmark set. Run on the host
# from anywhere:
#
#   gem5/calibrate.sh [-c large] [-l 20] [-f] [-- boom.py args...]
#
#   -c CONFIG   BOOM preset, default large
#   -l N        memory latency in cycles, used by both sides (default 20)
#   -f          re-run the RTL even if gem5/results/rtl-CONFIG.csv exists
#
# Steps: build both benchmark variants, run the RTL (BenchmarksSpec, slow, only
# when needed), run gem5 (tag "calib"), print the per-benchmark IPC ratio.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here/.."                              # spinal-boom
config=large lat=20 force=0
while getopts "c:l:f" o; do
  case $o in c) config=$OPTARG;; l) lat=$OPTARG;; f) force=1;; *) exit 2;; esac
done
shift $((OPTIND - 1)); [ "${1:-}" = "--" ] && shift

# The benchmarks are built with the container's GCC 13 + picolibc; skip Docker
# when the binaries are already up to date.
make -q -C gem5/bench bare 2>/dev/null || gem5/docker.sh make -s -C gem5/bench
# gem5 runs natively when gem5/build.sh has built it on this host.
in_gem5_env=gem5/docker.sh
[ "$(uname -s)" = Darwin ] && [ -x ../ref/gem5/build_native/RISCV/gem5.opt ] && in_gem5_env=

rtl=gem5/results/rtl-$config.csv
mkdir -p gem5/results
# The RTL CSV does not record the latency it was run with: use -f after changing -l.
if [ "$force" = 1 ] || [ ! -f "$rtl" ]; then
  # shellcheck disable=SC1091
  source env.sh
  BENCH_CONFIG=$config BENCH_MEM_LATENCY=$lat sbt -batch "testOnly boom.sim.BenchmarksSpec" \
    | tee gem5/results/rtl-$config.log | grep -E '^BENCH|\*\*\*'
fi

# -B: gem5 runs the same bare-metal ELFs as the RTL.
$in_gem5_env gem5/run.sh -B -c "$config" -t calib -- --mem-latency "$lat" "$@"
python3 gem5/scripts/summarize.py "gem5/results/gem5-$config-bare-calib" --rtl "$rtl"
