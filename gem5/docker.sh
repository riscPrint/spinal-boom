#!/usr/bin/env bash
# Run a command in the gem5 container. The saviour checkout is mounted at /work
# and commands run from /work/spinal-boom.
#   gem5/docker.sh                      # interactive shell
#   gem5/docker.sh gem5/build.sh        # any command
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"          # .../saviour
image="${GEM5_IMAGE:-saviour-gem5:v25.1}"

if ! docker image inspect "$image" >/dev/null 2>&1; then
  docker build -t "$image" "$here"
fi

tty=()
[ -t 0 ] && [ -t 1 ] && tty=(-it)
exec docker run --rm "${tty[@]}" -v "$root:/work" -w /work/spinal-boom \
  -e HOME=/tmp -u "$(id -u):$(id -g)" "$image" "${@:-bash}"
