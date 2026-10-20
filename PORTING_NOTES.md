# BOOM v4 → SpinalHDL porting notes

Source of truth: `../riscv-boom/src/main/scala/v4` (BOOM v4), `../ref/rocket-chip` (rocket-chip
master, for the pieces BOOM depends on) and `../ref/rocket-chip/dependencies/hardfloat`.

## Layout

| Spinal package       | Ported from                                   |
|----------------------|-----------------------------------------------|
| `hardfloat`          | berkeley-hardfloat                            |
| `rocketchip.util`    | rocket-chip `util` + the chisel3.util pieces whose cycle behaviour matters (Queue, Arbiter, RRArbiter, LFSR, PLRU, ...) |
| `rocketchip.config`  | CDE `Parameters` + the diplomacy-derived values (paddr width, block size, memory map/PMAs) |
| `rocketchip.rocket`  | rocket-chip `rocket` (CSRFile, ALU, MulDiv, TLB, PTW, PMP, RVC, ...) |
| `rocketchip.tile`    | rocket-chip `tile` (CoreParams, FPU units, ...) |
| `rocketchip.tilelink`| ClientMetadata / TL permission encodings      |
| `boom.*`             | BOOM v4                                       |

## Semantics decisions (Chisel → Spinal)

* **Last connect wins / partial registers.** `SpinalBoomConfig` inserts a phase that tags every
  signal `allowAssignmentOverride` and every register `AllowPartialyAssignedTag`, giving Chisel's
  `w := x; w.f := y` and "unconnected Reg holds its value" semantics. Combinational latch and
  undriven-signal checks stay on. Always elaborate with `SpinalBoomConfig()`.
* **Bit packing.** Chisel packs a Bundle first-field-MSB (Vec element 0 at the LSB); Spinal packs
  first-field-LSB. Internal round trips just use `asBits`/`assignFromBits`. Where the packing is
  architectural (CSR values, `mstatus`, PTEs from memory, ...) use `ChiselLayout.asUInt/fromUInt`.
* **Cat.** `Cat(a, b)` / `Cat(seq: _*)` = Chisel `Cat` (first is MSB). Never call Spinal's
  `Cat(seq)` without `: _*` (it is LSB-first). `CatSeq` / `CatLsbFirst` helpers exist.
* **Widths.** Spinal errors on width mismatch where Chisel truncates/extends: use `.resized` /
  `.resize(n)`. Bitwise ops on explicitly sized operands must match. Comparisons against
  out-of-range constants are allowed (`allowOutOfRangeLiterals`). Slicing an `SInt` yields an
  `SInt` in Spinal (Chisel: UInt) — slice `x.asUInt(...)` instead.
* **log2.** `spinal.core.log2Up(x)` == Chisel `log2Ceil(x)`. Chisel `log2Up` (min 1) is
  `log2UpChisel`. `log2Ceil` is defined in `rocketchip.util`.
* **Dynamic Seq/Vec indexing.** `seq.read(idx)` has rocket-chip `Seq.apply(UInt)` semantics
  (MSBs ignored, non-pow2 reflected). Chisel truncates a too-wide Vec index
  (`Vec.truncateIndex`); Spinal requires an exact width — use `vec.at(idx)` / `vec.write(idx)(f)`
  (or `vec(idx.resized)`), which truncate the same way.
* **Seq.** `rocketchip.util` aliases `Seq` to `scala.collection.Seq` so Spinal `Vec`s can be
  passed where Chisel code passed `Seq`s. Varargs (`: _*`) need `.toList`.
* **Traits with hardware fields** must `extend Bundle`, otherwise Spinal does not register the
  fields.
* **Bundles taking `(implicit p: Parameters)`** extend `ParameterizedBundle`, which clones them
  reflectively; any other constructor parameters must be `val`s.
* **`in`/`out` field names** shadow Spinal's direction keywords inside a bundle body — use the
  `IN`/`OUT` aliases there.
* **Submodules instantiated under `when`** in Chisel are hoisted out (their inputs are don't-care
  when unused); Spinal would otherwise see conditional input assignments.
* **Valid/Decoupled** → `Flow`/`Stream` (`.bits` → `.payload`). Where Chisel used `Valid(T)` as
  a *payload* (PTW requests), a small bundle with `valid`/`bits` is used.
* **Decode tables** use `rocketchip.util.BitPat` / `DecodeLogic` (Symplify-minimised; Chisel
  TruthTable padding/merging semantics).
* **DontCare.** firtool lowers Chisel `DontCare` to 0, and upstream relies on that (e.g. the LSU's
  integer writeback leaves `fflags.valid` as DontCare). `SpinalBoomConfig` sets
  `dontCareGenAsZero`, so `assignDontCare()` generates 0 as well.
