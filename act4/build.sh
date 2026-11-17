#!/usr/bin/env bash
# Build the ACT4 (riscv-arch-test) ELFs for BOOM.
#
#   act4/build.sh [act4 checkout] [make args...]
#
# Optional source checkout comes first. Remaining arguments go to make.
# Resolve cached sources with rvw; acquire them separately with rvw doctor.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
source "$root/scripts/verification-deps.sh"
explicit=""
if [ "$#" -gt 0 ] && [ -d "$1" ]; then explicit="$1"; shift; fi
act="$(boom_resolve_source act4 "$explicit")"
# Use the managed Sail binary unless the caller already supplied it on PATH.
sail=""
if [ -n "${RVW_SAIL:-}" ] || ! command -v sail_riscv_sim >/dev/null 2>&1; then
  sail="$("${RVW:-rvw}" doctor --resolve sail)"

fi
mkdir -p "$root/target/verification/act4"
private="$(mktemp -d "$root/target/verification/act4/source.XXXXXX")"
trap 'rm -rf "$private"' EXIT
if [ -n "$sail" ]; then
  mkdir -p "$private/bin"
  python3 - "$sail" "$private/bin/sail_riscv_sim" <<'PYSAIL'
import pathlib
import shlex
import sys
path = pathlib.Path(sys.argv[2])
path.write_text('#!/bin/sh\nexec ' + shlex.quote(sys.argv[1]) + ' "$@"\n')
path.chmod(0o755)
PYSAIL
  export PATH="$private/bin:$PATH"
fi
boom_copy_source "$act" "$private/tree"

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

work="$root/target/verification/act4/work"
if [ -n "${ACT4_ELFS:-}" ]; then
  # An arbitrary caller output path need not follow ACT4's directory layout.
  elfs="$(python3 -c 'import os,sys; print(os.path.abspath(sys.argv[1]))' "$ACT4_ELFS")"
  work="$private/work"
fi
make -C "$private/tree" CONFIG_FILES="$here/boom-rv64gc/test_config.yaml" \
  WORKDIR="$work" "$@"
if [ -n "${ACT4_ELFS:-}" ]; then
  python3 - "$work/boom-rv64gc/elfs" "$elfs" <<'PYOUT'
import shutil
import sys
shutil.copytree(sys.argv[1], sys.argv[2], dirs_exist_ok=True)
PYOUT
fi
