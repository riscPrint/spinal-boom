# Load-load ordering replay

## Scope

Worktree: `/Users/mahir/fun/saviour/spinal-boom-ldld`.
Branch: `lsu-ldld-order`. Base: `fdd21f446f7511a1cfa478628b9052b4e737d064`.
Upstream reference: `riscv-boom` at `ba03175e0a3cf5c0d5a7f14d385ac1c8998c4428`.
All simulations and Verilator builds use the machine-wide `boom` lock.

## Failure and fix

Large qsort with randomized memory timing and seed 975830445 fails at
`FAILURE lsu:L1241` with simulation time 500600. The old failure bundle is
absent. This result is a new reproduction from the unchanged RTL.

A younger load can complete before an older load is nacked and retried.
A cache probe marks the younger load observed. When the older load retries,
the load CAM finds the younger load already executed or succeeded. Upstream
v4 asserts in this case. Its replay assignments are commented out at
`src/main/scala/v4/lsu/lsu.scala:1251-1255`. BOOM v3 uses those assignments at
`src/main/scala/v3/lsu/lsu.scala:1129-1130`.

Restore `ldq_order_fail(i) := True` and `failed_load := True` under the
existing guards. The LSU selects the oldest failed load and reports
`MINI_EXCEPTION_MEM_ORDERING`. The ROB flushes and refetches that load.
It does not raise a software trap. No new flush path is needed.

RVWMO CoRR requires ordering for loads to overlapping bytes when they read
from different writes and no intervening store writes those bytes. The probe
check is conservative. These single-hart simulations test recovery. They do
not prove all multi-hart memory-model cases.

