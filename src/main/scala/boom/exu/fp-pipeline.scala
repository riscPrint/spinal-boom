//******************************************************************************
// Copyright (c) 2015 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Floating Point Datapath Pipeline
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import spinal.core._
import spinal.lib._

import rocketchip.config.Parameters
import rocketchip.rocket
import rocketchip.tile
import rocketchip.util._

import boom.common._
import boom.util._

/**
 * Top level datapath that wraps the floating point issue window, regfile, and arithmetic units.
 */
class FpPipeline(implicit p: Parameters) extends BoomModule with tile.HasFPUParameters
{
  val fpIssueParams = issueParams.find(_.iqType == IQ_FP).get
  val dispatchWidth = fpIssueParams.dispatchWidth
  val numLlPorts = lsuWidth
  val numWakeupPorts = fpIssueParams.issueWidth + numLlPorts
  val fpPregSz = log2Ceil(numFpPhysRegs)

  val io = new Bundle {
    val brupdate         = in(new BrUpdateInfo())
    val flush_pipeline   = in Bool()
    val fcsr_rm          = in UInt(tile.FPConstants.RM_SZ bits)
    val status           = in(new rocket.MStatus())

    val dis_uops         = Vec.fill(dispatchWidth)(slave(Stream(new MicroOp)))

    // +1 for recoding.
    val ll_wports        = in(Vec.fill(lsuWidth)(Flow(new ExeUnitResp(fLen+1))))// from memory unit
    val from_int         = slave(Stream(new ExeUnitResp(fLen+1)))// from integer RF
    val dgen             = master(Flow(new MemGen))           // to Load/Store Unit
    val to_int           = master(Stream(new ExeUnitResp(xLen)))           // to integer RF

    val wakeups          = out(Vec.fill(numWakeupPorts)(Flow(new Wakeup)))
    val wb               = out(Vec.fill(numWakeupPorts)(Flow(new ExeUnitResp(fLen+1))))

    val debug_tsc_reg    = in UInt(xLen bits)
  }

  //**********************************
  // construct all of the modules

  val exe_units: Seq[FPExeUnit] = (0 until fpWidth) map { w =>
    new FPExeUnit(
      hasFDiv = usingFDivSqrt && (w==fpWidth-1),
      hasFpiu = (w==fpWidth-1)
    ).setName(s"fp_exe_unit_${w}")
  }
  require (numFrfReadPorts >= 3)
  val numFrfLogicalReadPorts = fpWidth * 3
  val numFrfWritePorts = fpWidth + lsuWidth

  val issue_unit     = IssueUnit(fpIssueParams, numWakeupPorts, false, false)
  issue_unit.setName("fp_issue_unit")
  issue_unit.io.rob_head.assignDontCare()
  issue_unit.io.rob_pnr_idx.assignDontCare()
  val fregfileBankedWriteArray = Seq.fill(numFrfWritePorts) { None }
  val fregfile       = new BankedRF(
    UInt(fLen+1 bits),
    numFrfBanks,
    numFrfLogicalReadPorts,
    numFpPhysRegs,
    numFrfLogicalReadPorts,
    numFrfReadPorts,
    numFrfWritePorts,
    fregfileBankedWriteArray,
    "Floating Point"
  )
  val fp_bypasses = Vec.fill(fpWidth)(Flow(new ExeUnitResp(xLen+1)))
  val fp_wakeups = Vec.fill(numWakeupPorts)(Flow(new Wakeup))
  io.wakeups := fp_wakeups

  //*************************************************************
  // Issue window logic

  val iss_uops   = issue_unit.io.iss_uops

  issue_unit.io.tsc_reg := io.debug_tsc_reg
  issue_unit.io.brupdate := io.brupdate
  issue_unit.io.flush_pipeline := io.flush_pipeline
  issue_unit.io.squash_grant := exe_units.map(_.io_squash_iss).reduce(_||_)

