//******************************************************************************
// Ported from Rocket-Chip
// See LICENSE.Berkeley and LICENSE.SiFive in Rocket-Chip for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.lsu

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.{ChannelA, ChannelB, ChannelC, ChannelD, ChannelE, M2sTransfers, SizeRange}

import rocketchip.config.{Parameters, RegionType}
import rocketchip.tile.HasL1CacheParameters
import rocketchip.tilelink._
import rocketchip.rocket._
import rocketchip.rocket.constants.MemoryOpConstants
import rocketchip.util._

import boom.common._
import boom.exu.BrUpdateInfo
import boom.util._

// rocket-chip NBDcache bundles that BOOM's D$ uses.
class L1DataReadReq(implicit p: Parameters) extends L1HellaCacheBundle()(p) {
  val way_en = UInt(nWays bits)
  val addr   = UInt(untagBits bits)
}

class L1DataWriteReq(implicit p: Parameters) extends L1DataReadReq()(p) {
  val wmask  = UInt(rowWords bits)
  val data   = UInt(encRowBits bits)
}

class WritebackReq(implicit p: Parameters) extends L1HellaCacheBundle()(p) {
  val tag = UInt(tagBits bits)
  val idx = UInt(idxBits bits)
  val source = UInt(BoomNonBlockingDCache.busParams.sourceWidth bits)
  val param = UInt(TLPermissions.cWidth bits)
  val way_en = UInt(nWays bits)
  val voluntary = Bool()
}

class BoomWritebackUnit(implicit p: Parameters) extends L1HellaCacheModule()(p) {
  val edge = BoomNonBlockingDCache.edge
  val bus = edge.bus

  val io = new Bundle {
    val req = slave(Stream(new WritebackReq))
    val meta_read = master(Stream(new L1MetaReadReq))
    val resp = out Bool()
    val idx = out(Flow(UInt(idxBits bits)))
    val data_req = master(Stream(new L1DataReadReq))
    val data_resp = in UInt(encRowBits bits)
    val mem_grant = in Bool()
    val release = master(Stream(ChannelC(bus)))
    val lsu_release = master(Stream(ChannelC(bus)))
  }

  val req = Reg(new WritebackReq)
  val s_invalid     = U(0, 3 bits)
  val s_fill_buffer = U(1, 3 bits)
  val s_lsu_release = U(2, 3 bits)
  val s_active      = U(3, 3 bits)
  val s_grant       = U(4, 3 bits)
  val state = Reg(UInt(3 bits)) init s_invalid
  val data_req_cnt = Reg(UInt(log2UpChisel(refillCycles+1) bits)) init 0
  // Read tags: two stages that advance only while filling the buffer. The
  // fired valid resets to false; the first stage clears its count on no request.
  val filling = state === s_fill_buffer
  val data_req_fired = io.data_req.fire && io.meta_read.fire
  val r2_data_req = GatedPipeline.flow(data_req_fired,
    Mux(data_req_fired, data_req_cnt, U(0, data_req_cnt.getWidth bits)), filling, 2)
  val r2_data_req_fired = r2_data_req.valid
  val r2_data_req_cnt = r2_data_req.payload
  val (_, last_beat, all_beats_done, beat_count) = edge.count(io.release)
  val wb_buffer = Reg(Vec.fill(refillCycles)(UInt(encRowBits bits)))
  val acked = Reg(Bool()) init False

  io.idx.valid       := state =/= s_invalid
  io.idx.payload     := req.idx
  io.release.valid   := False
  io.release.payload.assignDontCare()
  io.req.ready       := False
  io.meta_read.valid := False
  io.meta_read.payload.assignDontCare()
  io.data_req.valid  := False
  io.data_req.payload.assignDontCare()
  io.resp            := False
  io.lsu_release.valid := False
  io.lsu_release.payload.assignDontCare()

  val r_address = Cat(req.tag, req.idx).asUInt << blockOffBits
  val id = cfg.nMSHRs
  val probeResponse = edge.ProbeAck(
                          fromSource = req.source,
                          toAddress = r_address,
                          lgSize = U(lgCacheBlockBytes),
                          reportPermissions = req.param,
                          data = wb_buffer.at(data_req_cnt).asBits)

  val voluntaryRelease = edge.Release(
                          fromSource = U(id),
                          toAddress = r_address,
                          lgSize = U(lgCacheBlockBytes),
                          shrinkPermissions = req.param,
                          data = wb_buffer.at(data_req_cnt).asBits)

  when (state === s_invalid) {
    io.req.ready := True
    when (io.req.fire) {
      state := s_fill_buffer
      data_req_cnt := 0
      req := io.req.payload
      acked := False
    }
  } elsewhen (state === s_fill_buffer) {
    io.meta_read.valid := data_req_cnt < refillCycles
    io.meta_read.payload.idx := req.idx
    io.meta_read.payload.tag := req.tag

    io.data_req.valid := data_req_cnt < refillCycles
    io.data_req.payload.way_en := req.way_en
    io.data_req.payload.addr := ((if (refillCycles > 1)
                              Cat(req.idx, data_req_cnt(log2UpChisel(refillCycles)-1 downto 0)).asUInt
                            else req.idx) << rowOffBits).resized

    when (data_req_fired) {
      data_req_cnt := data_req_cnt + 1
    }
    when (r2_data_req_fired) {
      wb_buffer.write(r2_data_req_cnt)(_ := io.data_resp)
      when (r2_data_req_cnt === (refillCycles-1)) {
        io.resp := True
        state := s_lsu_release
        data_req_cnt := 0
      }
    }
  } elsewhen (state === s_lsu_release) {
    io.lsu_release.valid   := True
    io.lsu_release.payload := probeResponse
    when (io.lsu_release.fire) {
     state := s_active
    }
  } elsewhen (state === s_active) {
    io.release.valid   := data_req_cnt < refillCycles
    io.release.payload := Mux(req.voluntary, voluntaryRelease, probeResponse)

    when (io.mem_grant) {
      acked := True
    }
    when (io.release.fire) {
      data_req_cnt := data_req_cnt + 1
    }
    when ((data_req_cnt === (refillCycles-1)) && io.release.fire) {
      state := Mux(req.voluntary, s_grant, s_invalid)
    }
  } elsewhen (state === s_grant) {
    when (io.mem_grant) {
      acked := True
    }
    when (acked) {
      state := s_invalid
    }
  }
}

