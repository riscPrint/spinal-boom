#!/usr/bin/env bash
# Run the benchmark builds on the gem5 BOOM model, in parallel, then
# summarize. Run inside the container, or natively on macOS after gem5/build.sh:
#
#   gem5/docker.sh gem5/run.sh [-c large] [-t TAG] [-j N] [-- boom.py args...]
#   gem5/run.sh ...                     # macOS: uses ref/gem5/build_native/RISCV
#   GEM5=path/to/gem5.opt gem5/run.sh ...   # any other gem5 binary
#
#   -B          run the bare-metal builds (the ELFs the RTL runs, gem5 full-system
#               mode) instead of the Linux builds (SE mode)
#   -c CONFIG   BOOM preset (small/medium/large/mega), default large
#   -t TAG      results go to gem5/results/gem5-CONFIG[-bare][-TAG]/ (use one per experiment)
#   -j N        parallel gem5 runs (default: nproc - 1)
#   -b "a b"    only these benchmarks
#   after --     extra boom.py arguments, e.g. -- --set cpu.numROBEntries=128
set -euo pipefail
cd "$(dirname "$0")/.."                    # spinal-boom
ncpu=$(getconf _NPROCESSORS_ONLN)
config=large tag="" jobs=$(( ncpu > 1 ? ncpu - 1 : 1 )) benches="" mode=linux
while getopts "Bc:t:j:b:" o; do
  case $o in B) mode=bare;; c) config=$OPTARG;; t) tag=$OPTARG;; j) jobs=$OPTARG;; b) benches=$OPTARG;; *) exit 2;; esac
done
shift $((OPTIND - 1)); [ "${1:-}" = "--" ] && shift
extra=("$@")

build=build; [ "$(uname -s)" = Darwin ] && build=build_native
gem5=${GEM5:-../ref/gem5/$build/RISCV/gem5.opt}
[ -x "$gem5" ] || { echo "no $gem5: run gem5/build.sh first" >&2; exit 1; }
bin=gem5/bench/build/$mode
[ -n "$benches" ] || benches=$(cd "$bin" && ls *.riscv | sed 's/\.riscv$//')
suffix=""; [ "$mode" = bare ] && suffix=-bare
out=gem5/results/gem5-$config$suffix${tag:+-$tag}
mkdir -p "$out"
printf '%s\n' "--config $config ${extra[*]:-}" > "$out/args.txt"

# Extra args travel to the parallel children as one shell-quoted string.
EXTRA=""; [ ${#extra[@]} -eq 0 ] || EXTRA=$(printf "%q " "${extra[@]}")
export gem5 bin out config EXTRA
run_one() {
  local b=$1
  eval "local args=($EXTRA)"
  if "$gem5" --outdir="$out/$b" gem5/configs/boom.py --config "$config" "${args[@]+"${args[@]}"}" \
       "$bin/$b.riscv" > "$out/$b.log" 2>&1; then
    echo "ok   $b"
  else
    echo "FAIL $b (see $out/$b.log)"
  fi
}
export -f run_one
printf '%s\n' $benches | xargs -P "$jobs" -I{} bash -c 'run_one {}'
python3 gem5/scripts/summarize.py "$out"
