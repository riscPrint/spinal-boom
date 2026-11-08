# Step 7 report

Branch: `pipeline-step7`. Worktree: `spinal-boom-step7`.
Implementation commit: `96dfe92`.

## Root cause

Hunks 0-2 of `git diff -U2 55d62d6 85535e5 --
src/main/scala/boom/exu/core.scala` reproduce the giga failure alone.
The counter, debug JALR, interrupt and FTQ response-selector subset passes.

The conversion changes simulation initial state. It does not drop or add a
capture enable, kill, reset or cycle. The first branch-resolution stage still
captures every cycle. The merged `b2` stage still captures one cycle later.
The branch valid still checks the current and one-cycle delayed ROB flush.

Two changes matter:

- The StageLinks rename the unreset branch registers from `brinfos_*` and `b2_*` to temporary names.
- One shared ROB-flush delay replaces independent unreset captures. Their next-state equations are equal after a clock edge, but their initial values can differ.

For each flush capture, `q_i[n] = rob_flush[n-1]` for `n >= 1`, after a clock edge.
Before the first capture, each `q_i[0]` has its own unreset value. The shared
version replaces all of those values with one value. Thus the change occurs
before the first capture, with no steady-state latency change.

This Verilator build initializes unreset values with scoped, name-dependent
seeds. Its generated C++ calls `VL_SCOPED_RAND_RESET_*`. Different initial
control values can write a different RAS value during initialization. The
stored value becomes visible much later on a wrong path.

The giga reference PC `1fb53fe606` equals the initial `ras_0` value with bit 0
cleared. The changed PC `db7bed2686` equals the initial FTQ entry 39 `ras_top`
value with bit 0 cleared. Their VPN2 values select root page-table cache lines
`800043c0` and `80004b40`. This explains the AcquireBlock difference at cycle
11019. The Konata label differs at cycle 11033. Commit, control and result
traces stay equal.

A branch-name fix alone matches giga but changes one boom2 wrong-path label.
That reference PC `7af2ff97d0` comes from initial `ras_0`; the changed PC
`793287d62c` comes from initial FTQ entry 9 `ras_top`. Keeping the independent
flush captures as well makes both targeted cases match all five trace files.
The PC values identify the retained storage values. They do not identify every
intermediate RAS write in the initialization sequence.

## Fix and commits

Commit `96dfe92` delays the complete `Vec[Flow[BrResolutionInfo]]` as data.
Each valid bit stays an unreset data bit. It names the registered containers
`brinfos` and `b2`. The generated giga RTL has the original names and widths
for all 707 branch-register fields. The independent ROB-flush `RegNext`
captures stay at their original consumers. The branch stages and ROB-flush
payload stage still use StageLinks.

| Commit | Work |
|---|---|
| `85535e5` | Eight missed chains: branch resolution, ROB-flush payload, dispatch to LDQ/STQ, busy-table wakeups, freelist deallocation, RF routing selects, FTQ response select, sim-top I-cache slice |
| `273af57` | BTB, SlowBTB, local-history, two-bank history and FTQ response, repair and redirect delays |
| `21878f3` | Memory-side delays, enabled-capture helper and three tests, removal of dead helpers |
| `96dfe92` | Preserve branch-register identity and independent unreset flush captures |

The first three commits were already on the paused branch. This work retains
and verifies them with the fix. No golden or comparison rule changes.

The five proposed enable fixes stay excluded. A `RegNext` declared inside a
Spinal `when` captures every cycle. Adding those enables would change the
current port.

`GatedPipeline` supplies the enabled-capture StageLink helper. Each enabled
stage has its own Node pair and complete capture enable. The D-cache writeback
read-tag chain uses it. Its three tests compare multi-stage, shared-enable,
mixed-enable, conditional-scope and Flow cases with enabled register chains.
The formerly manual frontend captures use free-running stages because their
original `RegNext` declarations were free-running. The optional PTW response,
HellaCache requester ID, ForwardingAgeLogic result and small LSU delays stay
converted. `ShiftRegister`, `holdUnless` and `readAndHold` are removed.

## Validation

Final validation passed on the source in `96dfe92`. Every build, simulation
and gate used the machine-wide `boom` lock. The gate uses parallelism 2 and
5 CPUs per worker, matching the recorded options. The existing goldens and all
five trace comparisons remain active. These runs cover the recorded BOOM
configurations. They do not exercise the multi-requester HellaCacheArbiter
branch, which BOOM does not elaborate. That branch has no runtime equivalence
result in this report.

| Check | Result |
|---|---|
| Unit and elaboration tests | 49/49 passed |
| VM smoke test | 1/1 passed |
| Quick | 255/255, zero differences |
| Extra | 170/170, zero differences |
| Full | 1,655/1,655, zero differences |

The 13 test suites ran 50 tests: 49 unit and elaboration tests, plus one VM
smoke test. There were zero failures, errors or skips.

Targeted isolation results:

| Source | Giga AMOAND | Boom2 load/store |
|---|---:|---:|
| Paused tip `21878f3` | Diff | Match |
| Name `b2` only | Match | Diff |
| Preserve both branch containers and names | Match | Diff |
| Preserve both containers and independent flush captures | Match | Match |

Local evidence is under `target/step7-debug/`. The hunk isolation reports are
`target/equivalence/20261107-194600-18938/report.json` and
`target/equivalence/20261107-195140-21843/report.json`. The final targeted giga
report is `target/equivalence/20261107-231609-94368/report.json`.
The quick report is `target/equivalence/20261107-233547-81766/report.json`.
The extra report is `target/equivalence/20261107-234852-43827/report.json`.
The full report is `target/equivalence/20261107-235544-92627/report.json`.
`target/step7-debug/final-validation.json` records the checked totals.
`precise-results.json` records the targeted refinement outcomes.
`seed-scan.py` and `seed-scan-giga.py` reproduce the initial PC-value matches.
`registered-bundles-registers.json` records the static 707-field comparison.
That static comparison is separate from the simulation results.

## Left manual

- Independent one-cycle ROB-flush captures. Sharing them changes initial state.
- SRAM read ports and alignment registers: BIM, HBIM, Tourney, I-cache arrays and RAS read bypass.
- Freelist allocation selection `r_sel` and its valid/state logic in `rename-freelist.scala`.
- PTW replacement snapshots `r_valid_vec_q` and `r_l2_plru_way`, and event flags `pte_hit` and `l2_refill`. The PTW L2 lookup pipeline is already converted.
- MSHR metadata wait state, allocation selection and hazard counters.
- Local-history write-command state, queues, FSMs, pointers, counters, CAMs, rename storage and ROB storage.
- Assertion-only delays and the commit-trace, RVFI and Konata observation paths.

These parts are not claimed as new conversions. State, memories and observation
paths remain outside the transaction-delay conversions in this change.

## Suggested TODO status

Step 7: retain the eight missed-chain conversions and the frontend and memory
conversions from `85535e5`, `273af57` and `21878f3`. Fix the giga difference with
`96dfe92`: preserve branch-register names and independent unreset flush
captures. Keep the five incorrect enable fixes excluded. The enabled-capture
helper and dead-helper removal are complete. Final gates: specs 49/49, VM smoke
1/1, quick 255/255, extra 170/170 and full 1,655/1,655, with zero trace
differences. Keep the manual items listed above separate. The gate on merged `pipeline-api` and
integration remain for review.

The shared `TODO.md` was not edited. No push or merge was done.
