//******************************************************************************
// Copyright (c) 2013 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Execution Units
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// The issue window schedules micro-ops onto a specific execution pipeline
// A given execution pipeline may contain multiple functional units; one or more
// read ports, and one or more writeports.
//
// Ports that only exist for some configurations are created inside `if`s and
// are named explicitly with setName (reflection does not see into Options).

package boom.exu

import scala.collection.mutable.ArrayBuffer

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline.{Builder, Link, Node, Payload, StageLink}
import rocketchip.rocket.{SFenceReq, CSR}
import rocketchip.rocket.constants.MemoryOpConstants
import rocketchip.config.Parameters
import rocketchip.tile
import rocketchip.util._

import boom.common._
import boom.ifu.FTQInfo
import boom.util._

class Wakeup(implicit p: Parameters) extends BoomBundle
  with HasBoomUOP
{
  val bypassable = Bool()
  val speculative_mask = UInt(aluWidth bits)
  val rebusy = Bool()
}

class ExeUnitResp(val dataWidth: Int)(implicit p: Parameters) extends BoomBundle
  with HasBoomUOP
{
  val data = UInt(dataWidth bits)
  val predicated = Bool() // Was this predicated off?
  val fflags = Flow(UInt(tile.FPConstants.FLAGS_SZ bits))
}

class MemGen(implicit p: Parameters) extends BoomBundle
  with HasBoomUOP
{
  val data = UInt(xLen bits)
}

class CSRResp(implicit p: Parameters) extends BoomBundle
  with HasBoomUOP
{
  val data = UInt(xLen bits)
  val addr = UInt(CSR.ADDRSZ bits)
}

abstract class ExecutionUnit(name: String)(implicit p: Parameters) extends BoomModule
{
  val fu_types = ArrayBuffer[(Int, Bool, String)]()
  def get_all_fu_types(): Vec[Bool] = {
    val r = Vec.fill(FC_SZ)(Bool())
    r.foreach(_ := False)
    fu_types.foreach { case (code, _, _) => r(code) := True }
    r
  }
  def get_ready_fu_types(): Vec[Bool] = {
    val r = Vec.fill(FC_SZ)(Bool())
    r.foreach(_ := False)
    fu_types.foreach { case (code, ready, _) => when (ready) { r(code) := True } }
    r
  }

  val io_kill = in Bool()
  val io_brupdate = in(new BrUpdateInfo)
  val io_status = in(new rocketchip.rocket.MStatus)
  val io_ready_fu_types = out(Vec.fill(FC_SZ)(Bool()))

  val io_fcsr_rm = in UInt(tile.FPConstants.RM_SZ bits)
  override def toString = {
    BoomCoreStringPrefix(s"===${name}ExeUnit") +
    fu_types.map { case (_, _, s) => BoomCoreStringPrefix(s" - ${s}") }.reduce(_+_)
  }

  val io_iss_uop = slave(Flow(new MicroOp))

  val UOP = Payload(new MicroOp)
  val iss, issKilled, arbUp, arbDown, rrdUp, rrdDown, exe = Node()
  iss.valid := io_iss_uop.valid
  iss(UOP) := io_iss_uop.payload
  val issCtrl = BranchPipeline.uopBoundary(iss, issKilled, UOP, io_brupdate, io_kill)
  val arbLink = new HoldStageLink(issKilled, arbUp, resetValid = false)
  val arbCtrl = BranchPipeline.uopBoundary(arbUp, arbDown, UOP, io_brupdate, io_kill)
  val rrdLink = StageLink(arbDown, rrdUp)
  val rrdCtrl = BranchPipeline.uopBoundary(rrdUp, rrdDown, UOP, io_brupdate, io_kill)
  val exeLink = StageLink(rrdDown, exe)
  val pipeLinks = ArrayBuffer[Link](issCtrl, arbLink, arbCtrl, rrdLink, rrdCtrl, exeLink)

  // Traits add operand payloads and replay controls after this base constructor.
  afterElaboration {
    Builder(pipeLinks)
    Seq(rrdUp, exe).foreach(_.valid.removeInitAssignments())
  }

  // Register-output views used by the functional units and their existing taps.
  val arb_uop = arbUp.toFlow(_(UOP))
  val rrd_uop = rrdUp.toFlow(_(UOP))
  val exe_uop = exe.toFlow(_(UOP))

  /** Replay holds ARB, rewrites its mask/hints and inserts a bubble in RRD. */
  def replayArb(cond: Bool, rebusied: Bool, clearP3: Boolean): Unit = {
    arbCtrl.haltWhen(cond || rebusied)
    arbLink.drainWhen(IsKilledByBranch(io_brupdate, io_kill, arbUp(UOP)) || rebusied)
    val held = arbLink.hold(UOP)
    held := UpdateBrMask(io_brupdate, arbUp(UOP))
    held.iw_p1_bypass_hint := False
    held.iw_p2_bypass_hint := False
    if (clearP3) held.iw_p3_bypass_hint := False
  }
}

