# Conditional register audit

## Corrected result

The original audit premise was wrong. A register declared and connected in
one Chisel conditional scope does not acquire that outer condition as an
enable. Chisel 6.5.0 with firtool 1.62.0 emits free-running registers for all
four measured forms. The base port was faithful at all 48 inventoried source
sites. All candidate gating changes are reverted. No gated site is kept.
The 48 source sites map to 47 port calls because two upstream forwarding
field assignments share one port index register.

Upstream BOOM is `ba03175e0a3cf5c0d5a7f14d385ac1c8998c4428`.
Base port is `fdd21f446f7511a1cfa478628b9052b4e737d064`.
The experiment uses upstream's build versions: Scala 2.13.14 and Chisel 6.5.0.
Chisel resolves firtool 1.62.0 without a manual installation.

## Reproduce the measured semantics

From this worktree, run `source env.sh`, then
`bash tools/chisel-scope/run.sh`. The module, testbench, emitted CHIRRTL,
emitted SystemVerilog and simulation output are retained under
`tools/chisel-scope/`. The output files are also written to `audit-results/`.
Icarus checks eight cycles. Uninitialized shift history is not checked until
it has a defined value. Reset is not used for these uninitialized registers.

The CHIRRTL contains register declarations and connections within `when`,
`else`, and `is` scopes. This syntax alone does not prove a register enable.
The emitted SystemVerilog has this unconditional clocked block:

```verilog
always @(posedge clock) begin
  a_REG <= x;
  REG <= x;
  d_REG <= x;
  s_r <= x;
  s_r_1 <= s_r;
end
```

`a_REG` is inside `when(c)`. `REG` is in an `elsewhen` predicate.
`d_REG` is inside `switch`/`is`. `s_r` and `s_r_1` implement
`ShiftRegister(x, 2)` inside `when(c)`. Output selection remains conditional.
For example, cycles 1, 2 and 3 have `c=0` and `sel=0`. All first-stage
registers still load 11, 12 and 23. When `c` and `sel` become true at cycle 4,
the shift output is 23 from the preceding disabled cycle.

Spinal's local `RegNext` has the same behavior. `RegNextWhen` preserves an
explicit enable. The replaced scope tests now check free-running behavior
over 400 random cycles. The lexical scanner remains a source inventory.
Its scope output must not be interpreted as a required enable.

## Failure diagnosis

The all-gated small `rv64ui-p-add`, seed 1, reproduces `rob:L708`.
Restoring only the ROB source to its base version lets execution reach the
core redirect defect. This diagnostic run retains frontend, FTQ, core and
LSU gating. It fails with `Pipeline has hung.`
The wave is `audit-results/gated-core-add.vcd`. Selected changes are also retained in
`tools/chisel-scope/evidence/gated-core-flush.txt`.

At time 3340, ROB flush valid is 1, flush type is 4 and PC low bits are 12.
At time 3350, flush valid falls to 0 and the redirect is active. Its PC is
`0x800000c7`. The gated type register is still 0. The gated PC-low register
still contains its initial value 7. At time 3360, after the redirect cycle,
the PC-low register captures 16 from the next payload and the FTQ register
captures index 2. The type register captures 0 because valid is already low.
This is a late capture of the wrong cycle, not an upstream flush pair rule.
The FTQ response PC at 3350 is `0x800000c8`, which aligns to `0x800000c0`.
The valid flush payload has PC low bits 12, edge-inst false and is-RVC false.
The expected next PC is `0x800000d0`. These fields are retained in
`tools/chisel-scope/evidence/gated-core-fields.txt`.

Upstream `exu/rob.scala:602-622` computes `flush_val` from exception or
flush-on-commit. Its payload comes from the exception uop or the committing
flush uop. There is no payload-hold register at this interface. The comment
about delaying a cycle does not add hardware. In `exu/core.scala:427-449`,
upstream delays valid and each payload field together by one cycle. Its
three ERET target stages also run every cycle. The delayed mispredict guard
and system-instruction commit FTQ index run every cycle.

In the ROB state cases, both exception-history stages must also run every
cycle. Gating them freezes prior exception history when the FSM changes
state. The observed rollback assertion is a symptom of that change.

