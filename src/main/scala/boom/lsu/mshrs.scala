//******************************************************************************
// Ported from Rocket-Chip
// See LICENSE.Berkeley and LICENSE.SiFive in Rocket-Chip for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.lsu

import spinal.core._
import spinal.lib._
import spinal.lib.bus.tilelink.{ChannelA, ChannelD, ChannelE, Opcode}

import rocketchip.config.Parameters
import rocketchip.rocket._
import rocketchip.rocket.constants.MemoryOpConstants
import rocketchip.tilelink._
import rocketchip.util._

import boom.common._
import boom.exu.BrUpdateInfo
import boom.util._

class BoomDCacheReqInternal(implicit p: Parameters) extends BoomDCacheReq()(p)
  with HasL1HellaCacheParameters
{
  // miss info
  val tag_match = Bool()
  val old_meta  = new L1Metadata
  val way_en    = UInt(nWays bits)

  // Used in the MSHRs
  val sdq_id    = UInt(log2Ceil(cfg.nSDQ) bits)
}

class BoomMSHR(implicit p: Parameters) extends BoomModule()(p)
  with HasL1HellaCacheParameters
  with MemoryOpConstants
{
  val edge = BoomNonBlockingDCache.edge
  val bus = edge.bus

  val io = new Bundle {
    val id = in UInt(log2Ceil(cfg.nMSHRs) bits)

    val req_pri_val = in Bool()
    val req_pri_rdy = out Bool()
    val req_sec_val = in Bool()
    val req_sec_rdy = out Bool()

    val clear_prefetch = in Bool()
    val brupdate       = in(new BrUpdateInfo)
    val exception    = in Bool()
    val rob_pnr_idx  = in UInt(robAddrSz bits)
    val rob_head_idx = in UInt(robAddrSz bits)

    val req          = in(new BoomDCacheReqInternal)
    val req_is_probe = in Bool()

    val idx = out(Flow(UInt(idxBits bits)))
    val way = out(Flow(UInt(nWays bits)))
    val tag = out(Flow(UInt(coreMaxAddrBits - untagBits bits)))

    val mem_acquire = master(Stream(ChannelA(bus)))

    val mem_grant   = slave(Stream(ChannelD(bus)))
    val mem_finish  = master(Stream(ChannelE(bus)))

    val prober_state = in(Flow(UInt(coreMaxAddrBits bits)))

    val refill      = master(Stream(new L1DataWriteReq))

    val meta_write  = master(Stream(new L1MetaWriteReq))
    val meta_read   = master(Stream(new L1MetaReadReq))
    val meta_resp   = in(Flow(new L1Metadata))
    val wb_req      = master(Stream(new WritebackReq))

    // To inform the prefetcher when we are commiting the fetch of this line
    val commit_val  = out Bool()
    val commit_addr = out UInt(coreMaxAddrBits bits)
    val commit_coh  = out(new ClientMetadata)

    // Reading from the line buffer
    val lb_read       = out(new LineBufferReadReq)
    val lb_resp       = in UInt(encRowBits bits)
    val lb_write      = out(Flow(new LineBufferWriteReq))

    // Replays go through the cache pipeline again
    val replay      = master(Stream(new BoomDCacheReqInternal))
    // Resp go straight out to the core
    val resp        = master(Stream(new BoomDCacheResp))

    // Writeback unit tells us when it is done processing our wb
    val wb_resp     = in Bool()

    val probe_rdy   = out Bool()
  }

  // TODO: Optimize this. We don't want to mess with cache during speculation
  // s_refill_req      : Make a request for a new cache line
  // s_refill_resp     : Store the refill response into our buffer
  // s_drain_rpq_loads : Drain out loads from the rpq
  //                   : If miss was misspeculated, go to s_invalid
  // s_wb_req          : Write back the evicted cache line
  // s_wb_resp         : Finish writing back the evicted cache line
  // s_meta_write_req  : Write the metadata for new cache lne
  // s_meta_write_resp :

  def st(i: Int) = U(i, 5 bits)
  val s_invalid = st(0); val s_refill_req = st(1); val s_refill_resp = st(2); val s_drain_rpq_loads = st(3)
  val s_meta_read = st(4); val s_meta_resp_1 = st(5); val s_meta_resp_2 = st(6); val s_meta_clear = st(7)
  val s_wb_meta_read = st(8); val s_wb_req = st(9); val s_wb_resp = st(10); val s_commit_line = st(11)
  val s_drain_rpq = st(12); val s_meta_write_req = st(13); val s_mem_finish_1 = st(14); val s_mem_finish_2 = st(15)
  val s_prefetched = st(16); val s_prefetch = st(17)
  val state = Reg(UInt(5 bits)) init s_invalid

  val req     = Reg(new BoomDCacheReqInternal)
  val req_idx = req.addr(untagBits-1 downto blockOffBits)
  val req_tag = req.addr >> untagBits
  val req_block_addr = (req.addr >> blockOffBits) << blockOffBits
  val req_needs_wb = Reg(Bool()) init False

  val new_coh = RegInit(ClientMetadata.onReset)
  val (_, shrink_param, coh_on_clear) = req.old_meta.coh.onCacheControl(M_FLUSH)
  val grow_param = new_coh.onAccess(req.uop.mem_cmd)._2
  val coh_on_grant = new_coh.onGrant(req.uop.mem_cmd, io.mem_grant.payload.param.asUInt.resize(TLPermissions.bdWidth))

  // We only accept secondary misses if the original request had sufficient permissions
  val (cmd_requires_second_acquire, is_hit_again, _, dirtier_coh, dirtier_cmd) =
    new_coh.onSecondaryAccess(req.uop.mem_cmd, io.req.uop.mem_cmd)

  val (_, _, refill_done, refill_address_inc) = edge.addr_inc(io.mem_grant)
  val sec_rdy = (!cmd_requires_second_acquire && !io.req_is_probe &&
                 !state.isOneOf(s_invalid, s_meta_write_req, s_mem_finish_1, s_mem_finish_2))// Always accept secondary misses

  val rpq = new BranchKillableQueue(new BoomDCacheReqInternal, cfg.nRPQ, u => u.uses_ldq, fastDeq=true)
  rpq.io.brupdate := io.brupdate
  rpq.io.flush  := io.exception
  assert(!(state === s_invalid && !rpq.io.empty))

  rpq.io.enq.valid   := ((io.req_pri_val && io.req_pri_rdy) || (io.req_sec_val && io.req_sec_rdy)) && !isPrefetch(io.req.uop.mem_cmd)
  rpq.io.enq.payload := io.req
  rpq.io.deq.ready   := False

  val grantack = Reg(Flow(ChannelE(bus)))
  val refill_ctr  = Reg(UInt(log2Ceil(cacheDataBeats) bits))
  val commit_line = Reg(Bool())
  val grant_had_data = Reg(Bool())
  val finish_to_prefetch = Reg(Bool())

  // Block probes if a tag write we started is still in the pipeline
  val meta_hazard = Reg(UInt(2 bits)) init 0
  when (meta_hazard =/= 0) { meta_hazard := meta_hazard + 1 }
  when (io.meta_write.fire) { meta_hazard := 1 }
  io.probe_rdy   := (meta_hazard === 0 && (state.isOneOf(s_invalid, s_refill_req, s_refill_resp, s_drain_rpq_loads) || (state === s_meta_read && grantack.valid)))
  io.idx.valid := state =/= s_invalid
  io.tag.valid := state =/= s_invalid
  io.way.valid := !state.isOneOf(s_invalid, s_prefetch)
  io.idx.payload := req_idx
  io.tag.payload := req_tag
  io.way.payload := req.way_en

  io.meta_write.valid    := False
  io.meta_write.payload.idx      := req_idx
  io.meta_write.payload.data.coh := coh_on_clear
  io.meta_write.payload.data.tag := req_tag.resized
  io.meta_write.payload.way_en   := req.way_en
  io.meta_write.payload.tag      := req_tag.resized
  io.req_pri_rdy         := False
  io.req_sec_rdy         := sec_rdy && rpq.io.enq.ready
  io.mem_acquire.valid   := False
  // TODO: Use AcquirePerm if just doing permissions acquire
  io.mem_acquire.payload := edge.AcquireBlock(
    fromSource      = io.id,
    toAddress       = (Cat(req_tag, req_idx).asUInt << blockOffBits),
    lgSize          = U(lgCacheBlockBytes),
    growPermissions = grow_param)
  io.refill.valid        := False
  io.refill.payload.addr   := (req_block_addr | (refill_ctr << rowOffBits).resized).resized
  io.refill.payload.way_en := req.way_en
  io.refill.payload.wmask  := ~U(0, rowWords bits)
  io.refill.payload.data   := io.lb_resp
  io.replay.valid        := False
  io.replay.payload      := rpq.io.deq.payload
  io.wb_req.valid        := False
  io.wb_req.payload.tag       := req.old_meta.tag
  io.wb_req.payload.idx       := req_idx
  io.wb_req.payload.param     := shrink_param
  io.wb_req.payload.way_en    := req.way_en
  io.wb_req.payload.source    := io.id.resized
  io.wb_req.payload.voluntary := True
  io.resp.valid          := False
  io.resp.payload.assignSomeByName(rpq.io.deq.payload)
  io.commit_val          := False
  io.commit_addr         := req.addr
  io.commit_coh          := coh_on_grant
  io.meta_read.valid     := False
  io.meta_read.payload.idx    := req_idx
  io.meta_read.payload.tag    := req_tag.resized
  io.meta_read.payload.way_en := req.way_en
  io.mem_finish.valid    := False
  io.mem_finish.payload  := grantack.payload
  io.lb_write.valid      := False
  io.lb_write.payload.offset := (refill_address_inc >> rowOffBits).resized
  io.lb_write.payload.data   := io.mem_grant.payload.data.asUInt
  io.mem_grant.ready := False
  io.lb_read.offset := (rpq.io.deq.payload.addr >> rowOffBits).resized

  when (io.req_sec_val && io.req_sec_rdy) {
    req.uop.mem_cmd := dirtier_cmd
    when (is_hit_again) {
      new_coh := dirtier_coh
    }
  }

  def handle_pri_req(old_state: UInt): UInt = {
    val new_state = CombInit(old_state)
    grantack.valid := False
    refill_ctr := 0
    assert(rpq.io.enq.ready)
    req := io.req
    val old_coh   = io.req.old_meta.coh
    req_needs_wb := old_coh.onCacheControl(M_FLUSH)._1 // does the line we are evicting need to be written back
    when (io.req.tag_match) {
      val (is_hit, _, coh_on_hit) = old_coh.onAccess(io.req.uop.mem_cmd)
      when (is_hit) { // set dirty bit
        assert(isWrite(io.req.uop.mem_cmd))
        new_coh     := coh_on_hit
        new_state   := s_drain_rpq
      } otherwise { // upgrade permissions
        new_coh     := old_coh
        new_state   := s_refill_req
      }
    } otherwise { // refill and writeback if necessary
      new_coh     := ClientMetadata.onReset
      new_state   := s_refill_req
    }
    new_state
  }

  val grant_has_data = edge.hasDataD(io.mem_grant.payload.opcode)

  when (state === s_invalid) {
    io.req_pri_rdy := True
    grant_had_data := False

    when (io.req_pri_val && io.req_pri_rdy) {
      state := handle_pri_req(state)
    }
  } elsewhen (state === s_refill_req) {
    io.mem_acquire.valid := True
    when (io.mem_acquire.fire) {
      state := s_refill_resp
    }
  } elsewhen (state === s_refill_resp) {
    io.mem_grant.ready := True
    when (grant_has_data) {
      io.lb_write.valid       := io.mem_grant.valid
    } otherwise {
      io.mem_grant.ready      := True
    }

    when (io.mem_grant.fire) {
      grant_had_data := grant_has_data
    }
    when (refill_done) {
      grantack.valid   := edge.isRequestD(io.mem_grant.payload.opcode)
      grantack.payload := edge.GrantAck(io.mem_grant.payload)
      state := Mux(grant_had_data, s_drain_rpq_loads, s_drain_rpq)
      assert(!(!grant_had_data && req_needs_wb))
      commit_line := False
      new_coh := coh_on_grant
    }
  } elsewhen (state === s_drain_rpq_loads) {
    val drain_load = (isRead(rpq.io.deq.payload.uop.mem_cmd) &&
                     !isWrite(rpq.io.deq.payload.uop.mem_cmd) &&
                     (rpq.io.deq.payload.uop.mem_cmd =/= M_XLR)) // LR should go through replay
    // drain all loads for now
    val rp_addr = Cat(req_tag, req_idx, rpq.io.deq.payload.addr(blockOffBits-1 downto 0)).asUInt
    val word_idx  = if (rowWords == 1) U(0, 1 bits) else rp_addr(log2UpChisel(rowWords*coreDataBytes)-1 downto log2UpChisel(wordBytes))
    val data      = io.lb_resp
    val data_word = data >> Cat(word_idx, U(0, log2UpChisel(coreDataBits) bits)).asUInt
    val loadgen = new LoadGen(rpq.io.deq.payload.uop.mem_size, rpq.io.deq.payload.uop.mem_signed,
      rp_addr, data_word, False, wordBytes)

    rpq.io.deq.ready  := io.resp.ready && drain_load

    io.lb_read.offset := (rpq.io.deq.payload.addr >> rowOffBits).resized

    io.resp.valid     := rpq.io.deq.valid && drain_load
    io.resp.payload.data := loadgen.data.resized
    io.resp.payload.is_hella := rpq.io.deq.payload.is_hella
    when (rpq.io.deq.fire) {
      commit_line   := True
    } elsewhen (rpq.io.empty && !commit_line) {
      when (!rpq.io.enq.fire) {
        state := s_mem_finish_1
        finish_to_prefetch := Bool(enablePrefetching)
      }
    } elsewhen (rpq.io.empty || (rpq.io.deq.valid && !drain_load)) {
      // io.commit_val is for the prefetcher. it tells the prefetcher that this line was correctly acquired
      // The prefetcher should consider fetching the next line
      io.commit_val := True
      state := s_meta_read
    }
  } elsewhen (state === s_meta_read) {
    io.meta_read.valid := !io.prober_state.valid || !grantack.valid || (io.prober_state.payload(untagBits-1 downto blockOffBits) =/= req_idx)
    when (io.meta_read.fire) {
      state := s_meta_resp_1
    }
  } elsewhen (state === s_meta_resp_1) {
    state := s_meta_resp_2
  } elsewhen (state === s_meta_resp_2) {
    val needs_wb = io.meta_resp.payload.coh.onCacheControl(M_FLUSH)._1
    state := Mux(!io.meta_resp.valid, s_meta_read, // Prober could have nack'd this read
             Mux(needs_wb, s_meta_clear, s_commit_line))
  } elsewhen (state === s_meta_clear) {
    io.meta_write.valid         := True

    when (io.meta_write.fire) {
      state      := s_wb_req
    }
  } elsewhen (state === s_wb_req) {
    io.wb_req.valid          := True
    when (io.wb_req.fire) {
      state := s_wb_resp
    }
  } elsewhen (state === s_wb_resp) {
    when (io.wb_resp) {
      state := s_commit_line
    }
  } elsewhen (state === s_commit_line) {
    io.lb_read.offset := refill_ctr

    io.refill.valid       := True
    when (io.refill.fire) {
      refill_ctr := refill_ctr + 1
      when (refill_ctr === (cacheDataBeats - 1)) {
        state := s_drain_rpq
      }
    }
  } elsewhen (state === s_drain_rpq) {
    // io.replay <> rpq.io.deq
    io.replay.valid  := rpq.io.deq.valid
    rpq.io.deq.ready := io.replay.ready
    io.replay.payload.way_en    := req.way_en
    io.replay.payload.addr := Cat(req_tag, req_idx, rpq.io.deq.payload.addr(blockOffBits-1 downto 0)).asUInt.resized
    when (io.replay.fire && isWrite(rpq.io.deq.payload.uop.mem_cmd)) {
      // Set dirty bit
      val (is_hit, _, coh_on_hit) = new_coh.onAccess(rpq.io.deq.payload.uop.mem_cmd)
      assert(is_hit, "We still don't have permissions for this store")
      new_coh := coh_on_hit
    }
    when (rpq.io.empty && !rpq.io.enq.valid) {
      state := s_meta_write_req
    }
  } elsewhen (state === s_meta_write_req) {
    io.meta_write.valid         := True
    io.meta_write.payload.idx      := req_idx
    io.meta_write.payload.data.coh := new_coh
    io.meta_write.payload.data.tag := req_tag.resized
    io.meta_write.payload.way_en   := req.way_en
    when (io.meta_write.fire) {
      state := s_mem_finish_1
      finish_to_prefetch := False
    }
  } elsewhen (state === s_mem_finish_1) {
    io.mem_finish.valid := grantack.valid
    when (io.mem_finish.fire || !grantack.valid) {
      grantack.valid := False
      state := s_mem_finish_2
    }
  } elsewhen (state === s_mem_finish_2) {
    state := Mux(finish_to_prefetch, s_prefetch, s_invalid)
  } elsewhen (state === s_prefetch) {
    io.req_pri_rdy := True
    when ((io.req_sec_val && !io.req_sec_rdy) || io.clear_prefetch) {
      state := s_invalid
    } elsewhen (io.req_sec_val && io.req_sec_rdy) {
      val (is_hit, _, coh_on_hit) = new_coh.onAccess(io.req.uop.mem_cmd)
      when (is_hit) { // Proceed with refill
        new_coh := coh_on_hit
        state := s_meta_read
      } otherwise { // Reacquire this line
        new_coh := ClientMetadata.onReset
        state := s_refill_req
      }
    } elsewhen (io.req_pri_val && io.req_pri_rdy) {
      grant_had_data := False
      state := handle_pri_req(state)
    }
  }
}

