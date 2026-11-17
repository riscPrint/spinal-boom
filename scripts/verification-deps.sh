#!/usr/bin/env bash
# Shared functions for BOOM verification wrappers. Resolution is offline.
boom_resolve_source() {
  local name="$1" explicit="${2:-}" path
  if [ -n "$explicit" ]; then
    path="$explicit"
  else
    path="$("${RVW:-rvw}" doctor --resolve "$name")" || return
  fi
  if [ ! -d "$path" ]; then
    echo "Missing $name source directory: $path" >&2
    return 1
  fi
  (cd "$path" && pwd -P)
}

# Copy source files, not Git metadata or an existing build environment.
# Follow symlinks so a build cannot write through them into the source cache.
boom_copy_source() {
  python3 - "$1" "$2" <<'PY'
import shutil
import sys
shutil.copytree(sys.argv[1], sys.argv[2], symlinks=False,
                ignore=shutil.ignore_patterns('.git', '.venv', 'work', '__pycache__'))
PY
}