Upstream LSU's three load-search kill histories delay request-fire and
not-killed by one cycle, independently of the current search match.
Its exception assertion checks the preceding cycle's address-generation
activity. Its forwarding indices delay the preceding cycle's selected
store index. Gating any of these substitutes older history at first use.
The explicit surrounding conditions select consumers, not register loads.

With only LSU gating retained, small seed-1 `rv64ui-p-add` passes.
`rv64ui-p-ld` also passes. `rv64mi-p-ma_addr` fails `lsu:L843`, and
`rv64ui-p-sd` fails "trying to commit an un-executed load entry."
Thus the cumulative `ab74551` add hang is not evidence that LSU gating
alone hangs add. The core gating remains present in that commit.

The isolated LSU wave is `audit-results/gated-lsu-ma_addr.vcd`.
Selected changes are retained in
`tools/chisel-scope/evidence/gated-lsu-exception.txt`.
Address-generation fire rises at time 10010. Exception valid rises at 10020
as address-generation fire falls. The gated assertion register cannot
capture the true input at 10010 because its enable is the later exception
valid. The assertion fails at 10030. The internal temporary is omitted from
the VCD, but its gated assignment is present in the emitted Verilog:

```verilog
if (when_ScopedRegNext_l11_10) begin // mem_xcpt_valids_0
  _zz_1 <= ((((will_fire_load_agen_exec_0 || will_fire_load_agen_0) ||
    will_fire_store_agen_0) || will_fire_load_retry_0) || will_fire_store_retry_0);
end
```

The store-test trace is retained in
`tools/chisel-scope/evidence/gated-lsu-store-kills.txt`.
A request fires at 8500. At 8510 the store-search enable and `s1_kill`
are high. At 8520 the gated history captures zero from request-fire.
The next request fires at 8530. At 8540 the same search enable is high,
but `s1_kill` stays low because its history still contains zero.
`fired_load_agen` and `s0_kills` remain zero throughout this interval.
The request is not killed while LSU clears its executed state.
The core then asserts at 8590 when it tries to commit the unexecuted load.
Upstream's free-running one-cycle history supplies true at 8540 and kills
that request. This explains the isolated LSU store-test failure.

## Site inventory

| Area | Source sites | Faithful at base | Gating reverted | Gating kept |
|---|---:|---:|---:|---:|
| Frontend and predictors | 18 | 18 | 18 | 0 |
| FTQ | 11 | 11 | 11 | 0 |
| Core and ROB | 12 | 12 | 12 | 0 |
| LSU | 7 | 7 | 7 | 0 |
| Total | 48 | 48 | 48 | 0 |

The following table retains the candidate commit and line references for
traceability. The last column identifies the original gating commit.

## Site table

Status describes the base port and the final decision. Calls on the same
line are numbered in source order, from the outer call to the inner call.

