//******************************************************************************
// Copyright (c) 2015 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Rename FreeList
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import spinal.core._
import spinal.lib._
import boom.common._
import boom.util._
import rocketchip.config.Parameters
import rocketchip.util._

class RenameFreeList(
  val allocWidth: Int,
  val deallocWidth: Int,
  val numPregs: Int,
  isImm: Boolean
)(implicit p: Parameters)
    extends BoomModule
{
  val pregSz = log2Ceil(numPregs)
  val n = numPregs

  val io = new Bundle {
    val initial_allocation = in UInt(numPregs bits)
    // Physical register requests.
    val reqs          = in(Vec.fill(allocWidth)(Bool()))
    val alloc_pregs   = out(Vec.fill(allocWidth)(Flow(UInt(pregSz bits))))

    // Pregs returned by the ROB.
    val despec      = in(Vec.fill(deallocWidth)(Flow(UInt(pregSz bits))))
    val dealloc     = in(Vec.fill(deallocWidth)(Flow(UInt(pregSz bits))))

    // Branch info for starting new allocation lists.
    val ren_br_tags   = in(Vec.fill(allocWidth+1)(Flow(UInt(brTagSz bits))))

    // Mispredict info for recovering speculatively allocated registers.
    val brupdate        = in(new BrUpdateInfo)

    val rollback      = in Bool()

    val debug_freelist = out UInt(numPregs bits)
  }

  // The free list register array and its branch allocation lists.
  val free_list = Reg(UInt(numPregs bits)) init (io.initial_allocation)
  val spec_alloc_list = Reg(UInt(numPregs bits)) init 0
  val br_alloc_lists = Reg(Vec.fill(maxBrCount)(UInt(numPregs bits)))

  // Select pregs from the free list.
  val sels = SelectFirstN(free_list, allocWidth)
  val sel_fire  = Vec.fill(allocWidth)(Bool())

  // Allocations seen by branches in each pipeline slot.
  val allocs = io.alloc_pregs map (a => UIntToOH(a.payload, n))
  val alloc_masks = (allocs zip io.reqs).scanRight(U(0, n bits)) { case ((a,r),m) => m | a & Fill(n,r) }

  // Masks that modify the freelist array.
  val sel_mask = (sels zip sel_fire) map { case (s,f) => s & Fill(n,f) } reduce(_|_)

  val br_deallocs = br_alloc_lists.at(io.brupdate.b2.uop.br_tag) & Fill(n, io.brupdate.b2.mispredict)
  val com_deallocs = FixedPipeline.delay(io.dealloc, 1).map(d => UIntToOH(d.payload, n) & Fill(n,d.valid)).reduce(_|_)
  val rollback_deallocs = spec_alloc_list & Fill(n, io.rollback)
  val dealloc_mask = com_deallocs | br_deallocs | rollback_deallocs

  val com_despec = io.despec.map(d => UIntToOH(d.payload, n) & Fill(n,d.valid)).reduce(_|_)

  // Update branch snapshots
  for (i <- 0 until maxBrCount) {
    val updated_br_alloc_list = if (isImm) {
      // Immediates clear the busy table when they read, potentially before older branches resolve.
      // Thus the branch alloc lists must be updated as well
      br_alloc_lists(i) & ~br_deallocs & ~com_deallocs | alloc_masks(0)
    } else {
      br_alloc_lists(i) & ~br_deallocs | alloc_masks(0)
    }
    br_alloc_lists(i) := updated_br_alloc_list
  }
  assert(PopCount(io.ren_br_tags.map(_.valid)) <= 1)
  val do_br_snapshot = io.ren_br_tags.map(_.valid).reduce(_||_)
  val br_snapshot_tag   = Mux1H(io.ren_br_tags.map(_.valid), io.ren_br_tags.map(_.payload))
  val br_snapshot_list  = Mux1H(io.ren_br_tags.map(_.valid), alloc_masks)
  when (do_br_snapshot) {
    br_alloc_lists.write(br_snapshot_tag)(_ := br_snapshot_list)
  }

  spec_alloc_list := (spec_alloc_list | alloc_masks(0)) & ~dealloc_mask & ~com_despec

  // Update the free list.
  free_list     := (free_list & ~sel_mask) | dealloc_mask

  // Pipeline logic | hookup outputs.
  for (w <- 0 until allocWidth) {
    val can_sel = sels(w).orR
    val r_valid = Reg(Bool()) init False
    val r_sel   = RegNextWhen(OHToUInt(sels(w)), sel_fire(w))

    r_valid := r_valid && !io.reqs(w) || can_sel
    sel_fire(w) := (!r_valid || io.reqs(w)) && can_sel

    io.alloc_pregs(w).payload := r_sel
    io.alloc_pregs(w).valid   := r_valid
  }

  io.debug_freelist := free_list | io.alloc_pregs.map(p => UIntToOH(p.payload, n) & Fill(n,p.valid)).reduce(_|_)

  if (!isImm)
    assert (!(io.debug_freelist & dealloc_mask).orR, "[freelist] Returning a free physical register.")
}