class BoomProbeUnit(implicit p: Parameters) extends L1HellaCacheModule()(p) {
  val edge = BoomNonBlockingDCache.edge
  val bus = edge.bus

  val io = new Bundle {
    val req = slave(Stream(ChannelB(bus)))
    val rep = master(Stream(ChannelC(bus)))
    val meta_read = master(Stream(new L1MetaReadReq))
    val meta_write = master(Stream(new L1MetaWriteReq))
    val wb_req = master(Stream(new WritebackReq))
    val way_en = in UInt(nWays bits)
    val wb_rdy = in Bool() // Is writeback unit currently busy? If so need to retry meta read when its done
    val mshr_rdy = in Bool() // Is MSHR ready for this request to proceed?
    val mshr_wb_rdy = out Bool() // Should we block MSHR writebacks while we finish our own?
    val block_state = in(new ClientMetadata())
    val lsu_release = master(Stream(ChannelC(bus)))

    val state = out(Flow(UInt(coreMaxAddrBits bits)))
  }

  def st(i: Int) = U(i, 4 bits)
  val s_invalid = st(0); val s_meta_read = st(1); val s_meta_resp = st(2); val s_mshr_req = st(3)
  val s_mshr_resp = st(4); val s_lsu_release = st(5); val s_release = st(6); val s_writeback_req = st(7)
  val s_writeback_resp = st(8); val s_meta_write = st(9); val s_meta_write_resp = st(10)
  val state = Reg(UInt(4 bits)) init s_invalid

  val req = Reg(ChannelB(bus))
  val req_idx = req.address(idxMSB downto idxLSB)
  val req_tag = req.address >> untagBits

  val way_en = Reg(UInt(nWays bits))
  val tag_matches = way_en.orR
  val old_coh = Reg(new ClientMetadata)
  val miss_coh = ClientMetadata.onReset
  val reply_coh = Mux(tag_matches, old_coh, miss_coh)
  val (is_dirty, report_param, new_coh) = reply_coh.onProbe(req.param.asUInt.resize(TLPermissions.bdWidth))

  io.state.valid   := state =/= s_invalid
  io.state.payload := req.address.resized

  io.req.ready := state === s_invalid
  io.rep.valid := state === s_release
  io.rep.payload := edge.ProbeAck(req, report_param)

  assert(!io.rep.valid || !edge.hasDataC(io.rep.payload.opcode),
    "ProbeUnit should not send ProbeAcks with data, WritebackUnit should handle it")

  io.meta_read.valid := state === s_meta_read
  io.meta_read.payload.idx := req_idx
  io.meta_read.payload.tag := req_tag.resized
  io.meta_read.payload.way_en := ~U(0, nWays bits)

  io.meta_write.valid := state === s_meta_write
  io.meta_write.payload.way_en := way_en
  io.meta_write.payload.idx := req_idx
  io.meta_write.payload.tag := req_tag.resized
  io.meta_write.payload.data.tag := req_tag.resized
  io.meta_write.payload.data.coh := new_coh

  io.wb_req.valid := state === s_writeback_req
  io.wb_req.payload.source := req.source.resized
  io.wb_req.payload.idx := req_idx
  io.wb_req.payload.tag := req_tag.resized
  io.wb_req.payload.param := report_param.resized
  io.wb_req.payload.way_en := way_en
  io.wb_req.payload.voluntary := False

  io.mshr_wb_rdy := !state.isOneOf(s_release, s_writeback_req, s_writeback_resp, s_meta_write, s_meta_write_resp)

  io.lsu_release.valid   := state === s_lsu_release
  io.lsu_release.payload := edge.ProbeAck(req, report_param)

  // state === s_invalid
  when (state === s_invalid) {
    when (io.req.fire) {
      state := s_meta_read
      req := io.req.payload
    }
  } elsewhen (state === s_meta_read) {
    when (io.meta_read.fire) {
      state := s_meta_resp
    }
  } elsewhen (state === s_meta_resp) {
    // we need to wait one cycle for the metadata to be read from the array
    state := s_mshr_req
  } elsewhen (state === s_mshr_req) {
    old_coh := io.block_state
    way_en := io.way_en
    // if the read didn't go through, we need to retry
    state := Mux(io.mshr_rdy && io.wb_rdy, s_mshr_resp, s_meta_read)
  } elsewhen (state === s_mshr_resp) {
    state := Mux(tag_matches && is_dirty, s_writeback_req, s_lsu_release)
  } elsewhen (state === s_lsu_release) {
    when (io.lsu_release.fire) {
      state := s_release
    }
  } elsewhen (state === s_release) {
    when (io.rep.ready) {
      state := Mux(tag_matches, s_meta_write, s_invalid)
    }
  } elsewhen (state === s_writeback_req) {
    when (io.wb_req.fire) {
      state := s_writeback_resp
    }
  } elsewhen (state === s_writeback_resp) {
    // wait for the writeback request to finish before updating the metadata
    when (io.wb_req.ready) {
      state := s_meta_write
    }
  } elsewhen (state === s_meta_write) {
    when (io.meta_write.fire) {
      state := s_meta_write_resp
    }
  } elsewhen (state === s_meta_write_resp) {
    state := s_invalid
  }
}

class BoomL1MetaReadReq(implicit p: Parameters) extends BoomBundle()(p) {
  val req = Vec.fill(lsuWidth)(new L1MetaReadReq)
}

class BoomL1DataReadReq(implicit p: Parameters) extends BoomBundle()(p) {
  val req = Vec.fill(lsuWidth)(new L1DataReadReq)
  val valid = Vec.fill(lsuWidth)(Bool())
}

