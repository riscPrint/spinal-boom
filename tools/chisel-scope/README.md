# Conditional register experiment

Run from the worktree root after `source env.sh`:

```sh
bash tools/chisel-scope/run.sh
```

Scala CLI uses Scala 2.13.14, Chisel 6.5.0 and its compiler plugin.
These are the versions in upstream BOOM's build.sbt. Chisel resolves
firtool 1.62.0. The testbench uses Icarus Verilog.

`evidence/` retains emitted CHIRRTL, SystemVerilog, eight simulation cycles,
and selected changes from the gated core diagnostic wave. The initial
uninitialized shift value is unknown. All subsequent checks pass.
Each first-stage register loads during disabled cycles. The output muxes
remain conditional. The registers are not gated by their declaration scope.

The core trace uses small `rv64ui-p-add`, seed 1, with all candidate gating
except the ROB changes. At 3340 the flush payload is valid. At 3350 the
redirect consumes stale registers. At 3360 the registers capture the next
payload. Full logs and VCDs are local under `audit-results/`.
