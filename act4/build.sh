#!/usr/bin/env bash
# Build the ACT4 (riscv-arch-test) ELFs for BOOM.
#
#   act4/build.sh [act4 checkout] [make args...]
#
# e.g. act4/build.sh ../ref/act4                 # every extension in the config
#      act4/build.sh ../ref/act4 EXTENSIONS=I,M  # a subset
#
# ELFs land in <act4 checkout>/work/boom-rv64gc/elfs (the default ACT4_ELFS of
# boom.sim.Act4Spec). Needs ACT4's prerequisites (uv/mise, Ruby, a riscv64 GCC)
# and sail_riscv_sim on PATH.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
act="$(cd "${1:-$here/../../ref/act4}" && pwd)"
shift || true

# udb (the gem ACT uses to validate the config and derive headers) loads a Z3
# library it downloaded at install time, and on macOS that download is the Linux
# build. Point it at Homebrew's z3 through a private cache directory instead of
# touching ~/.cache/udb. uv and mise keep their usual caches.
if [ "$(uname)" = Darwin ] && [ -d "$HOME/.cache/udb/z3" ]; then
  cache="${TMPDIR:-/tmp}/boom-act4-udb-cache"
  z3lib="$(brew --prefix z3)/lib/libz3.dylib"
  for d in "$HOME"/.cache/udb/z3/*/*; do
    rel="${d#$HOME/.cache/}"
    mkdir -p "$cache/$rel"
    ln -sf "$z3lib" "$cache/$rel/libz3.so"
  done
  export UV_CACHE_DIR="${UV_CACHE_DIR:-$HOME/.cache/uv}"
  export MISE_CACHE_DIR="${MISE_CACHE_DIR:-$HOME/.cache/mise}"
  export XDG_CACHE_HOME="$cache"
fi

make -C "$act" CONFIG_FILES="$here/boom-rv64gc/test_config.yaml" "$@"
