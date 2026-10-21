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

abstract class AbstractRenameFreeList(
  val allocWidth: Int,
  val deallocWidth: Int,
  val numPregs: Int)
  (implicit p: Parameters) extends BoomModule
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
}

class RenameFreeList(
  allocWidth: Int,
  deallocWidth: Int,
  numPregs: Int,
  isImm: Boolean
)(implicit p: Parameters)
    extends AbstractRenameFreeList(allocWidth, deallocWidth, numPregs)
{
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
  if (enableSuperscalarSnapshots) {
    val br_slots = io.ren_br_tags.map(tag => tag.valid).asUInt
    // Create branch allocation lists.
    for (i <- 0 until maxBrCount) {
      val list_req = io.ren_br_tags.map(tag => tag.payload === i).asUInt & br_slots
      val new_list = list_req.orR
      when (new_list) {
        br_alloc_lists(i) := Mux1H(list_req, alloc_masks)
      }
    }
  } else {
    assert(PopCount(io.ren_br_tags.map(_.valid)) <= 1)
    val do_br_snapshot = io.ren_br_tags.map(_.valid).reduce(_||_)
    val br_snapshot_tag   = Mux1H(io.ren_br_tags.map(_.valid), io.ren_br_tags.map(_.payload))
    val br_snapshot_list  = Mux1H(io.ren_br_tags.map(_.valid), alloc_masks)
    when (do_br_snapshot) {
      br_alloc_lists.write(br_snapshot_tag)(_ := br_snapshot_list)
    }
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

class BankedRenameFreeList(
  plWidth: Int,
  numPregs: Int
)(implicit p: Parameters)
    extends AbstractRenameFreeList(plWidth, plWidth, numPregs)
{
  def bankIdx(prs: UInt): UInt = prs(log2Ceil(plWidth)-1 downto 0)

  require(isPow2(plWidth))
  require(plWidth > 1)
  require(enableColumnALUWrites)
  require(numPregs % plWidth == 0)
  require(!enableSuperscalarSnapshots)

  val sel = Reg(UInt(plWidth bits)) init 1
  sel := RotateL1(sel)

  val freelists = (0 until plWidth).map { w =>
    val freelist = new RenameFreeList(1, plWidth, numPregs / plWidth, false)

    val initial = io.initial_allocation.asBools.zipWithIndex.filter(_._2 % plWidth == w).map(_._1)
    freelist.io.initial_allocation := initial.asUInt
    freelist.io.reqs(0) := False

    for (i <- 0 until plWidth) {
      freelist.io.despec(i).valid   := io.despec(i).valid && bankIdx(io.despec(i).payload) === w
      freelist.io.despec(i).payload := (io.despec(i).payload >> log2Ceil(plWidth)).resized

      freelist.io.dealloc(i).valid   := io.dealloc(i).valid && bankIdx(io.dealloc(i).payload) === w
      freelist.io.dealloc(i).payload := (io.dealloc(i).payload >> log2Ceil(plWidth)).resized
    }
    freelist.io.ren_br_tags(0).valid := False
    freelist.io.ren_br_tags(0).payload.assignDontCare()
    freelist.io.ren_br_tags(1).valid := False
    freelist.io.ren_br_tags(1).payload.assignDontCare()

    freelist.io.brupdate := io.brupdate
    freelist.io.rollback := io.rollback

    freelist
  }

  var older_than_branch: Bool = True
  for (w <- 0 until plWidth) {
    io.alloc_pregs(w).valid := False
    io.alloc_pregs(w).payload.assignDontCare()

    for (i <- 0 until plWidth) {
      when (sel((i + w) % plWidth)) {
        freelists(i).io.reqs(0) := io.reqs(w)
        io.alloc_pregs(w).valid   := freelists(i).io.alloc_pregs(0).valid
        io.alloc_pregs(w).payload := Cat(freelists(i).io.alloc_pregs(0).payload, U(i, log2Ceil(plWidth) bits)).asUInt.resized

        freelists(i).io.ren_br_tags(0).valid   := io.ren_br_tags.map(_.valid).reduce(_||_) && !older_than_branch
        freelists(i).io.ren_br_tags(0).payload := Mux1H(io.ren_br_tags.map(_.valid), io.ren_br_tags.map(_.payload))

        freelists(i).io.ren_br_tags(1).valid   := io.ren_br_tags.map(_.valid).reduce(_||_) &&  older_than_branch
        freelists(i).io.ren_br_tags(1).payload := Mux1H(io.ren_br_tags.map(_.valid), io.ren_br_tags.map(_.payload))
      }
    }
    older_than_branch = older_than_branch && !io.ren_br_tags(w+1).valid
  }

  io.debug_freelist := (0 until numPregs).map { w =>
    freelists(w % plWidth).io.debug_freelist(w >> log2Ceil(plWidth))
  }.asUInt
}