Sources: [RVWMO explanation](https://docs.riscv.org/reference/isa/unpriv/mm-eplan.html),
local upstream v3 and v4 sources above, port `lsu.scala` exception selection,
and port `rob.scala` mini-exception handling.

## Inputs and commands

Build the ROI-instrumented ELFs with the `Arch/bench` Makefile and runtime
from `risc-v-workstation`. A copy under `target/ldld/bench-src` keeps all build
outputs in this worktree. Use GCC from
`/opt/homebrew/opt/riscv-gnu-toolchain/bin` and sources from
`../ref/riscv-tests/benchmarks`. Use the Makefile defaults, including `-O2`.
The ELF hashes below identify these local inputs.

Run from the worktree after `source env.sh`. Prefix each sbt command with
`../risc-v-workstation/scripts/with-lock.sh boom`.

- Regression: `BENCH_DIR=$PWD/target/ldld/elfs sbt 'testOnly boom.sim.LdLdOrderingSpec'`.
- Fixed benchmarks: `BENCH_DIR=$PWD/target/ldld/elfs BENCH_SEED=1 BENCH_MEM_LATENCY=20 sbt 'testOnly boom.sim.BenchmarksSpec'`.
- Random benchmarks: use `BENCH_MEM_LATENCY=random` and seeds 975830445, 1 and 42.
- ISA: `RISCV_TESTS_CONFIG=small RISCV_TESTS_RVFI=1 RISCV_TESTS_SEED=975830445 sbt 'testOnly boom.sim.RiscvTestsSpec'`.
- VM: build with `tests/vm/build.sh`, then `sbt 'testOnly boom.sim.VmTestsSpec'`.
- ACT4: `ACT4_SEED=975830445 sbt 'testOnly boom.sim.Act4Spec'`.

The ISA runner excludes `rv64ui-p-ma_data`, `rv64ui-v-ma_data`, and
`rv64mi-p-instret_overflow`. The RVFI monitor checks RV64IMC instruction
semantics and stream consistency. Its ISA model does not cover every
extension used by the test programs. See architecture section 18.2.

Local logs and CSVs are under `target/ldld`. They are ignored build outputs.

| ELF | SHA-256 |
| --- | --- |
| dhrystone.riscv | `4606b602557821c2060c7e508c3d0bf64c83408c6e22d127d5c549d8b77c15d5` |
| median.riscv | `273e887ddf0bdcd8bee4bce3046a830327d9000d981444fb0f78fc5058edbb16` |
| memcpy.riscv | `82d41d5f8daf4466821b47b9222fd11c8e370b5d67272b27e4d2e229e82616d6` |
| multiply.riscv | `19ef66602a4cfc996da57c2c5da8b86276aa559a290559b95da70d536cc084cf` |
| qsort.riscv | `a221130bb7a4235c1d62cd8cd950aeee5c46bbf1bdf162a84f5277270fa704ad` |
| rsort.riscv | `a0f225e8299f97104ce6af8caf5fad4f660d685fb75904e6758166d2c41e14a3` |
| spmv.riscv | `6503e50c4c2aedaa092c44db59ac2178e70d63e47d53ec840641e6865ad3fba3` |
| towers.riscv | `f9b24f6ed3392160b4583630329cba75097bd9b2f4099b5c9db7e093ccad0bcd` |
| vvadd.riscv | `07dfac379a9a0933527e1152f4d3dadd2ea73a1a27025972b2130702a811605f` |

## Commits

- 1a57852dc99ebe5403bab3eb1056db7287263c59 test(lsu): reproduce load-load ordering failure with random timing
- 7cea77c9fd2fbace638119d49679907bd76a2c9e fix(lsu): replay observed younger loads after older load retries

## Results

| Check | Passed | Failed | Canceled |
| --- | ---: | ---: | ---: |
| Original RTL, seeded regression, expected assertion | 0 | 1 | 0 |
| Fixed RTL, seeded regression | 1 | 0 | 0 |
| Small riscv-tests with RVFI | 321 | 0 | 0 |
| Small VM tests | 1 | 0 | 0 |
| Large benchmarks, latency 20, original RTL | 9 | 0 | 0 |
| Large benchmarks, latency 20, fixed RTL | 9 | 0 | 0 |
| Large benchmarks, random timing, seed 975830445 | 9 | 0 | 0 |
| Large benchmarks, random timing, seed 1 | 9 | 0 | 0 |
| Large benchmarks, random timing, seed 42 | 9 | 0 | 0 |
| Small ACT4 | 585 | 35 | 0 |

The dedicated regression fails at `lsu:L1241` before the fix. It passes
after the fix. All benchmark runs use the same ELF inputs listed above.

## IPC

At fixed latency 20 and seed 1, all nine benchmarks have exactly the same
cycle and retired-instruction counts before and after the fix. The IPC
change is 0% for every benchmark. The shared values are below.

| Benchmark | Cycles | Retired instructions | IPC |
| --- | ---: | ---: | ---: |
| dhrystone | 115654 | 192525 | 1.6647 |
| median | 5276 | 4659 | 0.8831 |
| memcpy | 9247 | 5520 | 0.5970 |
| multiply | 10018 | 26816 | 2.6768 |
| qsort | 225858 | 122917 | 0.5442 |
| rsort | 78291 | 171158 | 2.1862 |
| spmv | 22901 | 33505 | 1.4630 |
| towers | 4144 | 3263 | 0.7874 |
| vvadd | 1028 | 2416 | 2.3502 |

Raw paired results: [baseline](../../target/ldld/baseline-fixed.csv) and
[candidate](../../target/ldld/candidate-fixed.csv).

The original randomized qsort run stops at the assertion. It does not
provide a completed ROI measurement. No paired randomized IPC claim is made.

## ACT4 failures

All 620 selected ACT4 tests ran. There were 585 passes, 35 failures, no
canceled tests, no ignored tests, and no pending tests. The suite completed
without an abort. The 35 names fall within the failure groups already listed
in architecture section 18.5. This task did not rerun ACT4 on the original
RTL, so it does not provide a paired ACT4 regression comparison.

The two `Zalrsc-sc` tests hit `L1DCache failed too many SCs in a row`.
That assertion is a documented upstream limitation and remains enabled.
No `lsu:L1241` assertion occurred in the fixed regression or benchmark runs.
Other ACT4 failure causes were not newly triaged in this task.

- `priv/ExceptionsSv/ExceptionsSv_sv39_exceptions_Smode-00`
- `priv/ExceptionsSv/ExceptionsSv_sv39_exceptions_Umode-00`
- `priv/ExceptionsSvSm/ExceptionsSvSm_sv39_exceptions_mprv_S_Mmode-00`
- `priv/ExceptionsSvSm/ExceptionsSvSm_sv39_exceptions_mprv_U_Mmode-00`
- `priv/ExceptionsSvZalrsc/ExceptionsSvZalrsc_sv39_exceptions_Zalrsc_Smode-00`
- `priv/ExceptionsSvZalrsc/ExceptionsSvZalrsc_sv39_exceptions_Zalrsc_Umode-00`
- `priv/ExceptionsSvZalrscSm/ExceptionsSvZalrscSm_sv39_exceptions_Zalrsc_mprv_S_Mmode-00`
- `priv/PMPS/PMPS_na4_legal_lxwr-00`
- `priv/PMPS/PMPS_tor_legal_lxwr-02-00`
- `priv/PMPSm/PMPSm_cfg_A_tor_bot-00`
- `priv/PMPSm/PMPSm_cfg_A_tor_zero-00`
- `priv/PMPSm/PMPSm_cfg_tor_check-01-00`
- `priv/PMPSm/PMPSm_na4_legal_lxwr-00`
- `priv/PMPSm/PMPSm_tor_legal_lxwr-02-00`
- `priv/PMPU/PMPU_na4_legal_lxwr-00`
- `priv/PMPU/PMPU_tor_legal_lxwr-02-00`
- `priv/PMPZca/PMPZca_aligned_na4-00`
- `priv/PMPZca/PMPZca_aligned_tor-00`
- `priv/PMPZca/PMPZca_cret_na4-00`
- `priv/PMPZca/PMPZca_cret_tor-00`
- `priv/PMPZca/PMPZca_misaligned_na4-00`
- `priv/PMPZca/PMPZca_misaligned_tor-00`
- `priv/Sm/Sm_F-00`
- `priv/Sm/Sm_mcsr_access-00`
- `priv/Sm/Sm_mcsr_cntr-00`
- `priv/Sstvecd/Sstvecd-00`
- `priv/Sv/Sv_sv39_canonical_Smode-00`
- `priv/Sv/Sv_sv39_canonical_Umode-00`
- `priv/SvPMP/SvPMP_sv39_pmp_on_pa_Smode-00`
- `priv/SvPMP/SvPMP_sv39_pmp_on_pa_Umode-00`
- `priv/ZicntrS/ZicntrS-00`
- `priv/ZicntrSm/ZicntrSm-00`
- `priv/ZicntrU/ZicntrU-00`
- `rv64i/Zalrsc/Zalrsc-sc.d-00`
- `rv64i/Zalrsc/Zalrsc-sc.w-00`

## Scope limits

All verification in this report is RTL simulation. No formal CoRR proof or
multi-hart litmus run was made. The full ACT4 result is not a clean pass.
No commit was pushed or merged. Only this worktree was edited and built.
