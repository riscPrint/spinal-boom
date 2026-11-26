//******************************************************************************
// Copyright (c) 2015 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Fetch Target Queue (FTQ)
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Each entry in the FTQ holds the fetch address and branch prediction snapshot state.
//
// TODO:
// * reduce port counts.

package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.exu._
import boom.util._

/**
 * FTQ Parameters used in configurations
 *
 * @param nEntries # of entries in the FTQ
 */
case class FtqParameters(
  nEntries: Int = 16
)

/**
 * Bundle to add to the FTQ RAM and to be used as the pass in IO
 */
class FTQBundle(implicit p: Parameters) extends BoomBundle
  with HasBoomFrontendParameters
{
  // IDX of instruction that was predicted taken, if any
  val cfi_idx   = Flow(UInt(log2Ceil(fetchWidth) bits))
  // Was the CFI in this bundle found to be taken? or not
  val cfi_taken = Bool()
  // Was this CFI mispredicted by the branch prediction pipeline?
  val cfi_mispredicted = Bool()
  // What type of CFI was taken out of this bundle
  val cfi_type = UInt(CFI_SZ bits)
  // mask of branches which were visible in this fetch bundle
  val br_mask   = UInt(fetchWidth bits)
  // This CFI is likely a CALL
  val cfi_is_call   = Bool()
  // This CFI is likely a RET
  val cfi_is_ret    = Bool()
  // Is the NPC after the CFI +4 or +2
  val cfi_npc_plus4 = Bool()
  // What was the top of the RAS that this bundle saw?
  val ras_top = UInt(vaddrBitsExtended bits)
  val ras_idx = UInt(log2Ceil(nRasEntries) bits)

  // Which bank did this start from?
  val start_bank = UInt(1 bits)
}

class FTQInfo(implicit p: Parameters) extends BoomBundle
{
  val valid     = Bool()
  val entry     = new FTQBundle
  val ghist     = new GlobalHistory
  val pc        = UInt(vaddrBitsExtended bits)
}

/**
 * Queue to store the fetch PC and other relevant branch predictor signals that are inflight in the
 * processor.
 */