trait HasIrfReadPorts extends ExecutionUnit {
  def nReaders: Int

  val io_arb_irf_reqs = Vec.fill(nReaders)(master(Stream(UInt(maxPregSz bits))))
  val io_arb_rebusys  = in(Vec.fill(lsuWidth)(Flow(new Wakeup)))
  val io_rrd_irf_resps    = in(Vec.fill(nReaders)(UInt(xLen bits)))
  val io_rrd_irf_bypasses = in(Vec.fill(coreWidth + lsuWidth)(Flow(new ExeUnitResp(xLen))))

  def rrd_bypass_hit(prs: UInt, rdata: UInt): (Bool, UInt) = {
    val hits = io_rrd_irf_bypasses map { b => b.valid && prs === b.payload.uop.pdst }
    (hits.reduce(_||_), Mux(hits.reduce(_||_), Mux1H(hits, io_rrd_irf_bypasses.map(_.payload.data)), rdata))
  }

  def rebusied(prs: UInt): Bool = {
    io_arb_rebusys.map { r => r.valid && r.payload.rebusy && r.payload.uop.pdst === prs }.reduce(_||_)
  }

  io_arb_irf_reqs(0).valid   := arb_uop.valid && arb_uop.payload.lrs1_rtype === RT_FIX && !arb_uop.payload.iw_p1_bypass_hint
  io_arb_irf_reqs(0).payload := arb_uop.payload.prs1

  if (nReaders == 2) {
    io_arb_irf_reqs(1).valid   := arb_uop.valid && arb_uop.payload.lrs2_rtype === RT_FIX && !arb_uop.payload.iw_p2_bypass_hint
    io_arb_irf_reqs(1).payload := arb_uop.payload.prs2
  }
  val arb_rebusied_prs1 = arb_uop.payload.lrs1_rtype === RT_FIX && rebusied(arb_uop.payload.prs1)
  val arb_rebusied_prs2 = arb_uop.payload.lrs2_rtype === RT_FIX && rebusied(arb_uop.payload.prs2) && Bool(nReaders == 2)
  val arb_rebusied      = arb_rebusied_prs1 || arb_rebusied_prs2

  val RS1, RS2 = Payload(UInt(xLen bits))
  val exe_rs1_data = exe(RS1)
  val exe_rs2_data = exe(RS2)
  val (rs1_hit, rs1_data) = rrd_bypass_hit(rrd_uop.payload.prs1, io_rrd_irf_resps(0))
  assert(!(rrd_uop.valid && rrd_uop.payload.lrs1_rtype === RT_FIX && rrd_uop.payload.iw_p1_bypass_hint && !rs1_hit))
  rrdDown(RS1) := Mux(rrd_uop.payload.lrs1_rtype === RT_ZERO, U(0, xLen bits), rs1_data)

  if (nReaders == 2) {
    val (rs2_hit, rs2_data) = rrd_bypass_hit(rrd_uop.payload.prs2, io_rrd_irf_resps(1))
    assert(!(rrd_uop.valid && rrd_uop.payload.lrs2_rtype === RT_FIX && rrd_uop.payload.iw_p2_bypass_hint && !rs2_hit))
    rrdDown(RS2) := Mux(rrd_uop.payload.lrs2_rtype === RT_ZERO, U(0, xLen bits), rs2_data)
  } else {
    rrdDown(RS2).assignDontCare()
  }

  // Spinal port: operand values for the RVFI trace (see rvfi.scala).
  val io_rvfi_rs = if (boomParams.enableRVFI) {
    val r = master(Flow(new RvfiRsWrite)).setName("io_rvfi_rs")
    val u = exe_uop.payload
    def isInt(rtype: UInt) = rtype === RT_FIX || rtype === RT_ZERO
    // A store-data (DGEN) issue reads rs2 through the rs1 read port.
    val is_dgen = u.fu_code(FC_DGEN) && !u.fu_code(FC_AGEN)
    r.valid := exe_uop.valid
    r.payload.rob_idx := u.rob_idx
    r.payload.wr_rs1  := isInt(u.lrs1_rtype) && !is_dgen
    r.payload.rs1     := exe_rs1_data
    r.payload.wr_rs2  := Mux(is_dgen, isInt(u.lrs1_rtype), Bool(nReaders == 2) && isInt(u.lrs2_rtype))
    r.payload.rs2     := Mux(is_dgen, exe_rs1_data, exe_rs2_data)
    r
  } else null
}