  //-------------------------------------------------------------
  // **** Dispatch Stage ****
  //-------------------------------------------------------------

  // Input (Dispatch)
  for (w <- 0 until dispatchWidth) {
    issue_unit.io.dis_uops(w) << io.dis_uops(w)
  }

  //-------------------------------------------------------------
  // **** Issue Stage ****
  //-------------------------------------------------------------

  // Output (Issue)
  for (i <- 0 until issue_unit.issueWidth) {
    val fu_types = exe_units(i).io_ready_fu_types
    issue_unit.io.fu_types(i) := fu_types
  }

  // Wakeup
  (issue_unit.io.wakeup_ports zip fp_wakeups).foreach { case (p, w) => p << w }

  issue_unit.io.pred_wakeup_port.valid := False
  issue_unit.io.pred_wakeup_port.payload.assignDontCare()
  issue_unit.io.child_rebusys := 0

  issue_unit.io.iss_uops zip exe_units foreach { case (i, u) => u.io_iss_uop << i }

  //-------------------------------------------------------------
  // **** Register Arbitrate Stage ****
  //-------------------------------------------------------------

  var rd_idx = 0
  for (unit <- exe_units) {
    for (i <- 0 until 3) {
      fregfile.io.arb_read_reqs(rd_idx).valid   := unit.io_arb_frf_reqs(i).valid
      fregfile.io.arb_read_reqs(rd_idx).payload := unit.io_arb_frf_reqs(i).payload.resized
      unit.io_arb_frf_reqs(i).ready := fregfile.io.arb_read_reqs(rd_idx).ready
      rd_idx += 1
    }
  }
  require(rd_idx == numFrfLogicalReadPorts)

  //-------------------------------------------------------------
  // **** Register Read Stage ****
  //-------------------------------------------------------------

  rd_idx = 0
  for (unit <- exe_units) {
    unit.io_rrd_frf_bypasses := fp_bypasses
    for (i <- 0 until 3) {
      unit.io_rrd_frf_resps(i) := fregfile.io.rrd_read_resps(rd_idx)
      rd_idx += 1
    }
  }
  require(rd_idx == numFrfLogicalReadPorts)

  //-------------------------------------------------------------
  // **** Execute Stage ****
  //-------------------------------------------------------------

  exe_units.foreach(_.io_brupdate := io.brupdate)

  //-------------------------------------------------------------
  // **** Writeback Stage ****
  //-------------------------------------------------------------

  val ll_wbarb = new Arbiter(new ExeUnitResp(fLen+1), 1 + // mem
                                                      1 + // fromint
                                                      1   // fdiv
  )

  // Hookup load writeback -- and recode FP values.
  val loadResponses = (0 until lsuWidth).map { i =>
    BranchPipeline.writebackTap(io.ll_wports(i), io.brupdate, io.flush_pipeline)
  }
  val load0 = loadResponses.head._2
  ll_wbarb.io.in(0).valid := load0.valid
  ll_wbarb.io.in(0).payload := load0.payload
  ll_wbarb.io.in(0).payload.data := recode(load0.payload.data,
    (load0.payload.uop.mem_size =/= 2).asUInt)

  val ifpu_resp = io.from_int
  ll_wbarb.io.in(1) << ifpu_resp

  ll_wbarb.io.in(2) << exe_units.find(_.hasFDiv).get.io_fdiv_resp.get

  // Cut up critical path by delaying the write by a cycle.
  // Wakeup signal is sent on cycle S0, write is now delayed until end of S1,
  // but Issue happens on S1 and RegRead doesn't happen until S2 so we're safe.
  fregfile.io.write_ports(0).valid        := ll_wbarb.io.out.valid && ll_wbarb.io.out.payload.uop.dst_rtype === RT_FLT
  fregfile.io.write_ports(0).payload.addr := ll_wbarb.io.out.payload.uop.pdst
  fregfile.io.write_ports(0).payload.data := ll_wbarb.io.out.payload.data

