#!/usr/bin/env bash
# Clone (if needed) and build gem5 for RISC-V.
#   gem5/docker.sh gem5/build.sh [opt|fast|debug]   # in the container -> ref/gem5/build/RISCV
#   gem5/build.sh [opt|fast|debug]                  # natively on macOS -> ref/gem5/build_native/RISCV
# Native deps (macOS): Xcode clang, Homebrew python@3.14, protobuf, gperftools,
# libpng, pkgconf; scons in a venv at $GEM5_VENV (default ~/opt/gem5-venv,
# created on first run).
set -euo pipefail
cd "$(dirname "$0")/../.."                 # saviour
ver="v25.1.0.1"
variant="${1:-opt}"
ncpu=$(getconf _NPROCESSORS_ONLN)
jobs="${GEM5_JOBS:-$(( ncpu > 2 ? ncpu - 2 : 1 ))}"

if [ ! -d ref/gem5 ]; then
  git clone --depth 1 --branch "$ver" https://github.com/gem5/gem5 ref/gem5
fi
cd ref/gem5

if [ "$(uname -s)" != Darwin ]; then
  # gem5's SConstruct asks interactively to install git hooks; skip that.
  exec scons build/RISCV/gem5.$variant -j"$jobs" --ignore-style
fi

# Native macOS build. Separate build root so it never mixes with the Linux
# (container) objects in build/RISCV.
dir=build_native/RISCV
brew=$(brew --prefix 2>/dev/null) || { echo "Homebrew not found (https://brew.sh)" >&2; exit 1; }
py=$brew/opt/python@3.14/bin/python3.14
missing=()
xcrun clang++ --version >/dev/null 2>&1 || { echo "no clang++: run xcode-select --install" >&2; exit 1; }
[ -x "$py" ] || missing+=(python@3.14)
for f in protobuf gperftools libpng pkgconf; do
  [ -d "$brew/opt/$f" ] || missing+=("$f")
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "missing Homebrew packages: brew install ${missing[*]}" >&2; exit 1
fi
venv="${GEM5_VENV:-$HOME/opt/gem5-venv}"
if [ ! -x "$venv/bin/scons" ]; then
  echo "creating scons venv in $venv"
  "$py" -m venv "$venv" && "$venv/bin/pip" install -q scons
fi
export PATH="$venv/bin:$PATH"
export PYTHON_CONFIG="$py-config"          # embed the same Python scons runs on
export CPATH="$brew/include${CPATH:+:$CPATH}"
export LIBRARY_PATH="$brew/lib${LIBRARY_PATH:+:$LIBRARY_PATH}"
[ -d "$dir/gem5.build" ] || scons defconfig "$dir" build_opts/RISCV --ignore-style
exec scons "$dir/gem5.$variant" -j"$jobs" --ignore-style