| Upstream file:line (call) | Port file:line | Kind | Status | Candidate gating commit |
|---|---|---|---|---|
| `exu/core.scala:430` (1) | `boom/exu/core.scala:448` | RegNext | faithful at base; gating reverted | `f91dae9` |
| `exu/core.scala:438` (1) | `boom/exu/core.scala:457` | ShiftRegister | faithful at base; gating reverted | `f91dae9` |
| `exu/core.scala:442` (1) | `boom/exu/core.scala:461` | RegNext | faithful at base; gating reverted | `f91dae9` |
| `exu/core.scala:443` (1) | `boom/exu/core.scala:462` | RegNext | faithful at base; gating reverted | `f91dae9` |
| `exu/core.scala:444` (1) | `boom/exu/core.scala:463` | RegNext | faithful at base; gating reverted | `f91dae9` |
| `exu/core.scala:449` (1) | `boom/exu/core.scala:467` | RegNext | faithful at base; gating reverted | `f91dae9` |
| `exu/core.scala:450` (1) | `boom/exu/core.scala:469` | RegNext | faithful at base; gating reverted | `f91dae9` |
| `exu/core.scala:801` (1) | `boom/exu/core.scala:808` | RegNext | faithful at base; gating reverted | `f91dae9` |
| `exu/rob.scala:814` (2) | `boom/exu/rob.scala:771` | RegNext | faithful at base; gating reverted | `be9cff7` |
| `exu/rob.scala:830` (2) | `boom/exu/rob.scala:789` | RegNext | faithful at base; gating reverted | `be9cff7` |
| `ifu/bpd/bim.scala:154` (1) | `boom/ifu/bpd/bim.scala:132` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/btb.scala:106` (1) | `boom/ifu/bpd/btb.scala:99` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/btb.scala:107` (1) | `boom/ifu/bpd/btb.scala:100` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/btb.scala:108` (1) | `boom/ifu/bpd/btb.scala:101` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/btb.scala:109` (1) | `boom/ifu/bpd/btb.scala:102` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/btb.scala:114` (1) | `boom/ifu/bpd/btb.scala:107` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/btb.scala:115` (1) | `boom/ifu/bpd/btb.scala:108` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/btb.scala:116` (1) | `boom/ifu/bpd/btb.scala:109` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/btb.scala:117` (1) | `boom/ifu/bpd/btb.scala:110` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/btb.scala:117` (2) | `boom/ifu/bpd/btb.scala:110` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/local.scala:84` (1) | `boom/ifu/bpd/local.scala:81` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/local.scala:84` (2) | `boom/ifu/bpd/local.scala:81` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/local.scala:84` (3) | `boom/ifu/bpd/local.scala:81` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/predictor.scala:285` (1) | `boom/ifu/bpd/predictor.scala:275` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/predictor.scala:286` (1) | `boom/ifu/bpd/predictor.scala:276` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/predictor.scala:288` (1) | `boom/ifu/bpd/predictor.scala:278` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/predictor.scala:289` (1) | `boom/ifu/bpd/predictor.scala:279` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/bpd/slowbtb.scala:107` (1) | `boom/ifu/bpd/slowbtb.scala:100` | RegNext | faithful at base; gating reverted | `98008d3` |
| `ifu/fetch-target-queue.scala:235` (1) | `boom/ifu/fetch-target-queue.scala:228` | RegNext | faithful at base; gating reverted | `d482ae7` |
| `ifu/fetch-target-queue.scala:237` (1) | `boom/ifu/fetch-target-queue.scala:230` | RegNext | faithful at base; gating reverted | `d482ae7` |
| `ifu/fetch-target-queue.scala:238` (1) | `boom/ifu/fetch-target-queue.scala:231` | RegNext | faithful at base; gating reverted | `d482ae7` |
| `ifu/fetch-target-queue.scala:243` (1) | `boom/ifu/fetch-target-queue.scala:237` | RegNext | faithful at base; gating reverted | `d482ae7` |
| `ifu/fetch-target-queue.scala:271` (1) | `boom/ifu/fetch-target-queue.scala:265` | RegNext | faithful at base; gating reverted | `d482ae7` |
| `ifu/fetch-target-queue.scala:272` (1) | `boom/ifu/fetch-target-queue.scala:266` | RegNext | faithful at base; gating reverted | `d482ae7` |
| `ifu/fetch-target-queue.scala:273` (1) | `boom/ifu/fetch-target-queue.scala:267` | RegNext | faithful at base; gating reverted | `d482ae7` |
| `ifu/fetch-target-queue.scala:319` (1) | `boom/ifu/fetch-target-queue.scala:314` | RegNext | faithful at base; gating reverted | `d482ae7` |
| `ifu/fetch-target-queue.scala:320` (1) | `boom/ifu/fetch-target-queue.scala:315` | RegNext | faithful at base; gating reverted | `d482ae7` |
| `ifu/fetch-target-queue.scala:324` (1) | `boom/ifu/fetch-target-queue.scala:319` | RegNext | faithful at base; gating reverted | `d482ae7` |
| `ifu/fetch-target-queue.scala:324` (2) | `boom/ifu/fetch-target-queue.scala:319` | RegNext | faithful at base; gating reverted | `d482ae7` |
| `lsu/lsu.scala:840` (1) | `boom/lsu/lsu.scala:843` | RegNext | faithful at base; gating reverted | `ab74551` |
| `lsu/lsu.scala:1263` (1) | `boom/lsu/lsu.scala:1249` | RegNext | faithful at base; gating reverted | `ab74551` |
| `lsu/lsu.scala:1288` (1) | `boom/lsu/lsu.scala:1273` | RegNext | faithful at base; gating reverted | `ab74551` |
| `lsu/lsu.scala:1376` (1) | `boom/lsu/lsu.scala:1356` | RegNext | faithful at base; gating reverted | `ab74551` |
| `lsu/lsu.scala:1663` (1) | `boom/lsu/lsu.scala:1627` | RegNext | faithful at base; gating reverted | `ab74551` |
| `lsu/lsu.scala:1664` (1) | `boom/lsu/lsu.scala:1628` | RegNext | faithful at base; gating reverted | `ab74551` |
| `lsu/lsu.scala:1665` (1) | `boom/lsu/lsu.scala:1628` | RegNext | faithful at base; gating reverted | `ab74551` |

## Final verification

The restored-source runner completed at 2026-11-07 22:38:02 UTC. All full
suites ran under the BOOM lock. The driver log records waits but does not
record lock acquisition. Its last wait entry is not its current status.
Individual suite logs show that execution began at about 22:16 UTC.

| Check | Passed | Failed |
|---|---:|---:|
| Chisel/Icarus experiment | 8 cycles | 0 |
| Scope parser tests | 5 | 0 |
| Spinal scope tests | 2 | 0 |
| Restored single core tests: add, ma_addr, sd | 3 | 0 |
| Small riscv-tests with RVFI, seed 1 | 321 | 0 |
| Medium riscv-tests, seed 1 | 321 | 0 |
| VM tests | 1 | 0 |
| ACT4 | 585 | 35 |
| Large benchmarks, seed 1, memory latency 20 | 9 | 0 |

VM ELF build passed. No suite aborted. No tests were canceled, ignored or
pending. The full runner returns status 1 because ACT4 has 35 failures.
The required ACT4 directory was `../ref/act4/work/boom-rv64gc/elfs`.

The ACT4 result agrees with the existing baseline status in `act4/README.md`:
585 of 620 pass, with 35 known failures. These include fetch-PMP boundary
cases, canonical-address and trap-value reference differences, SC retry
assertions, and cases that the existing report has not fully diagnosed.
The exact observed failure names are retained in
`tools/chisel-scope/evidence/act4-failures.txt`.
They are not waived or reclassified as passes. No new baseline ACT4 control
run was added: `git diff fdd21f4 -- src/main/scala` is empty, and the baseline
status is taken from the existing ACT4 README. Thus no claim of 620/620 is
supported by this run. No new co-simulation or whole-core equivalence run
was performed.

The final tree restores every production Scala file to `fdd21f4` and
removes the incorrect scope helper. Only tests, experiment evidence, scanner
wording and documentation differ from that production source tree.
The exact source comparison also bounds the remaining ACT4 failures:
there is no retained RegNext change to explain a new RTL regression.

Suite logs, exit codes and full diagnostic VCDs are local in `audit-results/`.
The previous all-gated suite logs are in `audit-results/previous-gated-suites/`.
The scanner still reports lexical conditions. Its output is not an enable
specification or a proof of compiler behavior.

## IPC comparison

All nine benchmark ELFs match the recorded baseline SHA-256 values.
The restored CSV is byte-for-byte identical to `audit-results/baseline-large.csv`.
Every ROI cycle count and retired-instruction count is unchanged. The retained
comparison is `tools/chisel-scope/evidence/ipc-comparison.csv`.

| Benchmark | Baseline IPC | Restored IPC | Change |
|---|---:|---:|---:|
| dhrystone | 1.6822 | 1.6822 | 0.00% |
| median | 0.8965 | 0.8965 | 0.00% |
| memcpy | 0.6031 | 0.6031 | 0.00% |
| multiply | 2.6797 | 2.6797 | 0.00% |
| qsort | 0.5460 | 0.5460 | 0.00% |
| rsort | 1.8534 | 1.8534 | 0.00% |
| spmv | 1.3066 | 1.3066 | 0.00% |
| towers | 0.7804 | 0.7804 | 0.00% |
| vvadd | 1.9069 | 1.9069 | 0.00% |