class FetchTargetQueue(implicit p: Parameters) extends BoomModule
  with HasBoomCoreParameters
  with HasBoomFrontendParameters
{
  val num_entries = ftqSz
  private val idx_sz = log2Ceil(num_entries)

  val io = new Bundle {
    // Enqueue one entry for every fetch cycle.
    val enq = slave(Stream(new FetchBundle()))
    // Pass to FetchBuffer (newly fetched instructions).
    val enq_idx = out UInt(idx_sz bits)
    // ROB tells us the youngest committed ftq_idx to remove from FTQ.
    val deq = slave(Flow(UInt(idx_sz bits)))

    // Give PC info to BranchUnit.
    val arb_ftq_reqs = in(Vec.fill(3)(UInt(log2Ceil(ftqSz) bits)))
    val rrd_ftq_resps = out(Vec.fill(3)(new FTQInfo))

    val com_pc    = out UInt(vaddrBitsExtended bits)

    // Used to regenerate PC for trace port stuff in FireSim
    // Don't tape this out, this blows up the FTQ
    val debug_ftq_idx  = in(Vec.fill(coreWidth)(UInt(log2Ceil(ftqSz) bits)))
    val debug_fetch_pc = out(Vec.fill(coreWidth)(UInt(vaddrBitsExtended bits)))

    val redirect = slave(Flow(UInt(idx_sz bits)))

    val brupdate = in(new BrUpdateInfo)

    val bpdupdate = master(Flow(new BranchPredictionUpdate))

    val ras_update = out Bool()
    val ras_update_idx = out UInt(log2Ceil(nRasEntries) bits)
    val ras_update_pc  = out UInt(vaddrBitsExtended bits)
  }
  val bpd_ptr    = Reg(UInt(idx_sz bits)) init 0
  val deq_ptr    = Reg(UInt(idx_sz bits)) init 0
  val enq_ptr    = Reg(UInt(idx_sz bits)) init 1

  val full = ((WrapInc(WrapInc(enq_ptr, num_entries), num_entries) === bpd_ptr) ||
              (WrapInc(enq_ptr, num_entries) === bpd_ptr))

  val pcs      = Reg(Vec.fill(num_entries)(UInt(vaddrBitsExtended bits)))
  val meta     = new SyncReadMemC(num_entries, Vec.fill(nBanks)(UInt(bpdMaxMetaLength bits)))
  val ram      = Reg(Vec.fill(num_entries)(new FTQBundle))
  val ghist    = Seq.fill(2) { new SyncReadMemC(num_entries, new GlobalHistory) }

  val do_enq = io.enq.fire

  // This register lets us initialize the ghist to 0
  val prev_ghist = Reg(new GlobalHistory) init (new GlobalHistory().getZero)
  val prev_entry = Reg(new FTQBundle) init (new FTQBundle().getZero)
  val prev_pc    = Reg(UInt(vaddrBitsExtended bits)) init 0

  val new_entry = new FTQBundle
  new_entry.cfi_idx   := io.enq.payload.cfi_idx
  // Initially, if we see a CFI, it is assumed to be taken.
  // Branch resolutions may change this
  new_entry.cfi_taken     := io.enq.payload.cfi_idx.valid
  new_entry.cfi_mispredicted := False
  new_entry.cfi_type      := io.enq.payload.cfi_type
  new_entry.cfi_is_call   := io.enq.payload.cfi_is_call
  new_entry.cfi_is_ret    := io.enq.payload.cfi_is_ret
  new_entry.cfi_npc_plus4 := io.enq.payload.cfi_npc_plus4
  new_entry.ras_top       := io.enq.payload.ras_top
  new_entry.ras_idx       := io.enq.payload.ghist.ras_idx
  new_entry.br_mask       := io.enq.payload.br_mask & io.enq.payload.mask
  new_entry.start_bank    := bank(io.enq.payload.pc)

  val new_ghist = Mux(io.enq.payload.ghist.current_saw_branch_not_taken,
    io.enq.payload.ghist,
    prev_ghist.update(
      prev_entry.br_mask,
      prev_entry.cfi_taken,
      prev_entry.br_mask(prev_entry.cfi_idx.payload),
      prev_entry.cfi_idx.payload,
      prev_entry.cfi_idx.valid,
      prev_pc,
      prev_entry.cfi_is_call,
      prev_entry.cfi_is_ret
    )
  )

  ghist.foreach(g => g.write(enq_ptr, new_ghist, do_enq))
  meta.write(enq_ptr, io.enq.payload.bpd_meta, do_enq)

  when (do_enq) {
    pcs(enq_ptr)           := io.enq.payload.pc
    ram(enq_ptr) := new_entry

    prev_pc    := io.enq.payload.pc
    prev_entry := new_entry
    prev_ghist := new_ghist

    enq_ptr := WrapInc(enq_ptr, num_entries)
  }

  io.enq_idx := enq_ptr

  io.bpdupdate.valid := False
  io.bpdupdate.payload.assignDontCare()

  when (io.deq.valid) {
    deq_ptr := io.deq.payload
  }

  // This register avoids a spurious bpd update on the first fetch packet
  val first_empty = Reg(Bool()) init True

  // We can update the branch predictors when we know the target of the
  // CFI in this fetch bundle

  val ras_update = CombInit(False)
  val ras_update_pc = CombInit(U(0, vaddrBitsExtended bits))
  val ras_update_idx = CombInit(U(0, log2Ceil(nRasEntries) bits))
  // RAS update transaction: one unconditional stage, valid uninitialised.
  val rasUpdateIn = new Bundle {
    val pc  = UInt(vaddrBitsExtended bits)
    val idx = UInt(log2Ceil(nRasEntries) bits)
  }
  rasUpdateIn.pc  := ras_update_pc
  rasUpdateIn.idx := ras_update_idx
  val rasUpdate = FixedPipeline(ras_update, rasUpdateIn, 1, holdPayload = false, resetValid = false)
  io.ras_update     := rasUpdate.valid
  io.ras_update_pc  := rasUpdate.payload.pc
  io.ras_update_idx := rasUpdate.payload.idx

  val bpd_update_mispredict = Reg(Bool()) init False
  val bpd_update_repair = Reg(Bool()) init False
  val bpd_repair_idx = Reg(UInt(log2Ceil(ftqSz) bits))
  val bpd_end_idx = Reg(UInt(log2Ceil(ftqSz) bits))
  val bpd_repair_pc = Reg(UInt(vaddrBitsExtended bits))

  val bpd_idx = Mux(io.redirect.valid, io.redirect.payload,
    Mux(bpd_update_repair || bpd_update_mispredict, bpd_repair_idx, bpd_ptr))
  val bpd_any = Bool()
  val bpdRead = new Bundle {
    val entry = new FTQBundle
    val pc = UInt(vaddrBitsExtended bits)
    val target = UInt(vaddrBitsExtended bits)
  }
  bpdRead.entry := ram(bpd_idx)
  bpdRead.pc := pcs(bpd_idx)
  bpdRead.target := pcs(WrapInc(bpd_idx, num_entries))
  val bpdRd = FixedPipeline(bpd_any, bpdRead, 1, holdPayload = false, resetValid = false)
  val bpd_entry = bpdRd.payload.entry
  val bpd_ghist = ghist(0).read(bpd_idx, True)
  val bpd_meta  = meta.read(bpd_idx, True) // TODO fix these SRAMs
  val bpd_pc    = bpdRd.payload.pc
  val bpd_target = bpdRd.payload.target

  // Repair FSM inputs, each one unconditional data-only stage. (The originals
  // were RegNexts declared inside the FSM branches, hence free-running.)
  val delayed_redirect = FixedPipeline.delay(io.redirect.valid, 1)
  val delayed_b2_mispredict = FixedPipeline.delay(io.brupdate.b2.mispredict, 1)
  val delayed_b2_ftq_idx = FixedPipeline.delay(io.brupdate.b2.uop.ftq_idx, 1)
  val delayed_enq_ptr = FixedPipeline.delay(enq_ptr, 1)
  val delayed_bpd_update_mispredict = FixedPipeline.delay(bpd_update_mispredict, 1)
  when (io.redirect.valid) {
    bpd_update_mispredict := False
    bpd_update_repair     := False
  } elsewhen (delayed_b2_mispredict) {
    bpd_update_mispredict := True
    bpd_repair_idx        := delayed_b2_ftq_idx
    bpd_end_idx           := delayed_enq_ptr
  } elsewhen (bpd_update_mispredict) {
    bpd_update_mispredict := False
    bpd_update_repair     := True
    bpd_repair_idx        := WrapInc(bpd_repair_idx, num_entries)
  } elsewhen (bpd_update_repair && delayed_bpd_update_mispredict) {
    bpd_repair_pc         := bpd_pc
    bpd_repair_idx        := WrapInc(bpd_repair_idx, num_entries)
  } elsewhen (bpd_update_repair) {
    bpd_repair_idx        := WrapInc(bpd_repair_idx, num_entries)
    when (WrapInc(bpd_repair_idx, num_entries) === bpd_end_idx ||
      bpd_pc === bpd_repair_pc)  {
      bpd_update_repair := False
    }
  }

  val do_commit_update     = (!bpd_update_mispredict &&
                              !bpd_update_repair &&
                               bpd_ptr =/= deq_ptr &&
                               enq_ptr =/= WrapInc(bpd_ptr, num_entries) &&
                              !io.brupdate.b2.mispredict &&
                              !io.redirect.valid && !delayed_redirect)
  val do_mispredict_update = bpd_update_mispredict
  val do_repair_update     = bpd_update_repair

  bpd_any := do_commit_update || do_repair_update || do_mispredict_update
  // Update-kind qualifiers, delayed with the predictor-update read.
  val bpd_was_repair = FixedPipeline.delay(do_repair_update, 1)
  val bpd_was_mispredict = FixedPipeline.delay(do_mispredict_update, 1)
  when (bpdRd.valid) {
    val cfi_idx = bpd_entry.cfi_idx.payload
    val valid_repair = bpd_pc =/= bpd_repair_pc

    io.bpdupdate.valid := (!first_empty &&
                           (bpd_entry.cfi_idx.valid || bpd_entry.br_mask =/= 0) &&
                           !(bpd_was_repair && !valid_repair))
    io.bpdupdate.payload.is_mispredict_update := bpd_was_mispredict
    io.bpdupdate.payload.is_repair_update     := bpd_was_repair
    io.bpdupdate.payload.pc      := bpd_pc
    io.bpdupdate.payload.btb_mispredicts := 0
    io.bpdupdate.payload.br_mask := Mux(bpd_entry.cfi_idx.valid,
      MaskLower(UIntToOH(cfi_idx, fetchWidth)) & bpd_entry.br_mask, bpd_entry.br_mask)
    io.bpdupdate.payload.cfi_idx := bpd_entry.cfi_idx
    io.bpdupdate.payload.cfi_mispredicted := bpd_entry.cfi_mispredicted
    io.bpdupdate.payload.cfi_taken  := bpd_entry.cfi_taken
    io.bpdupdate.payload.target     := bpd_target
    io.bpdupdate.payload.cfi_is_br  := bpd_entry.br_mask(cfi_idx)
    io.bpdupdate.payload.cfi_is_jal := bpd_entry.cfi_type === CFI_JAL.U || bpd_entry.cfi_type === CFI_JALR.U
    io.bpdupdate.payload.ghist      := bpd_ghist
    io.bpdupdate.payload.meta       := bpd_meta

    first_empty := False
  }

  when (do_commit_update) {
    bpd_ptr := WrapInc(bpd_ptr, num_entries)
  }

  io.enq.ready := FixedPipeline.delay(!full || do_commit_update, 1)

  val redirect_idx = io.redirect.payload
  val redirect_entry = ram(redirect_idx)
  val redirect_new_entry = CombInit(redirect_entry)

  // Redirect write-back staging: one unconditional data-only stage.
  val redirect_wb_entry = FixedPipeline.delay(redirect_new_entry, 1)
  val redirect_wb_idx = FixedPipeline.delay(io.redirect.payload, 1)
  when (io.redirect.valid) {
    enq_ptr    := WrapInc(io.redirect.payload, num_entries)

    when (io.brupdate.b2.mispredict) {
      val new_cfi_idx = (io.brupdate.b2.uop.pc_lob ^
        Mux(redirect_entry.start_bank === 1, U(1 << log2Ceil(bankBytes), io.brupdate.b2.uop.pc_lob.getWidth bits), U(0, io.brupdate.b2.uop.pc_lob.getWidth bits)))(log2Ceil(fetchWidth) downto 1)
      redirect_new_entry.cfi_idx.valid    := True
      redirect_new_entry.cfi_idx.payload  := new_cfi_idx
      redirect_new_entry.cfi_mispredicted := True
      redirect_new_entry.cfi_taken        := io.brupdate.b2.taken
      redirect_new_entry.cfi_is_call      := redirect_entry.cfi_is_call && redirect_entry.cfi_idx.payload === new_cfi_idx
      redirect_new_entry.cfi_is_ret       := redirect_entry.cfi_is_ret  && redirect_entry.cfi_idx.payload === new_cfi_idx
    }

    ras_update     := True
    ras_update_pc  := redirect_entry.ras_top
    ras_update_idx := redirect_entry.ras_idx

  } elsewhen (delayed_redirect) {
    prev_entry := redirect_wb_entry
    prev_ghist := bpd_ghist
    prev_pc    := bpd_pc

    ram(redirect_wb_idx) := redirect_wb_entry
  }

  //-------------------------------------------------------------
  // **** Core Read PCs ****
  //-------------------------------------------------------------

  for (i <- 0 until 3) {
    val idx = Mux(ClockDomain.current.isResetActive, U(0, log2Ceil(ftqSz) bits), io.arb_ftq_reqs(i))
    val is_enq = (idx === enq_ptr) && io.enq.fire
    val get_entry = ram(idx)

    // Read-port response: one unconditional stage, valid uninitialised.
    val rrdIn = new Bundle {
      val entry = new FTQBundle
      val pc = UInt(vaddrBitsExtended bits)
    }
    rrdIn.entry := get_entry
    rrdIn.pc := Mux(is_enq, io.enq.payload.pc, pcs(idx))
    val rrd = FixedPipeline(idx =/= enq_ptr || is_enq, rrdIn, 1, holdPayload = false, resetValid = false)
    io.rrd_ftq_resps(i).entry     := rrd.payload.entry
    if (i == 0) {
      io.rrd_ftq_resps(i).ghist   := ghist(1).read(idx, True)
    } else {
      io.rrd_ftq_resps(i).ghist.assignDontCare()
    }
    io.rrd_ftq_resps(i).pc        := rrd.payload.pc
    io.rrd_ftq_resps(i).valid     := rrd.valid
  }

  io.com_pc := FixedPipeline.delay(pcs(Mux(io.deq.valid, io.deq.payload, deq_ptr)), 1)

  for (w <- 0 until coreWidth) {
    io.debug_fetch_pc(w) := FixedPipeline.delay(pcs(io.debug_ftq_idx(w)), 1)
  }
}
