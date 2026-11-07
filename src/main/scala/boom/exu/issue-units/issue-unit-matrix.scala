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

class IssueUnitAgeMatrix(
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

  // Slots stay in place. Each age row marks the entries older than its slot.
  val slots = (0 until numIssueSlots).map(_ => new IssueSlot(numWakeupPorts, iqType == IQ_MEM, iqType == IQ_FP))
  val issue_slots = slots.map(_.io)
  for (s <- issue_slots) {
    s.wakeup_ports := io.wakeup_ports
    s.pred_wakeup_port := io.pred_wakeup_port
    s.child_rebusys := io.child_rebusys
    s.squash_grant := io.squash_grant
    s.brupdate := io.brupdate
    s.kill := io.flush_pipeline
    s.clear := False
    s.in_uop.valid := False
    s.in_uop.payload.assignDontCare()
  }
  assert(PopCount(issue_slots.map(_.grant)) <= issueWidth, "[issue] window giving out too many grants.")

  val slots_empty = issue_slots.map(s => !s.will_be_valid && !s.valid).asUInt
  val dis_readys = Vec.fill(dispatchWidth)(Bool())
  val dis_indices = Vec.fill(dispatchWidth)(UInt(log2Ceil(numIssueSlots) bits))
  val dis_valids = (0 until dispatchWidth).map(i => io.dis_uops(i).valid &&
    !dis_uops(i).exception && !dis_uops(i).is_fence && !dis_uops(i).is_fencei)
  var unallocated_slots = slots_empty
  for (i <- 0 until dispatchWidth) {
    dis_readys(i) := unallocated_slots.orR
    dis_indices(i) := PriorityEncoder(unallocated_slots).resized
    unallocated_slots = unallocated_slots & ~UIntToOH(dis_indices(i), numIssueSlots)
    io.dis_uops(i).ready := dis_readys(i)
    for (w <- 0 until numIssueSlots) {
      when(dis_valids(i) && dis_readys(i) && dis_indices(i) === w) {
        issue_slots(w).in_uop.valid := True
        issue_slots(w).in_uop.payload := dis_uops(i)
      }
    }
  }

  val slots_age_matrix = Vec.fill(numIssueSlots)(Reg(UInt(numIssueSlots bits)) init(0))
  val slots_valid_alloc = issue_slots.map(_.in_uop.valid)
  val slots_valid_dealloc = issue_slots.map(s => s.valid && !s.will_be_valid)
  val older_allocations = Vec.fill(numIssueSlots)(UInt(numIssueSlots bits))
  older_allocations.foreach(_ := 0)
  var prior_allocations = U(0, numIssueSlots bits)
  for (i <- 0 until dispatchWidth) {
    val lane_allocates = dis_valids(i) && dis_readys(i)
    when(lane_allocates) { older_allocations(dis_indices(i)) := prior_allocations }
    prior_allocations = Mux(lane_allocates,
      prior_allocations | UIntToOH(dis_indices(i), numIssueSlots), prior_allocations)
  }
  val surviving_slots = issue_slots.map(_.valid).asUInt & ~slots_valid_dealloc.asUInt
  for (i <- 0 until numIssueSlots) {
    when(slots_valid_alloc(i)) {
      slots_age_matrix(i) := surviving_slots | older_allocations(i)
    } elsewhen(slots_valid_dealloc(i)) {
      slots_age_matrix(i) := 0
    } otherwise {
      for (j <- 0 until numIssueSlots) {
        when(slots_valid_alloc(j) || slots_valid_dealloc(j)) { slots_age_matrix(i)(j) := False }
      }
    }
  }

  val iss_ready = Vec.fill(issueWidth)(UInt(numIssueSlots bits))
  for (i <- 0 until issueWidth; j <- 0 until numIssueSlots) {
    val fu_code_match = (issue_slots(j).iss_uop.fu_code zip io.fu_types(i)).map {
      case (r,c) => r && c
    }.reduce(_||_)
    iss_ready(i)(j) := fu_code_match && issue_slots(j).request
  }
  val iss_signals = Vec.fill(issueWidth)(Flow(UInt(log2Ceil(numIssueSlots) bits)))
  if (!params.useFullIssueSel) {
    val issue_oldest_oh = Vec.fill(issueWidth)(UInt(numIssueSlots bits))
    for (i <- 0 until issueWidth) {
      for (j <- 0 until numIssueSlots) {
        issue_oldest_oh(i)(j) := !(slots_age_matrix(i) & iss_ready(i)).orR && iss_ready(i)(j)
      }
      assert(!iss_ready(i).orR || issue_oldest_oh(i).orR)
      assert(PopCount(issue_oldest_oh(i)) <= dispatchWidth)
    }
    for (i <- 0 until issueWidth) {
      val (old_valid, old_index) = ParallelFindOne(issue_oldest_oh(i), i % 2 == 0)
      val oldest_valid = Bool()
      oldest_valid := old_valid
      val compute_valid_mask = UInt(numIssueSlots bits)
      compute_valid_mask := iss_ready(i)
      for (j <- 0 until i) {
        when(iss_signals(j).valid) {
          compute_valid_mask(iss_signals(j).payload) := False
          when(old_index === iss_signals(j).payload) { oldest_valid := False }
        }
      }
      when(old_valid) { compute_valid_mask(old_index.resized) := False }
      val (other_valid, other_index) = ParallelFindOne(compute_valid_mask, i % 2 == 1)
      iss_signals(i).valid := oldest_valid || other_valid
      iss_signals(i).payload := Mux(oldest_valid, old_index, other_index).resized
      for (j <- 0 until i) {
        assert(!(iss_signals(i).valid && iss_signals(j).valid) ||
          iss_signals(i).payload =/= iss_signals(j).payload)
      }
    }
  } else {
    for (i <- 0 until issueWidth) {
      iss_signals(i).valid := False
      iss_signals(i).payload.assignDontCare()
      for (j <- 0 until numIssueSlots) {
        when(iss_ready(i)(j) && !(slots_age_matrix(j) & iss_ready(i)).orR) {
          iss_signals(i).valid := True
          iss_signals(i).payload := j
        }
      }
      when(iss_signals(i).valid) {
        for (j <- i+1 until issueWidth) { iss_ready(j)(iss_signals(i).payload) := False }
      }
    }
  }
  for (w <- 0 until numIssueSlots) {
    issue_slots(w).grant := (0 until issueWidth).map(i =>
      iss_signals(i).valid && iss_signals(i).payload === w).reduce(_||_)
  }
  val issue_payloads = Vec(issue_slots.map(_.iss_uop))
  for (i <- 0 until issueWidth) {
    io.iss_uops(i).valid := iss_signals(i).valid && !io.squash_grant
    io.iss_uops(i).payload.assignDontCare()
    when(iss_signals(i).valid) { io.iss_uops(i).payload := issue_payloads(iss_signals(i).payload) }
  }
}