* **SyncReadMem.** Chisel leaves `SyncReadMem` read-during-write undefined by default; the
  firtool / chipyard flow simulates it as a registered address plus an asynchronous read, so a
  same-cycle write to the read address is visible (write-first). The port reproduces that
  lowering (a compatibility choice, not a Chisel guarantee). Spinal's `readSync` would return the old data. All synchronous-read memories go
  through `readSyncChisel` (`rocketchip.util.MemAugment`), `SyncReadVecMem` or `SyncReadMemC`, which
  reproduce the Chisel behaviour.
* **Dynamic bit select.** Chisel's `x(idx: UInt)` is `(x >> idx)(0)`, i.e. 0 when `idx` is out of
  range. Where upstream relies on that (busy table, LSU forwarding) the port uses the same shift.
* **Chisel `withReset(reset || clear)` on a Queue** becomes `Queue(..., hasFlush = true)`.
* **Bulk connects between differently-shaped bundles** (e.g. `ExeUnitResp(xLen)` into
  `ExeUnitResp(65)`, `BoomDCacheReqInternal` into `BoomDCacheResp`) are written out field by
  field, resizing where Chisel would extend/truncate.
* **Construction-order quirk kept:** `AbstractRenameStage` calls the overridden
  `BypassAllocations` before `RenameStage.int` is initialised, so that path sees `int == false`
  (as upstream).
* **TileLink.** rocket's `TLEdgeOut` helpers (AcquireBlock, Get, Put, Release, ProbeAck, GrantAck,
  count/done/addr_inc, MaskGen) and `TLArbiter.lowest` are in `rocketchip.tilelink.Edges`, built on
  Spinal's channel bundles. Permission/param encodings are the TileLink ones (shared by both).
  Bus shapes that diplomacy would infer are given explicitly: `ICache.busParams`,
  `BoomNonBlockingDCache.busParams` (D$ data width = row width).

## Deliberate differences

* No clock gating (rocket/BOOM's `clockGate` paths and the custom-CSR wires that only feed
  clock-gating are omitted).
* CSRFile: vector CSRs not implemented (`require(!usingVector)`); hypervisor logic is ported but
  is dead for BOOM (which hard-wires `useHypervisor = false`).
* ECC on caches/TLBs is not supported (`tagECC`/`dataECC` must be `None`); the L2 TLB parity code
  is ported.
* No TileLink atomics (Arithmetic/Logical) or Hints — Spinal's TileLink does not define them.
  This is equivalent to rocket-chip with no manager advertising atomics: AMOs to uncached
  regions raise access faults (cacheable AMOs execute in the D$ as usual).
* Not ported: RoCC (`require(!usingRoCC)`), the software/DPI branch predictor, the TraceGen tile,
  the trace encoder / trace-core ingress, the age-matrix issue unit (`useMatrixIssue` throws until
  it is ported), the cycle-by-cycle printfs, and rocket's `PlusArg` (the hang detector uses its
  default, bit 13).
* The tile is a plain component: the I$ (`imem`, TL-UH Get) and D$ (`dmem`, TL-C) masters are
  separate ports, and hart id / reset vector / interrupts are inputs. `boom.system.BoomSimTop`
  merges the two masters with Spinal's `tilelink.Arbiter` for simulation.
* Config fragments (`WithNSmallBooms`, `WithTAGELBPD`, ...) are functions in `BoomConfigs`.
* `enableRVFI` (BoomCoreParams) is a port-only option for the riscv-formal harness.

## Upstream bugs fixed in the port

The first two were found by ACT4, the others in review; each is a small, commented change and
easy to revert.

* **LSU, faulting AMO/SC still executes** (`lsu.scala`, STQ `addr.valid`). Upstream holds an STQ
  entry's address invalid only on a store page fault ("Prevent AMOs from executing!"). The
  `exception` flag that keeps an entry out of the store-execute queue is set a cycle later (from
  `mem_xcpt`), so a misaligned or access-faulting AMO/SC could still be sent to the D$: it
  modified memory and its response hit a flushed ROB entry ("[rob] writeback occurred to an
  invalid ROB entry"). The port also holds the address invalid on `ma_st` / `ae_st`.
* **NBDTLB ignores the PTW's page fault** (`tlb.scala`). Current rocket-chip's PTW reports a leaf
  PTE with reserved bits set (N/PBMT/bits 63:54) via `resp.pf` and leaves its permission bits as
  they are; rocket's TLB keeps that per entry, BOOM's NBDTLB (derived from an older rocket TLB)
  never read it, so loads/stores to such pages succeeded. The port stores `pf` per entry and
  folds it into the fault arrays as rocket's TLB does. (The I-TLB is rocket's and was correct.)
