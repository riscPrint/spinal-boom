//******************************************************************************
// Copyright (c) 2015 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISC-V Processor Core
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// BOOM has the following (conceptual) stages:
//   if0 - Instruction Fetch 0 (next-pc select)
//   if1 - Instruction Fetch 1 (I$ access)
//   if2 - Instruction Fetch 2 (instruction return)
//   if3 - Instruction Fetch 3 (enqueue to fetch buffer)
//   if4 - Instruction Fetch 4 (redirect from bpd)
//   dec - Decode
//   ren - Rename1
//   dis - Rename2/Dispatch
//   iss - Issue
//   rrd - Register Read
//   exe - Execute
//   mem - Memory
//   sxt - Sign-extend
//   wb  - Writeback
//   com - Commit
//
// Port notes: RoCC, the trace-core ingress and the cycle-by-cycle printfs
// are not ported. The commit trace port (`io.trace`, rocket's TraceBundle)
// is kept since the RVFI port builds on it.

package boom.exu

import spinal.core._
import spinal.lib._

import rocketchip.config.Parameters
import rocketchip.rocket.Instructions._
import rocketchip.rocket.{Causes, PRV, CSR, CSRs, TracedInstruction}
import rocketchip.tile.{HasFPUParameters, CustomCSR}
import rocketchip.util._

import boom.common._
import boom.ifu.{GlobalHistory, HasBoomFrontendParameters}
import boom.util._

/** rocket-chip's TraceBundle with BOOM's custom field. */
class BoomCoreTrace(implicit p: Parameters) extends BoomBundle {
  val insns = Vec.fill(retireWidth)(new TracedInstruction)
  val time = UInt(xLen bits)
  val rob_empty = Bool() // BoomTraceBundle
}

/**
 * Top level core object that connects the Frontend to the rest of the pipeline.
 */
