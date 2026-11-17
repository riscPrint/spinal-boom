# Verification dependencies

BOOM uses the workstation dependency resolver. It does not need a sibling
`ref` directory for verification. Install `rvw` from the workstation first.
Set `RVW` to an executable path to select a specific installation.

Acquire dependencies as a separate setup step:

```sh
rvw doctor --resolve riscv-tests --acquire
rvw doctor --resolve act4 --acquire
rvw doctor --resolve riscv-formal --acquire
rvw doctor --resolve sail --acquire
```

Without `--acquire`, resolution is offline and read-only. The resolver returns
an absolute path. `--json` returns path and identity data. The workstation owns
the pins and source validation. Its source cache is `RVW_SRC_CACHE`, with
`~/.cache/rvw/src` as the default.

| Dependency | Required version | Authoritative source override |
|---|---|---|
| ACT4 | `56d40ab301dba23dd3104c17bd969afbaaea1931` | `ACT4_SOURCE` |
| riscv-tests | `d7e86f701b929b5d801f8cda1a4871ab05f452bb` | `RISCV_TESTS_SRC` |
| riscv-formal | `c992aa61fdfe0846c5ed90324c596202a1c69b76` | `RISCV_FORMAL` |
| Sail | `0.15` | `RVW_SAIL` |

`rvw doctor --resolve sail` must return the `sail_riscv_sim` executable path.
`env.sh` adds its directory to PATH if it can resolve the cached executable.
It does not acquire Sail. The ACT4 wrapper also resolves Sail when it is absent
from PATH or when `RVW_SAIL` is set.

## Build and run

```sh
source env.sh
scripts/build-riscv-tests.sh
sbt 'testOnly boom.sim.RiscvTestsSpec'
act4/build.sh
sbt 'testOnly boom.sim.Act4Spec'
formal/run.sh cover_ch0
formal/gen_monitors.sh
```

| Generated input | Default path | Authoritative override |
|---|---|---|
| ISA ELFs | `target/verification/riscv-tests/isa` | `RISCV_TESTS` |
| ACT4 ELFs | `target/verification/act4/work/boom-rv64gc/elfs` | `ACT4_ELFS` |

The Scala suites fail if the directory is absent or the selection is empty.
Cycle equivalence uses the same ISA default. The RegNext verification script
requires ACT4 and reports its failure instead of skipping it.

The ISA wrapper calls `rvw regress riscv-tests-build --out PATH --xlen 64`.
It passes extra arguments through. BOOM keeps its existing suite names,
environments, filters, and three exclusions.

`act4/build.sh [checkout] [make arguments...]` accepts an explicit source
checkout as its first argument. Without one, it uses the resolver. It builds
from a temporary private source copy. It keeps the BOOM configuration in
`act4/boom-rv64gc` and the macOS Z3 cache workaround. It does not use the
workstation ACT4 profile. Extra make arguments, such as `EXTENSIONS=I,M`, keep
their existing meaning. `ACT4_ELFS` can select a different output directory.
As with an incremental ACT4 build, use a fresh output directory for a new
selection if old ELFs must be excluded.

`formal/run.sh [checkout] [make arguments...]` uses a private copy under
`target/verification/formal/run.*`. It retains the work directory and check
logs. `formal/gen_monitors.sh [checkout]` uses a temporary private source copy and writes
the BOOM monitors. The formal check selection, `checks.cfg`, and monitor
transforms are unchanged.

BOOM does not add `rvw.toml` for this migration. The wrappers retain the BOOM
ACT4 profile and formal selection. A workstation project profile would add a
second configuration without replacing these contracts.

## Validation boundary

Wrapper tests use fake tools in temporary directories. They do not establish
native build or simulation results. The parent integration must validate the
resolver CLI, Sail executable path, and ISA build CLI before this branch is
pushed. No source directory can be deleted on the basis of these tests alone.
Old paths in dated validation reports describe past runs.
