//******************************************************************************
// Copyright (c) 2018 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Fetch Buffer
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Takes a FetchBundle and converts into a vector of MicroOps.

package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

/**
 * Bundle that is made up of converted MicroOps from the Fetch Bundle
 * input to the Fetch Buffer. This is handed to the Decode stage.
 */
class FetchBufferResp(implicit p: Parameters) extends BoomBundle
{
  val uops = Vec.fill(coreWidth)(Flow(new MicroOp()))
}

/**
 * Buffer to hold fetched packets and convert them into a vector of MicroOps
 * to give the Decode stage
 */
class FetchBuffer(implicit p: Parameters) extends BoomModule
  with HasBoomCoreParameters
  with HasBoomFrontendParameters
{
  val numEntries = numFetchBufferEntries
  val io = new Bundle {
    val enq = slave(Stream(new FetchBundle()))
    val deq = master(Stream(new FetchBufferResp()))

    // Was the pipeline redirected? Clear/reset the fetchbuffer.
    val clear = in Bool()
  }

  require (numEntries > fetchWidth)
  require (numEntries % coreWidth == 0)
  val numRows = numEntries / coreWidth

  val ram = Reg(Vec.fill(numEntries)(new MicroOp))
  ram.setName("fb_uop_ram")
  val deq_vec = Vec.fill(numRows)(Vec.fill(coreWidth)(new MicroOp))

  val head = Reg(UInt(numRows bits)) init 1
  val tail = Reg(UInt(numEntries bits)) init 1

  val maybe_full = Reg(Bool()) init False

  //-------------------------------------------------------------
  // **** Enqueue Uops ****
  //-------------------------------------------------------------
  // Step 1: Convert FetchPacket into a vector of MicroOps.
  // Step 2: Generate one-hot write indices.
  // Step 3: Write MicroOps into the RAM.

  def rotateLeft(in: UInt, k: Int): UInt = {
    val n = in.getWidth
    Cat(in(n-k-1 downto 0), in(n-1 downto n-k)).asUInt
  }

  val might_hit_head = (1 until fetchWidth).map(k => rotateLeft(tail, k).asBools.zipWithIndex.filter
    {case (e,i) => i % coreWidth == 0}.map {case (e,i) => e}.toSeq.asUInt).map(tail => head & tail).reduce(_|_).orR
  val at_head = (tail.asBools.zipWithIndex.filter {case (e,i) => i % coreWidth == 0}
    .map {case (e,i) => e}.toSeq.asUInt & head).orR
  val do_enq = !(at_head && maybe_full || might_hit_head)

  io.enq.ready := do_enq

  // Input microops.
  val in_mask = Vec.fill(fetchWidth)(Bool())
  val in_uops = Vec.fill(fetchWidth)(new MicroOp())

  // Step 1: Convert FetchPacket into a vector of MicroOps.
  for (b <- 0 until nBanks) {
    for (w <- 0 until bankWidth) {
      val i = (b * bankWidth) + w

      val pc = (bankAlign(io.enq.payload.pc) + (i << 1))

      in_uops(i).assignDontCare()
      in_mask(i)                := io.enq.valid && io.enq.payload.mask(i)
      in_uops(i).edge_inst      := False
      in_uops(i).debug_pc       := pc.resized
      in_uops(i).pc_lob         := pc.resized

      in_uops(i).is_sfb         := io.enq.payload.sfbs(i) || io.enq.payload.shadowed_mask(i)

      if (w == 0) {
        when (io.enq.payload.edge_inst(b)) {
          in_uops(i).debug_pc  := (bankAlign(io.enq.payload.pc) + (b * bankBytes) - 2).resized
          in_uops(i).pc_lob    := (bankAlign(io.enq.payload.pc) + (b * bankBytes)).resized
          in_uops(i).edge_inst := True
        }
      }
      in_uops(i).ftq_idx        := io.enq.payload.ftq_idx
      in_uops(i).inst           := io.enq.payload.exp_insts(i)
      in_uops(i).debug_inst     := io.enq.payload.insts(i)
      in_uops(i).is_rvc         := io.enq.payload.insts(i)(1 downto 0) =/= 3
      in_uops(i).taken          := io.enq.payload.cfi_idx.payload === i && io.enq.payload.cfi_idx.valid

      in_uops(i).xcpt_pf_if     := io.enq.payload.xcpt_pf_if
      in_uops(i).fetch_pmp_ae := io.enq.payload.pmp_ae_if(i) && !io.enq.payload.xcpt_ae_if
      in_uops(i).fetch_pmp_ae_second := !io.enq.payload.pmp_ae_first(i)
      in_uops(i).xcpt_ae_if     := io.enq.payload.xcpt_ae_if || io.enq.payload.pmp_ae_if(i)
      in_uops(i).bp_debug_if    := io.enq.payload.bp_debug_if_oh(i)
      in_uops(i).bp_xcpt_if     := io.enq.payload.bp_xcpt_if_oh(i)

      in_uops(i).debug_fsrc     := io.enq.payload.fsrc
    }
  }

  // Step 2. Generate one-hot write indices.
  val enq_idxs = Vec.fill(fetchWidth)(UInt(numEntries bits))

  def inc(ptr: UInt): UInt = {
    val n = ptr.getWidth
    Cat(ptr(n-2 downto 0), ptr(n-1)).asUInt
  }

  var enq_idx = tail
  for (i <- 0 until fetchWidth) {
    enq_idxs(i) := enq_idx
    enq_idx = Mux(in_mask(i), inc(enq_idx), enq_idx)
  }

  // Step 3: Write MicroOps into the RAM.
  for (i <- 0 until fetchWidth) {
    for (j <- 0 until numEntries) {
      when (do_enq && in_mask(i) && enq_idxs(i)(j)) {
        ram(j) := in_uops(i)
      }
    }
  }

  //-------------------------------------------------------------
  // **** Dequeue Uops ****
  //-------------------------------------------------------------

  val tail_collisions = (0 until numEntries).map(i =>
                          head(i/coreWidth) && (!maybe_full || Bool(i % coreWidth != 0))).asUInt & tail
  val slot_will_hit_tail = (0 until numRows).map(i => tail_collisions((i+1)*coreWidth-1 downto i*coreWidth)).reduce(_|_)
  val will_hit_tail = slot_will_hit_tail.orR

  val do_deq = io.deq.ready && !will_hit_tail

  val deq_valids = (~MaskUpper(slot_will_hit_tail)).asBools

  // Generate vec for dequeue read port.
  for (i <- 0 until numEntries) {
    deq_vec(i/coreWidth)(i%coreWidth) := ram(i)
  }

  io.deq.payload.uops zip deq_valids           map {case (d,v) => d.valid := v}
  io.deq.payload.uops zip Mux1H(head, deq_vec) map {case (d,q) => d.payload  := q}
  io.deq.valid := deq_valids.reduce(_||_)

  //-------------------------------------------------------------
  // **** Update State ****
  //-------------------------------------------------------------

  when (do_enq) {
    tail := enq_idx
    when (in_mask.reduce(_||_)) {
      maybe_full := True
    }
  }

  when (do_deq) {
    head := inc(head)
    maybe_full := False
  }

  when (io.clear) {
    head := 1
    tail := 1
    maybe_full := False
  }

  // TODO Is this necessary?
  when (ClockDomain.current.isResetActive) {
    io.deq.payload.uops foreach { u => u.valid := False }
  }
}