trait HasImmrfReadPort extends ExecutionUnit {
  val io_arb_immrf_req    = master(Stream(UInt(immPregSz bits)))
  assert(io_arb_immrf_req.ready)
  val io_rrd_immrf_resp   = in UInt(xLen bits)
  val io_rrd_immrf_wakeup = master(Flow(new Wakeup))

  io_arb_immrf_req.valid := (arb_uop.valid &&
    !arb_uop.payload.imm_sel.isOneOf(IS_N, IS_SH)
  )
  io_arb_immrf_req.payload := arb_uop.payload.pimm

  io_rrd_immrf_wakeup.valid := (rrd_uop.valid &&
    !rrd_uop.payload.imm_sel.isOneOf(IS_N, IS_SH)
  )
  io_rrd_immrf_wakeup.payload.speculative_mask := 0
  io_rrd_immrf_wakeup.payload.rebusy := False
  io_rrd_immrf_wakeup.payload.bypassable := False
  io_rrd_immrf_wakeup.payload.uop := rrd_uop.payload

  val IMM = Payload(UInt(xLen bits))
  val exe_imm_data = exe(IMM)
  rrdDown(IMM) := Mux(rrd_uop.payload.imm_sel === IS_SH,
    Sext(rrd_uop.payload.pimm, xLen),
    Sext(ImmGen(io_rrd_immrf_resp, rrd_uop.payload.imm_sel), xLen)
  )
}

trait HasPrfReadPort extends ExecutionUnit {
  val io_arb_prf_req    = master(Stream(UInt(log2Ceil(ftqSz) bits)))
  assert(io_arb_prf_req.ready)
  val io_rrd_prf_resp   = in Bool()

  io_arb_prf_req.valid   := arb_uop.valid
  io_arb_prf_req.payload := arb_uop.payload.ppred

  val PRED = Payload(Bool())
  val exe_pred_data = exe(PRED)
  rrdDown(PRED) := io_rrd_prf_resp
}

trait HasBrfReadPort extends ExecutionUnit {
  val io_arb_brf_req    = master(Stream(UInt(brTagSz bits)))
  assert(io_arb_brf_req.ready)
  val io_rrd_brf_resp   = in(new BrInfoBundle)

  io_arb_brf_req.valid   := (arb_uop.valid && arb_uop.payload.is_br)
  io_arb_brf_req.payload := arb_uop.payload.br_tag

  rrdCtrl.bypass(UOP).ldq_idx   := io_rrd_brf_resp.ldq_idx
  rrdCtrl.bypass(UOP).stq_idx   := io_rrd_brf_resp.stq_idx
  rrdCtrl.bypass(UOP).rxq_idx   := io_rrd_brf_resp.rxq_idx
}

trait HasFrfReadPorts extends ExecutionUnit {
  val io_arb_frf_reqs  = Vec.fill(3)(master(Stream(UInt(maxPregSz bits))))
  val io_rrd_frf_resps = in(Vec.fill(3)(UInt(xLen+1 bits)))
  val io_rrd_frf_bypasses = in(Vec.fill(fpWidth)(Flow(new ExeUnitResp(fLen+1))))

  def rrd_bypass_hit(prs: UInt, rdata: UInt): (Bool, UInt) = {
    val hits = io_rrd_frf_bypasses map { b => b.valid && prs === b.payload.uop.pdst }
    (hits.reduce(_||_), Mux(hits.reduce(_||_), Mux1H(hits, io_rrd_frf_bypasses.map(_.payload.data)), rdata))
  }

  io_arb_frf_reqs(0).valid   := arb_uop.valid && arb_uop.payload.lrs1_rtype === RT_FLT && !arb_uop.payload.iw_p1_bypass_hint
  io_arb_frf_reqs(0).payload := arb_uop.payload.prs1
  io_arb_frf_reqs(1).valid   := arb_uop.valid && arb_uop.payload.lrs2_rtype === RT_FLT && !arb_uop.payload.iw_p2_bypass_hint
  io_arb_frf_reqs(1).payload := arb_uop.payload.prs2
  io_arb_frf_reqs(2).valid   := arb_uop.valid && arb_uop.payload.frs3_en && !arb_uop.payload.iw_p3_bypass_hint
  io_arb_frf_reqs(2).payload := arb_uop.payload.prs3

  val RS1, RS2, RS3 = Payload(UInt(fLen+1 bits))
  val exe_rs1_data = exe(RS1)
  val exe_rs2_data = exe(RS2)
  val exe_rs3_data = exe(RS3)