abstract class AbstractBoomDataArray(implicit p: Parameters) extends BoomModule with HasL1HellaCacheParameters {
  val io = new Bundle {
    val read  = in(Vec.fill(lsuWidth)(Flow(new L1DataReadReq)))
    val write = in(Flow(new L1DataWriteReq))
    val resp  = out(Vec.fill(lsuWidth)(Vec.fill(nWays)(UInt(encRowBits bits))))
    val s1_nacks = out(Vec.fill(lsuWidth)(Bool()))
  }

  def pipeMap[T <: Data](f: Int => T): Vec[T] = Vec((0 until lsuWidth).map(f))
}

class BoomDuplicatedDataArray(implicit p: Parameters) extends AbstractBoomDataArray
{
  val waddr = io.write.payload.addr >> rowOffBits
  for (j <- 0 until lsuWidth) {

    val raddr = io.read(j).payload.addr >> rowOffBits
    for (w <- 0 until nWays) {
      val array = new SyncReadVecMem(nSets * refillCycles, rowWords, UInt(encDataBits bits)).setName(s"array_${w}_${j}")
      val data = (0 until rowWords) map (i => io.write.payload.data(encDataBits*(i+1)-1 downto encDataBits*i))
      array.write(waddr, data, io.write.payload.wmask.asBools, io.write.payload.way_en(w) && io.write.valid)
      io.resp(j)(w) := RegNext(array.read(raddr, io.read(j).valid).asBits.asUInt)
    }
    io.s1_nacks(j) := False
  }
}

/**
 * Elaboration-time description of the D$'s TileLink master port (what
 * rocket-chip's diplomacy derives for BoomNonBlockingDCache's client node).
 */
object BoomNonBlockingDCache {
  def busParams(implicit p: Parameters): tilelink.BusParameter = {
    val cfg = p.tile.dcache.get
    TLBusParams(
      addressWidth = p.system.paddrBits,
      // the refill path moves a whole row per beat
      dataWidth    = cfg.rowBits,
      // [0, nMSHRs): cached MSHRs, nMSHRs: writeback unit, then the MMIO MSHRs
      sourceCount  = cfg.nMSHRs + 1 + cfg.nMMIOs,
      emits = M2sTransfers(
        acquireT = SizeRange(cfg.blockBytes),
        acquireB = SizeRange(cfg.blockBytes),
        get      = SizeRange(1, p.tile.core.xLen / 8),
        putFull  = SizeRange(1, p.tile.core.xLen / 8)),
      probe = SizeRange(cfg.blockBytes),
      sinkWidth = BoomTileBus.sinkWidth)
  }

  def edge(implicit p: Parameters): TLEdge = TLEdge(busParams, p.tile.dcache.get.blockBytes)

  def flushOnFenceI(implicit p: Parameters): Boolean =
    p.tile.dcache.get.scratch.isEmpty && !p.system.managers.forall(m => !m.supportsAcquireT || !m.executable ||
      m.regionType >= RegionType.TRACKED || m.regionType <= RegionType.IDEMPOTENT)
}

/** Parameters shared by the tile's TileLink ports. */
object BoomTileBus {
  /** Width of the D-channel sink id the managers may use (for GrantAck). */
  val sinkWidth = 4
}

/**
 * Top level class wrapping a non-blocking dcache.
 */
