//******************************************************************************
// Copyright (c) 2017 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Frontend
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.ifu

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline.{Builder, CtrlLink, Node, StageLink}
import spinal.lib.bus.tilelink
import rocketchip.config.Parameters
import rocketchip.rocket._
import rocketchip.util._

import boom.common._
import boom.exu.{BranchDecode, BrUpdateInfo}
import boom.util._

/**
 * Main Frontend module that connects the icache, TLB, fetch controller,
 * and branch prediction pipeline together.
 *
 * rocket-chip builds this as a LazyModule; here the I$'s TileLink master
 * port is `io.mem` and the reset vector is an input.
 *
 * @param icacheParams parameters for the icache
 */
class BoomFrontend(icParams: ICacheParams, staticIdForMetadataUseOnly: Int)(implicit p: Parameters) extends BoomModule
  with HasBoomFrontendParameters
{
  val io = new Bundle {
    val cpu = slave(new BoomFrontendIO())
    val ptw = master(new TLBPTWIO())
    val reset_vector = in UInt(paddrBits bits)
    val mem = master(tilelink.Bus(ICache.busParams(icParams)))
  }
  require(fetchWidth*coreInstBytes == icParams.fetchBytes)

  val bpd = new BranchPredictor
  bpd.io.f3_fire := False
  val ras = new BoomRAS

  val icache = new ICache(icParams, staticIdForMetadataUseOnly)
  io.mem << icache.io.mem
  icache.io.invalidate := io.cpu.flush_icache
  val tlb = new TLB(true, log2Ceil(fetchBytes), TLBConfig(nTLBSets, nTLBWays))
  io.ptw <> tlb.io.ptw
  io.cpu.perf.tlbMiss := io.ptw.req.fire
  io.cpu.perf.acquire := icache.io.perf.acquire

  // --------------------------------------------------------
  // **** NextPC Select (F0) ****
  //      Send request to ICache
  // --------------------------------------------------------

  val s0_vpc       = UInt(vaddrBitsExtended bits)
  s0_vpc := 0
  val s0_ghist     = new GlobalHistory
  s0_ghist.assignFromBits(B(0, s0_ghist.getBitsWidth bits))
  val s0_tsrc      = UInt(BSRC_SZ bits)
  s0_tsrc := 0
  val s0_valid     = CombInit(False)
  val s0_is_replay = CombInit(False)
  val s0_is_sfence = CombInit(False)
  val s0_replay_resp = new TLBResp(log2Ceil(fetchBytes))
  val s0_replay_ppc  = UInt(paddrBits bits)

  icache.io.req.valid     := s0_valid
  icache.io.req.payload.addr := s0_vpc.resized

  bpd.io.f0_req.valid      := s0_valid && io.cpu.enable_bpd
  bpd.io.f0_req.payload.pc    := Mux(io.cpu.enable_bpd, s0_vpc, U(0, vaddrBitsExtended bits))
  bpd.io.f0_req.payload.ghist := s0_ghist
  when (!io.cpu.enable_bpd) {
    bpd.io.f0_req.payload.ghist.assignFromBits(B(0, s0_ghist.getBitsWidth bits))
  }

  // --------------------------------------------------------
  // **** ICache Access (F1) ****
  //      Translate VPC
  // --------------------------------------------------------
  // F3 pressure reissues at F0; no ready path crosses these boundaries.
  val f0, f1, f1Out, f2 = Node()
  val f0f1 = StageLink(f0, f1)
  val f1c = CtrlLink(f1, f1Out)
  val f1f2 = StageLink(f1Out, f2)
  f0.valid := s0_valid
  val VPC = f0.insert(s0_vpc)
  val GHIST = f0.insert(s0_ghist)
  val TSRC = f0.insert(s0_tsrc)
  val IS_REPLAY = f0.insert(s0_is_replay)
  val IS_SFENCE = f0.insert(s0_is_sfence)
  val REPLAY_RESP = f0.insert(s0_replay_resp)
  val REPLAY_PPC = f0.insert(s0_replay_ppc)
  val SFENCE = f0.insert(io.cpu.sfence)

  val s1_vpc       = f1(VPC)
  val s1_valid     = f1.isValid
  val s1_ghist     = f1(GHIST)
  val s1_is_replay = f1(IS_REPLAY)
  val s1_is_sfence = f1(IS_SFENCE)
  val f1_clear     = CombInit(False)
  val s1_tsrc      = f1(TSRC)
  tlb.io.req.valid      := (s1_valid && !s1_is_replay && !f1_clear) || s1_is_sfence
  tlb.io.req.payload.cmd.assignDontCare()
  tlb.io.req.payload.vaddr := s1_vpc
  tlb.io.req.payload.passthrough := False
  tlb.io.req.payload.size  := log2Ceil(coreInstBytes * fetchWidth)
  tlb.io.req.payload.prv   := io.ptw.status.prv
  tlb.io.req.payload.v     := io.ptw.status.v
  tlb.io.sfence         := f1(SFENCE)
  tlb.io.kill           := False

  val s1_tlb_miss = !s1_is_replay && tlb.io.resp.miss
  val s1_tlb_resp = Mux(s1_is_replay, f1(REPLAY_RESP), tlb.io.resp)
  val s1_ppc  = Mux(s1_is_replay, f1(REPLAY_PPC), tlb.io.resp.paddr)
  val s1_bpd_resp = bpd.io.resp.f1

  icache.io.s1_paddr := s1_ppc
  icache.io.s1_kill  := tlb.io.resp.miss || f1_clear

  val f1_mask = fetchMask(s1_vpc)
  val f1_redirects = (0 until fetchWidth) map { i =>
    f1_mask(i) && s1_bpd_resp.preds(i).predicted_pc.valid && s1_bpd_resp.preds(i).taken
  }
  val f1_do_redirect = f1_redirects.reduce(_||_) && Bool(useBPD)
  val f1_targs = s1_bpd_resp.preds.map(_.predicted_pc.payload)
  val f1_targ = if (nBanks == 1) {
    Mux1H(f1_redirects, f1_targs)
  } else {
    require(nBanks == 2)
    Mux(f1_redirects.take(bankWidth).reduce(_||_), Mux1H(f1_redirects.take(bankWidth), f1_targs.take(bankWidth)),
      Mux1H(f1_redirects.drop(bankWidth), f1_targs.drop(bankWidth)))
  }
  val f1_next_fetch = nextFetch(s1_vpc)
  val f1_predicted_target = Mux(f1_do_redirect,
                                f1_targ,
                                f1_next_fetch)

  val f1_predicted_ghist = s1_ghist.update(
    s1_bpd_resp.preds.map(p => p.is_br).asUInt & f1_mask.resize(fetchWidth),
    PriorityMux(f1_redirects, s1_bpd_resp.preds).taken && f1_do_redirect,
    PriorityMux(f1_redirects, s1_bpd_resp.preds).is_br,
    PriorityEncoder(f1_redirects),
    f1_do_redirect,
    s1_vpc,
    False,
    False)

  when (s1_valid) {
    // Stop fetching on fault
    s0_valid     := True
    s0_tsrc      := BSRC_1
    s0_vpc       := f1_predicted_target
    s0_ghist     := f1_predicted_ghist
    s0_is_replay := False
  }

  // --------------------------------------------------------
  // **** ICache Response (F2) ****
  // --------------------------------------------------------

  val PPC = f1.insert(s1_ppc)
  val TLB_RESP = f1.insert(s1_tlb_resp)
  val TLB_MISS = f1.insert(s1_tlb_miss)
  val NEXT_FETCH = f1.insert(f1_next_fetch)
  val F1_TARG = if (useSlowBTBRedirect) Some(f1.insert(f1_targ)) else None
  f1c.terminateWhen(f1_clear)
  // F1 comparisons must use f1(GHIST), before this next-value repair mux.
  val s2_ghist_next = f1c.bypass(GHIST)
  s2_ghist_next := s1_ghist

  val s2_valid = f2.isValid
  val s2_vpc   = f2(VPC)
  val s2_ghist = f2(GHIST)
  val s2_ppc  = f2(PPC)
  val s2_tsrc = f2(TSRC) // tsrc provides the predictor component which provided the prediction TO this instruction
  val s2_fsrc = UInt(BSRC_SZ bits) // fsrc provides the predictor component which provided the prediction FROM this instruction
  s2_fsrc := BSRC_1
  val f2_clear = CombInit(False)
  val s2_tlb_resp = f2(TLB_RESP)
  val s2_tlb_miss = f2(TLB_MISS)
  val s2_is_replay = f2(IS_REPLAY) && s2_valid
  val s2_xcpt = s2_valid && (s2_tlb_resp.ae.inst || s2_tlb_resp.pf.inst) && !s2_is_replay
  val f3_ready = Bool()

  icache.io.s2_kill := s2_xcpt
  icache.io.s2_prefetch := False

  val f2_bpd_resp = bpd.io.resp.f2
  val f2_fetch_mask = fetchMask(s2_vpc)
  val f2_redirects = (0 until fetchWidth) map { i =>
    f2_fetch_mask(i) && f2_bpd_resp.preds(i).predicted_pc.valid && f2_bpd_resp.preds(i).taken
  }
  val f2_targs = f2_bpd_resp.preds.map(_.predicted_pc.payload)
  val f2_do_redirect = f2_redirects.reduce(_||_) && Bool(useBPD)
  val f2_next_fetch = f2(NEXT_FETCH)
  val f2_predicted_target = Mux(f2_do_redirect,
                                if (useSlowBTBRedirect) f2(F1_TARG.get) else PriorityMux(f2_redirects, f2_targs),
                                f2_next_fetch)
  val f2_predicted_ghist = s2_ghist.update(
    f2_bpd_resp.preds.map(p => p.is_br && p.predicted_pc.valid).asUInt & f2_fetch_mask.resize(fetchWidth),
    PriorityMux(f2_redirects, f2_bpd_resp.preds).taken && f2_do_redirect,
    PriorityMux(f2_redirects, f2_bpd_resp.preds).is_br,
    PriorityEncoder(f2_redirects),
    f2_do_redirect,
    s2_vpc,
    False,
    False)

  val f2_aligned_pc = bankAlign(s2_vpc)
  val f2_bank_mask  = bankMask(s2_vpc)
  val f2_inst_mask  = Vec.fill(fetchWidth)(Bool())

  // Tracks trailing 16b of previous fetch packet
  val f2_prev_half = Reg(UInt(16 bits))
  // Tracks if last fetchpacket contained a half-inst
  val f2_prev_is_half = Reg(Bool()) init False

  val f2_fetch_bundle = new FetchBundle
  f2_fetch_bundle.assignDontCare()
  f2_fetch_bundle.pc         := s2_vpc
  f2_fetch_bundle.next_pc    := Mux(f2_do_redirect, PriorityMux(f2_redirects, f2_targs), f2_next_fetch)
  f2_fetch_bundle.next_fetch := f2_next_fetch
  f2_fetch_bundle.xcpt_pf_if := s2_tlb_resp.pf.inst
  f2_fetch_bundle.xcpt_ae_if := s2_tlb_resp.ae.inst
  f2_fetch_bundle.fsrc       := s2_fsrc
  f2_fetch_bundle.tsrc       := s2_tsrc
  f2_fetch_bundle.ghist      := s2_ghist
  f2_fetch_bundle.mask       := f2_inst_mask.asUInt
  f2_fetch_bundle.cfi_idx.valid   := f2_redirects.reduce(_||_)
  f2_fetch_bundle.cfi_idx.payload := PriorityEncoder(f2_redirects)

  require(fetchWidth >= 4) // Logic gets kind of annoying with fetchWidth = 2
  def isRVC(inst: UInt) = (inst(1 downto 0) =/= 3)
  var bank_prev_is_half: Bool = f2_prev_is_half
  var bank_prev_half: UInt    = f2_prev_half
  var last_inst: UInt = U(0, 16 bits)
  for (b <- 0 until nBanks) {
    f2_fetch_bundle.bpd_meta(b) := 0

    val bank_data  = icache.io.resp.payload.data((b+1)*bankWidth*16-1 downto b*bankWidth*16)
    for (w <- 0 until bankWidth) {
      val i = (b * bankWidth) + w
      val valid = Bool()
      f2_inst_mask(i) := s2_valid && f2_fetch_mask(i) && valid
      f2_fetch_bundle.pcs(i) := f2_aligned_pc + (i << 1) -
        ((f2_fetch_bundle.edge_inst(b) && Bool(w == 0)).asUInt << 1).resize(vaddrBitsExtended)
      if (w == 0) {
        valid := True
        // The RVC expanders are built outside the `when`s (Chisel builds them inside).
        val half_inst      = Cat(bank_data(15 downto 0), f2_prev_half).asUInt
        val half_inst_exp  = ExpandRVC(half_inst)
        val prev_inst      = Cat(bank_data(15 downto 0), last_inst).asUInt
        val prev_inst_exp  = if (b > 0) ExpandRVC(prev_inst) else null
        val whole_inst     = bank_data(31 downto 0)
        val whole_inst_exp = ExpandRVC(whole_inst)
        when (bank_prev_is_half) {
          f2_fetch_bundle.insts(i)     := half_inst
          f2_fetch_bundle.exp_insts(i) := half_inst_exp
          f2_fetch_bundle.edge_inst(b) := True
          if (b > 0) {
            when (f2_bank_mask(b-1)) {
              f2_fetch_bundle.insts(i)     := prev_inst
              f2_fetch_bundle.exp_insts(i) := prev_inst_exp
            }
          }
        } otherwise {
          f2_fetch_bundle.insts(i)     := whole_inst
          f2_fetch_bundle.exp_insts(i) := whole_inst_exp
          f2_fetch_bundle.edge_inst(b) := False
        }
      } else if (w == 1) {
        // Need special case since 0th instruction may carry over the wrap around
        val inst = bank_data(47 downto 16)
        f2_fetch_bundle.insts(i)     := inst
        f2_fetch_bundle.exp_insts(i) := ExpandRVC(inst)
        valid := bank_prev_is_half || !(f2_inst_mask(i-1) && !isRVC(f2_fetch_bundle.insts(i-1)))
      } else if (w == bankWidth - 1) {
        val inst = Cat(U(0, 16 bits), bank_data(bankWidth*16-1 downto (bankWidth-1)*16)).asUInt
        f2_fetch_bundle.insts(i)     := inst
        f2_fetch_bundle.exp_insts(i) := ExpandRVC(inst)
        valid := !((f2_inst_mask(i-1) && !isRVC(f2_fetch_bundle.insts(i-1))) || !isRVC(inst))
      } else {
        val inst = bank_data(w*16+32-1 downto w*16)
        f2_fetch_bundle.insts(i)     := inst
        f2_fetch_bundle.exp_insts(i) := ExpandRVC(inst)
        valid := !(f2_inst_mask(i-1) && !isRVC(f2_fetch_bundle.insts(i-1)))
      }
    }
    last_inst = f2_fetch_bundle.insts((b+1)*bankWidth-1)(15 downto 0)
    bank_prev_is_half = Mux(f2_bank_mask(b),
      (!(f2_inst_mask((b+1)*bankWidth-2) && !isRVC(f2_fetch_bundle.insts((b+1)*bankWidth-2))) && !isRVC(last_inst)),
      bank_prev_is_half)
    bank_prev_half = Mux(f2_bank_mask(b),
      last_inst(15 downto 0),
      bank_prev_half)
  }
  f2_fetch_bundle.end_half.valid   := bank_prev_is_half
  f2_fetch_bundle.end_half.payload := bank_prev_half

  val f2_correct_f1_ghist = s1_ghist =/= f2_predicted_ghist && Bool(enableGHistStallRepair)

  when ((s2_valid && !icache.io.resp.valid) ||
        (s2_valid && icache.io.resp.valid && !f3_ready)) {
    s0_valid := (!s2_tlb_resp.ae.inst && !s2_tlb_resp.pf.inst) || s2_is_replay || s2_tlb_miss
    s0_vpc   := s2_vpc
    s0_is_replay := s2_valid && icache.io.resp.valid
    s0_ghist := s2_ghist
    s0_tsrc  := s2_tsrc
    f1_clear := True
  } elsewhen (s2_valid && f3_ready) {
    when (s1_valid && s1_vpc === f2_predicted_target && !f2_correct_f1_ghist) {
      // We trust our prediction of what the global history for the next branch should be
      s2_ghist_next := f2_predicted_ghist
    }
    f2_prev_is_half := bank_prev_is_half && !f2_do_redirect
    f2_prev_half    := bank_prev_half
    when ((s1_valid && (s1_vpc =/= f2_predicted_target || f2_correct_f1_ghist)) || !s1_valid) {
      f1_clear := True

      s0_valid     := !((s2_tlb_resp.ae.inst || s2_tlb_resp.pf.inst) && !s2_is_replay)
      s0_vpc       := f2_predicted_target
      s0_is_replay := False
      s0_ghist     := f2_predicted_ghist
      s2_fsrc      := BSRC_2
      s0_tsrc      := BSRC_2
    }
  }
  s0_replay_resp := s2_tlb_resp
  s0_replay_ppc  := s2_ppc

  // --------------------------------------------------------
  // **** F3 ****
  // --------------------------------------------------------
  val f3_clear = CombInit(False)
  // Chisel: withReset(reset || f3_clear)
  val f3 = new Queue(new FetchBundle, 1, pipe=true, flow=false, hasFlush=true)
  f3.io.flush := f3_clear

  // Queue up the bpd resp as well, incase f4 backpressures f3
  // This is "flow" because the response (enq) arrives in f3, not f2
  val f3_bpd_queue = new Queue(new BranchPredictionBundle, 1, pipe=true, flow=true, hasFlush=true)
  f3_bpd_queue.io.flush := f3_clear

  val f4_ready = Bool()
  f3_ready := f3.io.enq.ready
  f3.io.enq.valid   := (s2_valid && !f2_clear &&
    (icache.io.resp.valid || ((s2_tlb_resp.ae.inst || s2_tlb_resp.pf.inst) && !s2_tlb_miss))
  )
  f3.io.enq.payload    := f2_fetch_bundle

  // The BPD resp comes in f3
  f3_bpd_queue.io.enq.valid := f3.io.deq.valid && RegNext(f3.io.enq.ready)
  f3_bpd_queue.io.enq.payload  := bpd.io.resp.f3
  when (f3_bpd_queue.io.enq.fire) {
    bpd.io.f3_fire := True
  }

  f3.io.deq.ready := f4_ready
  f3_bpd_queue.io.deq.ready := f4_ready
  val f3_bpd_resp     = f3_bpd_queue.io.deq.payload

  val f3_bank_mask    = bankMask(f3.io.deq.payload.pc)
  val f3_aligned_pc   = bankAlign(f3.io.deq.payload.pc)
  val f3_is_last_bank_in_block = isLastBankInBlock(f3_aligned_pc)
  val f3_is_rvc       = Vec.fill(fetchWidth)(Bool())
  val f3_redirects    = Vec.fill(fetchWidth)(Bool())
  val f3_targs        = Vec.fill(fetchWidth)(UInt(vaddrBitsExtended bits))
  val f3_cfi_types    = Vec.fill(fetchWidth)(UInt(CFI_SZ bits))
  val f3_shadowed_mask = Vec.fill(fetchWidth)(Bool())
  val f3_fetch_bundle = CombInit(f3.io.deq.payload)
  val f3_mask         = Vec.fill(fetchWidth)(Bool())
  val f3_br_mask      = Vec.fill(fetchWidth)(Bool())
  val f3_call_mask    = Vec.fill(fetchWidth)(Bool())
  val f3_ret_mask     = Vec.fill(fetchWidth)(Bool())
  val f3_npc_plus4_mask = Vec.fill(fetchWidth)(Bool())
  val f3_btb_mispredicts = Vec.fill(fetchWidth)(Bool())
  f3_fetch_bundle.mask    := f3_mask.asUInt
  f3_fetch_bundle.br_mask := f3_br_mask.asUInt
  f3_fetch_bundle.ftq_idx := 0 // This gets assigned later
  f3_fetch_bundle.shadowed_mask := f3_shadowed_mask

  var redirect_found: Bool = False
  for (b <- 0 until nBanks) {
    for (w <- 0 until bankWidth) {
      val i = (b * bankWidth) + w
      val pc = f3_fetch_bundle.pcs(i)

      val bpu = new BreakpointUnit(nBreakpoints)
      bpu.io.status   := io.cpu.status
      bpu.io.bp       := io.cpu.bp
      bpu.io.ea.assignDontCare()
      bpu.io.pc       := pc.resized
      bpu.io.mcontext := io.cpu.mcontext
      bpu.io.scontext := io.cpu.scontext

      val bpd_decoder = new BranchDecode
      bpd_decoder.io.inst := f3_fetch_bundle.exp_insts(i)
      bpd_decoder.io.pc   := pc
      val brsigs = bpd_decoder.io.out

      f3_is_rvc(i) := isRVC(f3_fetch_bundle.insts(i))
      f3_mask  (i) := f3.io.deq.valid && f3.io.deq.payload.mask(i) && !redirect_found
      f3_targs (i) := Mux(brsigs.cfi_type === CFI_JALR,
        f3_bpd_resp.preds(i).predicted_pc.payload,
        brsigs.target)

      // Flush BTB entries for JALs if we mispredict the target
      f3_btb_mispredicts(i) := (brsigs.cfi_type === CFI_JAL && f3.io.deq.payload.mask(i) &&
        f3_bpd_resp.preds(i).predicted_pc.valid &&
        (f3_bpd_resp.preds(i).predicted_pc.payload =/= brsigs.target)
      )

      f3_npc_plus4_mask(i) := (if (w == 0) {
        !f3_is_rvc(i) && !f3_fetch_bundle.edge_inst(b)
      } else {
        !f3_is_rvc(i)
      })

      val ow = log2Ceil(icBlockBytes) + 1
      val offset_from_aligned_pc = (
        U(i << 1, ow bits) +
        brsigs.sfb_offset.payload.resize(ow) -
        Mux(f3_fetch_bundle.edge_inst(b) && Bool(w == 0), U(2, ow bits), U(0, ow bits))
      )
      val lower_mask = UInt(2*fetchWidth bits)
      val upper_mask = UInt(2*fetchWidth bits)
      lower_mask := UIntToOH(U(i), 2*fetchWidth)
      upper_mask := (UIntToOH(offset_from_aligned_pc(log2Ceil(fetchBytes)+1 downto 1)) <<
        Mux(f3_is_last_bank_in_block, U(bankWidth), U(0))).resized

      f3_fetch_bundle.sfbs(i) := (
        f3_mask(i) &&
        brsigs.sfb_offset.valid &&
        (offset_from_aligned_pc <= Mux(f3_is_last_bank_in_block, U(fetchBytes+bankBytes), U(2*fetchBytes)))
      )
      f3_fetch_bundle.sfb_masks(i)       := ~MaskLower(lower_mask) & ~MaskUpper(upper_mask)
      f3_fetch_bundle.shadowable_mask(i) := (!(f3_fetch_bundle.xcpt_pf_if || f3_fetch_bundle.xcpt_ae_if || bpu.io.debug_if || bpu.io.xcpt_if) &&
                                             f3_bank_mask(b) &&
                                             (brsigs.shadowable || !f3_mask(i)))
      f3_fetch_bundle.sfb_dests(i)       := offset_from_aligned_pc.resized

      // Redirect if
      //  1) its a JAL/JALR (unconditional)
      //  2) the BPD believes this is a branch and says we should take it
      f3_redirects(i)    := f3_mask(i) && (
        brsigs.cfi_type === CFI_JAL || brsigs.cfi_type === CFI_JALR ||
        (brsigs.cfi_type === CFI_BR && f3_bpd_resp.preds(i).taken && Bool(useBPD))
      )

      f3_br_mask(i)   := f3_mask(i) && brsigs.cfi_type === CFI_BR
      f3_cfi_types(i) := brsigs.cfi_type
      f3_call_mask(i) := brsigs.is_call
      f3_ret_mask(i)  := brsigs.is_ret

      f3_fetch_bundle.bp_debug_if_oh(i) := bpu.io.debug_if
      f3_fetch_bundle.bp_xcpt_if_oh (i) := bpu.io.xcpt_if

      redirect_found = redirect_found || f3_redirects(i)
    }
  }

  f3_fetch_bundle.cfi_type      := f3_cfi_types(f3_fetch_bundle.cfi_idx.payload)
  f3_fetch_bundle.cfi_is_call   := f3_call_mask(f3_fetch_bundle.cfi_idx.payload)
  f3_fetch_bundle.cfi_is_ret    := f3_ret_mask (f3_fetch_bundle.cfi_idx.payload)
  f3_fetch_bundle.cfi_npc_plus4 := f3_npc_plus4_mask(f3_fetch_bundle.cfi_idx.payload)

  f3_fetch_bundle.lhist    := f3_bpd_resp.lhist
  f3_fetch_bundle.bpd_meta := f3_bpd_resp.meta

  when (f3.io.deq.fire) {
    assert(f3_bpd_resp.pc === f3_fetch_bundle.pc)
  }

  f3_fetch_bundle.cfi_idx.valid   := f3_redirects.reduce(_||_)
  f3_fetch_bundle.cfi_idx.payload := PriorityEncoder(f3_redirects)

  // Use the branch predictor response in fetch-3, the decoded branch target
  // isn't available fast enough
  val f3_predicted_targs = f3_bpd_resp.preds.map(_.predicted_pc.payload)

  val (f3_predicted_redirects, f3_redirect_target) = {
    val redirects = Vec((0 until fetchWidth) map { i =>
      f3.io.deq.payload.mask(i) && f3_bpd_resp.preds(i).predicted_pc.valid && f3_bpd_resp.preds(i).taken
    })
    val target = if (useSlowBTBRedirect) f3.io.deq.payload.next_pc else PriorityMux(redirects, f3_predicted_targs)
    (redirects, target)
  }
  val f3_predicted_do_redirect = f3_predicted_redirects.reduce(_||_) && Bool(useBPD)

  val f3_predicted_target = Mux(f3_predicted_do_redirect,
                                f3_redirect_target,
                                f3.io.deq.payload.next_fetch)
  val f3_predicted_ghist = f3_fetch_bundle.ghist.update(
    f3_bpd_resp.preds.map(p => p.is_br && p.predicted_pc.valid).asUInt & f3.io.deq.payload.mask,
    PriorityMux(f3_predicted_redirects, f3_bpd_resp.preds).taken && f3_predicted_do_redirect,
    PriorityMux(f3_predicted_redirects, f3_bpd_resp.preds).is_br,
    PriorityEncoder(f3_predicted_redirects),
    f3_predicted_do_redirect,
    f3.io.deq.payload.pc,
    False,
    False
  )
  val f3_decoded_target = Mux(f3_redirects.reduce(_||_),
    PriorityMux(f3_redirects, f3_targs),
    f3.io.deq.payload.next_fetch
  )

  f3_fetch_bundle.next_pc       := f3_decoded_target

  val f3_correct_f2_ghist = s2_ghist =/= f3_predicted_ghist && Bool(enableGHistStallRepair)
  val f3_correct_f1_ghist = s1_ghist =/= f3_predicted_ghist && Bool(enableGHistStallRepair)

  when (f3.io.deq.valid && f4_ready) {
    when (s2_valid && s2_vpc === f3_predicted_target && !f3_correct_f2_ghist) {
      f3.io.enq.payload.ghist := f3_predicted_ghist
    } elsewhen (( s2_valid &&  (s2_vpc =/= f3_predicted_target || f3_correct_f2_ghist)) ||
                (!s2_valid &&  s1_valid && (s1_vpc =/= f3_predicted_target || f3_correct_f1_ghist)) ||
                (!s2_valid && !s1_valid)) {
      f2_clear := True
      f2_prev_is_half := f3_fetch_bundle.end_half.valid && !f3_predicted_do_redirect
      f2_prev_half    := f3_fetch_bundle.end_half.payload
      f1_clear := True

      s0_valid     := !(f3_fetch_bundle.xcpt_pf_if || f3_fetch_bundle.xcpt_ae_if)
      s0_vpc       := f3_predicted_target
      s0_is_replay := False
      s0_ghist     := f3_predicted_ghist
      s0_tsrc      := BSRC_3

      f3_fetch_bundle.fsrc := BSRC_3
    }
  }

  // When f3 finds a btb mispredict, queue up a bpd correction update
  val f4_btb_corrections = new Queue(new BranchPredictionUpdate, 2)
  f4_btb_corrections.io.enq.valid := f3.io.deq.fire && f3_btb_mispredicts.reduce(_||_) && Bool(enableBTBFastRepair)
  f4_btb_corrections.io.enq.payload.assignDontCare()
  f4_btb_corrections.io.enq.payload.is_mispredict_update := False
  f4_btb_corrections.io.enq.payload.is_repair_update     := False
  f4_btb_corrections.io.enq.payload.btb_mispredicts      := f3_btb_mispredicts.asUInt
  f4_btb_corrections.io.enq.payload.pc                   := f3_fetch_bundle.pc
  f4_btb_corrections.io.enq.payload.ghist                := f3_fetch_bundle.ghist
  f4_btb_corrections.io.enq.payload.lhist                := f3_fetch_bundle.lhist
  f4_btb_corrections.io.enq.payload.meta                 := f3_fetch_bundle.bpd_meta

  // -------------------------------------------------------
  // **** F4 ****
  // -------------------------------------------------------
  val f4_clear = CombInit(False)
  val f4 = new Queue(new FetchBundle, 1, pipe=true, flow=false, hasFlush=true)
  f4.io.flush := f4_clear
  // TODO: Allow for 4-cycle branch predictors, instead of just reusing the cycle-3
  // response
  val f4_bpd_queue = new Queue(new BranchPredictionBundle, 1, pipe=true, flow=false, hasFlush=true)
  f4_bpd_queue.io.flush := f3_clear // sic: upstream resets this queue with f3_clear

  val fb  = new FetchBuffer
  val ftq = new FetchTargetQueue

  // RAS takes a cycle to read
  val ras_read_idx = Reg(UInt(log2Ceil(nRasEntries) bits)) init 0
  ras.io.read_idx := ras_read_idx
  when (f3.io.deq.fire) {
    ras_read_idx := f4.io.enq.payload.ghist.ras_idx
    ras.io.read_idx := f4.io.enq.payload.ghist.ras_idx
  }

  // Deal with sfbs
  val f4_shadowable_masks = Vec((0 until fetchWidth) map { i =>
     f4.io.deq.payload.shadowable_mask.asUInt |
    ~f4.io.deq.payload.sfb_masks(i)(fetchWidth-1 downto 0)
  })
  val f3_shadowable_masks = Vec((0 until fetchWidth) map { i =>
    Mux(f4.io.enq.valid, f4.io.enq.payload.shadowable_mask.asUInt, U(0, fetchWidth bits)) |
    ~f4.io.deq.payload.sfb_masks(i)(2*fetchWidth-1 downto fetchWidth)
  })
  val f4_sfbs = Vec((0 until fetchWidth) map { i =>
    Bool(enableSFBOpt) &&
    ((~f4_shadowable_masks(i) === 0) &&
     (~f3_shadowable_masks(i) === 0) &&
     f4.io.deq.payload.sfbs(i) &&
     !(f4.io.deq.payload.cfi_idx.valid && f4.io.deq.payload.cfi_idx.payload === i) &&
      Mux(f4.io.deq.payload.sfb_dests(i) === 0,
        !f3.io.deq.payload.end_half.valid,
      Mux(f4.io.deq.payload.sfb_dests(i) === fetchBytes,
        !f4.io.deq.payload.end_half.valid,
        True)
      )
     )
  })
  val f4_sfb_valid = f4_sfbs.reduce(_||_) && f4.io.deq.valid
  val f4_sfb_mask  = PriorityMux(f4_sfbs, f4.io.deq.payload.sfb_masks)
  // If we have a SFB, wait for next fetch to be available in f3
  val f4_delay     = (
    f4.io.deq.payload.sfbs.reduce(_||_) &&
    !f4.io.deq.payload.cfi_idx.valid &&
    !f4.io.enq.valid &&
    !f4.io.deq.payload.xcpt_pf_if &&
    !f4.io.deq.payload.xcpt_ae_if
  )
  when (f4_sfb_valid) {
    f3_shadowed_mask := Vec(f4_sfb_mask(2*fetchWidth-1 downto fetchWidth).asBools)
  } otherwise {
    f3_shadowed_mask.foreach(_ := False)
  }

  f4_ready := f4.io.enq.ready
  f4.io.enq.valid := f3.io.deq.valid && !f3_clear
  f4.io.enq.payload  := f3_fetch_bundle
  f4.io.deq.ready := fb.io.enq.ready && ftq.io.enq.ready && !f4_delay

  f4_bpd_queue.io.enq.valid := f3.io.enq.valid
  f4_bpd_queue.io.enq.payload  := f3_bpd_resp
  f4_bpd_queue.io.deq.ready := f4.io.deq.ready

  fb.io.enq.valid := f4.io.deq.valid && ftq.io.enq.ready && !f4_delay
  fb.io.enq.payload  := f4.io.deq.payload
  fb.io.enq.payload.ftq_idx := ftq.io.enq_idx
  fb.io.enq.payload.sfbs    := Vec(Mux(f4_sfb_valid, UIntToOH(PriorityEncoder(f4_sfbs), fetchWidth), U(0, fetchWidth bits)).asBools)
  fb.io.enq.payload.shadowed_mask := Vec((
    Mux(f4_sfb_valid, f4_sfb_mask(fetchWidth-1 downto 0), U(0, fetchWidth bits)) |
    f4.io.deq.payload.shadowed_mask.asUInt
  ).asBools)

  ftq.io.enq.valid          := f4.io.deq.valid && fb.io.enq.ready && !f4_delay
  ftq.io.enq.payload        := f4.io.deq.payload
  ftq.io.enq.payload.ras_top   := ras.io.read_addr

  val bpd_update_arbiter = new Arbiter(new BranchPredictionUpdate, 2)
  bpd_update_arbiter.io.in(0).valid   := ftq.io.bpdupdate.valid
  bpd_update_arbiter.io.in(0).payload := ftq.io.bpdupdate.payload
  assert(bpd_update_arbiter.io.in(0).ready)
  bpd_update_arbiter.io.in(1) << f4_btb_corrections.io.deq
  bpd.io.update.valid   := bpd_update_arbiter.io.out.valid
  bpd.io.update.payload := bpd_update_arbiter.io.out.payload
  bpd_update_arbiter.io.out.ready := True

  val f4_decoded_ghist = f4.io.deq.payload.ghist.update(
    f4.io.deq.payload.br_mask,
    f4.io.deq.payload.cfi_idx.valid,
    f4.io.deq.payload.br_mask(f4.io.deq.payload.cfi_idx.payload),
    f4.io.deq.payload.cfi_idx.payload,
    f4.io.deq.payload.cfi_idx.valid,
    f4.io.deq.payload.pc,
    f4.io.deq.payload.cfi_is_call,
    f4.io.deq.payload.cfi_is_ret
  )
  val f4_decoded_target = Mux(f4.io.deq.payload.cfi_idx.valid && f4.io.deq.payload.cfi_is_ret && Bool(useBPD) && Bool(useRAS),
    ras.io.read_addr, f4.io.deq.payload.next_pc)
  val f4_correct_f1_ghist = s1_ghist =/= f4_decoded_ghist && Bool(enableGHistStallRepair)
  val f4_correct_f2_ghist = s2_ghist =/= f4_decoded_ghist && Bool(enableGHistStallRepair)
  val f4_correct_f3_ghist = f3.io.deq.payload.ghist =/= f4_decoded_ghist && Bool(enableGHistStallRepair)

  when (f4.io.deq.valid) {
    when (f3.io.deq.valid && f3.io.deq.payload.pc === f4_decoded_target && !f4_correct_f3_ghist) {
      f4.io.enq.payload.ghist := f4_decoded_ghist
    } elsewhen (!f3.io.deq.valid && s2_valid && s2_vpc === f4_decoded_target && !f4_correct_f2_ghist) {
      f3.io.enq.payload.ghist := f4_decoded_ghist
    } elsewhen (!f3.io.deq.valid && !s2_valid && s1_vpc === f4_decoded_target && !f4_correct_f1_ghist) {
      s2_ghist_next := f4_decoded_ghist
    } elsewhen (( f3.io.deq.valid && (f3.io.deq.payload.pc =/= f4_decoded_target || f4_correct_f3_ghist)) ||
                (!f3.io.deq.valid &&  s2_valid && (s2_vpc =/= f4_decoded_target || f4_correct_f2_ghist)) ||
                (!f3.io.deq.valid && !s2_valid &&  s1_valid && (s1_vpc =/= f4_decoded_target || f4_correct_f1_ghist)) ||
                (!f3.io.deq.valid && !s2_valid && !s1_valid)) {
      f3_clear := True
      f2_clear := True
      f2_prev_is_half := f4.io.deq.payload.end_half.valid && !f4.io.deq.payload.cfi_idx.valid
      f2_prev_half    := f4.io.deq.payload.end_half.payload
      f1_clear := True

      s0_valid := !(f4.io.deq.payload.xcpt_pf_if || f4.io.deq.payload.xcpt_ae_if)
      s0_vpc   := f4_decoded_target
      s0_is_replay := False
      s0_ghist := f4_decoded_ghist
      s0_tsrc  := BSRC_4
      fb.io.enq.payload.fsrc := BSRC_4
    }
  }

  ras.io.write_valid := f4.io.deq.valid && f4.io.deq.payload.cfi_is_call && f4.io.deq.payload.cfi_idx.valid
  ras.io.write_addr  := bankAlign(f4.io.deq.payload.pc) + (f4.io.deq.payload.cfi_idx.payload << 1).resized + Mux(
    f4.io.deq.payload.cfi_npc_plus4, U(4, vaddrBitsExtended bits), U(2, vaddrBitsExtended bits))
  ras.io.write_idx   := WrapInc(f4.io.deq.payload.ghist.ras_idx, nRasEntries)

  when (ftq.io.ras_update && Bool(enableRasTopRepair)) {
    ras.io.write_valid := True
    ras.io.write_idx   := ftq.io.ras_update_idx
    ras.io.write_addr  := ftq.io.ras_update_pc
  }

  // -------------------------------------------------------
  // **** To Core (F5) ****
  // -------------------------------------------------------

  io.cpu.fetchpacket << fb.io.deq
  ftq.io.arb_ftq_reqs := io.cpu.arb_ftq_reqs
  io.cpu.rrd_ftq_resps := ftq.io.rrd_ftq_resps
  io.cpu.com_pc := ftq.io.com_pc
  ftq.io.deq.valid   := io.cpu.commit.valid
  ftq.io.deq.payload := io.cpu.commit.payload.resized
  ftq.io.brupdate := io.cpu.brupdate

  ftq.io.redirect.valid   := io.cpu.redirect_val
  ftq.io.redirect.payload := io.cpu.redirect_ftq_idx
  fb.io.clear := False

  when (io.cpu.sfence.valid) {
    fb.io.clear := True
    f4_clear    := True
    f3_clear    := True
    f2_clear    := True
    f2_prev_is_half := False
    f1_clear    := True

    s0_valid     := False
    s0_vpc       := io.cpu.sfence.payload.addr.resized
    s0_is_replay := False
    s0_is_sfence := True

  } elsewhen (io.cpu.redirect_flush) {
    fb.io.clear := True
    f4_clear    := True
    f3_clear    := True
    f2_clear    := True
    f2_prev_is_half := False
    f1_clear    := True

    s0_valid     := io.cpu.redirect_val
    s0_vpc       := io.cpu.redirect_pc
    s0_ghist     := io.cpu.redirect_ghist
    s0_tsrc      := BSRC_C
    s0_is_replay := False

    ftq.io.redirect.valid   := io.cpu.redirect_val
    ftq.io.redirect.payload := io.cpu.redirect_ftq_idx
  }

  ftq.io.debug_ftq_idx := io.cpu.debug_ftq_idx
  io.cpu.debug_fetch_pc := ftq.io.debug_fetch_pc

  val jump_to_reset = Reg(Bool()) init True

  when (jump_to_reset) {
    s0_valid := True
    s0_vpc   := io.reset_vector.resized
    s0_ghist.assignFromBits(B(0, s0_ghist.getBitsWidth bits))
    s0_tsrc  := BSRC_C
    fb.io.clear := True
    f4_clear    := True
    f3_clear    := True
    f2_clear    := True
    f2_prev_is_half := False
    f1_clear    := True
    jump_to_reset := False
  }

  Builder(f0f1, f1c, f1f2)

  override def toString: String =
    (BoomCoreStringPrefix("====Overall Frontend Params====") + "\n"
    + icache.toString + bpd.toString)
}