class BoomIOMSHR(id: Int)(implicit p: Parameters) extends BoomModule()(p)
  with HasL1HellaCacheParameters
  with MemoryOpConstants
{
  val edge = BoomNonBlockingDCache.edge
  val bus = edge.bus

  val io = new Bundle {
    val req  = slave(Stream(new BoomDCacheReq))
    val resp = master(Stream(new BoomDCacheResp))
    val mem_access = master(Stream(ChannelA(bus)))
    val mem_ack    = slave(Flow(ChannelD(bus)))

    // We don't need brupdate in here because uncacheable operations are guaranteed non-speculative
  }

  def beatOffset(addr: UInt): UInt = if (beatOffBits > wordOffBits) addr(beatOffBits-1 downto wordOffBits) else U(0, 0 bits)

  def wordFromBeat(addr: UInt, dat: UInt): UInt = {
    val shift = Cat(beatOffset(addr), U(0, wordOffBits+log2Ceil(wordBytes) bits)).asUInt
    (dat >> shift).resize(wordBits)
  }

  val req = Reg(new BoomDCacheReq)
  val grant_word = Reg(UInt(wordBits bits))

  val s_idle       = U(0, 2 bits)
  val s_mem_access = U(1, 2 bits)
  val s_mem_ack    = U(2, 2 bits)
  val s_resp       = U(3, 2 bits)

  val state = Reg(UInt(2 bits)) init s_idle
  io.req.ready := state === s_idle

  val loadgen = new LoadGen(req.uop.mem_size, req.uop.mem_signed, req.addr, grant_word, False, wordBytes)

  val a_source  = U(id)
  val a_address = req.addr
  val a_size    = req.uop.mem_size
  val a_data    = Fill(beatWords, req.data)

  val get      = edge.Get(a_source, a_address, a_size)
  val put      = edge.Put(a_source, a_address, a_size, a_data.asBits)
  // Spinal TileLink has no Arithmetic/Logical messages: equivalent to no
  // manager supporting atomics, so atomics never reach an IOMSHR.
  require(!p.system.anySupportLogical && !p.system.anySupportArithmetic,
    "TileLink atomics are not supported by this port")
  // If no managers support atomics, assert fail if processor asks for them
  assert(state === s_idle || !isAMO(req.uop.mem_cmd))
  val atomics = ChannelA(bus).getZero
  assert(state === s_idle || req.uop.mem_cmd =/= M_XSC)

  io.mem_access.valid   := state === s_mem_access
  io.mem_access.payload := Mux(isAMO(req.uop.mem_cmd), atomics, Mux(isRead(req.uop.mem_cmd), get, put))

  val send_resp = isRead(req.uop.mem_cmd)

  io.resp.valid     := (state === s_resp) && send_resp
  io.resp.payload.uop  := req.uop
  io.resp.payload.data := loadgen.data.resized
  io.resp.payload.is_hella := req.is_hella

  when (io.req.fire) {
    req   := io.req.payload
    state := s_mem_access
  }
  when (io.mem_access.fire) {
    state := s_mem_ack
  }
  when (state === s_mem_ack && io.mem_ack.valid) {
    state := s_resp
    when (isRead(req.uop.mem_cmd)) {
      grant_word := wordFromBeat(req.addr, io.mem_ack.payload.data.asUInt)
    }
  }
  when (state === s_resp) {
    when (!send_resp || io.resp.fire) {
      state := s_idle
    }
  }
}