  val (rs1_hit, rs1_data) = rrd_bypass_hit(rrd_uop.payload.prs1, io_rrd_frf_resps(0))
  assert(!(rrd_uop.valid && rrd_uop.payload.lrs1_rtype === RT_FLT && rrd_uop.payload.iw_p1_bypass_hint && !rs1_hit))
  rrdDown(RS1) := rs1_data

  val (rs2_hit, rs2_data) = rrd_bypass_hit(rrd_uop.payload.prs2, io_rrd_frf_resps(1))
  assert(!(rrd_uop.valid && rrd_uop.payload.lrs2_rtype === RT_FLT && rrd_uop.payload.iw_p2_bypass_hint && !rs2_hit))
  rrdDown(RS2) := rs2_data

  val (rs3_hit, rs3_data) = rrd_bypass_hit(rrd_uop.payload.prs3, io_rrd_frf_resps(2))
  assert(!(rrd_uop.valid && rrd_uop.payload.iw_p3_bypass_hint && !rs3_hit))
  rrdDown(RS3) := rs3_data
}

trait HasFtqReadPort extends ExecutionUnit {
  val io_arb_ftq_reqs  = Vec.fill(2)(master(Stream(UInt(log2Ceil(ftqSz) bits))))
  val io_rrd_ftq_resps = in(Vec.fill(2)(new FTQInfo))

  // Only allow one SFB branch through multiple pipes, to avoid unnecessary predicate wakeup logic
  io_arb_ftq_reqs(0).valid   := arb_uop.valid && (arb_uop.payload.op1_sel === OP1_PC || arb_uop.payload.is_sfb_br)
  io_arb_ftq_reqs(0).payload := arb_uop.payload.ftq_idx

  // Only JALR checks the next-pc
  io_arb_ftq_reqs(1).valid   := arb_uop.valid && (arb_uop.payload.br_type === B_JR || arb_uop.payload.is_sfb_br)
  io_arb_ftq_reqs(1).payload := WrapInc(arb_uop.payload.ftq_idx, ftqSz)

  val FTQ = Payload(Vec.fill(2)(new FTQInfo))
  val exe_ftq_data = exe(FTQ)
  rrdDown(FTQ) := io_rrd_ftq_resps
}

class MemExeUnit(
  val hasAGen          : Boolean       = false,
  val hasDGen          : Boolean       = false
)(implicit p: Parameters) extends ExecutionUnit("Mem")
  with HasIrfReadPorts
  with HasImmrfReadPort
{
  def nReaders = 1

  val io_squash_iss = out Bool()
  io_squash_iss := (io_arb_irf_reqs(0).valid && !io_arb_irf_reqs(0).ready)

  replayArb(io_squash_iss, arb_rebusied, clearP3 = false)

  val io_agen: Option[Flow[MemGen]] = if (hasAGen) {
    val loads_saturating = exe_uop.valid && exe_uop.payload.uses_ldq && exe_uop.payload.fu_code(FC_AGEN)
    val saturating_loads_counter = Reg(UInt(5 bits)) init 0
    when (loads_saturating) { saturating_loads_counter := saturating_loads_counter + 1 }
      .otherwise { saturating_loads_counter := 0 }
    val pause_mem = RegNext(loads_saturating) && saturating_loads_counter === U(31, 5 bits)
    val load_ready = !pause_mem
    fu_types += ((FC_AGEN, load_ready, "AGen"))

    val sum = (exe_rs1_data.asSInt + exe_imm_data.asSInt).asUInt
    val ea_sign = Mux(sum(vaddrBits-1), ~sum(63 downto vaddrBits) === 0,
                                         sum(63 downto vaddrBits) =/= 0)
    val effective_address = Cat(ea_sign, sum(vaddrBits-1 downto 0)).asUInt

    val agen = master(Flow(new MemGen)).setName("io_agen")
    agen.valid        := exe_uop.valid && exe_uop.payload.fu_code(FC_AGEN)
    agen.payload.uop  := exe_uop.payload
    agen.payload.data := Sext(effective_address, xLen)
    Some(agen)
  } else {
    assert(!(exe_uop.valid && exe_uop.payload.fu_code(FC_AGEN)))
    None
  }

  val io_dgen: Option[Flow[MemGen]] = if (hasDGen) {
    fu_types += ((FC_DGEN, True, "DGen"))
    val dgen = master(Flow(new MemGen)).setName("io_dgen")
    dgen.valid        := exe_uop.valid && exe_uop.payload.fu_code(FC_DGEN)
    dgen.payload.data := exe_rs1_data
    dgen.payload.uop  := exe_uop.payload
    Some(dgen)
  } else {
    assert(!(exe_uop.valid && exe_uop.payload.fu_code(FC_DGEN)))
    None
  }

  io_ready_fu_types := get_ready_fu_types()
}