* **NBDTLB installs a refill raced by `sfence.vma`** (`tlb.scala`). `invalidate_refill` was
  computed but unused, so a walk that was in flight when an `sfence.vma` arrived still installed
  its (possibly stale) result. The port invalidates the refilled entry as rocket's TLB does.
  Covered by `boom.lsu.NBDTLBSpec`.
* **NBDTLB drops the PTW's `ae_ptw`** (`tlb.scala`). The entry had one `ae` bit, filled from
  `ae_final` and used where rocket uses `ae_ptw`, so a walk that could not read a PTE (address
  outside memory, or PMP-denied) gave a page fault instead of an access fault. The port keeps
  `ae_ptw` and `ae_final` separately, as rocket's TLB does; `ae_final` therefore no longer hides
  a permission page fault. Covered by `tests/vm/ae_ptw.S` (`VmTestsSpec`).

## Known non-conformances kept from upstream

* **Fetch PMP granularity.** The I-TLB checks PMP over the whole fetch block
  (`coreInstBytes * fetchWidth`, 8 bytes on the small config), so a PMP region narrower than
  that (e.g. NA4) around fetched code faults even when the instruction itself is inside it.
* **`debug_sc_fail_cnt`**: the D$ asserts after 100 failed SCs in a row to one address. That is
  legal software behaviour (ACT's `Zalrsc-sc.{w,d}` deliberately retry an SC outside the
  reservation set 100 times), but the assertion stops the simulation.
* `instret_overflow` (riscv-tests): CSR written at execute, retirement counted at commit.

## Simulation harness notes

* `SpinalBoomConfig` gives every assertion without a message its Scala location
  (`PhaseAssertLocations`), so `FAILURE lsu:L391` says which one fired.
* BOOM's D$ (like upstream) drops clean lines without a Release. Spinal's sim `MemoryAgent`
  decides whether a Grant carries data from its own record of the client's permissions, so a
  later `AcquireBlock NtoB` for that line got a dataless `Grant`. The harness's
  `BoomMemoryAgent` sends `GrantData` whenever the acquire comes from N, as TileLink requires.
* Spinal's `tilelink.Arbiter` locks on a stalled requester, while rocket's I$ withdraws
  `a.valid` (s2_kill); `BoomSimTop` puts an `m2sPipe` on the I$ A channel.
* `BoomSim.Platform`: a CLINT (0x0200_0000) and an interrupt generator (0x0C00_0000, words 0/1 =
  meip/seip) are uncached IO regions served from simulation memory; the harness drives the
  interrupt lines from them every cycle and `mtime` counts cycles.

## Cycle-equivalence gate for pipeline refactoring

`scripts/cycle-equivalence.py` records or checks commit cycles, every fired
TileLink beat, Konata transitions including flushes, and selected per-cycle
frontend/LSU/cache controls in one deterministic simulation per ELF/preset.
Instrumentation uses the existing off-by-default Konata probe; core logic is
unchanged. The reference is `0cb0b69`. See
[commands, trace formats and coverage limits](docs/CYCLE_EQUIVALENCE.md).

## Pipelining API

Transaction stage chains use `spinal.lib.misc.pipeline` from SpinalHDL 1.15.0.
`Node.insert` creates a `Payload`; `StageLink` places the registers and `Builder`
connects only payloads read downstream. Construct all consumers before calling
`Builder`. No custom Link is needed for the fixed-latency units.

* `rocketchip.util.FixedPipeline` replaces Rocket `Pipe`: valid is reset to false,
  data is uninitialised and captures only when the preceding valid is true.
  In 1.15.0 `withPayloadHold()` is ineffective without ready, so the final ready
  is tied to true and every link uses `withoutCollapse()`. This is a constant
  enable path, with no backpressure and no change in latency. A zero delay is
  combinational, including its invalid payload.
* Unconditional `RegNext` / Flow-delay chains use `holdPayload = false`, with no
  ready signals. `FixedPipeline.delay` carries data only and adds no valid or reset
  registers. For an uninitialised valid, `resetValid = false` removes the init
  assignments from the StageLink-generated valid registers after `Builder`.
* `boom.util.BranchPipeline.boundary` is a `CtrlLink` before a `StageLink`.
  `terminateWhen` kills using the OLD mask and flush; `bypass(REQ)` clears resolved
  mask bits in the value crossing the register. Thus next valid is
  `valid && !(flush || (oldMask & mispredictMask).orR)` and next mask is
  `oldMask & ~resolveMask`. The final node gets no additional kill. Termination
  suffices for a free-running pipe; `throwWhen` would also request upstream
  cancellation/forgetting, which these sources do not need. There is no `flush()`
  method in this API.

This changes the expression of the original port, not the upstream cycle contract.
Queues, FSMs, memories, CAMs, rename tables and the ROB remain outside this work.