class BoomCore(roccCSRs: Seq[Seq[CustomCSR]] = Nil)(implicit p: Parameters) extends BoomModule
  with HasBoomFrontendParameters // TODO: Don't add this trait
  with HasFPUParameters
{
  require(!usingRoCC, "RoCC is not supported by the Spinal port")

  val io = new Bundle {
    val hartid = in UInt(hartIdLen bits)
    val interrupts = in(new rocketchip.tile.CoreInterrupts(false))
    val ifu = master(new boom.ifu.BoomFrontendIO)
    val ptw = master(new rocketchip.rocket.DatapathPTWIO())
    val lsu = new boom.lsu.LSUCoreIO().flip()
    val trace = out(new BoomCoreTrace)
    val fcsr_rm = out UInt(rocketchip.tile.FPConstants.RM_SZ bits)
    val traceStall = in Bool()
    // Spinal port: riscv-formal trace (see rvfi.scala)
    val rvfi = if (boomParams.enableRVFI) out(Vec.fill(retireWidth)(RvfiChannel(xLen))) else null
  }
  io.ptw.hgatp.assignDontCare()
  io.ptw.vsatp.assignDontCare()
  io.ptw.hstatus.assignDontCare()
  io.ptw.gstatus.assignDontCare()

  //**********************************
  // construct all of the modules

  val mem_exe_units: Seq[MemExeUnit] = (0 until memWidth) map { w =>
    new MemExeUnit(
      hasAGen = w >= (memWidth - lsuWidth),
      hasDGen = true
    ).setName(s"mem_exe_unit_${w}")
  }
  val agen_exe_units: Seq[MemExeUnit] = mem_exe_units.filter(_.hasAGen)
  val unq_exe_units: Seq[UniqueExeUnit] = Seq(
    new UniqueExeUnit(
      hasCSR = true,
      hasMul = true,
      hasDiv = true,
      hasIfpu = true
    ).setName(s"unique_exe_unit_0")
  )
  val unq_exe_unit = unq_exe_units(0)
  val csr_resp = unq_exe_unit.io_csr_resp.get
  val alu_exe_units: Seq[ALUExeUnit] = (0 until aluWidth) map { w =>
    new ALUExeUnit(
      id             = w
    ).setName(s"alu_exe_unit_${w}")
  }
  val all_exe_units: Seq[ExecutionUnit with HasIrfReadPorts with HasImmrfReadPort] = (alu_exe_units ++ mem_exe_units ++ unq_exe_units)

  // Meanwhile, the FP pipeline holds the FP issue window, FP regfile, and FP arithmetic units.
  val fp_pipeline = new FpPipeline

  val numIrfWritePorts        = aluWidth + lsuWidth + 1
  val numIrfLogicalReadPorts  = all_exe_units.map(_.nReaders).reduce(_+_)

  val numIntWakeups           = coreWidth + lsuWidth + 1
  val numFpWakeupPorts        = fp_pipeline.io.wakeups.length

  val numImmReaders     = aluWidth + memWidth + 1 // "Wakeup" immediates when they are read

  val decode_units      = (0 until decodeWidth) map { w => new DecodeUnit().setName(s"decode_${w}") }
  val dec_brmask_logic  = new BranchMaskGenerationLogic(coreWidth)
  val rename_stage      = new RenameStage(coreWidth, numIntPhysRegs, numIntWakeups, false)
  val fp_rename_stage   = new RenameStage(coreWidth, numFpPhysRegs, numFpWakeupPorts, true)
  val pred_rename_stage = new PredRenameStage(coreWidth, 1)
  val imm_rename_stage  = new ImmRenameStage(coreWidth, numImmReaders) // wakeup ports used when insts read imm
  val rename_stages     = Seq(rename_stage, pred_rename_stage, imm_rename_stage) ++ (if (usingFPU) Seq(fp_rename_stage) else Nil)

  val mem_iss_unit     = IssueUnit(memIssueParam, numIntWakeups, false)
  val unq_iss_unit     = IssueUnit(unqIssueParam, numIntWakeups, false)
  val alu_iss_unit     = IssueUnit(aluIssueParam, numIntWakeups, true)
  mem_iss_unit.setName("mem_issue_unit")
  unq_iss_unit.setName("unq_issue_unit")
  alu_iss_unit.setName("alu_issue_unit")
  val dispatcher       = new BasicDispatcher
  val iregfile         = new PartiallyPortedRF(
    UInt(xLen bits),
    numIntPhysRegs,
    numIrfLogicalReadPorts,
    numIrfReadPorts,
    numIrfWritePorts,
    "Integer"
  )
  val pregfile         = new FullyPortedRF(
    Bool(),
    ftqSz,
    aluWidth,
    1,
    "Predicate"
  )
  val immregfile       = new FullyPortedRF(
    UInt(LONGEST_IMM_SZ bits),
    numImmPhysRegs,
    numImmReaders,
    coreWidth,
    "Immediate"
  )
  val bregfile         = new FullyPortedRF(
    new BrInfoBundle,
    maxBrCount,
    aluWidth,
    coreWidth,
    "Branch"
  )
  val rob              = new Rob(
    numIrfWritePorts + numFpWakeupPorts,
    trace || boomParams.enableRVFI
  )
  // Used to wakeup registers in rename and issue. ROB needs to listen to something else.
  val int_wakeups  = Vec.fill(numIntWakeups)(Flow(new Wakeup))
  val pred_wakeups = Vec.fill(aluWidth)(Flow(new Wakeup))

  // The arb stage guarantees only 1 pred wakeup per cycle
  assert(PopCount(pred_wakeups.map(_.valid)) <= 1)
  val pred_wakeup  = Flow(new Wakeup)
  pred_wakeup.valid            := pred_wakeups.map(_.valid).reduce(_||_)
  pred_wakeup.payload.assignDontCare()
  pred_wakeup.payload.uop.pdst := Mux1H(pred_wakeups.map(_.valid), pred_wakeups.map(_.payload.uop.pdst))

  val int_bypasses  = Vec.fill(coreWidth + lsuWidth)(Flow(new ExeUnitResp(xLen)))

  /** Chisel connects Valid(ExeUnitResp) of different data widths, extending/truncating `data`. */
  def connectResp(dst: Flow[ExeUnitResp], src: Flow[ExeUnitResp]): Unit = {
    dst.valid := src.valid
    dst.payload.uop        := src.payload.uop
    dst.payload.data       := src.payload.data.resized
    dst.payload.predicated := src.payload.predicated
    dst.payload.fflags     := src.payload.fflags
  }
  /** `<>` between a requester Stream and a register-file read port of a different index width. */
  def connectReadReq(port: Stream[UInt], req: Stream[UInt]): Unit = {
    port.valid   := req.valid
    port.payload := req.payload.resized
    req.ready    := port.ready
  }

  //***********************************
  // Pipeline State Registers and Wires

  // Decode/Rename1 Stage
  val dec_valids = Vec.fill(coreWidth)(Bool())  // are the decoded instruction valid? It may be held up though.
  val dec_uops   = Vec.fill(coreWidth)(new MicroOp())
  val dec_fire   = Vec.fill(coreWidth)(Bool())  // can the instruction fire beyond decode?
                                                // (can still be stopped in ren or dis)
  val dec_ready  = Bool()
  val dec_xcpts  = Vec.fill(coreWidth)(Bool())
  val ren_stalls = Vec.fill(coreWidth)(Bool())

  // Rename2/Dispatch stage
  val dis_valids = Vec.fill(coreWidth)(Bool())
  val dis_uops   = Vec.fill(coreWidth)(new MicroOp)
  val dis_fire   = Vec.fill(coreWidth)(Bool())
  val dis_ready  = Bool()

  // Issue Stage/Register Read
  val mem_iss_uops = mem_iss_unit.io.iss_uops
  val alu_iss_uops = alu_iss_unit.io.iss_uops
  val unq_iss_uops = unq_iss_unit.io.iss_uops

  // --------------------------------------
  // Dealing with branch resolutions

  // Keep independent unreset flush captures at each consumer.
  // Sharing these captures changes their simulation initial values.

  // The individual branch resolutions from each ALU
  val brinfoInputs = Vec.fill(coreWidth)(Flow(new BrResolutionInfo))
  val brinfos = FixedPipeline.delay(brinfoInputs, 1)
    .setName("brinfos")

  // "Merged" branch update info from all ALUs
  // brmask contains masks for rapidly clearing mispredicted instructions
  // brindices contains indices to reset pointers for allocated structures
  //           brindices is delayed a cycle
  val brupdate  = new BrUpdateInfo
  val b1    = new BrUpdateMasks
  val b2Input = new BrResolutionInfo
  val b2    = FixedPipeline.delay(b2Input, 1).setName("b2")

  brupdate.b1 := b1
  brupdate.b2 := b2

  for ((b, a) <- brinfoInputs zip alu_exe_units) {
    b.payload := UpdateBrMask(brupdate, a.io_brinfo.payload)
    b.valid := a.io_brinfo.valid && !rob.io.flush.valid && !IsKilledByBranch(brupdate, RegNext(rob.io.flush.valid), a.io_brinfo.payload)
  }
  b1.resolve_mask := brinfos.map(x => (x.valid.asUInt << x.payload.uop.br_tag).resize(maxBrCount)).reduce(_|_)
  b1.mispredict_mask := brinfos.map(x => ((x.valid && x.payload.mispredict).asUInt << x.payload.uop.br_tag).resize(maxBrCount)).reduce(_|_)

  // Find the oldest mispredict and use it to update indices
  val live_brinfos      = brinfos.map(br => br.valid && br.payload.mispredict && !IsKilledByBranch(brupdate, RegNext(rob.io.flush.valid), br.payload.uop))
  val mispredict_val    = live_brinfos.reduce(_||_)

  b2Input.mispredict  := mispredict_val
  b2Input.cfi_type    := Mux1H(live_brinfos, brinfos.map(_.payload.cfi_type))
  b2Input.taken       := Mux1H(live_brinfos, brinfos.map(_.payload.taken))
  b2Input.pc_sel      := Mux1H(live_brinfos, brinfos.map(_.payload.pc_sel))
  b2Input.uop         := UpdateBrMask(brupdate, PriorityMux(live_brinfos, brinfos.map(_.payload.uop)))
  b2Input.jalr_target := Mux1H(live_brinfos, brinfos.map(_.payload.jalr_target))
  b2Input.target_offset := Mux1H(live_brinfos, brinfos.map(_.payload.target_offset))

  val oldest_mispredict_ftq_idx = Mux1H(live_brinfos, brinfos.map(_.payload.uop.ftq_idx))

  assert (!((brupdate.b1.mispredict_mask =/= 0 || brupdate.b2.mispredict)
    && rob.io.rollback), "Can't have a mispredict during rollback.")

  io.ifu.brupdate := brupdate

  for (eu <- all_exe_units) {
    eu.io_brupdate := brupdate
  }

  fp_pipeline.io.brupdate := brupdate

  // Load/Store Unit & ExeUnits
  var agen_idx = 0
  var dgen_idx = 0
  for (eu <- mem_exe_units) {
    if (eu.hasAGen) {
      io.lsu.agen(agen_idx) := eu.io_agen.get
      agen_idx += 1
    }
    if (eu.hasDGen) {
      io.lsu.dgen(dgen_idx) := eu.io_dgen.get
      dgen_idx += 1
    }
  }
  io.lsu.dgen(dgen_idx) := fp_pipeline.io.dgen

  //-------------------------------------------------------------
  // Uarch Hardware Performance Events (HPEs)

  val perfEvents = new rocketchip.rocket.EventSets(Seq(
    new rocketchip.rocket.EventSet((mask, hits) => (mask & hits).orR, Seq(
      ("exception", () => rob.io.com_xcpt.valid),
      ("nop",       () => False),
      ("nop",       () => False),
      ("nop",       () => False))),

    new rocketchip.rocket.EventSet((mask, hits) => (mask & hits).orR, Seq(
      ("nop",                               () => False),
      ("branch misprediction",              () => b2.mispredict),
      ("control-flow target misprediction", () => b2.mispredict &&
                                                  b2.cfi_type === CFI_JALR),
      ("flush",                             () => rob.io.flush.valid)
    )),

    new rocketchip.rocket.EventSet((mask, hits) => (mask & hits).orR, Seq(
      ("I$ miss",     () => io.ifu.perf.acquire),
      ("D$ miss",     () => io.lsu.perf.acquire),
      ("D$ release",  () => io.lsu.perf.release),
      ("ITLB miss",   () => io.ifu.perf.tlbMiss),
      ("DTLB miss",   () => io.lsu.perf.tlbMiss),
      ("L2 TLB miss", () => io.ptw.perf.l2miss)))))
  val csr = new rocketchip.rocket.CSRFile(perfEvents, boomParams.customCSRs.decls, roccCSRs.flatten)
  csr.io.inst.foreach(_.assignDontCare())
  csr.io.rocc_interrupt := False
  csr.io.gva.assignDontCare()
  csr.io.htval.assignDontCare()
  csr.io.mhtinst_read_pseudo := False

  // Chisel: custom_csrs.csrs <> csr.io.customCSRs (stall/set/sdata driven here)
  val custom_csrs = new BoomCustomCSRs
  for ((lhs, rhs) <- custom_csrs.csrs zip csr.io.customCSRs) {
    rhs.stall := False
    rhs.set   := False
    rhs.sdata.assignDontCare()
    lhs.ren   := rhs.ren
    lhs.wen   := rhs.wen
    lhs.wdata := rhs.wdata
    lhs.value := rhs.value
    lhs.stall := rhs.stall
    lhs.set   := rhs.set
    lhs.sdata := rhs.sdata
  }
  io.ifu.enable_bpd := custom_csrs.enableBPD

  val icache_blocked = False
  csr.io.counters foreach { c => c.inc := FixedPipeline.delay(perfEvents.evaluate(c.eventSel), 1).asUInt.resized }

  //****************************************
  // Time Stamp Counter & Retired Instruction Counter
  // (only used for printf and vcd dumps - the actual counters are in the CSRFile)
  val debug_tsc_reg = Reg(UInt(xLen bits)) init 0
  val debug_irt_reg = Reg(UInt(xLen bits)) init 0

  debug_tsc_reg := debug_tsc_reg + 1
  debug_irt_reg := debug_irt_reg + PopCount(rob.io.commit.arch_valids).resized

  //****************************************
  // Print-out information about the machine

  val issStr = "(Age-based Priority)"

  val fpPipelineStr = fp_pipeline.toString

  override def toString: String =
    (BoomCoreStringPrefix("====Overall Core Params====") + "\n\n"
    + mem_exe_units.map(_.toString).mkString("") + "\n"
    + unq_exe_units.map(_.toString).mkString("") + "\n\n"
    + alu_exe_units.map(_.toString).mkString("") + "\n\n"
    + fpPipelineStr + "\n\n"
    + rob.toString + "\n\n"
    + BoomCoreStringPrefix(
        "===Other Core Params===",
        "Fetch Width           : " + fetchWidth,
        "Decode Width          : " + coreWidth,
        "Issue Width           : " + issueParams.map(_.issueWidth).sum,
        "ROB Size              : " + numRobEntries,
        "Issue Window Size     : " + issueParams.map(_.numEntries) + issStr,
        "Load/Store Unit Size  : " + numLdqEntries + "/" + numStqEntries,
        "Num Int Phys Registers: " + numIntPhysRegs,
        "Num FP  Phys Registers: " + numFpPhysRegs,
        "Load-to-use delay     : 5",
        "Max Branch Count      : " + maxBrCount)
    + "\n\n" + iregfile.toString + "\n\n"
    + BoomCoreStringPrefix(
        "Num Wakeup Ports      : " + numIntWakeups,
        "Num Bypass Ports      : " + int_bypasses.length) + "\n"
    + BoomCoreStringPrefix(
        "DCache Ways           : " + dcacheParams.nWays,
        "DCache Sets           : " + dcacheParams.nSets,
        "DCache nMSHRs         : " + dcacheParams.nMSHRs,
        "ICache Ways           : " + icacheParams.nWays,
        "ICache Sets           : " + icacheParams.nSets,
        "D-TLB Ways            : " + dcacheParams.nTLBWays,
        "I-TLB Ways            : " + icacheParams.nTLBWays,
        "Paddr Bits            : " + paddrBits,
        "Vaddr Bits            : " + vaddrBits) + "\n"
    + BoomCoreStringPrefix(
        "Using FPU Unit?       : " + usingFPU.toString,
        "Using FDivSqrt?       : " + usingFDivSqrt.toString,
        "Using VM?             : " + usingVM.toString) + "\n")

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Fetch Stage/Frontend ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------
  io.ifu.redirect_val         := False
  io.ifu.redirect_flush       := False
  io.ifu.redirect_pc.assignDontCare()
  io.ifu.redirect_ftq_idx.assignDontCare()
  io.ifu.redirect_ghist.assignDontCare()

  // Breakpoint info
  io.ifu.status  := csr.io.status
  io.ifu.bp      := csr.io.bp
  io.ifu.mcontext := csr.io.mcontext
  io.ifu.scontext := csr.io.scontext

  io.ifu.flush_icache := (0 until coreWidth).map { i =>
    (rob.io.commit.arch_valids(i) && rob.io.commit.uops(i).is_fencei) ||
    FixedPipeline.delay(dec_valids(i) && dec_uops(i).is_jalr && csr.io.status.debug, 1)
  }.reduce(_||_)

  val csr_evec_delayed = FixedPipeline.delay(csr.io.evec, 3)

  // TODO FIX THIS HACK
  // The below code works because of two quirks with the flush mechanism
  //  1 ) All flush_on_commit instructions are also is_unique,
  //      In the future, this constraint will be relaxed.
  //  2 ) We send out flush signals one cycle after the commit signal. We need to
  //      mux between one/two cycle delay for the following cases:
  //       ERETs are reported to the CSR two cycles before we send the flush
  //       Exceptions are reported to the CSR on the cycle we send the flush
  // This discrepency should be resolved elsewhere.
  // Delayed ROB-flush fields: one unconditional, uninitialised data stage
  // (the original RegNexts were declared inside the branches, so free-running).
  val flushInfo = new Bundle {
    val flush_typ = cloneOf(rob.io.flush.payload.flush_typ)
    val pc_lob    = cloneOf(rob.io.flush.payload.pc_lob)
    val edge_inst = Bool()
    val is_rvc    = Bool()
    val ftq_idx   = cloneOf(rob.io.flush.payload.ftq_idx)
  }
  flushInfo.assignSomeByName(rob.io.flush.payload)
  val delayed_flush = FixedPipeline.delay(flushInfo, 1)
  when (RegNext(rob.io.flush.valid)) {
    io.ifu.redirect_val   := True
    io.ifu.redirect_flush := True
    val flush_typ = delayed_flush.flush_typ
    // Clear the global history when we flush the ROB (exceptions, AMOs, unique instructions, etc.)
    val new_ghist = new GlobalHistory
    new_ghist.assignFromBits(B(0, new_ghist.getBitsWidth bits))
    new_ghist.current_saw_branch_not_taken := True
    new_ghist.ras_idx := io.ifu.rrd_ftq_resps(0).entry.ras_idx
    io.ifu.redirect_ghist := new_ghist
    when (FlushTypes.useCsrEvec(flush_typ)) {
      io.ifu.redirect_pc  := Mux(flush_typ === FlushTypes.eret,
                                 csr_evec_delayed,
                                 csr.io.evec)
    } otherwise {
      val flush_pc = (AlignPCToBoundary(io.ifu.rrd_ftq_resps(0).pc, icBlockBytes)
                      + delayed_flush.pc_lob.resize(vaddrBitsExtended)
                      - Mux(delayed_flush.edge_inst, U(2, vaddrBitsExtended bits), U(0, vaddrBitsExtended bits)))
      val flush_pc_next = flush_pc + Mux(delayed_flush.is_rvc, U(2, vaddrBitsExtended bits), U(4, vaddrBitsExtended bits))
      io.ifu.redirect_pc := Mux(FlushTypes.useSamePC(flush_typ),
                                flush_pc, flush_pc_next)
    }
    io.ifu.redirect_ftq_idx := delayed_flush.ftq_idx
  } elsewhen (brupdate.b2.mispredict && !RegNext(rob.io.flush.valid)) {
    val block_pc = AlignPCToBoundary(io.ifu.rrd_ftq_resps(0).pc, icBlockBytes)
    val uop_maybe_pc = block_pc | brupdate.b2.uop.pc_lob.resize(vaddrBitsExtended)
    val npc = uop_maybe_pc + Mux(brupdate.b2.uop.is_rvc || brupdate.b2.uop.edge_inst, U(2, vaddrBitsExtended bits), U(4, vaddrBitsExtended bits))
    val jal_br_target = UInt(vaddrBitsExtended bits)
    jal_br_target := (uop_maybe_pc.asSInt + brupdate.b2.target_offset.resize(vaddrBitsExtended) +
      (Fill(vaddrBitsExtended-1, brupdate.b2.uop.edge_inst) << 1).resize(vaddrBitsExtended).asSInt).asUInt
    val bj_addr = Mux(brupdate.b2.cfi_type === CFI_JALR, brupdate.b2.jalr_target, jal_br_target)
    val mispredict_target = Mux(brupdate.b2.pc_sel === PC_PLUS4, npc, bj_addr)
    io.ifu.redirect_val     := True
    io.ifu.redirect_pc      := mispredict_target
    io.ifu.redirect_flush   := True
    io.ifu.redirect_ftq_idx := brupdate.b2.uop.ftq_idx
    val use_same_ghist = (brupdate.b2.cfi_type === CFI_BR &&
                          !brupdate.b2.taken &&
                          bankAlign(block_pc) === bankAlign(npc))
    val ftq_entry = io.ifu.rrd_ftq_resps(0).entry
    val lobW = brupdate.b2.uop.pc_lob.getWidth
    val cfi_idx = (brupdate.b2.uop.pc_lob ^
      Mux(ftq_entry.start_bank === 1, U(1 << log2Ceil(bankBytes), lobW bits), U(0, lobW bits)))(log2Ceil(fetchWidth) downto 1)
    val ftq_ghist = io.ifu.rrd_ftq_resps(0).ghist
    val next_ghist = ftq_ghist.update(
      ftq_entry.br_mask,
      brupdate.b2.taken,
      brupdate.b2.cfi_type === CFI_BR,
      cfi_idx,
      True,
      io.ifu.rrd_ftq_resps(0).pc,
      ftq_entry.cfi_is_call && ftq_entry.cfi_idx.payload === cfi_idx,
      ftq_entry.cfi_is_ret  && ftq_entry.cfi_idx.payload === cfi_idx)

    io.ifu.redirect_ghist   := Mux(
      use_same_ghist,
      ftq_ghist,
      next_ghist)
    io.ifu.redirect_ghist.current_saw_branch_not_taken := use_same_ghist
  } elsewhen (rob.io.flush_frontend || brupdate.b1.mispredict_mask =/= 0) {
    io.ifu.redirect_flush   := True
  }

  // Tell the FTQ it can deallocate entries by passing youngest ftq_idx.
  val youngest_com_idx = if (coreWidth == 1) U(0, 1 bits) else
    U(coreWidth-1, log2Ceil(coreWidth) bits) - PriorityEncoder(rob.io.commit.valids.reverse).resize(log2Ceil(coreWidth))
  io.ifu.commit.valid := rob.io.commit.valids.reduce(_|_) || rob.io.com_xcpt.valid
  io.ifu.commit.payload := Mux(rob.io.com_xcpt.valid,
                             rob.io.com_xcpt.payload.ftq_idx,
                             rob.io.commit.uops.at(youngest_com_idx).ftq_idx).resized

  assert(!(rob.io.commit.valids.reduce(_|_) && rob.io.com_xcpt.valid),
    "ROB can't commit and except in same cycle!")

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Decode Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // track mask of finished instructions in the bundle
  // use this to mask out insts coming from FetchBuffer that have been finished
  // for example, back pressure may cause us to only issue some instructions from FetchBuffer
  // but on the next cycle, we only want to retry a subset
  val dec_finished_mask = Reg(UInt(coreWidth bits)) init 0

  //-------------------------------------------------------------
  // Pull out instructions and send to the Decoders

  io.ifu.fetchpacket.ready := dec_ready
  val dec_fbundle = io.ifu.fetchpacket.payload

  //-------------------------------------------------------------
  // Decoders

  for (w <- 0 until coreWidth) {
    dec_valids(w)                      := io.ifu.fetchpacket.valid && dec_fbundle.uops(w).valid &&
                                          !dec_finished_mask(w)
    decode_units(w).io.enq.uop         := dec_fbundle.uops(w).payload
    decode_units(w).io.status          := csr.io.status
    decode_units(w).io.csr_decode      <> csr.io.decode(w)
    decode_units(w).io.interrupt       := FixedPipeline.delay(csr.io.interrupt, 1)
    decode_units(w).io.interrupt_cause := FixedPipeline.delay(csr.io.interrupt_cause, 1)
    decode_units(w).io.fcsr_rm         := csr.io.fcsr_rm

    dec_uops(w) := decode_units(w).io.deq.uop
  }

  //-------------------------------------------------------------
  // FTQ GetPC Port Arbitration
  // 3 ports
  // port0 goes to flush,mispredict,xcpt
  // port1/2 goes to jmp unit

  val xcpt_pc_req = Stream(UInt(log2Ceil(ftqSz) bits))
  val mispredict_pc_req = Stream(UInt(log2Ceil(ftqSz) bits))
  val flush_pc_req = Stream(UInt(log2Ceil(ftqSz) bits))

  val ftq_arb = new Arbiter(UInt(log2Ceil(ftqSz) bits), 3)

  // Order by the oldest. Flushes come from the oldest instructions in pipe
  // Decoding exceptions come from youngest
  ftq_arb.io.in(0) << flush_pc_req
  ftq_arb.io.in(1) << mispredict_pc_req
  ftq_arb.io.in(2) << xcpt_pc_req

  io.ifu.arb_ftq_reqs(0) := ftq_arb.io.out.payload
  ftq_arb.io.out.ready  := True

  val ftq_port_issued = Array.fill[Bool](2) { False }
  val ftq_port_addrs  = Array.fill[UInt](2) { U(0, log2Ceil(ftqSz) bits) }
  for (i <- 0 until aluWidth) {
    for (w <- 0 until 2) {
      val req = alu_exe_units(i).io_arb_ftq_reqs(w)
      var read_issued: Bool = False
      val data_sel = UInt(2 bits)
      data_sel := 0
      for (j <- 0 until 2) {
        val issue_read = CombInit(False)
        val use_port = CombInit(False)
        when (!read_issued && !ftq_port_issued(j) && req.valid) {
          issue_read := True
          use_port := True
          data_sel := U(1 << j, 2 bits)
        }
        val was_port_issued_yet = ftq_port_issued(j)
        ftq_port_issued(j) = use_port || ftq_port_issued(j)
        ftq_port_addrs(j) = ftq_port_addrs(j) | Mux(was_port_issued_yet || !use_port, U(0, log2Ceil(ftqSz) bits), req.payload)
        read_issued = issue_read || read_issued
      }
      req.ready := read_issued
      alu_exe_units(i).io_rrd_ftq_resps(w) := Mux(FixedPipeline.delay(data_sel(0), 1),
        io.ifu.rrd_ftq_resps(1), io.ifu.rrd_ftq_resps(2))
    }
  }
  for (j <- 0 until 2) {
    io.ifu.arb_ftq_reqs(j+1)  := ftq_port_addrs(j)
  }

  // Frontend Exception Requests
  val xcpt_idx = PriorityEncoder(dec_xcpts)
  xcpt_pc_req.valid    := dec_xcpts.reduce(_||_)
  xcpt_pc_req.payload  := dec_uops.at(xcpt_idx).ftq_idx
  rob.io.xcpt_fetch_pc := io.ifu.rrd_ftq_resps(0).pc

  flush_pc_req.valid   := rob.io.flush.valid
  flush_pc_req.payload := rob.io.flush.payload.ftq_idx

  // Mispredict requests (to get the correct target)
  mispredict_pc_req.valid   := mispredict_val
  mispredict_pc_req.payload := oldest_mispredict_ftq_idx

  //-------------------------------------------------------------
  // Decode/Rename1 pipeline logic

  dec_xcpts := Vec(dec_uops zip dec_valids map {case (u,v) => u.exception && v})
  // Frontend exceptions need to shoot straight past dec/dis without stall, to match
  // the timing of the FTQ resp which provides badaddr.
  // Wait for pipeline to empty before letting an exception past dec
  val dec_prior_slot_valid = dec_valids.scanLeft(False) ((s,v) => s || v)
  val dec_xcpt_stall = (0 until coreWidth).map(w => dec_xcpts(w) &&
    (!rob.io.empty || !io.lsu.fencei_rdy || dec_prior_slot_valid(w) || dis_valids.reduce(_||_) || !xcpt_pc_req.ready)
  )
  // stall fetch/dcode because we ran out of branch tags
  val branch_mask_full = Vec.fill(coreWidth)(Bool())

  val dec_hazards = (0 until coreWidth).map(w =>
                      dec_valids(w) &&
                      (  !dis_ready
                      || rob.io.rollback
                      || dec_xcpt_stall(w)
                      || branch_mask_full(w)
                      || brupdate.b1.mispredict_mask =/= 0
                      || brupdate.b2.mispredict
                      || io.ifu.redirect_flush))

  val dec_stalls = dec_hazards.scanLeft(False) ((s,h) => s || h).takeRight(coreWidth)
  dec_fire := Vec((0 until coreWidth).map(w => dec_valids(w) && !dec_stalls(w)))

  // all decoders are empty and ready for new instructions
  dec_ready := dec_fire.last

  when (dec_ready || io.ifu.redirect_flush) {
    dec_finished_mask := 0
  } otherwise {
    dec_finished_mask := dec_fire.asUInt | dec_finished_mask
  }

  //-------------------------------------------------------------
  // Branch Mask Logic

  dec_brmask_logic.io.brupdate := brupdate
  dec_brmask_logic.io.flush_pipeline := RegNext(rob.io.flush.valid)

  for (w <- 0 until coreWidth) {
    dec_brmask_logic.io.is_branch(w) := !dec_finished_mask(w) && dec_uops(w).allocate_brtag
    dec_brmask_logic.io.will_fire(w) :=  dec_fire(w) &&
                                         dec_uops(w).allocate_brtag // ren, dis can back pressure us
    dec_uops(w).br_tag  := dec_brmask_logic.io.br_tag(w)
    dec_uops(w).br_mask := dec_brmask_logic.io.br_mask(w)
  }

  branch_mask_full := dec_brmask_logic.io.is_full

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Register Rename Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Inputs
  for (rename <- rename_stages) {
    rename.io.kill := io.ifu.redirect_flush
    rename.io.brupdate := brupdate

    rename.io.debug_rob_empty := rob.io.empty

    rename.io.dec_fire := dec_fire
    rename.io.dec_uops := dec_uops

    rename.io.dis_fire := dis_fire
    rename.io.dis_ready := dis_ready

    rename.io.com_valids := rob.io.commit.valids
    rename.io.com_uops := rob.io.commit.uops
    rename.io.rollback := rob.io.rollback
  }

  // Outputs
  dis_uops := rename_stage.io.ren2_uops
  dis_valids := rename_stage.io.ren2_mask
  ren_stalls := rename_stage.io.ren_stalls

  /**
   * TODO This is a bit nasty, but it's currently necessary to
   * split the INT/FP rename pipelines into separate instantiations.
   * Won't have to do this anymore with a properly decoupled FP pipeline.
   */
  for (w <- 0 until coreWidth) {
    val i_uop     = rename_stage.io.ren2_uops(w)
    val f_uop     = fp_rename_stage.io.ren2_uops(w)
    val p_uop     = pred_rename_stage.io.ren2_uops(w)
    val imm_uop   = imm_rename_stage.io.ren2_uops(w)
    val f_stall   = fp_rename_stage.io.ren_stalls(w)
    val p_stall   = pred_rename_stage.io.ren_stalls(w)
    val imm_stall = imm_rename_stage.io.ren_stalls(w)

    // lrs1 can "pass through" to prs1. Used solely to index the csr file.
    dis_uops(w).prs1 := Mux(dis_uops(w).lrs1_rtype === RT_FLT, f_uop.prs1,
                        Mux(dis_uops(w).lrs1_rtype === RT_FIX, i_uop.prs1, dis_uops(w).lrs1.resize(maxPregSz)))
    dis_uops(w).prs2 := Mux(dis_uops(w).lrs2_rtype === RT_FLT, f_uop.prs2, i_uop.prs2)
    dis_uops(w).prs3 := f_uop.prs3
    dis_uops(w).ppred := p_uop.ppred
    dis_uops(w).pdst := Mux(dis_uops(w).dst_rtype  === RT_FLT, f_uop.pdst,
                        Mux(dis_uops(w).dst_rtype  === RT_FIX, i_uop.pdst,
                        Mux(dis_uops(w).is_sfb_br            , p_uop.pdst,
                                                               LFSR(maxPregSz) // Random set the PDST so the ALU banked IQ is balanced
                        )))
    dis_uops(w).imm_sel := imm_uop.imm_sel
    dis_uops(w).pimm    := imm_uop.pimm

    dis_uops(w).stale_pdst := Mux(dis_uops(w).dst_rtype === RT_FLT, f_uop.stale_pdst, i_uop.stale_pdst)

    dis_uops(w).prs1_busy := i_uop.prs1_busy && (dis_uops(w).lrs1_rtype === RT_FIX) ||
                             f_uop.prs1_busy && (dis_uops(w).lrs1_rtype === RT_FLT)
    dis_uops(w).prs2_busy := i_uop.prs2_busy && (dis_uops(w).lrs2_rtype === RT_FIX) ||
                             f_uop.prs2_busy && (dis_uops(w).lrs2_rtype === RT_FLT)
    dis_uops(w).prs3_busy := f_uop.prs3_busy && dis_uops(w).frs3_en
    dis_uops(w).ppred_busy := p_uop.ppred_busy && dis_uops(w).is_sfb_shadow

    ren_stalls(w) := rename_stage.io.ren_stalls(w) || f_stall || p_stall || imm_stall
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Dispatch Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  //-------------------------------------------------------------
  // Rename2/Dispatch pipeline logic

  val dis_prior_slot_valid = dis_valids.scanLeft(False) ((s,v) => s || v)
  val dis_prior_slot_unique = (dis_uops zip dis_valids).scanLeft(False) {case (s,(u,v)) => s || v && u.is_unique}
  val wait_for_empty_pipeline = (0 until coreWidth).map(w => (dis_uops(w).is_unique || !custom_csrs.enableOOO) &&
                                  (!rob.io.empty || !io.lsu.fencei_rdy || dis_prior_slot_valid(w)))
  // Only 1 branch-tag allocating instruction allowed to proceed per cycle;
  // reduces checkpointing complexity.
  val block_brtag = (dis_uops zip dis_valids).map{case (u,v) => v && u.allocate_brtag}.scanLeft(False)(_||_)
  val brtag_stall = (dis_uops.map(_.allocate_brtag) zip block_brtag) map {case (p,r) => p && r }
  val dis_hazards = (0 until coreWidth).map(w =>
                      dis_valids(w) &&
                      (  !rob.io.ready
                      || brtag_stall(w)
                      || ren_stalls(w)
                      || io.lsu.ldq_full(w) && dis_uops(w).uses_ldq
                      || io.lsu.stq_full(w) && dis_uops(w).uses_stq
                      || !dispatcher.io.ren_uops(w).ready
                      || wait_for_empty_pipeline(w)
                      || dis_prior_slot_unique(w)
                      || brupdate.b1.mispredict_mask =/= 0
                      || brupdate.b2.mispredict
                      || io.ifu.redirect_flush))

  io.lsu.fence_dmem := (dis_valids zip wait_for_empty_pipeline).map {case (v,w) => v && w} .reduce(_||_)

  val dis_stalls = dis_hazards.scanLeft(False) ((s,h) => s || h).takeRight(coreWidth)
  dis_fire := Vec(dis_valids zip dis_stalls map {case (v,s) => v && !s})
  dis_ready := !dis_stalls.last

  //-------------------------------------------------------------
  // LDQ/STQ Allocation Logic

  for (w <- 0 until coreWidth) {
    // Dispatching instructions request load/store queue entries when they can proceed.
    dis_uops(w).ldq_idx := io.lsu.dis_ldq_idx(w)
    dis_uops(w).stq_idx := io.lsu.dis_stq_idx(w)
  }

  //-------------------------------------------------------------
  // Rob Allocation Logic

  rob.io.enq_valids := dis_fire
  rob.io.enq_uops   := dis_uops
  rob.io.enq_partial_stall := dis_stalls.last // TODO come up with better ROB compacting scheme.
  rob.io.debug_tsc := debug_tsc_reg
  rob.io.csr_stall := csr.io.csr_stall
  rob.io.trace_stall := io.traceStall

  // Minor hack: ecall and breaks need to increment the FTQ deq ptr earlier than commit, since
  // they write their PC into the CSR the cycle before they commit.
  // Since these are also unique, increment the FTQ ptr when they are dispatched
  val dispatched_sys = FixedPipeline(
    dis_fire.reduce(_||_) && dis_uops.at(PriorityEncoder(dis_fire)).is_sys_pc2epc,
    dis_uops.at(PriorityEncoder(dis_valids)).ftq_idx, 1, holdPayload = false, resetValid = false)
  when (dispatched_sys.valid) {
    io.ifu.commit.valid   := True
    io.ifu.commit.payload := dispatched_sys.payload.resized
  }

  for (w <- 0 until coreWidth) {
    // note: this assumes uops haven't been shifted - there's a 1:1 match between PC's LSBs and "w" here
    // (thus the LSB of the rob_idx gives part of the PC)
    if (coreWidth == 1) {
      dis_uops(w).rob_idx := rob.io.rob_tail_idx
    } else {
      dis_uops(w).rob_idx := Cat(rob.io.rob_tail_idx >> log2Ceil(coreWidth),
                               U(w, log2Ceil(coreWidth) bits)).asUInt.resized
    }
  }

  //-------------------------------------------------------------
  // Dispatch to issue queues

  // Get uops from rename2
  for (w <- 0 until coreWidth) {
    dispatcher.io.ren_uops(w).valid   := dis_fire(w)
    dispatcher.io.ren_uops(w).payload := dis_uops(w)
  }

  // Send dispatched uops to correct issue queues
  // Backpressure through dispatcher if necessary
  for (i <- 0 until issueParams.size) {
    val dst = if (issueParams(i).iqType == IQ_FP) {
      fp_pipeline.io.dis_uops
    } else if (issueParams(i).iqType == IQ_MEM) {
      mem_iss_unit.io.dis_uops
    } else if (issueParams(i).iqType == IQ_ALU) {
      alu_iss_unit.io.dis_uops
    } else if (issueParams(i).iqType == IQ_UNQ) {
      unq_iss_unit.io.dis_uops
    } else {
      require(false); null
    }
    (dst zip dispatcher.io.dis_uops(i)).foreach { case (d, s) => d << s }
  }

  //-------------------------------------------------------------
  // Write immediates, branches into immediate file
  for (w <- 0 until coreWidth) {
    val dispatched = FixedPipeline(dis_fire(w), dis_uops(w), 1, holdPayload = false, resetValid = false)
    val uop = dispatched.payload

    immregfile.io.write_ports(w).valid        := dispatched.valid && !uop.imm_sel.isOneOf(IS_N, IS_SH)
    immregfile.io.write_ports(w).payload.addr := uop.pimm.resized
    immregfile.io.write_ports(w).payload.data := uop.imm_packed

    bregfile.io.write_ports(w).valid                 := dispatched.valid && uop.allocate_brtag
    bregfile.io.write_ports(w).payload.addr          := uop.br_tag.resized
    bregfile.io.write_ports(w).payload.data.ldq_idx  := uop.ldq_idx
    bregfile.io.write_ports(w).payload.data.stq_idx  := uop.stq_idx
    bregfile.io.write_ports(w).payload.data.rxq_idx  := uop.rxq_idx
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Issue Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  var wu_idx = 0
  var wb_idx = 0
  var bypass_idx = 0
  for (i <- 0 until lsuWidth) {
    int_wakeups(wu_idx) := io.lsu.iwakeups(i)
    val (raw, killed) = BranchPipeline.writebackTap(io.lsu.iresp(i), brupdate, RegNext(rob.io.flush.valid))
    connectResp(rob.io.wb_resps(wb_idx), killed)
    iregfile.io.write_ports(wb_idx).valid        := raw.valid
    iregfile.io.write_ports(wb_idx).payload.addr := raw.payload.uop.pdst
    iregfile.io.write_ports(wb_idx).payload.data := raw.payload.data

    int_bypasses(bypass_idx) := raw
    wu_idx += 1
    wb_idx += 1
    bypass_idx += 1
  }
  val ll_arb = new Arbiter(new ExeUnitResp(xLen),
    1 + // Mul
    1 + // F2I
    (if (unq_exe_unit.hasDiv) 1 else 0) +
    (if (unq_exe_unit.hasCSR) 1 else 0))

  var arb_idx = 0
  ll_arb.io.in(arb_idx).valid   := unq_exe_unit.io_mul_resp.get.valid
  ll_arb.io.in(arb_idx).payload := unq_exe_unit.io_mul_resp.get.payload
  arb_idx += 1
  ll_arb.io.in(arb_idx) << fp_pipeline.io.to_int
  arb_idx += 1
  if (unq_exe_unit.hasDiv) {
    ll_arb.io.in(arb_idx) << unq_exe_unit.io_div_resp.get
    arb_idx += 1
  }
  if (unq_exe_unit.hasCSR) {
    ll_arb.io.in(arb_idx).valid       := csr_resp.valid
    ll_arb.io.in(arb_idx).payload.uop := csr_resp.payload.uop
    ll_arb.io.in(arb_idx).payload.data := csr.io.rw.rdata
    ll_arb.io.in(arb_idx).payload.predicated     := False
    ll_arb.io.in(arb_idx).payload.fflags.valid   := False
    ll_arb.io.in(arb_idx).payload.fflags.payload := 0
    assert(!(ll_arb.io.in(arb_idx).valid && !ll_arb.io.in(arb_idx).ready))
    arb_idx += 1
  }
  ll_arb.io.out.ready := True
  int_wakeups(wu_idx).valid := ll_arb.io.out.valid && ll_arb.io.out.payload.uop.dst_rtype === RT_FIX
  int_wakeups(wu_idx).payload.uop  := ll_arb.io.out.payload.uop
  int_wakeups(wu_idx).payload.speculative_mask := 0
  int_wakeups(wu_idx).payload.rebusy := False
  int_wakeups(wu_idx).payload.bypassable := False
  wu_idx += 1

  val ll_wb = FixedPipeline(
    ll_arb.io.out.valid && !IsKilledByBranch(brupdate, RegNext(rob.io.flush.valid), ll_arb.io.out.payload),
    ll_arb.io.out.payload, 1, holdPayload = false, resetValid = false)
  connectResp(rob.io.wb_resps(wb_idx), ll_wb)

  iregfile.io.write_ports(wb_idx).valid        := ll_arb.io.out.valid && ll_arb.io.out.payload.uop.dst_rtype === RT_FIX
  iregfile.io.write_ports(wb_idx).payload.addr := ll_arb.io.out.payload.uop.pdst
  iregfile.io.write_ports(wb_idx).payload.data := ll_arb.io.out.payload.data
  wb_idx += 1

  // loop through each issue-port (exe_units are statically connected to an issue-port)
  for (i <- 0 until aluWidth) {
    val unit = alu_exe_units(i)
    val fast_wakeup = unit.io_fast_wakeup

    int_bypasses(bypass_idx).valid   := unit.io_alu_resp.valid && unit.io_alu_resp.payload.uop.dst_rtype === RT_FIX
    int_bypasses(bypass_idx).payload := unit.io_alu_resp.payload
    bypass_idx += 1

    int_wakeups(wu_idx) := fast_wakeup
    wu_idx += 1

    val alu_wb = FixedPipeline(
      unit.io_alu_resp.valid && !IsKilledByBranch(brupdate, RegNext(rob.io.flush.valid), unit.io_alu_resp.payload),
      unit.io_alu_resp.payload, 1, holdPayload = false, resetValid = false)
    connectResp(rob.io.wb_resps(wb_idx), alu_wb)

    iregfile.io.write_ports(wb_idx).valid        := unit.io_alu_resp.valid && unit.io_alu_resp.payload.uop.dst_rtype === RT_FIX
    iregfile.io.write_ports(wb_idx).payload.addr := unit.io_alu_resp.payload.uop.pdst
    iregfile.io.write_ports(wb_idx).payload.data := unit.io_alu_resp.payload.data
    wb_idx += 1

    pred_wakeups(i) := unit.io_fast_pred_wakeup
  }
  require (wu_idx == numIntWakeups)
  require (wb_idx == numIrfWritePorts)

  // arb stage guarantees 1 preg writer per cycle
  val pregfile_write_valids = alu_exe_units.map(u => u.io_alu_resp.valid && u.io_alu_resp.payload.uop.is_sfb_br)
  assert(PopCount(pregfile_write_valids) <= 1)
  pregfile.io.write_ports(0).valid        := pregfile_write_valids.reduce(_||_)
  pregfile.io.write_ports(0).payload.addr := Mux1H(pregfile_write_valids, alu_exe_units.map(_.io_alu_resp.payload.uop.pdst))
  // Chisel connects the xLen-wide ALU data to the Bool write port (bit 0)
  pregfile.io.write_ports(0).payload.data := Mux1H(pregfile_write_valids, alu_exe_units.map(_.io_alu_resp.payload.data)).lsb

  // Connect the predicate wakeup port
  alu_iss_unit.io.pred_wakeup_port.valid   := pred_wakeup.valid
  alu_iss_unit.io.pred_wakeup_port.payload := pred_wakeup.payload.uop.pdst.resized
  mem_iss_unit.io.pred_wakeup_port.valid   := False
  mem_iss_unit.io.pred_wakeup_port.payload.assignDontCare()
  unq_iss_unit.io.pred_wakeup_port.valid   := False
  unq_iss_unit.io.pred_wakeup_port.payload.assignDontCare()

  // ----------------------------------------------------------------
  // Connect the wakeup ports to the busy tables in the rename stages

  for ((renport, intport) <- rename_stage.io.wakeups zip int_wakeups) {
    renport := intport
  }
  if (usingFPU) {
    fp_rename_stage.io.wakeups := fp_pipeline.io.wakeups
  }

  pred_rename_stage.io.wakeups(0) := pred_wakeup
  (imm_rename_stage.io.wakeups zip all_exe_units).foreach { case (p, u) => p := u.io_rrd_immrf_wakeup }

  rename_stage.io.child_rebusys := alu_exe_units.map(_.io_child_rebusy).reduce(_|_)
  imm_rename_stage.io.child_rebusys := 0
  pred_rename_stage.io.child_rebusys := 0
  fp_rename_stage.io.child_rebusys := 0

  (mem_iss_unit.io.fu_types zip mem_exe_units).foreach { case (f, u) => f := u.io_ready_fu_types }
  (alu_iss_unit.io.fu_types zip alu_exe_units).foreach { case (f, u) => f := u.io_ready_fu_types }
  (unq_iss_unit.io.fu_types zip unq_exe_units).foreach { case (f, u) => f := u.io_ready_fu_types }

  for (iss_unit <- Seq(mem_iss_unit, alu_iss_unit, unq_iss_unit)) {
    iss_unit.io.tsc_reg  := debug_tsc_reg
    iss_unit.io.brupdate := brupdate
    iss_unit.io.flush_pipeline := RegNext(rob.io.flush.valid)

    // Rebusy children of misspeculated wakeups
    iss_unit.io.child_rebusys := alu_exe_units.map(_.io_child_rebusy).reduce(_|_)

    (iss_unit.io.wakeup_ports zip int_wakeups).foreach { case (p, w) => p := w }

    iss_unit.io.rob_pnr_idx := rob.io.rob_pnr_idx
    iss_unit.io.rob_head    := rob.io.rob_head_idx
  }

  mem_iss_unit.io.squash_grant := (
    mem_exe_units.map(_.io_squash_iss).reduce(_||_) ||
    alu_exe_units.map(_.io_squash_iss).reduce(_||_) ||
    io.lsu.iwakeups.map(_.payload.rebusy).reduce(_||_)
  )
  unq_iss_unit.io.squash_grant := (
    unq_exe_units.map(_.io_squash_iss).reduce(_||_) ||
    alu_exe_units.map(_.io_squash_iss).reduce(_||_) ||
    io.lsu.iwakeups.map(_.payload.rebusy).reduce(_||_)
  )
  alu_iss_unit.io.squash_grant := (
    alu_exe_units.map(_.io_squash_iss).reduce(_||_) ||
    io.lsu.iwakeups.map(_.payload.rebusy).reduce(_||_)
  )

  mem_iss_unit.io.iss_uops zip mem_exe_units foreach { case (i, u) => u.io_iss_uop := i }
  alu_iss_unit.io.iss_uops zip alu_exe_units foreach { case (i, u) => u.io_iss_uop := i }
  unq_iss_unit.io.iss_uops zip unq_exe_units foreach { case (i, u) => u.io_iss_uop := i }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Register Read Arbitrate Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------
  arb_idx = 0
  for ((unit, w) <- all_exe_units.zipWithIndex) {
    for (i <- 0 until unit.nReaders) {
      connectReadReq(iregfile.io.arb_read_reqs(arb_idx), unit.io_arb_irf_reqs(i))
      arb_idx += 1
    }
    connectReadReq(immregfile.io.arb_read_reqs(w), unit.io_arb_immrf_req)
    unit.io_arb_rebusys := io.lsu.iwakeups
  }
  require(arb_idx == numIrfLogicalReadPorts)
  for ((unit, w) <- (alu_exe_units).zipWithIndex) {
    connectReadReq(pregfile.io.arb_read_reqs(w), unit.io_arb_prf_req)
    connectReadReq(bregfile.io.arb_read_reqs(w), unit.io_arb_brf_req)
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Register Read Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Register Read <- Issue (rrd <- iss)
  var rd_idx = 0
  for ((unit, w) <- all_exe_units.zipWithIndex) {
    for (i <- 0 until unit.nReaders) {
      unit.io_rrd_irf_resps(i) := iregfile.io.rrd_read_resps(rd_idx)
      rd_idx += 1
    }
    unit.io_rrd_immrf_resp := immregfile.io.rrd_read_resps(w).resized
    unit.io_rrd_irf_bypasses := int_bypasses
  }
  require (rd_idx == numIrfLogicalReadPorts)
  for ((unit, w) <- alu_exe_units.zipWithIndex) {
    unit.io_rrd_prf_resp := pregfile.io.rrd_read_resps(w)
    unit.io_rrd_brf_resp := bregfile.io.rrd_read_resps(w)
  }

  //-------------------------------------------------------------
  // Privileged Co-processor 0 Register File
  // Note: Normally this would be bad in that I'm writing state before
  // committing, so to get this to work I stall the entire pipeline for
  // CSR instructions so I never speculate these instructions.

  io.lsu.sfence := unq_exe_unit.io_sfence.get
  io.ifu.sfence := unq_exe_unit.io_sfence.get

  // for critical path reasons, we aren't zero'ing this out if resp is not valid
  csr.io.rw.addr        := csr_resp.payload.addr
  csr.io.rw.cmd         := CSR.maskCmd(csr_resp.valid, csr_resp.payload.uop.csr_cmd)
  csr.io.rw.wdata       := csr_resp.payload.data

  rob.io.csr_replay.valid := csr_resp.valid && csr.io.rw_stall
  rob.io.csr_replay.payload.uop := csr_resp.payload.uop
  rob.io.csr_replay.payload.cause := MINI_EXCEPTION_CSR_REPLAY.litValue
  rob.io.csr_replay.payload.badvaddr.assignDontCare()

  // Extra I/O
  // Delay retire/exception 1 cycle
  // ECALL/EBREAK take their trap in CSRFile after the ROB commits them.
  // They must not increment instret (Priv. 1.12, 3.3.1).
  val countedRetire = (0 until coreWidth).map { w =>
    rob.io.commit.arch_valids(w) && !rob.io.commit.uops(w).is_sys_pc2epc
  }
  val committed_exception = FixedPipeline(rob.io.com_xcpt.valid, rob.io.com_xcpt.payload,
    1, holdPayload = false, resetValid = false)
  csr.io.retire    := FixedPipeline.delay(PopCount(countedRetire), 1).resized
  csr.io.exception := committed_exception.valid
  // csr.io.pc used for setting EPC during exception or CSR.io.trace.

  csr.io.pc        := (AlignPCToBoundary(io.ifu.com_pc, icBlockBytes)
                     + committed_exception.payload.pc_lob.resize(vaddrBitsExtended)
                     - Mux(committed_exception.payload.edge_inst, U(2, vaddrBitsExtended bits), U(0, vaddrBitsExtended bits)))
  // Cause not valid for for CALL or BREAKPOINTs (CSRFile will override it).
  csr.io.cause     := committed_exception.payload.cause

  val tval_valid = csr.io.exception &&
    csr.io.cause.isOneOf(Seq(
      //Causes.illegal_instruction.U, we currently only write 0x0 for illegal instructions
      Causes.breakpoint,
      Causes.misaligned_load,
      Causes.misaligned_store,
      Causes.load_access,
      Causes.store_access,
      Causes.fetch_access,
      Causes.load_page_fault,
      Causes.store_page_fault,
      Causes.fetch_page_fault).map(c => U(c, xLen bits)))

  // TODO move this function to some central location (since this is used elsewhere).
  def encodeVirtualAddress(a0: UInt, ea: UInt): UInt =
    if (vaddrBitsExtended == vaddrBits) {
      ea
    } else {
      // Efficient means to compress 64-bit VA into vaddrBits+1 bits.
      // (VA is bad if VA(vaddrBits) != VA(vaddrBits-1)).
      val a = a0.asSInt >> vaddrBits
      val msb = Mux(a === 0 || a === -1, ea(vaddrBits), !ea(vaddrBits-1))
      Cat(msb, ea(vaddrBits-1 downto 0)).asUInt
    }

  csr.io.tval := Mux(tval_valid,
    FixedPipeline.delay(encodeVirtualAddress(rob.io.com_xcpt.payload.badvaddr, rob.io.com_xcpt.payload.badvaddr), 1), U(0, vaddrBitsExtended bits))

  // reading requires serializing the entire pipeline
  csr.io.fcsr_flags.valid   := rob.io.commit.fflags.valid
  csr.io.fcsr_flags.payload := rob.io.commit.fflags.payload.asBits
  if (csr.io.set_fs_dirty != null) {
    // Upstream only uses fflags.valid. FP loads do not produce fflags, but
    // they change an FP register and must mark FS Dirty (Priv. 1.12, 3.1.6.6).
    val fpLoadCommitted = (0 until coreWidth).map { w =>
      rob.io.commit.arch_valids(w) && rob.io.commit.uops(w).uses_ldq &&
        rob.io.commit.uops(w).dst_rtype === RT_FLT
    }.reduce(_ || _)
    csr.io.set_fs_dirty := rob.io.commit.fflags.valid || fpLoadCommitted
  }

  all_exe_units.foreach(i => i.io_fcsr_rm := csr.io.fcsr_rm)
  io.fcsr_rm := csr.io.fcsr_rm

  fp_pipeline.io.fcsr_rm := csr.io.fcsr_rm

  csr.io.hartid := io.hartid
  csr.io.interrupts := io.interrupts

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Load/Store Unit ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // enqueue basic load/store info in Decode
  for (w <- 0 until coreWidth) {
    io.lsu.dis_uops(w).valid   := dis_fire(w)
    io.lsu.dis_uops(w).payload := dis_uops(w)
  }

  // tell LSU about committing loads and stores to clear entries
  io.lsu.commit                  := rob.io.commit

  // tell LSU that it should fire a load that waits for the rob to clear
  io.lsu.commit_load_at_rob_head := rob.io.com_load_is_at_rob_head

  //com_xcpt.valid comes too early, will fight against a branch that resolves same cycle as an exception
  io.lsu.exception := RegNext(rob.io.flush.valid)

  // Handle Branch Mispeculations
  io.lsu.brupdate := brupdate
  io.lsu.rob_head_idx := rob.io.rob_head_idx
  io.lsu.rob_pnr_idx  := rob.io.rob_pnr_idx

  io.lsu.tsc_reg := debug_tsc_reg

  // Connect IFPU
  fp_pipeline.io.from_int << unq_exe_unit.io_ifpu_resp.get

  // Connect FLDs
  (fp_pipeline.io.ll_wports zip io.lsu.fresp).foreach { case (d, s) => connectResp(d, s) }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Commit Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  var cnt = numIrfWritePorts
  for (wb <- fp_pipeline.io.wb) {
    rob.io.wb_resps(cnt) := wb
    rob.io.wb_resps(cnt).payload.data := ieee(wb.payload.data).resized
    cnt += 1
  }

  require (cnt == rob.numWakeupPorts)

  // branch resolution
  rob.io.brupdate := brupdate

  io.lsu.status := csr.io.status
  io.lsu.bp     := csr.io.bp
  io.lsu.mcontext := csr.io.mcontext
  io.lsu.scontext := csr.io.scontext

  all_exe_units.foreach(u => u.io_status := csr.io.status)
  fp_pipeline.io.status := csr.io.status

  // LSU <> ROB
  rob.io.lsu_clr_bsy    := io.lsu.clr_bsy
  rob.io.lsu_clr_unsafe := io.lsu.clr_unsafe
  rob.io.lxcpt          << io.lsu.lxcpt

  assert (!(csr.io.singleStep), "[core] single-step is unsupported.")

  //-------------------------------------------------------------
  // **** Flush Pipeline ****
  //-------------------------------------------------------------
  // flush on exceptions, miniexeptions, and after some special instructions

  fp_pipeline.io.flush_pipeline := RegNext(rob.io.flush.valid)

  for (eu <- all_exe_units)
    eu.io_kill := RegNext(rob.io.flush.valid)

  assert (!(rob.io.com_xcpt.valid && !rob.io.flush.valid),
    "[core] exception occurred, but pipeline flush signal not set!")

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Outputs to the External World ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // detect pipeline freezes and throw error
  val idle_cycles = WideCounter(32)
  when (rob.io.commit.valids.asUInt.orR ||
        csr.io.csr_stall ||
        ClockDomain.current.isResetActive) {
    idle_cycles := U(0)
  }
  // rocket PlusArg("boom_timeout", 13): fixed at its default here
  assert (!(idle_cycles.value(13)), "Pipeline has hung.")

  fp_pipeline.io.debug_tsc_reg := debug_tsc_reg

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Page Table Walker

  io.ptw.ptbr       := csr.io.ptbr
  io.ptw.status     := csr.io.status
  io.ptw.pmp        := csr.io.pmp
  io.ptw.sfence     := io.ifu.sfence

  //-------------------------------------------------------------
  // Commit trace (rocket TraceBundle)

  io.trace.assignDontCare()
  io.trace.time := csr.io.time
  io.trace.insns.foreach(t => t.valid := False)
  io.trace.rob_empty := rob.io.empty

  if (trace || boomParams.enableRVFI) {
    for (w <- 0 until coreWidth) {
      // Delay the trace so we have a cycle to pull PCs out of the FTQ
      io.trace.insns(w).valid      := RegNext(rob.io.commit.arch_valids(w))

      // Recalculate the PC
      io.ifu.debug_ftq_idx(w) := rob.io.commit.uops(w).ftq_idx
      val iaddr = (AlignPCToBoundary(io.ifu.debug_fetch_pc(w), icBlockBytes)
                   + RegNext(rob.io.commit.uops(w).pc_lob).resize(vaddrBitsExtended)
                   - Mux(RegNext(rob.io.commit.uops(w).edge_inst), U(2, vaddrBitsExtended bits), U(0, vaddrBitsExtended bits)))(vaddrBits-1 downto 0)
      io.trace.insns(w).iaddr      := Sext(iaddr, xLen).resized

      def getInst(uop: MicroOp, inst: UInt): UInt = {
        Mux(uop.is_rvc, Cat(U(0, 16 bits), inst(15 downto 0)).asUInt, inst)
      }

      def getWdata(uop: MicroOp, wdata: UInt): UInt = {
        Mux((uop.dst_rtype === RT_FIX && uop.ldst =/= 0) || (uop.dst_rtype === RT_FLT), wdata, U(0, xLen bits))
      }

      // use debug_insts instead of uop.debug_inst to use the rob's debug_inst_mem
      // note: rob.debug_insts comes 1 cycle later
      io.trace.insns(w).insn       := getInst(RegNext(rob.io.commit.uops(w)), rob.io.commit.debug_insts(w))
      if (io.trace.insns(w).wdata != null)
        io.trace.insns(w).wdata := RegNext(getWdata(rob.io.commit.uops(w), rob.io.commit.debug_wdata(w))).resized

      // These csr signals do not exactly match up with the ROB commit signals.
      io.trace.insns(w).priv       := RegNext(Cat(RegNext(RegNext(csr.io.status.debug)), csr.io.status.prv).asUInt)
      // Can determine if it is an interrupt or not based on the MSB of the cause
      io.trace.insns(w).exception  := RegNext(rob.io.com_xcpt.valid && !rob.io.com_xcpt.payload.cause(xLen - 1)) && Bool(w == 0)
      io.trace.insns(w).interrupt  := RegNext(rob.io.com_xcpt.valid && rob.io.com_xcpt.payload.cause(xLen - 1)) && Bool(w == 0)
      io.trace.insns(w).cause      := RegNext(rob.io.com_xcpt.payload.cause)
      io.trace.insns(w).tval       := RegNext(csr.io.tval).resized
    }
  } else {
    io.ifu.debug_ftq_idx.assignDontCare()
  }

  //-------------------------------------------------------------
  // RVFI (Spinal port)

  if (boomParams.enableRVFI) {
    val rvfi = new RvfiTracker(
      nRs = all_exe_units.size, nBr = aluWidth, nAddr = lsuWidth, nStore = memWidth + 1, nLoad = 2 * lsuWidth)
    (rvfi.io.rs zip all_exe_units).foreach { case (p, u) => p := u.io_rvfi_rs }
    (rvfi.io.br zip alu_exe_units).foreach { case (p, u) => p := u.io_rvfi_br }
    rvfi.io.addr  := io.lsu.agen
    rvfi.io.store := io.lsu.dgen
    for (w <- 0 until lsuWidth) {
      rvfi.io.load(2 * w)     := io.lsu.iresp(w)
      rvfi.io.load(2 * w + 1) := io.lsu.fresp(w)
    }
    rvfi.io.commit := rob.io.commit
    rvfi.io.xcpt := rob.io.com_xcpt.valid
    rvfi.io.xcpt_uop := rob.com_xcpt_uop.pull()
    rvfi.io.xcpt_is_interrupt := rob.io.com_xcpt.payload.cause(xLen - 1)
    rvfi.io.xcpt_cause := rob.io.com_xcpt.payload.cause
    rvfi.io.flush_redirect.valid := RegNext(rob.io.flush.valid) && FlushTypes.useCsrEvec(RegNext(rob.io.flush.payload.flush_typ))
    rvfi.io.flush_redirect.payload := io.ifu.redirect_pc
    rvfi.io.prv := csr.io.status.prv.resized
    io.rvfi := rvfi.io.rvfi
  }
}