  assert (ll_wbarb.io.in(0).ready) // never backpressure the memory unit.
  when (ifpu_resp.valid) { assert (ifpu_resp.payload.uop.dst_rtype === RT_FLT) }

  var w_cnt = 1
  for (i <- 1 until lsuWidth) {
    val raw = loadResponses(i)._1
    fregfile.io.write_ports(w_cnt).valid        := raw.valid
    fregfile.io.write_ports(w_cnt).payload.addr := raw.payload.uop.pdst
    fregfile.io.write_ports(w_cnt).payload.data := recode(raw.payload.data,
      (raw.payload.uop.mem_size =/= 2).asUInt)
    w_cnt += 1
  }
  for (eu <- exe_units) {
    fregfile.io.write_ports(w_cnt).valid        := eu.io_fpu_resp.valid && eu.io_fpu_resp.payload.uop.dst_rtype === RT_FLT
    fregfile.io.write_ports(w_cnt).payload.addr := eu.io_fpu_resp.payload.uop.pdst
    fregfile.io.write_ports(w_cnt).payload.data := eu.io_fpu_resp.payload.data
    w_cnt += 1
  }
  for (w <- 0 until fpWidth) {
    fp_bypasses(w).valid   := exe_units(w).io_fpu_resp.valid && exe_units(w).io_fpu_resp.payload.uop.dst_rtype === RT_FLT
    fp_bypasses(w).payload := exe_units(w).io_fpu_resp.payload
  }

  require (w_cnt == fregfile.io.write_ports.length)

  val fpiu_unit = exe_units.find(_.hasFpiu).get
  io.to_int << fpiu_unit.io_fpiu_resp.get
  io.dgen   := fpiu_unit.io_dgen.get

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // **** Commit Stage ****
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  var idx = 0
  for (w <- 0 until fpWidth) {
    fp_wakeups(idx)  := exe_units(w).io_wakeup
    io.wb(idx) := exe_units(w).io_fpu_resp
    idx += 1
  }

  fp_wakeups(idx).valid    := ll_wbarb.io.out.valid
  fp_wakeups(idx).payload.uop := ll_wbarb.io.out.payload.uop
  fp_wakeups(idx).payload.speculative_mask := 0
  fp_wakeups(idx).payload.bypassable := False
  fp_wakeups(idx).payload.rebusy := False

  io.wb(idx).valid   := ll_wbarb.io.out.valid
  io.wb(idx).payload := ll_wbarb.io.out.payload
  ll_wbarb.io.out.ready := True
  idx += 1

  for (i <- 1 until lsuWidth) {
    val (raw, wb) = loadResponses(i)
    fp_wakeups(idx).valid := wb.valid && wb.payload.uop.dst_rtype === RT_FLT
    fp_wakeups(idx).payload.uop := wb.payload.uop
    fp_wakeups(idx).payload.speculative_mask := 0
    fp_wakeups(idx).payload.bypassable := False
    fp_wakeups(idx).payload.rebusy := False

    io.wb(idx) := wb
    io.wb(idx).payload.data := recode(
      raw.payload.data,
      (raw.payload.uop.mem_size =/= 2).asUInt
    )
    idx += 1
  }
  require (idx == numWakeupPorts)
  exe_units.foreach(_.io_fcsr_rm := io.fcsr_rm)
  exe_units.foreach(_.io_status := io.status)

  //-------------------------------------------------------------
  // **** Flush Pipeline ****
  //-------------------------------------------------------------
  // flush on exceptions, miniexeptions, and after some special instructions

  for (w <- 0 until exe_units.length) {
    exe_units(w).io_kill := io.flush_pipeline
  }

  override def toString: String =
    (BoomCoreStringPrefix("===FP Pipeline===") + "\n"
    + exe_units.map(_.toString).mkString("\n") + "\n"
    + fregfile.toString
    + BoomCoreStringPrefix(
      "Num Wakeup Ports      : " + numWakeupPorts))
}