class BoomNonBlockingDCache(staticIdForMetadataUseOnly: Int)(implicit p: Parameters) extends BoomModule
  with HasL1HellaCacheParameters
  with MemoryOpConstants
{
  val edge = BoomNonBlockingDCache.edge
  val bus = edge.bus

  val io = new Bundle {
    val lsu = slave(new LSUDMemIO)
    val mem = master(tilelink.Bus(bus))
  }
  val tl_out = io.mem

  require(!tileParams.core.haveCFlush || cfg.scratch.isEmpty, "CFLUSH_D_L1 instruction requires a D$")
  require(cacheParams.replacementPolicy == "random", "only random D$ replacement is ported")

  def widthMap[T <: Data](f: Int => T): Vec[T] = Vec((0 until lsuWidth).map(f))

  val t_replay = U(0, 3 bits); val t_probe = U(1, 3 bits); val t_wb = U(2, 3 bits)
  val t_mshr_meta_read = U(3, 3 bits); val t_lsu = U(4, 3 bits); val t_prefetch = U(5, 3 bits)

  val wb = new BoomWritebackUnit
  val prober = new BoomProbeUnit
  val mshrs = new BoomMSHRFile
  mshrs.io.clear_all    := io.lsu.force_order
  mshrs.io.brupdate     := io.lsu.brupdate
  mshrs.io.exception    := io.lsu.exception
  mshrs.io.rob_pnr_idx  := io.lsu.rob_pnr_idx
  mshrs.io.rob_head_idx := io.lsu.rob_head_idx

  // tags
  def onReset = L1Metadata(U(0), ClientMetadata.onReset)
  val meta = Seq.fill(lsuWidth) { new L1MetadataArray(onReset _) }
  val metaWriteArb = new Arbiter(new L1MetaWriteReq, 2)
  // 0 goes to MSHR refills, 1 goes to prober
  val metaReadArb = new Arbiter(new BoomL1MetaReadReq, 6)
  // 0 goes to MSHR replays, 1 goes to prober, 2 goes to wb, 3 goes to MSHR meta read,
  // 4 goes to pipeline, 5 goes to prefetcher

  metaReadArb.io.in.foreach(_.payload.assignDontCare())
  for (w <- 0 until lsuWidth) {
    meta(w).io.write.valid   := metaWriteArb.io.out.fire
    meta(w).io.write.payload := metaWriteArb.io.out.payload
    meta(w).io.read.valid    := metaReadArb.io.out.valid
    meta(w).io.read.payload  := metaReadArb.io.out.payload.req(w)
  }
  metaReadArb.io.out.ready  := meta.map(_.io.read.ready).reduce(_||_)
  metaWriteArb.io.out.ready := meta.map(_.io.write.ready).reduce(_||_)

  // data
  val data = new BoomDuplicatedDataArray
  val dataWriteArb = new Arbiter(new L1DataWriteReq, 2)
  // 0 goes to pipeline, 1 goes to MSHR refills
  val dataReadArb = new Arbiter(new BoomL1DataReadReq, 3)
  // 0 goes to MSHR replays, 1 goes to wb, 2 goes to pipeline
  dataReadArb.io.in.foreach(_.payload.assignDontCare())

  for (w <- 0 until lsuWidth) {
    data.io.read(w).valid   := dataReadArb.io.out.payload.valid(w) && dataReadArb.io.out.valid
    data.io.read(w).payload := dataReadArb.io.out.payload.req(w)
  }
  dataReadArb.io.out.ready := True

  data.io.write.valid   := dataWriteArb.io.out.fire
  data.io.write.payload := dataWriteArb.io.out.payload
  dataWriteArb.io.out.ready := True

  // ------------
  // New requests

  io.lsu.req.ready := metaReadArb.io.in(4).ready && dataReadArb.io.in(2).ready
  metaReadArb.io.in(4).valid := io.lsu.req.valid
  dataReadArb.io.in(2).valid := io.lsu.req.valid
  for (w <- 0 until lsuWidth) {
    // Tag read for new requests
    metaReadArb.io.in(4).payload.req(w).idx    := (io.lsu.req.payload(w).payload.addr >> blockOffBits).resized
    metaReadArb.io.in(4).payload.req(w).way_en.assignDontCare()
    metaReadArb.io.in(4).payload.req(w).tag.assignDontCare()
    // Data read for new requests
    dataReadArb.io.in(2).payload.valid(w)      := io.lsu.req.payload(w).valid
    dataReadArb.io.in(2).payload.req(w).addr   := io.lsu.req.payload(w).payload.addr.resized
    dataReadArb.io.in(2).payload.req(w).way_en := ~U(0, nWays bits)
  }

  // ------------
  // MSHR Replays
  val replay_req = Vec.fill(lsuWidth)(new BoomDCacheReq)
  replay_req.assignDontCare()
  replay_req(0).uop        := mshrs.io.replay.payload.uop
  replay_req(0).addr       := mshrs.io.replay.payload.addr
  replay_req(0).data       := mshrs.io.replay.payload.data
  replay_req(0).is_hella   := mshrs.io.replay.payload.is_hella
  mshrs.io.replay.ready    := metaReadArb.io.in(0).ready && dataReadArb.io.in(0).ready
  // Tag read for MSHR replays
  // We don't actually need to read the metadata, for replays we already know our way
  metaReadArb.io.in(0).valid                 := mshrs.io.replay.valid
  metaReadArb.io.in(0).payload.req(0).idx    := (mshrs.io.replay.payload.addr >> blockOffBits).resized
  metaReadArb.io.in(0).payload.req(0).way_en.assignDontCare()
  metaReadArb.io.in(0).payload.req(0).tag.assignDontCare()
  // Data read for MSHR replays
  dataReadArb.io.in(0).valid                 := mshrs.io.replay.valid
  dataReadArb.io.in(0).payload.req(0).addr   := mshrs.io.replay.payload.addr.resized
  dataReadArb.io.in(0).payload.req(0).way_en := mshrs.io.replay.payload.way_en
  dataReadArb.io.in(0).payload.valid         := widthMap(w => Bool(w == 0))

  // -----------
  // MSHR Meta read
  val mshr_read_req = Vec.fill(lsuWidth)(new BoomDCacheReq)
  mshr_read_req.assignDontCare()
  mshr_read_req(0).uop      := NullMicroOp
  mshr_read_req(0).addr     := (Cat(mshrs.io.meta_read.payload.tag, mshrs.io.meta_read.payload.idx).asUInt << blockOffBits).resized
  mshr_read_req(0).data.assignDontCare()
  mshr_read_req(0).is_hella := False
  metaReadArb.io.in(3).valid          := mshrs.io.meta_read.valid
  metaReadArb.io.in(3).payload.req(0) := mshrs.io.meta_read.payload
  mshrs.io.meta_read.ready            := metaReadArb.io.in(3).ready

  // -----------
  // Write-backs
  val wb_fire = wb.io.meta_read.fire && wb.io.data_req.fire
  val wb_req = Vec.fill(lsuWidth)(new BoomDCacheReq)
  wb_req.assignDontCare()
  wb_req(0).uop      := NullMicroOp
  wb_req(0).addr     := Cat(wb.io.meta_read.payload.tag, wb.io.data_req.payload.addr).asUInt.resized
  wb_req(0).data.assignDontCare()
  wb_req(0).is_hella := False
  // Couple the two decoupled interfaces of the WBUnit's meta_read and data_read
  // Can't launch data read if possibility of conflict w. write
  // Tag read for write-back
  metaReadArb.io.in(2).valid          := wb.io.meta_read.valid
  metaReadArb.io.in(2).payload.req(0) := wb.io.meta_read.payload
  wb.io.meta_read.ready := metaReadArb.io.in(2).ready && dataReadArb.io.in(1).ready
  // Data read for write-back
  dataReadArb.io.in(1).valid          := wb.io.data_req.valid
  dataReadArb.io.in(1).payload.req(0) := wb.io.data_req.payload
  dataReadArb.io.in(1).payload.valid  := widthMap(w => Bool(w == 0))
  wb.io.data_req.ready  := metaReadArb.io.in(2).ready && dataReadArb.io.in(1).ready
  assert(!(wb.io.meta_read.fire ^ wb.io.data_req.fire))

  // -------
  // Prober
  val prober_fire  = prober.io.meta_read.fire
  val prober_req   = Vec.fill(lsuWidth)(new BoomDCacheReq)
  prober_req.assignDontCare()
  prober_req(0).uop      := NullMicroOp
  prober_req(0).addr     := (Cat(prober.io.meta_read.payload.tag, prober.io.meta_read.payload.idx).asUInt << blockOffBits).resized
  prober_req(0).data.assignDontCare()
  prober_req(0).is_hella := False
  // Tag read for prober
  metaReadArb.io.in(1).valid          := prober.io.meta_read.valid
  metaReadArb.io.in(1).payload.req(0) := prober.io.meta_read.payload
  prober.io.meta_read.ready := metaReadArb.io.in(1).ready
  // Prober does not need to read data array

  // -------
  // Prefetcher
  val prefetch_fire = mshrs.io.prefetch.fire
  val prefetch_req  = Vec.fill(lsuWidth)(new BoomDCacheReq)
  prefetch_req.assignDontCare()
  prefetch_req(0) := mshrs.io.prefetch.payload
  // Tag read for prefetch
  metaReadArb.io.in(5).valid                 := mshrs.io.prefetch.valid
  metaReadArb.io.in(5).payload.req(0).idx    := (mshrs.io.prefetch.payload.addr >> blockOffBits).resized
  metaReadArb.io.in(5).payload.req(0).way_en.assignDontCare()
  metaReadArb.io.in(5).payload.req(0).tag.assignDontCare()
  mshrs.io.prefetch.ready := metaReadArb.io.in(5).ready
  // Prefetch does not need to read data array

  val oneHot0 = Vec((0 until lsuWidth).map(w => Bool(w == 0)))
  val allZero = Vec(Seq.fill(lsuWidth)(False))
  val s0_valid = Mux(io.lsu.req.fire, Vec(io.lsu.req.payload.map(_.valid)),
                 Mux(mshrs.io.replay.fire || wb_fire || prober_fire || prefetch_fire || mshrs.io.meta_read.fire,
                                        oneHot0, allZero))
  val s0_req   = Mux(io.lsu.req.fire          , Vec(io.lsu.req.payload.map(_.payload)),
                 Mux(wb_fire                  , wb_req,
                 Mux(prober_fire              , prober_req,
                 Mux(prefetch_fire            , prefetch_req,
                 Mux(mshrs.io.meta_read.fire, mshr_read_req
                                              , replay_req)))))
  val s0_type  = Mux(io.lsu.req.fire        , t_lsu,
                 Mux(wb_fire                  , t_wb,
                 Mux(prober_fire              , t_probe,
                 Mux(prefetch_fire            , t_prefetch,
                 Mux(mshrs.io.meta_read.fire, t_mshr_meta_read
                                              , t_replay)))))

  // Does this request need to send a response or nack
  val s0_send_resp_or_nack = Mux(io.lsu.req.fire, s0_valid,
    Mux(mshrs.io.replay.fire && isRead(mshrs.io.replay.payload.uop.mem_cmd), oneHot0, allZero))

  val REQ = Payload(new BoomDCacheReq)
  val SEND = Payload(Bool())
  val TAG_WAY = Payload(UInt(nWays bits))
  val META = Payload(cloneOf(meta(0).io.resp))
  val PROBE_NACK = Payload(Bool())
  val DATA_NACK = Payload(Bool())
  val WB_IDX_MATCH = Payload(Bool())
  val TYPE = Payload(cloneOf(s0_type))
  val META_WAY = Payload(UInt(nWays bits))
  val REPLAY_WAY = Payload(UInt(nWays bits))
  val WB_WAY = Payload(UInt(nWays bits))

  // Common context has no valid: every field advances on every cycle.
  val common = new Area {
    val c0, c1, c2 = Node()
    c0(TYPE) := s0_type
    c0(META_WAY) := mshrs.io.meta_read.payload.way_en
    c0(REPLAY_WAY) := mshrs.io.replay.payload.way_en
    c0(WB_WAY) := wb.io.data_req.payload.way_en
    val links = Seq(StageLink(c0, c1), StageLink(c1, c2))
  }
  val s2_store_failed = Bool()
  val lane = for (w <- 0 until lsuWidth) yield new Area {
    val s0, s0k, s1, s1k, s2 = Node()
    s0.valid := s0_valid(w)
    s0(REQ) := s0_req(w)
    s0(SEND) := s0_send_resp_or_nack(w)
    val k0 = BranchPipeline.boundary(s0, s0k, REQ, io.lsu.brupdate, False)
    k0.terminateWhen(io.lsu.exception && s0(REQ).uop.uses_ldq)
    k0.terminateWhen(s2_store_failed && io.lsu.req.fire && s0(REQ).uop.uses_stq)
    val l01 = StageLink(s0k, s1)
    val k1 = BranchPipeline.boundary(s1, s1k, REQ, io.lsu.brupdate, False)
    k1.terminateWhen(io.lsu.s1_kill(w))
    k1.terminateWhen(io.lsu.exception && s1(REQ).uop.uses_ldq)
    k1.terminateWhen(s2_store_failed && common.c1(TYPE) === t_lsu && s1(REQ).uop.uses_stq)
    val l12 = StageLink(s1k, s2)
    def links = Seq(k0, l01, k1, l12)
  }
  val s1_req = widthMap(w => lane(w).s1(REQ))
  val s1_valid = widthMap(w => lane(w).s1.isValid)

  for (w <- 0 until lsuWidth)
    assert(!(io.lsu.s1_kill(w) && !RegNext(io.lsu.req.fire) && !RegNext(io.lsu.req.payload(w).valid)))
  val s1_addr = s1_req.map(_.addr)
  val s1_nack = s1_addr.map(a => a(idxMSB downto idxLSB) === prober.io.meta_write.payload.idx && !prober.io.req.ready)
  val s1_type = common.c1(TYPE)
  val s1_mshr_meta_read_way_en = common.c1(META_WAY)
  val s1_replay_way_en = common.c1(REPLAY_WAY)
  val s1_wb_way_en = common.c1(WB_WAY)

  // tag check
  def wayMap[T <: Data](f: Int => T): Vec[T] = Vec((0 until nWays).map(f))
  val s1_tag_eq_way = widthMap(i => wayMap((w: Int) => meta(i).io.resp(w).tag === (s1_addr(i) >> untagBits)).asUInt)
  val s1_tag_match_way = widthMap(i =>
                         Mux(s1_type === t_replay, s1_replay_way_en,
                         Mux(s1_type === t_wb,     s1_wb_way_en,
                         Mux(s1_type === t_mshr_meta_read, s1_mshr_meta_read_way_en,
                           wayMap((w: Int) => s1_tag_eq_way(i)(w) && meta(i).io.resp(w).coh.isValid()).asUInt))))

  val s1_wb_idx_matches = widthMap(i => (s1_addr(i)(untagBits-1 downto blockOffBits) === wb.io.idx.payload) && wb.io.idx.valid)

  for (w <- 0 until lsuWidth) {
    io.lsu.s1_nack_advisory(w) := data.io.s1_nacks(w)
  }

  for (w <- 0 until lsuWidth) {
    lane(w).s1(TAG_WAY) := s1_tag_match_way(w)
    lane(w).s1(META) := meta(w).io.resp
    lane(w).s1(PROBE_NACK) := s1_nack(w)
    lane(w).s1(DATA_NACK) := data.io.s1_nacks(w)
    lane(w).s1(WB_IDX_MATCH) := s1_wb_idx_matches(w)
  }
  val s2_req = widthMap(w => lane(w).s2(REQ))
  val s2_type = common.c2(TYPE)
  val s2_valid = widthMap(w => lane(w).s2.isValid)
  val s2_tag_match_way = widthMap(w => lane(w).s2(TAG_WAY))
  val s2_tag_match = s2_tag_match_way.map(_.orR)
  val s2_hit_state = widthMap(i => Mux1H(s2_tag_match_way(i), lane(i).s2(META).map(_.coh)))
  val s2_has_permission = widthMap(w => s2_hit_state(w).onAccess(s2_req(w).uop.mem_cmd)._1)
  val s2_new_hit_state  = widthMap(w => s2_hit_state(w).onAccess(s2_req(w).uop.mem_cmd)._3)

  val s2_hit = widthMap(w => (s2_tag_match(w) && s2_has_permission(w) && s2_hit_state(w) === s2_new_hit_state(w) && !mshrs.io.block_hit(w)) || s2_type.isOneOf(t_replay, t_wb))
  val s2_nack = Vec.fill(lsuWidth)(Bool())
  assert(!(s2_type === t_replay && !s2_hit(0)), "Replays should always hit")
  assert(!(s2_type === t_wb && !s2_hit(0)), "Writeback should always see data hit")

  val s2_wb_idx_matches = widthMap(w => lane(w).s2(WB_IDX_MATCH))

  // lr/sc
  val debug_sc_fail_addr = Reg(UInt(coreMaxAddrBits bits)) init 0
  val debug_sc_fail_cnt  = Reg(UInt(8 bits)) init 0

  val lrsc_count = Reg(UInt(log2Ceil(lrscCycles) bits)) init 0
  val lrsc_valid = lrsc_count > lrscBackoff
  val lrsc_addr  = Reg(UInt(coreMaxAddrBits - blockOffBits bits))
  val s2_lr = s2_req(0).uop.mem_cmd === M_XLR && (!lane(0).s2(PROBE_NACK) || s2_type === t_replay)
  val s2_sc = s2_req(0).uop.mem_cmd === M_XSC && (!lane(0).s2(PROBE_NACK) || s2_type === t_replay)
  val s2_lrsc_addr_match = widthMap(w => lrsc_valid && lrsc_addr === (s2_req(w).addr >> blockOffBits))
  val s2_sc_fail = s2_sc && !s2_lrsc_addr_match(0)
  when (lrsc_count > 0) { lrsc_count := lrsc_count - 1 }
  when (s2_valid(0) && ((s2_type === t_lsu && s2_hit(0) && !s2_nack(0)) ||
                     (s2_type === t_replay && s2_req(0).uop.mem_cmd =/= M_FLUSH_ALL))) {
    when (s2_lr) {
      lrsc_count := lrscCycles - 1
      lrsc_addr := s2_req(0).addr >> blockOffBits
    }
    when (lrsc_count > 0) {
      lrsc_count := 0
    }
  }
  for (w <- 0 until lsuWidth) {
    when (s2_valid(w)                            &&
      s2_type === t_lsu                          &&
      !s2_hit(w)                                 &&
      !(s2_has_permission(w) && s2_tag_match(w)) &&
      s2_lrsc_addr_match(w)                      &&
      !s2_nack(w)) {
      lrsc_count := 0
    }
  }

  when (s2_valid(0)) {
    when (s2_req(0).addr === debug_sc_fail_addr) {
      when (s2_sc_fail) {
        debug_sc_fail_cnt := debug_sc_fail_cnt + 1
      } elsewhen (s2_sc) {
        debug_sc_fail_cnt := 0
      }
    } otherwise {
      when (s2_sc_fail) {
        debug_sc_fail_addr := s2_req(0).addr
        debug_sc_fail_cnt  := 1
      }
    }
  }
  assert(debug_sc_fail_cnt < 100, "L1DCache failed too many SCs in a row")

  val s2_data = Vec.fill(lsuWidth)(Vec.fill(nWays)(UInt(encRowBits bits)))
  for (i <- 0 until lsuWidth) {
    for (w <- 0 until nWays) {
      s2_data(i)(w) := data.io.resp(i)(w)
    }
  }

  val s2_data_muxed = widthMap(w => Mux1H(s2_tag_match_way(w), s2_data(w)))
  val s2_word_idx   = widthMap(w => if (rowWords == 1) U(0, 1 bits) else s2_req(w).addr(log2UpChisel(rowWords*wordBytes)-1 downto log2UpChisel(wordBytes)))

  // replacement policy (rocket's cacheParams.replacement)
  val replacer = new RandomReplacement(nWays)
  val s1_replaced_way_en = UIntToOH(replacer.way, nWays)
  val REPL_WAY = common.c1.insert(replacer.way)
  val s2_replaced_way_en = UIntToOH(common.c2(REPL_WAY), nWays)
  val s2_repl_meta = widthMap(i => Mux1H(s2_replaced_way_en, lane(i).s2(META).toSeq))

  // nack because of incoming probe
  val s2_nack_hit    = widthMap(w => lane(w).s2(PROBE_NACK))
  // Nack when we hit something currently being evicted
  val s2_nack_victim = widthMap(w => s2_valid(w) &&  s2_hit(w) && mshrs.io.secondary_miss(w))
  // MSHRs not ready for request
  val s2_nack_miss   = widthMap(w => s2_valid(w) && !s2_hit(w) && !mshrs.io.req(w).ready)
  // Bank conflict on data arrays
  val s2_nack_data   = widthMap(w => s2_valid(w) && lane(w).s2(DATA_NACK))
  // Can't allocate MSHR for same set currently being written back
  val s2_nack_wb     = widthMap(w => s2_valid(w) && !s2_hit(w) && s2_wb_idx_matches(w))

  s2_nack           := widthMap(w => (s2_nack_miss(w) || s2_nack_hit(w) || s2_nack_victim(w) || s2_nack_data(w) || s2_nack_wb(w)) && s2_type =/= t_replay)
  assert(!(s2_nack_data.reduce(_||_) && s2_type.isOneOf(t_replay, t_wb)))
  val s2_send_resp = widthMap(w => (
    lane(w).s2(SEND) &&
      (!(s2_nack_hit(w) || s2_nack_victim(w) || s2_nack_data(w)) || s2_type === t_replay) &&
      s2_hit(w) && isRead(s2_req(w).uop.mem_cmd)
  ))
  val s2_send_store_ack = widthMap(w => (
    lane(w).s2(SEND) && !s2_nack(w) && isWrite(s2_req(w).uop.mem_cmd) &&
      (s2_hit(w) || mshrs.io.req(w).fire)))
  val s2_send_nack = widthMap(w => (lane(w).s2(SEND) && s2_nack(w)))
  for (w <- 0 until lsuWidth)
    assert(!(s2_send_resp(w) && s2_send_nack(w)))

  // hits always send a response
  // If MSHR is not available, LSU has to replay this request later
  // If MSHR is available and this is only a store(not a amo), we don't need to wait for resp later
  s2_store_failed := s2_valid(0) && s2_nack(0) && s2_send_nack(0) && s2_req(0).uop.uses_stq

  // Miss handling
  for (w <- 0 until lsuWidth) {
    mshrs.io.req(w).valid := s2_valid(w)          &&
                            !s2_hit(w)            &&
                            !s2_nack_hit(w)       &&
                            !s2_nack_victim(w)    &&
                            !s2_nack_data(w)      &&
                            !s2_nack_wb(w)        &&
                             s2_type.isOneOf(t_lsu, t_prefetch)             &&
                            !(io.lsu.exception && s2_req(w).uop.uses_ldq)   &&
                             (isPrefetch(s2_req(w).uop.mem_cmd) ||
                              isRead(s2_req(w).uop.mem_cmd)     ||
                              isWrite(s2_req(w).uop.mem_cmd))
    assert(!(mshrs.io.req(w).valid && s2_type === t_replay), "Replays should not need to go back into MSHRs")
    mshrs.io.req(w).payload.assignDontCare()
    mshrs.io.req(w).payload.uop         := s2_req(w).uop
    mshrs.io.req(w).payload.addr        := s2_req(w).addr
    mshrs.io.req(w).payload.tag_match   := s2_tag_match(w)
    mshrs.io.req(w).payload.old_meta    := Mux(s2_tag_match(w), L1Metadata(s2_repl_meta(w).tag, s2_hit_state(w)), s2_repl_meta(w))
    mshrs.io.req(w).payload.way_en      := Mux(s2_tag_match(w), s2_tag_match_way(w), s2_replaced_way_en)

    mshrs.io.req(w).payload.data        := s2_req(w).data
    mshrs.io.req(w).payload.is_hella    := s2_req(w).is_hella
    mshrs.io.req_is_probe(w)            := s2_type === t_probe && s2_valid(w)
  }

  mshrs.io.meta_resp.valid      := !s2_nack_hit(0) || prober.io.mshr_wb_rdy
  mshrs.io.meta_resp.payload    := Mux1H(s2_tag_match_way(0), lane(0).s2(META))
  when (mshrs.io.req.map(_.fire).reduce(_||_)) { replacer.miss }
  tl_out.a << mshrs.io.mem_acquire

  // probes and releases
  prober.io.req.valid   := tl_out.b.valid && !lrsc_valid
  tl_out.b.ready        := prober.io.req.ready && !lrsc_valid
  prober.io.req.payload := tl_out.b.payload
  prober.io.way_en      := s2_tag_match_way(0)
  prober.io.block_state := s2_hit_state(0)
  metaWriteArb.io.in(1) << prober.io.meta_write
  prober.io.mshr_rdy    := mshrs.io.probe_rdy
  prober.io.wb_rdy      := (prober.io.meta_write.payload.idx =/= wb.io.idx.payload) || !wb.io.idx.valid
  mshrs.io.prober_state := prober.io.state

  // refills
  mshrs.io.mem_grant.payload := tl_out.d.payload
  when (tl_out.d.payload.source === cfg.nMSHRs) {
    // This should be ReleaseAck
    tl_out.d.ready := True
    mshrs.io.mem_grant.valid := False
  } otherwise {
    // This should be GrantData
    mshrs.io.mem_grant.valid := tl_out.d.valid
    tl_out.d.ready := mshrs.io.mem_grant.ready
  }

  dataWriteArb.io.in(1) << mshrs.io.refill
  metaWriteArb.io.in(0) << mshrs.io.meta_write

  tl_out.e << mshrs.io.mem_finish

  // writebacks
  val wbArb = new Arbiter(new WritebackReq, 2)
  // 0 goes to prober, 1 goes to MSHR evictions
  wbArb.io.in(0)       << prober.io.wb_req
  wbArb.io.in(1)       << mshrs.io.wb_req
  wb.io.req            << wbArb.io.out
  wb.io.data_resp       := s2_data_muxed(0)
  mshrs.io.wb_resp      := wb.io.resp
  wb.io.mem_grant       := tl_out.d.fire && tl_out.d.payload.source === cfg.nMSHRs

  val lsu_release_arb = new Arbiter(ChannelC(bus), 2)
  io.lsu.release << lsu_release_arb.io.out
  lsu_release_arb.io.in(0) << wb.io.lsu_release
  lsu_release_arb.io.in(1) << prober.io.lsu_release

  TLArbiter.lowest(tl_out.c, Seq((edge.numBeats1(wb.io.release.payload), wb.io.release),
                                 (edge.numBeats1(prober.io.rep.payload), prober.io.rep)))

  io.lsu.perf.release := edge.done(tl_out.c)
  io.lsu.perf.acquire := edge.done(tl_out.a)

  // load data gen
  val s2_data_word_prebypass = widthMap(w => s2_data_muxed(w) >> Cat(s2_word_idx(w), U(0, log2Ceil(coreDataBits) bits)).asUInt)
  val s2_data_word = Vec.fill(lsuWidth)(UInt(encRowBits bits))

  val loadgen = (0 until lsuWidth).map { w =>
    new LoadGen(s2_req(w).uop.mem_size, s2_req(w).uop.mem_signed, s2_req(w).addr,
                s2_data_word(w), s2_sc && Bool(w == 0), wordBytes)
  }
  // Mux between cache responses and uncache responses
  for (w <- 0 until lsuWidth) {
    io.lsu.resp(w).valid := s2_valid(w) && s2_send_resp(w)
    io.lsu.resp(w).payload.uop := s2_req(w).uop
    io.lsu.resp(w).payload.data := (loadgen(w).data | s2_sc_fail.asUInt.resized).resized
    io.lsu.resp(w).payload.is_hella := s2_req(w).is_hella

    io.lsu.nack(w).valid   := s2_valid(w) && s2_send_nack(w)
    io.lsu.nack(w).payload := s2_req(w)
    assert(!(io.lsu.nack(w).valid && s2_type =/= t_lsu))

    io.lsu.store_ack(w).valid   := s2_valid(w) && s2_send_store_ack(w) && Bool(w == 0)
    io.lsu.store_ack(w).payload := s2_req(w)
  }

  io.lsu.ll_resp << mshrs.io.resp

  // Store/amo hits
  val DATA_WORD = Payload(cloneOf(s2_data_word(0)))
  val store = new Area {
    val t2, t3, t3d, t4, t5 = Node()
    t2.valid := s2_valid(0) && s2_hit(0) && isWrite(s2_req(0).uop.mem_cmd) &&
      !s2_sc_fail && !(s2_send_nack(0) && s2_nack(0))
    t2(REQ) := s2_req(0)
    t2(DATA_WORD) := s2_data_word(0)
    t2(TAG_WAY) := s2_tag_match_way(0)
    val l23 = StageLink(t2, t3)
    val amo = CtrlLink(t3, t3d)
    val updated = amo.bypass(REQ)
    updated := t3(REQ)
    val links = Seq(l23, amo, StageLink(t3d, t4), StageLink(t4, t5))
  }
  val s3_req = store.t3d(REQ)
  val s3_valid = store.t3d.isValid
  val s3_data_word = store.t3(DATA_WORD)
  for (w <- 1 until lsuWidth) {
    assert(!(s2_valid(w) && s2_hit(w) && isWrite(s2_req(w).uop.mem_cmd) &&
                         !s2_sc_fail && !(s2_send_nack(w) && s2_nack(w))),
      "Store must go through 0th pipe in L1D")
  }

  // For bypassing
  val s4_req   = store.t4(REQ)
  val s4_valid = store.t4.isValid
  val s5_req   = store.t5(REQ)
  val s5_valid = store.t5.isValid

  val s3_bypass = widthMap(w => s3_valid && ((s2_req(w).addr >> wordOffBits) === (s3_req.addr >> wordOffBits)))
  val s4_bypass = widthMap(w => s4_valid && ((s2_req(w).addr >> wordOffBits) === (s4_req.addr >> wordOffBits)))
  val s5_bypass = widthMap(w => s5_valid && ((s2_req(w).addr >> wordOffBits) === (s5_req.addr >> wordOffBits)))

  // Store -> Load bypassing
  for (w <- 0 until lsuWidth) {
    s2_data_word(w) := Mux(s3_bypass(w), s3_req.data.resize(encRowBits),
                       Mux(s4_bypass(w), s4_req.data.resize(encRowBits),
                       Mux(s5_bypass(w), s5_req.data.resize(encRowBits),
                                         s2_data_word_prebypass(w))))
  }
  val amoalu   = new AMOALU(xLen)
  amoalu.io.mask := new StoreGen(s3_req.uop.mem_size, s3_req.addr, U(0), xLen/8).mask
  amoalu.io.cmd  := s3_req.uop.mem_cmd
  amoalu.io.lhs  := s3_data_word.resized
  amoalu.io.rhs  := store.t3(REQ).data

  store.updated.data := amoalu.io.out
  val s3_way   = store.t3(TAG_WAY)

  dataWriteArb.io.in(0).valid           := s3_valid
  dataWriteArb.io.in(0).payload.addr    := s3_req.addr.resized
  dataWriteArb.io.in(0).payload.wmask   := (if (rowWords == 1) U(1, 1 bits) else UIntToOH(s3_req.addr(rowOffBits-1 downto offsetlsb), rowWords))
  dataWriteArb.io.in(0).payload.data    := Fill(rowWords, s3_req.data)
  dataWriteArb.io.in(0).payload.way_en  := s3_way

  io.lsu.ordered := mshrs.io.fence_rdy && !s1_valid.reduce(_||_) && !s2_valid.reduce(_||_)
  Builder(lane.flatMap(_.links) ++ common.links ++ store.links)
  Seq(store.t3, store.t4, store.t5).foreach(_.valid.removeInitAssignments())
  lane.foreach(_.s2.valid.removeInitAssignments())

}