class LineBufferReadReq(implicit p: Parameters) extends BoomBundle()(p)
  with HasL1HellaCacheParameters
{
  val offset  = UInt(log2Ceil(cacheDataBeats) bits)
}

class LineBufferWriteReq(implicit p: Parameters) extends LineBufferReadReq()(p)
{
  val data   = UInt(encRowBits bits)
}

class LineBufferMetaWriteReq(implicit p: Parameters) extends BoomBundle()(p)
{
  val id   = UInt(log2Ceil(nLBEntries) bits)
  val coh  = new ClientMetadata
  val addr = UInt(coreMaxAddrBits bits)
}

class LineBufferMeta(implicit p: Parameters) extends BoomBundle()(p)
  with HasL1HellaCacheParameters
{
  val coh  = new ClientMetadata
  val addr = UInt(coreMaxAddrBits bits)
}

class BoomMSHRFile(implicit p: Parameters) extends BoomModule()(p)
  with HasL1HellaCacheParameters
  with MemoryOpConstants
{
  val edge = BoomNonBlockingDCache.edge
  val bus = edge.bus

  val io = new Bundle {
    val req  = Vec.fill(lsuWidth)(slave(Stream(new BoomDCacheReqInternal))) // Req from s2 of DCache pipe
    val req_is_probe = in(Vec.fill(lsuWidth)(Bool()))
    val resp = master(Stream(new BoomDCacheResp))
    val secondary_miss = out(Vec.fill(lsuWidth)(Bool()))
    val block_hit = out(Vec.fill(lsuWidth)(Bool()))

    val brupdate       = in(new BrUpdateInfo)
    val exception    = in Bool()
    val rob_pnr_idx  = in UInt(robAddrSz bits)
    val rob_head_idx = in UInt(robAddrSz bits)

    val mem_acquire  = master(Stream(ChannelA(bus)))
    val mem_grant    = slave(Stream(ChannelD(bus)))
    val mem_finish   = master(Stream(ChannelE(bus)))

    val refill     = master(Stream(new L1DataWriteReq))
    val meta_write = master(Stream(new L1MetaWriteReq))
    val meta_read  = master(Stream(new L1MetaReadReq))
    val meta_resp  = in(Flow(new L1Metadata))
    val replay     = master(Stream(new BoomDCacheReqInternal))
    val prefetch   = master(Stream(new BoomDCacheReq))
    val wb_req     = master(Stream(new WritebackReq))

    val prober_state = in(Flow(UInt(coreMaxAddrBits bits)))

    val clear_all = in Bool() // Clears all uncommitted MSHRs to prepare for fence

    val wb_resp   = in Bool()

    val fence_rdy = out Bool()
    val probe_rdy = out Bool()
  }

  val req_idx = OHToUInt(io.req.map(_.valid))
  // Chisel: WireInit(io.req(req_idx))
  val req = Stream(new BoomDCacheReqInternal)
  req.valid   := io.req.at(req_idx).valid
  req.payload := io.req.at(req_idx).payload
  req.ready   := io.req.at(req_idx).ready
  val req_is_probe = io.req_is_probe(0)

  for (w <- 0 until lsuWidth)
    io.req(w).ready := False

  val prefetcher: DataPrefetcher = if (enablePrefetching) new NLPrefetcher
                                                     else new NullPrefetcher

  io.prefetch << prefetcher.io.prefetch

  val cacheable = p.system.fastProperty(req.payload.addr, _.supportsAcquireB)

  // --------------------
  // The MSHR SDQ
  val sdq_val      = Reg(UInt(cfg.nSDQ bits)) init 0
  val sdq_alloc_id = PriorityEncoder(~sdq_val(cfg.nSDQ-1 downto 0))
  val sdq_rdy      = !sdq_val.andR
  val sdq_enq      = req.fire && cacheable && isWrite(req.payload.uop.mem_cmd)
  val sdq          = Mem(UInt(coreDataBits bits), cfg.nSDQ)

  sdq.write(sdq_alloc_id.resized, req.payload.data, sdq_enq)

  // --------------------
  // The LineBuffer Data
  // Holds refilling lines, prefetched lines
  val lb = Reg(Vec.fill(nLBEntries)(Vec.fill(cacheDataBeats)(UInt(encRowBits bits))))
  def widthMap[T <: Data](f: Int => T): Vec[T] = Vec((0 until lsuWidth).map(f))

  val idx_matches = Vec.fill(lsuWidth)(Vec.fill(cfg.nMSHRs)(Bool()))
  val tag_matches = Vec.fill(lsuWidth)(Vec.fill(cfg.nMSHRs)(Bool()))
  val way_matches = Vec.fill(lsuWidth)(Vec.fill(cfg.nMSHRs)(Bool()))

  val tag_match   = widthMap(w => Mux1H(idx_matches(w), tag_matches(w)))
  val idx_match   = widthMap(w => idx_matches(w).reduce(_||_))
  val way_match   = widthMap(w => Mux1H(idx_matches(w), way_matches(w)))

  val wb_tag_list = Vec.fill(cfg.nMSHRs)(UInt(tagBits bits))

  val meta_write_arb = new Arbiter(new L1MetaWriteReq           , cfg.nMSHRs)
  val meta_read_arb  = new Arbiter(new L1MetaReadReq            , cfg.nMSHRs)
  val wb_req_arb     = new Arbiter(new WritebackReq             , cfg.nMSHRs)
  val replay_arb     = new Arbiter(new BoomDCacheReqInternal    , cfg.nMSHRs)
  val resp_arb       = new Arbiter(new BoomDCacheResp           , cfg.nMSHRs + nIOMSHRs)
  val refill_arb     = new Arbiter(new L1DataWriteReq           , cfg.nMSHRs)

  val commit_vals    = Vec.fill(cfg.nMSHRs)(Bool())
  val commit_addrs   = Vec.fill(cfg.nMSHRs)(UInt(coreMaxAddrBits bits))
  val commit_cohs    = Vec.fill(cfg.nMSHRs)(new ClientMetadata)

  var sec_rdy: Bool = False

  io.fence_rdy := True
  io.probe_rdy := True
  io.mem_grant.ready := False

  val mshr_alloc_idx = UInt(log2Ceil(cfg.nMSHRs) bits)
  val pri_rdy = CombInit(False)
  val pri_val = req.valid && sdq_rdy && cacheable && !idx_match.at(req_idx)
  val mshrs = (0 until cfg.nMSHRs) map { i =>
    val mshr = new BoomMSHR
    mshr.io.id := U(i, log2Ceil(cfg.nMSHRs) bits)

    for (w <- 0 until lsuWidth) {
      idx_matches(w)(i) := mshr.io.idx.valid && mshr.io.idx.payload === io.req(w).payload.addr(untagBits-1 downto blockOffBits)
      tag_matches(w)(i) := mshr.io.tag.valid && mshr.io.tag.payload === io.req(w).payload.addr >> untagBits
      way_matches(w)(i) := mshr.io.way.valid && mshr.io.way.payload === io.req(w).payload.way_en
    }
    wb_tag_list(i) := mshr.io.wb_req.payload.tag

    mshr.io.req_pri_val  := (mshr_alloc_idx === i) && pri_val
    when (mshr_alloc_idx === i) {
      pri_rdy := mshr.io.req_pri_rdy
    }

    mshr.io.req_sec_val  := req.valid && sdq_rdy && tag_match.at(req_idx) && idx_matches.at(req_idx)(i) && cacheable
    mshr.io.req          := req.payload
    mshr.io.req_is_probe := req_is_probe
    mshr.io.req.sdq_id   := sdq_alloc_id

    // Clear because of a FENCE, a request to the same idx as a prefetched line,
    // a probe to that prefetched line, all mshrs are in use
    mshr.io.clear_prefetch := ((io.clear_all && !req.valid)||
      (req.valid && idx_matches.at(req_idx)(i) && cacheable && !tag_match.at(req_idx)) ||
      (req_is_probe && idx_matches.at(req_idx)(i)))
    mshr.io.brupdate       := io.brupdate
    mshr.io.exception    := io.exception
    mshr.io.rob_pnr_idx  := io.rob_pnr_idx
    mshr.io.rob_head_idx := io.rob_head_idx

    mshr.io.prober_state := io.prober_state

    mshr.io.wb_resp      := io.wb_resp

    meta_write_arb.io.in(i) << mshr.io.meta_write
    meta_read_arb.io.in(i)  << mshr.io.meta_read
    mshr.io.meta_resp       := io.meta_resp
    wb_req_arb.io.in(i)     << mshr.io.wb_req
    replay_arb.io.in(i)     << mshr.io.replay
    refill_arb.io.in(i)     << mshr.io.refill

    mshr.io.lb_resp            := lb(i).at(mshr.io.lb_read.offset)
    when (mshr.io.lb_write.valid) {
      lb(i).write(mshr.io.lb_write.payload.offset)(_ := mshr.io.lb_write.payload.data)
    }

    commit_vals(i)  := mshr.io.commit_val
    commit_addrs(i) := mshr.io.commit_addr
    commit_cohs(i)  := mshr.io.commit_coh

    // Chisel: mem_grant defaults to invalid/DontCare; `<>` io.mem_grant when the source matches
    mshr.io.mem_grant.valid   := False
    mshr.io.mem_grant.payload := io.mem_grant.payload
    when (io.mem_grant.payload.source === i) {
      mshr.io.mem_grant.valid := io.mem_grant.valid
      io.mem_grant.ready      := mshr.io.mem_grant.ready
    }

    sec_rdy   = sec_rdy || (mshr.io.req_sec_rdy && mshr.io.req_sec_val)
    resp_arb.io.in(i) << mshr.io.resp

    when (!mshr.io.req_pri_rdy) {
      io.fence_rdy := False
    }
    for (w <- 0 until lsuWidth) {
      when (!mshr.io.probe_rdy && idx_matches(w)(i) && io.req_is_probe(w)) {
        io.probe_rdy := False
      }
    }

    mshr
  }

  // Try to round-robin the MSHRs
  val mshr_head      = Reg(UInt(log2Ceil(cfg.nMSHRs) bits)) init 0
  mshr_alloc_idx    := RegNext(AgePriorityEncoder(mshrs.map(m=>m.io.req_pri_rdy), mshr_head).resize(log2Ceil(cfg.nMSHRs)))
  when (pri_rdy && pri_val) { mshr_head := WrapInc(mshr_head, cfg.nMSHRs) }

  io.meta_write << meta_write_arb.io.out
  io.meta_read  << meta_read_arb.io.out
  io.wb_req     << wb_req_arb.io.out

  val mmio_alloc_arb = new Arbiter(Bool(), nIOMSHRs)

  var mmio_rdy: Bool = False

  val mmios = (0 until nIOMSHRs) map { i =>
    val id = cfg.nMSHRs + 1 + i // +1 for wb unit
    val mshr = new BoomIOMSHR(id)

    mmio_alloc_arb.io.in(i).valid := mshr.io.req.ready
    mmio_alloc_arb.io.in(i).payload.assignDontCare()
    mshr.io.req.valid   := mmio_alloc_arb.io.in(i).ready
    mshr.io.req.payload.assignSomeByName(req.payload)

    mmio_rdy = mmio_rdy || mshr.io.req.ready

    mshr.io.mem_ack.payload := io.mem_grant.payload
    mshr.io.mem_ack.valid   := io.mem_grant.valid && io.mem_grant.payload.source === id
    when (io.mem_grant.payload.source === id) {
      io.mem_grant.ready := True
    }

    resp_arb.io.in(cfg.nMSHRs + i) << mshr.io.resp
    when (!mshr.io.req.ready) {
      io.fence_rdy := False
    }
    mshr
  }

  mmio_alloc_arb.io.out.ready := req.valid && !cacheable

  TLArbiter.lowestFromSeq(io.mem_acquire, mshrs.map(_.io.mem_acquire) ++ mmios.map(_.io.mem_access), (a: ChannelA) => edge.numBeats1(a))
  TLArbiter.lowestFromSeq(io.mem_finish,  mshrs.map(_.io.mem_finish), (e: ChannelE) => edge.numBeats1(e))

  val respq = new BranchKillableQueue(new BoomDCacheResp, 4, u => u.uses_ldq)
  respq.io.brupdate  := io.brupdate
  respq.io.flush     := io.exception
  respq.io.enq       << resp_arb.io.out
  io.resp            << respq.io.deq

  for (w <- 0 until lsuWidth) {
    io.req(w).ready      := (if (lsuWidth == 1) True else req_idx === w) &&
      Mux(!cacheable, mmio_rdy, sdq_rdy && Mux(idx_match(w), tag_match(w) && sec_rdy, pri_rdy))
    io.secondary_miss(w) := idx_match(w) && way_match(w) && !tag_match(w)
    io.block_hit(w)      := idx_match(w) && tag_match(w)
  }
  io.refill         << refill_arb.io.out

  val free_sdq = io.replay.fire && isWrite(io.replay.payload.uop.mem_cmd)

  io.replay << replay_arb.io.out
  io.replay.payload.data := sdq.readAsync(replay_arb.io.out.payload.sdq_id.resized)

  when (io.replay.valid || sdq_enq) {
    sdq_val := sdq_val & ~(UIntToOH(replay_arb.io.out.payload.sdq_id, cfg.nSDQ) & Fill(cfg.nSDQ, free_sdq)) |
      PriorityEncoderOH(~sdq_val(cfg.nSDQ-1 downto 0)) & Fill(cfg.nSDQ, sdq_enq)
  }

  val pfRequest = new Bundle {
    val addr = cloneOf(prefetcher.io.req_addr)
    val coh = cloneOf(prefetcher.io.req_coh)
  }
  pfRequest.addr := Mux1H(commit_vals, commit_addrs)
  pfRequest.coh := Mux1H(commit_vals, commit_cohs)
  val pfInput = FixedPipeline(commit_vals.reduce(_||_), pfRequest, 1,
    holdPayload = false, resetValid = false)
  prefetcher.io.mshr_avail := FixedPipeline.delay(pri_rdy, 1)
  prefetcher.io.req_val := pfInput.valid
  prefetcher.io.req_addr := pfInput.payload.addr
  prefetcher.io.req_coh := pfInput.payload.coh

}