class UniqueExeUnit(
  val hasCSR           : Boolean       = false,
  val hasMul           : Boolean       = false,
  val hasDiv           : Boolean       = false,
  val hasIfpu          : Boolean       = false
)(implicit p: Parameters) extends ExecutionUnit("Unq")
  with HasIrfReadPorts
  with HasImmrfReadPort
  with MemoryOpConstants
{
  def nReaders = 2

  val io_squash_iss = out Bool()
  io_squash_iss := ((io_arb_irf_reqs(0).valid && !io_arb_irf_reqs(0).ready) ||
                    (io_arb_irf_reqs(1).valid && !io_arb_irf_reqs(1).ready))

  replayArb(io_squash_iss, arb_rebusied, clearP3 = false)

  val exe_int_req = new FuncUnitReq(xLen)
  exe_int_req.uop       := exe_uop.payload
  exe_int_req.rs1_data  := exe_rs1_data
  exe_int_req.rs2_data  := exe_rs2_data
  exe_int_req.rs3_data.assignDontCare()
  exe_int_req.imm_data  := exe_imm_data
  exe_int_req.pred_data.assignDontCare()
  exe_int_req.ftq_info.assignDontCare()

  val io_mul_resp: Option[Flow[ExeUnitResp]] = if (hasMul) {
    fu_types += ((FC_MUL, True, "IMul"))
    val imul = new PipelinedMulUnit(imulLatency, xLen)
    imul.io.req.valid   := exe_uop.valid && exe_uop.payload.fu_code(FC_MUL)
    imul.io.req.payload := exe_int_req
    imul.io.brupdate    := io_brupdate
    imul.io.kill        := io_kill
    imul.io.resp.ready  := True

    val resp = master(Flow(new ExeUnitResp(xLen))).setName("io_mul_resp")
    resp.valid   := imul.io.resp.valid
    resp.payload := imul.io.resp.payload
    Some(resp)
  } else {
    assert(!(exe_uop.valid && exe_uop.payload.fu_code(FC_MUL)))
    None
  }

  val (io_csr_resp, io_sfence): (Option[Flow[CSRResp]], Option[Flow[SFenceReq]]) = if (hasCSR) {
    fu_types += ((FC_CSR, True, "CSR"))
    val alu = new ALUUnit(dataWidth = xLen)
    alu.io.req.valid   := exe_uop.valid && exe_uop.payload.fu_code(FC_CSR)
    alu.io.req.payload := exe_int_req
    alu.io.resp.ready  := True
    alu.io.brupdate    := io_brupdate
    alu.io.kill        := io_kill

    val csrInput = new CSRResp
    csrInput.uop  := alu.io.resp.payload.uop
    csrInput.data := alu.io.resp.payload.data
    csrInput.addr := exe_imm_data.resized
    val c = master(Flow(new CSRResp)).setName("io_csr_resp")
    c << FixedPipeline(alu.io.resp.valid && exe_uop.payload.csr_cmd =/= CSR.N,
      csrInput, 1, holdPayload = false, resetValid = false)

    val sfenceInput = new SFenceReq
    sfenceInput.rs1  := exe_uop.payload.pimm(0)
    sfenceInput.rs2  := exe_uop.payload.pimm(1)
    sfenceInput.addr := exe_rs1_data.resized
    sfenceInput.asid := exe_rs2_data.resized
    sfenceInput.hv   := exe_uop.payload.mem_cmd === M_HFENCEV
    sfenceInput.hg   := exe_uop.payload.mem_cmd === M_HFENCEG
    val s = master(Flow(new SFenceReq)).setName("io_sfence")
    s << FixedPipeline(exe_uop.valid && exe_uop.payload.is_sfence,
      sfenceInput, 1, holdPayload = false, resetValid = false)

    (Some(c), Some(s))
  } else {
    assert(!(exe_uop.valid && exe_uop.payload.fu_code(FC_CSR)))
    assert(!(exe_uop.valid && exe_uop.payload.mem_cmd === M_SFENCE))
    (None, None)
  }

  assert(!(exe_uop.valid && exe_uop.payload.is_rocc))

  val io_ifpu_resp: Option[Stream[ExeUnitResp]] = if (hasIfpu) {
    val ifpu_ready = Bool()
    fu_types += ((FC_I2F, ifpu_ready, "IFPU"))

    val ifpu = new IntToFPUnit(latency=intToFpLatency)
    ifpu.io.req.valid   := exe_uop.valid && exe_uop.payload.fu_code(FC_I2F)
    // Chisel bulk-connects the xLen-wide request into the 65-bit port (zero-extending).
    ifpu.io.req.payload.uop       := exe_int_req.uop
    ifpu.io.req.payload.imm_data  := exe_int_req.imm_data
    ifpu.io.req.payload.pred_data.assignDontCare()
    ifpu.io.req.payload.ftq_info.assignDontCare()
    ifpu.io.req.payload.rs1_data := exe_int_req.rs1_data.resized
    ifpu.io.req.payload.rs2_data := exe_int_req.rs2_data.resized
    ifpu.io.req.payload.rs3_data.assignDontCare()
    ifpu.io.req.payload.uop.fp_rm  := exe_uop.payload.prs2(4 downto 2)
    ifpu.io.req.payload.uop.fp_typ := exe_uop.payload.prs2(1 downto 0)
    ifpu.io.fcsr_rm    := io_fcsr_rm
    ifpu.io.brupdate   := io_brupdate
    ifpu.io.kill       := io_kill

    // buffer up results since we share write-port on integer regfile.
    val queue = new BranchKillableQueue(new ExeUnitResp(xLen+1),
      entries = intToFpLatency + 6) // TODO being overly conservative
    queue.io.enq << ifpu.io.resp
    queue.io.brupdate := io_brupdate
    queue.io.flush    := io_kill
    assert (!(queue.io.enq.valid && !queue.io.enq.ready))
    ifpu_ready := RegNext(queue.io.count < 2)

    val ifpu_resp = master(Stream(new ExeUnitResp(xLen+1))).setName("io_ifpu_resp")
    ifpu_resp << queue.io.deq
    Some(ifpu_resp)
  } else {
    assert(!(exe_uop.valid && exe_uop.payload.fu_code(FC_I2F)))
    None
  }

  val io_div_resp: Option[Stream[ExeUnitResp]] = if (hasDiv) {
    val div_ready = Bool()
    fu_types += ((FC_DIV, div_ready, "IDiv"))

    val div = new DivUnit(xLen)
    assert(!(div.io.req.valid && !div.io.req.ready))
    div.io.req.valid   := exe_uop.valid && exe_uop.payload.fu_code(FC_DIV)
    div.io.req.payload := exe_int_req
    div.io.brupdate    := io_brupdate
    div.io.kill        := io_kill

    div_ready := (div.io.req.ready &&
      !(exe_uop.valid && exe_uop.payload.fu_code(FC_DIV)) &&
      !(rrd_uop.valid && rrd_uop.payload.fu_code(FC_DIV)) &&
      !(arb_uop.valid && arb_uop.payload.fu_code(FC_DIV))
    )

    val div_resp = master(Stream(new ExeUnitResp(xLen))).setName("io_div_resp")
    div_resp << div.io.resp
    Some(div_resp)
  } else {
    assert(!(exe_uop.valid && exe_uop.payload.fu_code(FC_DIV)))
    None
  }

  io_ready_fu_types := get_ready_fu_types()
}

