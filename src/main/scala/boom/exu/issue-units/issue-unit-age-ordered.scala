//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Processor Issue Logic
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import spinal.core._
import spinal.lib._

import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

class IssueUnitCollapsing(
  params: IssueParams,
  numWakeupPorts: Int)
  (implicit p: Parameters)
  extends IssueUnit(params, numWakeupPorts)
{
  //-------------------------------------------------------------
  // Set up the dispatch uops
  // special case "storing" 2 uops within one issue slot.

  val dis_uops = Array.fill(dispatchWidth) {new MicroOp()}
  for (w <- 0 until dispatchWidth) {
    dis_uops(w) := io.dis_uops(w).payload
    dis_uops(w).iw_issued := False
    dis_uops(w).iw_issued_partial_agen := False
    dis_uops(w).iw_issued_partial_dgen := False
    dis_uops(w).iw_p1_bypass_hint := False
    dis_uops(w).iw_p2_bypass_hint := False
    dis_uops(w).iw_p3_bypass_hint := False

    // Handle wakeups on dispatch
    val prs1_matches = io.wakeup_ports.map { wu => wu.payload.uop.pdst === io.dis_uops(w).payload.prs1 }
    val prs2_matches = io.wakeup_ports.map { wu => wu.payload.uop.pdst === io.dis_uops(w).payload.prs2 }
    val prs3_matches = io.wakeup_ports.map { wu => wu.payload.uop.pdst === io.dis_uops(w).payload.prs3 }
    val prs1_wakeups = (io.wakeup_ports zip prs1_matches).map { case (wu,m) => wu.valid && m }
    val prs2_wakeups = (io.wakeup_ports zip prs2_matches).map { case (wu,m) => wu.valid && m }
    val prs3_wakeups = (io.wakeup_ports zip prs3_matches).map { case (wu,m) => wu.valid && m }
    val prs1_rebusys = (io.wakeup_ports zip prs1_matches).map { case (wu,m) => wu.payload.rebusy && m }
    val prs2_rebusys = (io.wakeup_ports zip prs2_matches).map { case (wu,m) => wu.payload.rebusy && m }
    val bypassables  = io.wakeup_ports.map { wu => wu.payload.bypassable }
    val speculative_masks = io.wakeup_ports.map { wu => wu.payload.speculative_mask }

    when (prs1_wakeups.reduce(_||_)) {
      dis_uops(w).prs1_busy := False
      dis_uops(w).iw_p1_speculative_child := Mux1H(prs1_wakeups, speculative_masks)
      dis_uops(w).iw_p1_bypass_hint := Mux1H(prs1_wakeups, bypassables)
    }
    when (prs1_rebusys.reduce(_||_) || ((io.child_rebusys & io.dis_uops(w).payload.iw_p1_speculative_child) =/= 0)) {
      dis_uops(w).prs1_busy := io.dis_uops(w).payload.lrs1_rtype === RT_FIX
    }
    when (prs2_wakeups.reduce(_||_)) {
      dis_uops(w).prs2_busy := False
      dis_uops(w).iw_p2_speculative_child := Mux1H(prs2_wakeups, speculative_masks)
      dis_uops(w).iw_p2_bypass_hint := Mux1H(prs2_wakeups, bypassables)
    }

    when (prs2_rebusys.reduce(_||_) || ((io.child_rebusys & io.dis_uops(w).payload.iw_p2_speculative_child) =/= 0)) {
      dis_uops(w).prs2_busy := io.dis_uops(w).payload.lrs2_rtype === RT_FIX
    }

    when (prs3_wakeups.reduce(_||_)) {
      dis_uops(w).prs3_busy := False
      dis_uops(w).iw_p3_bypass_hint := Mux1H(prs3_wakeups, bypassables)
    }
    when (io.pred_wakeup_port.valid && io.pred_wakeup_port.payload === io.dis_uops(w).payload.ppred) {
      dis_uops(w).ppred_busy := False
    }

    if (iqType == IQ_UNQ) {
      when (io.dis_uops(w).payload.fu_code(FC_I2F)) {
        dis_uops(w).prs2 := Cat(io.dis_uops(w).payload.fp_rm, io.dis_uops(w).payload.fp_typ).asUInt.resized
      }
      when (io.dis_uops(w).payload.is_sfence) {
        dis_uops(w).pimm := io.dis_uops(w).payload.mem_size.resized
      }
    }

    if (iqType == IQ_MEM) {
      // For store addr gen for FP, rs2 is the FP register, and we don't wait for that here
      when (io.dis_uops(w).payload.uses_stq && io.dis_uops(w).payload.lrs2_rtype === RT_FLT) {
        dis_uops(w).lrs2_rtype := RT_X
        dis_uops(w).prs2_busy  := False
      }
      dis_uops(w).prs3_busy := False
    } else if (iqType == IQ_FP) {
      // FP "StoreAddrGen" is really storeDataGen, and rs1 is the integer address register
      when (io.dis_uops(w).payload.uses_stq) {
        dis_uops(w).lrs1_rtype := RT_X
        dis_uops(w).prs1_busy  := False
      }
    }

    if (iqType != IQ_ALU) {
      assert(!(io.dis_uops(w).payload.ppred_busy && io.dis_uops(w).valid))
      dis_uops(w).ppred_busy := False
    }
  }

  //-------------------------------------------------------------
  // Issue Table

  val slots = (0 until numIssueSlots) map { w => new IssueSlot(numWakeupPorts, iqType == IQ_MEM, iqType == IQ_FP) }
  val issue_slots = slots.map(_.io)

  for (i <- 0 until numIssueSlots) {
    issue_slots(i).wakeup_ports     := io.wakeup_ports
    issue_slots(i).pred_wakeup_port := io.pred_wakeup_port
    issue_slots(i).child_rebusys    := io.child_rebusys
    issue_slots(i).squash_grant     := io.squash_grant
    issue_slots(i).brupdate         := io.brupdate
    issue_slots(i).kill             := io.flush_pipeline
  }

  for (w <- 0 until issueWidth) {
    io.iss_uops(w).valid := False
  }

  //-------------------------------------------------------------

  assert (PopCount(issue_slots.map(s => s.grant)) <= issueWidth, "[issue] window giving out too many grants.")

  //-------------------------------------------------------------
  // Figure out how much to shift entries by

  // Slow slots only shift 1 per cycle, these reduce critical path
  val nSlowSlots = params.numSlowEntries
  // Fast slots can shift up to dispatchWidth per cycle, so they can handle full dispatch throughput
  val nFastSlots = numIssueSlots - nSlowSlots

  require (nFastSlots >= dispatchWidth)
  require (nFastSlots <= numIssueSlots)

  val vacants = issue_slots.map(s => !(s.valid)) ++ io.dis_uops.map(_.valid).map(!_)
  val shamts_oh = Vec.fill(numIssueSlots+dispatchWidth)(UInt(dispatchWidth bits))
  // track how many to shift up this entry by by counting previous vacant spots
  def SaturatingCounterOH(count_oh:UInt, inc: Bool, max: Int): UInt = {
     val next = UInt(max bits)
     next := count_oh.resized // Chisel truncates the dispatchWidth-wide count
     when (count_oh === 0 && inc) {
       next := 1
     } elsewhen (!count_oh(max-1) && inc) {
       next := (count_oh << 1).resized
     }
     next
  }
  shamts_oh(0) := 0
  for (i <- 1 until numIssueSlots + dispatchWidth) {
    val shift = if (i < nSlowSlots) (dispatchWidth min 1 + (i * (dispatchWidth-1)/nSlowSlots).toInt) else dispatchWidth
    if (dispatchWidth == 1 || shift == 1) {
      shamts_oh(i) := vacants.take(i).reduce(_||_).asUInt.resized
    } else {
      shamts_oh(i) := SaturatingCounterOH(shamts_oh(i-1), vacants(i-1), shift).resized
    }
  }

  //-------------------------------------------------------------

  // which entries' uops will still be next cycle? (not being issued and vacated)
  val will_be_valid = (0 until numIssueSlots).map(i => issue_slots(i).will_be_valid) ++
                      (0 until dispatchWidth).map(i => io.dis_uops(i).valid &&
                                                        !dis_uops(i).exception &&
                                                        !dis_uops(i).is_fence &&
                                                        !dis_uops(i).is_fencei)

  val uops = issue_slots.map(s=>s.out_uop) ++ dis_uops.map(s=>s)
  for (i <- 0 until numIssueSlots) {
    issue_slots(i).in_uop.valid   := False
    issue_slots(i).in_uop.payload := uops(i+1)
    for (j <- 1 to dispatchWidth by 1) {
      when (shamts_oh(i+j) === (1 << (j-1))) {
        issue_slots(i).in_uop.valid   := will_be_valid(i+j)
        issue_slots(i).in_uop.payload := uops(i+j)
      }
    }
    issue_slots(i).clear        := shamts_oh(i) =/= 0
  }

  //-------------------------------------------------------------
  // Dispatch/Entry Logic
  // did we find a spot to slide the new dispatched uops into?

  // Only look at the fast slots to determine readiness to dispatch.
  // Slow slot do not compact fast enough to make this calculation valid
  val is_available = Reg(Vec.fill(nFastSlots)(Bool()))
  is_available := Vec((nSlowSlots until numIssueSlots).map(i =>
    (!issue_slots(i).will_be_valid || issue_slots(i).clear) && !(issue_slots(i).in_uop.valid)))
  for (w <- 0 until dispatchWidth) {
    // Chisel: w.U(log2Ceil(nFastSlots).W) + PopCount(...) (sum width = the wider operand)
    val fired = PopCount(io.dis_uops.map(_.fire))
    val sw = log2Ceil(nFastSlots) max fired.getWidth
    io.dis_uops(w).ready := RegNext(PopCount(is_available) > (U(w, sw bits) + fired.resize(sw)))
    assert (!io.dis_uops(w).ready || (shamts_oh(w+numIssueSlots) >> w) =/= 0)
  }

  //-------------------------------------------------------------
  // Issue Select Logic
  val requests = issue_slots.map(s => s.request)
  val port_issued = Array.fill[Bool](issueWidth){False}

  val iss_select_mask = Array.ofDim[Boolean](issueWidth, numIssueSlots)
  if (params.useFullIssueSel) {
    for (w <- 0 until issueWidth) {
      for (i <- 0 until numIssueSlots) {
        iss_select_mask(w)(i) = true
      }
    }
  } else {
    for (w <- 0 until issueWidth) {
      for (i <- 0 until numIssueSlots) {
        iss_select_mask(w)(i) = (w % 2) == (i % 2)
      }
      iss_select_mask(w)(0) = true
    }
  }

  val iss_uops = Vec.fill(issueWidth)(Flow(new MicroOp))
  for (w <- 0 until issueWidth) {
    iss_uops(w).valid := False
    iss_uops(w).payload.assignDontCare()
  }

  for (i <- 0 until numIssueSlots) {
    issue_slots(i).grant := False
    var uop_issued: Bool = False

    // when enabling a conservative implementation of speculative non-interference, delay issue of instructions that have not passed PnR
    val issue_slot_past_pnr = IsOlder(issue_slots(i).iss_uop.rob_idx, io.rob_pnr_idx, io.rob_head) | (issue_slots(i).iss_uop.rob_idx === io.rob_pnr_idx)
    val issue_past_pnr = MuxCase(True, Seq(
      issue_slots(i).iss_uop.is_br -> issue_slot_past_pnr,
      issue_slots(i).iss_uop.is_jal -> issue_slot_past_pnr,
      issue_slots(i).iss_uop.is_jalr -> issue_slot_past_pnr,
      issue_slots(i).iss_uop.fu_code(FC_MUL) -> issue_slot_past_pnr,
      issue_slots(i).iss_uop.fu_code(FC_DIV) -> issue_slot_past_pnr,
      issue_slots(i).iss_uop.fu_code(FC_AGEN) -> (issue_slots(i).iss_uop.rob_idx === io.rob_head)
    ))
    val can_issue_sni = issue_past_pnr | !Bool(enableConservativeSNI)

    for (w <- 0 until issueWidth) {
      val fu_code_match = (issue_slots(i).iss_uop.fu_code zip io.fu_types(w)).map {
        case (r,c) => r && c
      } .reduce(_||_)

      val can_allocate = fu_code_match && Bool(iss_select_mask(w)(i)) && can_issue_sni

      when (requests(i) && !uop_issued && can_allocate && !port_issued(w)) {
        issue_slots(i).grant := True
        iss_uops(w).valid    := True
        iss_uops(w).payload  := issue_slots(i).iss_uop
      }
      val was_port_issued_yet = port_issued(w)
      port_issued(w) = (requests(i) && !uop_issued && can_allocate) | port_issued(w)
      uop_issued = (requests(i) && can_allocate && !was_port_issued_yet) | uop_issued
    }
  }
  io.iss_uops := iss_uops
  when (io.squash_grant) {
    io.iss_uops.foreach { u => u.valid := False }
  }
}
