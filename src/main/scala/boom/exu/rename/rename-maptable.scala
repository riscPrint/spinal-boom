//******************************************************************************
// Copyright (c) 2015 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Rename Map Table
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import spinal.core._
import spinal.lib._
import boom.common._
import boom.util._
import rocketchip.config.Parameters
import rocketchip.util._

case class MapReq(lregSz: Int) extends Bundle
{
  val lrs1 = UInt(lregSz bits)
  val lrs2 = UInt(lregSz bits)
  val lrs3 = UInt(lregSz bits)
  val ldst = UInt(lregSz bits)
}

case class MapResp(pregSz: Int) extends Bundle
{
  val prs1 = UInt(pregSz bits)
  val prs2 = UInt(pregSz bits)
  val prs3 = UInt(pregSz bits)
  val stale_pdst = UInt(pregSz bits)
}

case class RemapReq(lregSz: Int, pregSz: Int) extends Bundle
{
  val ldst = UInt(lregSz bits)
  val pdst = UInt(pregSz bits)
  val valid = Bool()
}

class RenameMapTable(
  val plWidth: Int,
  val numLregs: Int,
  val numPregs: Int,
  val bypass: Boolean,
  val float: Boolean)
  (implicit p: Parameters) extends BoomModule
{
  val pregSz = log2Ceil(numPregs)

  val io = new Bundle {
    // Logical sources -> physical sources.
    val map_reqs    = in(Vec.fill(plWidth)(MapReq(lregSz)))
    val map_resps   = out(Vec.fill(plWidth)(MapResp(pregSz)))

    // Remapping an ldst to a newly allocated pdst?
    val remap_reqs  = in(Vec.fill(plWidth)(RemapReq(lregSz, pregSz)))
    val com_remap_reqs = in(Vec.fill(plWidth)(RemapReq(lregSz, pregSz)))

    // Dispatching branches: need to take snapshots of table state.
    val ren_br_tags = in(Vec.fill(plWidth+1)(Flow(UInt(brTagSz bits))))

    // Signals for restoring state following misspeculation.
    val brupdate      = in(new BrUpdateInfo)
    val rollback    = in Bool()
  }

  // The map table register array and its branch snapshots.
  val map_table = RegInit(Vec((0 until numLregs) map { i => U(i, pregSz bits) }))
  val com_map_table = RegInit(Vec((0 until numLregs) map { i => U(i, pregSz bits) }))
  val br_snapshots = Reg(Vec.fill(maxBrCount)(Vec.fill(numLregs)(UInt(pregSz bits))))

  // The intermediate states of the map table following modification by each pipeline slot.
  val remap_table = Vec.fill(plWidth+1)(Vec.fill(numLregs)(UInt(pregSz bits)))
  val com_remap_table = Vec.fill(plWidth+1)(Vec.fill(numLregs)(UInt(pregSz bits)))

  // Uops requesting changes to the map table.
  val remap_pdsts = io.remap_reqs map (_.pdst)
  val remap_ldsts_oh = io.remap_reqs map (req => UIntToOH(req.ldst, numLregs) & Fill(numLregs, req.valid))

  val com_remap_pdsts = io.com_remap_reqs map (_.pdst)
  val com_remap_ldsts_oh = io.com_remap_reqs map (req => UIntToOH(req.ldst, numLregs) & Fill(numLregs, req.valid))

  // Figure out the new mappings seen by each pipeline slot.
  for (i <- 0 until numLregs) {
    val remapped_row = (remap_ldsts_oh.map(ldst => ldst(i)) zip remap_pdsts)
      .scanLeft(map_table(i)) {case (pdst, (ldst, new_pdst)) => Mux(ldst, new_pdst, pdst)}

    val com_remapped_row = (com_remap_ldsts_oh.map(ldst => ldst(i)) zip com_remap_pdsts)
      .scanLeft(com_map_table(i)) {case (pdst, (ldst, new_pdst)) => Mux(ldst, new_pdst, pdst)}

    for (j <- 0 until plWidth+1) {
      remap_table(j)(i) := remapped_row(j)
      com_remap_table(j)(i) := com_remapped_row(j)
    }
  }

  // Create snapshots of new mappings.
  assert(PopCount(io.ren_br_tags.map(_.valid)) <= 1)
  val do_br_snapshot = io.ren_br_tags.map(_.valid).reduce(_||_)
  val br_snapshot_tag   = Mux1H(io.ren_br_tags.map(_.valid), io.ren_br_tags.map(_.payload))
  val br_snapshot_table = Mux1H(io.ren_br_tags.map(_.valid), remap_table)
  when (do_br_snapshot) {
    br_snapshots.write(br_snapshot_tag)(_ := br_snapshot_table)
  }

  when (io.brupdate.b2.mispredict) {
    // Restore the map table to a branch snapshot.
    map_table := br_snapshots.at(io.brupdate.b2.uop.br_tag)
  } elsewhen (io.rollback) {
    map_table := com_map_table
  } otherwise {
    // Update mappings.
    map_table := remap_table(plWidth)
  }
  com_map_table := com_remap_table(plWidth)

  // Read out mappings.
  for (i <- 0 until plWidth) {
    io.map_resps(i).prs1       := (0 until i).foldLeft(map_table.at(io.map_reqs(i).lrs1)) ((p,k) =>
      Mux(Bool(bypass) && io.remap_reqs(k).valid && io.remap_reqs(k).ldst === io.map_reqs(i).lrs1, io.remap_reqs(k).pdst, p))
    io.map_resps(i).prs2       := (0 until i).foldLeft(map_table.at(io.map_reqs(i).lrs2)) ((p,k) =>
      Mux(Bool(bypass) && io.remap_reqs(k).valid && io.remap_reqs(k).ldst === io.map_reqs(i).lrs2, io.remap_reqs(k).pdst, p))
    io.map_resps(i).prs3       := (0 until i).foldLeft(map_table.at(io.map_reqs(i).lrs3)) ((p,k) =>
      Mux(Bool(bypass) && io.remap_reqs(k).valid && io.remap_reqs(k).ldst === io.map_reqs(i).lrs3, io.remap_reqs(k).pdst, p))
    io.map_resps(i).stale_pdst := (0 until i).foldLeft(map_table.at(io.map_reqs(i).ldst)) ((p,k) =>
      Mux(Bool(bypass) && io.remap_reqs(k).valid && io.remap_reqs(k).ldst === io.map_reqs(i).ldst, io.remap_reqs(k).pdst, p))

    if (!float) io.map_resps(i).prs3.assignDontCare()
  }

  // Don't flag the creation of duplicate 'p0' mappings during rollback.
  // These cases may occur soon after reset, as all maptable entries are initialized to 'p0'.
  io.remap_reqs map (req => (req.pdst, req.valid)) foreach {case (p,r) =>
    assert (!r || !map_table.map(_ === p).reduce(_||_), "[maptable] Trying to write a duplicate mapping.")}
}