class ALUExeUnit(
  val id: Int
)(implicit p: Parameters) extends ExecutionUnit("Alu")
  with HasIrfReadPorts
  with HasPrfReadPort
  with HasImmrfReadPort
  with HasBrfReadPort
  with HasFtqReadPort
{
  def nReaders = 2

  val io_fast_wakeup = master(Flow(new Wakeup))
  io_fast_wakeup.valid    := (
    io_iss_uop.valid &&
    (io_iss_uop.payload.dst_rtype === RT_FIX)
  )
  io_fast_wakeup.payload.uop := io_iss_uop.payload
  io_fast_wakeup.payload.speculative_mask := U(1 << id, aluWidth bits)
  io_fast_wakeup.payload.rebusy := False
  io_fast_wakeup.payload.bypassable := True

  val io_fast_pred_wakeup = master(Flow(new Wakeup))
  io_fast_pred_wakeup.valid    := rrd_uop.valid && rrd_uop.payload.is_sfb_br
  io_fast_pred_wakeup.payload.uop := rrd_uop.payload
  io_fast_pred_wakeup.payload.speculative_mask := 0
  io_fast_pred_wakeup.payload.rebusy := False
  io_fast_pred_wakeup.payload.bypassable := False

  val io_squash_iss = out Bool()

  io_squash_iss := ((io_arb_irf_reqs(0).valid && !io_arb_irf_reqs(0).ready) ||
                    (io_arb_irf_reqs(1).valid && !io_arb_irf_reqs(1).ready) ||
                    (io_arb_ftq_reqs(0).valid && !io_arb_ftq_reqs(0).ready) ||
                    (io_arb_ftq_reqs(1).valid && !io_arb_ftq_reqs(1).ready))

  val io_child_rebusy = out UInt(aluWidth bits)
  io_child_rebusy := 0
  when (arb_rebusied && arb_uop.valid) {
    io_child_rebusy := U(1 << id, aluWidth bits)
  }

  // The arbiter didn't grant us a slot. Thus, we should replay the instruction in this slot,
  // But next time we read, it reads from the regfile, not the bypass paths, so disable the bypass hints
  replayArb(io_squash_iss, arb_rebusied, clearP3 = false)

  val exe_int_req = new FuncUnitReq(xLen)
  exe_int_req.uop       := exe_uop.payload
  exe_int_req.rs1_data  := exe_rs1_data
  exe_int_req.rs2_data  := exe_rs2_data
  exe_int_req.rs3_data.assignDontCare()
  exe_int_req.imm_data  := exe_imm_data
  exe_int_req.pred_data := exe_pred_data
  exe_int_req.ftq_info  := exe_ftq_data

  fu_types += ((FC_ALU, True, "ALU"))

  val alu = new ALUUnit(dataWidth = xLen)
  alu.io.req.valid   := exe_uop.valid && exe_uop.payload.fu_code(FC_ALU)
  alu.io.req.payload := exe_int_req
  alu.io.resp.ready  := True
  alu.io.brupdate    := io_brupdate
  alu.io.kill        := io_kill

  val io_alu_resp = master(Flow(new ExeUnitResp(xLen)))
  io_alu_resp.valid   := alu.io.resp.valid
  io_alu_resp.payload := alu.io.resp.payload

  val io_brinfo = master(Flow(new BrResolutionInfo))
  io_brinfo := alu.io.brinfo

  // Spinal port: resolved branch direction for the RVFI trace (incl. short-forward branches).
  val io_rvfi_br = if (boomParams.enableRVFI) {
    val r = master(Flow(new RvfiBrWrite)).setName("io_rvfi_br")
    r.valid := alu.io.req.valid && (exe_uop.payload.is_br || exe_uop.payload.is_sfb_br)
    r.payload.rob_idx := exe_uop.payload.rob_idx
    r.payload.taken   := alu.io.brinfo.payload.taken
    r
  } else null

  io_ready_fu_types := get_ready_fu_types()
}

class FPExeUnit(val hasFDiv: Boolean = false, val hasFpiu: Boolean = false)(implicit p: Parameters)
  extends ExecutionUnit("FP")
  with tile.HasFPUParameters
  with HasFrfReadPorts
{
  val io_squash_iss = out Bool()
  io_squash_iss := (
    (io_arb_frf_reqs(0).valid && !io_arb_frf_reqs(0).ready) ||
    (io_arb_frf_reqs(1).valid && !io_arb_frf_reqs(1).ready) ||
    (io_arb_frf_reqs(2).valid && !io_arb_frf_reqs(2).ready)
  )

  replayArb(io_squash_iss, False, clearP3 = true)

  val exe_fp_req = new FuncUnitReq(xLen+1)
  exe_fp_req.uop := exe_uop.payload
  exe_fp_req.rs1_data := exe_rs1_data
  exe_fp_req.rs2_data := exe_rs2_data
  exe_fp_req.rs3_data := exe_rs3_data
  exe_fp_req.pred_data.assignDontCare()
  exe_fp_req.imm_data.assignDontCare()
  exe_fp_req.ftq_info.assignDontCare()

  val fpu = new FPUUnit
  fu_types += ((FC_FPU, True, "FPU"))
  fpu.io.req.valid := exe_uop.valid && (
    exe_uop.payload.fu_code(FC_FPU) || (if (hasFpiu) exe_uop.payload.fu_code(FC_F2I) else False)
  )
  fpu.io.req.payload := exe_fp_req
  fpu.io.fcsr_rm  := io_fcsr_rm
  fpu.io.brupdate := io_brupdate
  fpu.io.kill     := io_kill
  fpu.io.resp.ready := True

  val io_wakeup = master(Flow(new Wakeup))
  val fastWakeupLatency = dfmaLatency - 3 // Three stages WAKE-ISS-ARB
  require (fastWakeupLatency >= 0)
  val fast_wakeups = Vec.fill(fastWakeupLatency + 1)(Flow(new Wakeup))
  fast_wakeups(0).valid    := exe_uop.valid && exe_uop.payload.fu_code(FC_FPU)
  fast_wakeups(0).payload.uop := exe_uop.payload
  fast_wakeups(0).payload.speculative_mask := 0
  fast_wakeups(0).payload.rebusy           := False
  fast_wakeups(0).payload.bypassable       := True
  val wakeupNodes = Vector.fill(fastWakeupLatency + 1)(Node())
  val wakeupPayload = wakeupNodes.head.insert(fast_wakeups(0).payload)
  wakeupNodes.head.valid := fast_wakeups(0).valid
  val wakeupLinks = (0 until fastWakeupLatency).flatMap { i =>
    val boundary = Node()
    Vector(BranchPipeline.boundary(wakeupNodes(i), boundary, wakeupPayload, io_brupdate, io_kill),
      StageLink(boundary, wakeupNodes(i + 1)))
  }
  for (i <- 1 to fastWakeupLatency) {
    fast_wakeups(i).valid := wakeupNodes(i).isValid
    fast_wakeups(i).payload := wakeupNodes(i)(wakeupPayload)
  }
  if (wakeupLinks.nonEmpty) {
    Builder(wakeupLinks)
    wakeupNodes.tail.foreach(_.valid.removeInitAssignments())
  } else {
    wakeupNodes.head.build()
  }
  io_wakeup := fast_wakeups(fastWakeupLatency)

  val io_fpu_resp = master(Flow(new ExeUnitResp(xLen+1)))
  io_fpu_resp.valid   := fpu.io.resp.valid && !fpu.io.resp.payload.uop.fu_code(FC_F2I)
  io_fpu_resp.payload := fpu.io.resp.payload

  val io_fdiv_resp: Option[Stream[ExeUnitResp]] = if (hasFDiv) {
    val fdivsqrt_ready = Bool()
    fu_types += ((FC_FDV, fdivsqrt_ready, "FDiv"))

    val fdivsqrt = new FDivSqrtUnit2
    assert(!(fdivsqrt.io.req.valid && !fdivsqrt.io.req.ready))
    fdivsqrt.io.req.valid   := exe_uop.valid && exe_uop.payload.fu_code(FC_FDV)
    fdivsqrt.io.req.payload := exe_fp_req
    fdivsqrt.io.brupdate := io_brupdate
    fdivsqrt.io.kill     := io_kill
    fdivsqrt.io.fcsr_rm  := io_fcsr_rm

    fdivsqrt_ready := (fdivsqrt.io.req.ready &&
      !(exe_uop.valid && exe_uop.payload.fu_code(FC_FDV)) &&
      !(rrd_uop.valid && rrd_uop.payload.fu_code(FC_FDV)) &&
      !(arb_uop.valid && arb_uop.payload.fu_code(FC_FDV))
    )

    val fdiv_resp = master(Stream(new ExeUnitResp(xLen+1))).setName("io_fdiv_resp")
    fdiv_resp << fdivsqrt.io.resp
    Some(fdiv_resp)
  } else {
    assert(!(exe_uop.valid && exe_uop.payload.fu_code(FC_FDV)))
    None
  }

  val (io_fpiu_resp, io_dgen): (Option[Stream[ExeUnitResp]], Option[Flow[MemGen]]) = if (hasFpiu) {
    val fpiu_ready = Bool()
    fu_types += ((FC_F2I, fpiu_ready, "Fpiu"))

    val queue = new BranchKillableQueue(new ExeUnitResp(xLen+1),
      entries = dfmaLatency + 6) // TODO being overly conservative
    fpiu_ready               := RegNext(queue.io.count < 2)
    queue.io.enq.valid       := ( fpu.io.resp.valid &&
                                  fpu.io.resp.payload.uop.fu_code(FC_F2I) &&
                                 !fpu.io.resp.payload.uop.uses_stq) // STA means store data gen for floating point
    queue.io.enq.payload     := fpu.io.resp.payload
    queue.io.brupdate        := io_brupdate
    queue.io.flush           := io_kill
    assert(!(queue.io.enq.valid && !queue.io.enq.ready))

    // Chisel truncates the 65-bit data onto the xLen-wide port.
    val fpiu_resp = master(Stream(new ExeUnitResp(xLen))).setName("io_fpiu_resp")
    fpiu_resp.valid := queue.io.deq.valid
    queue.io.deq.ready := fpiu_resp.ready
    fpiu_resp.payload.uop        := queue.io.deq.payload.uop
    fpiu_resp.payload.data       := queue.io.deq.payload.data.resized
    fpiu_resp.payload.predicated := queue.io.deq.payload.predicated
    fpiu_resp.payload.fflags     := queue.io.deq.payload.fflags

    val dgenInput = new MemGen
    dgenInput.uop := exe_uop.payload
    dgenInput.data := ieee(exe_rs2_data).resized
    val dgen = master(Flow(new MemGen)).setName("io_dgen")
    dgen << FixedPipeline(exe_uop.valid && exe_uop.payload.uses_stq &&
      !IsKilledByBranch(io_brupdate, io_kill, exe_uop.payload),
      dgenInput, 1, holdPayload = false, resetValid = false)
    (Some(fpiu_resp), Some(dgen))
  } else {
    assert(!(exe_uop.valid && exe_uop.payload.fu_code(FC_F2I)))
    (None, None)
  }

  io_ready_fu_types := get_ready_fu_types()
}
