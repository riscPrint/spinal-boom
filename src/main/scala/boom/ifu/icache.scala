//******************************************************************************
// Copyright (c) 2017 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// ICache
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.ifu

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline.{Builder, CtrlLink, Node, StageLink}
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.{Opcode, M2sTransfers, SizeRange}
import rocketchip.config.Parameters
import rocketchip.rocket.ICacheParams
import rocketchip.tilelink.{TLEdge, TLBusParams}
import rocketchip.util._

import boom.common._
import boom.util._

class ICacheReq(implicit p: Parameters) extends BoomBundle with HasBoomFrontendParameters {
  val addr = UInt(vaddrBits bits)
}

/**
 * IO Signals leaving the ICache
 */
case class ICacheResp(fetchBytes: Int) extends Bundle
{
  val data = UInt(fetchBytes * 8 bits)
  val replay = Bool()
  val ae = Bool()
}

/**
 * ICache module. rocket-chip builds this as a LazyModule with a diplomatic
 * TileLink client node; here the TileLink master port is `io.mem`.
 *
 * @param icacheParams parameters for the icache
 * @param tileId the id of the tile
 */
class ICache(icParams: ICacheParams, staticIdForMetadataUseOnly: Int)(implicit p: Parameters) extends BoomModule
  with HasBoomFrontendParameters
{
  val enableICacheDelay = tileParams.core.asInstanceOf[BoomCoreParams].enableICacheDelay
  val icacheSinglePorted = tileParams.core.asInstanceOf[BoomCoreParams].icacheSinglePorted

  // How many bits do we intend to fetch at most every cycle?
  val wordBits = icParams.fetchBytes * 8

  val busParams = ICache.busParams(icParams)
  val edge_out = TLEdge(busParams, cacheBlockBytes)

  val io = new Bundle {
    val req = slave(Stream(new ICacheReq))
    val s1_paddr = in UInt(paddrBits bits) // delayed one cycle w.r.t. req

    val s1_kill = in Bool() // delayed one cycle w.r.t. req
    val s2_kill = in Bool() // delayed two cycles; prevents I$ miss emission
    val s2_prefetch = in Bool() // should I$ prefetch next line on a miss?

    val resp = master(Flow(ICacheResp(icParams.fetchBytes)))
    val invalidate = in Bool()

    val perf = out(new Bundle {
      val acquire = Bool()
    })

    val mem = master(tilelink.Bus(busParams))
  }
  val tl_out = io.mem

  require(isPow2(nSets) && isPow2(nWays))
  require(usingVM)
  require(pgIdxBits >= untagBits)

  // Each of these cases require some special-case handling.
  require (tl_out.d.payload.data.getWidth == wordBits)

  val s0_valid = io.req.fire
  val s0_vaddr = io.req.payload.addr

  val c0, c1, c1Out, c2 = Node()
  val c01 = StageLink(c0, c1)
  val c1c = CtrlLink(c1, c1Out)
  val c12 = StageLink(c1Out, c2)
  c0.valid := s0_valid
  c1c.terminateWhen(io.s1_kill)

  val s1_valid = c1.isValid
  val s1_tag_hit = Vec.fill(nWays)(Bool())
  val s1_hit = s1_tag_hit.reduce(_||_)
  val s2_valid = c2.isValid
  val s2_hit = c2(c1.insert(s1_hit))

  val invalidated = Reg(Bool())
  val refill_valid = Reg(Bool()) init False
  val refill_fire = tl_out.a.fire
  val s2_miss = s2_valid && !s2_hit && !RegNext(refill_valid)
  val refill_paddr = RegNextWhen(io.s1_paddr, s1_valid && !(refill_valid || s2_miss))
  val refill_tag = refill_paddr(tagBits+untagBits-1 downto untagBits)
  val refill_idx = refill_paddr(untagBits-1 downto blockOffBits)
  val d_has_data = edge_out.hasDataD(tl_out.d.payload.opcode)
  val refill_one_beat = tl_out.d.fire && d_has_data

  io.req.ready := !refill_one_beat

  val (_, _, d_done, refill_cnt) = edge_out.count(tl_out.d.fire, tl_out.d.payload.size, d_has_data)
  val refill_done = refill_one_beat && d_done
  tl_out.d.ready := True

  val repl_way = if (isDM) U(0, 1 bits) else LFSR(16, refill_fire)(log2Ceil(nWays)-1 downto 0)

  val tag_array = new SyncReadVecMem(nSets, nWays, UInt(tagBits bits)).setName("tag_array")
  val tag_rdata = if (icacheSinglePorted) {
    tag_array.read(s0_vaddr(untagBits-1 downto blockOffBits), !refill_done && s0_valid)
  } else {
    tag_array.read(s0_vaddr(untagBits-1 downto blockOffBits), io.req.valid)
  }

  tag_array.write(refill_idx, Seq.fill(nWays)(refill_tag), Seq.tabulate(nWays)(repl_way === _), refill_done)

  val vb_array = Reg(UInt(nSets*nWays bits)) init 0
  when (refill_one_beat) {
    // vb_array.bitSet(idx, value)
    val bit = Cat(repl_way, refill_idx).asUInt
    val v = refill_done && !invalidated
    vb_array := (vb_array & ~(U(1, nSets*nWays bits) |<< bit)) | (U(v).resize(nSets*nWays) |<< bit)
  }

  when (io.invalidate) {
    vb_array := 0
    invalidated := True
  }

  val s2_dout   = Vec.fill(nWays)(UInt(wordBits bits))
  val s1_bankid = Bool()

  for (i <- 0 until nWays) {
    val s1_idx = io.s1_paddr(untagBits-1 downto blockOffBits)
    val s1_tag = io.s1_paddr(tagBits+untagBits-1 downto untagBits)
    val s1_vb = vb_array(Cat(U(i, log2UpChisel(nWays) bits), s1_idx).asUInt.resize(log2Ceil(nSets*nWays)))
    val tag = tag_rdata(i)
    s1_tag_hit(i) := s1_vb && tag === s1_tag
  }
  assert(PopCount(s1_tag_hit) <= 1 || !s1_valid)

  val ramDepth = nSets * refillCycles

  val dataArrays = Seq.tabulate(nBanks) { b =>
    new SyncReadVecMem(ramDepth, nWays, UInt(wordBits/nBanks bits)).setName(s"dataArrayB${b}")
  }
  if (nBanks == 1) {
    // Use unbanked icache for narrow accesses.
    s1_bankid := False
    val array = dataArrays(0)
    def row(addr: UInt) = addr(untagBits-1 downto blockOffBits-log2Ceil(refillCycles))
    val s0_ren = s0_valid
    val wen = refill_one_beat && !invalidated

    val mem_idx = Mux(refill_one_beat, ((refill_idx << log2Ceil(refillCycles)) | refill_cnt.resized).resize(log2Ceil(ramDepth)),
                      row(s0_vaddr).resize(log2Ceil(ramDepth)))
    val wmask = UIntToOH(repl_way, 1 << repl_way.getWidth)(nWays-1 downto 0).asBools
    array.write(mem_idx, Seq.fill(nWays)(tl_out.d.payload.data.asUInt), wmask, wen)
    if (enableICacheDelay) {
      if (icacheSinglePorted)
        s2_dout := array.read(RegNext(mem_idx), RegNext(!wen && s0_ren))
      else
        s2_dout := array.read(RegNext(mem_idx), RegNext(s0_ren))
    } else {
      if (icacheSinglePorted)
        s2_dout := RegNext(array.read(mem_idx, !wen && s0_ren))
      else
        s2_dout := RegNext(array.read(mem_idx, io.req.valid))
    }
  } else {
    // Use two banks, interleaved.
    val array_0 = dataArrays(0)
    val array_1 = dataArrays(1)
    require (nBanks == 2)

    // Bank0 row's id wraps around if Bank1 is the starting bank.
    def b0Row(addr: UInt) =
      addr(untagBits-1 downto blockOffBits-log2Ceil(refillCycles)) + bank(addr)
    // Bank1 row's id stays the same regardless of which Bank has the fetch address.
    def b1Row(addr: UInt) =
      addr(untagBits-1 downto blockOffBits-log2Ceil(refillCycles))

    val BANK = c0.insert(bank(s0_vaddr))
    s1_bankid := c1(BANK).lsb

    val s0_ren = s0_valid
    val wen = (refill_one_beat && !invalidated)
    val wmask = UIntToOH(repl_way, 1 << repl_way.getWidth)(nWays-1 downto 0).asBools

    // write a refill beat across both banks.
    val refill_row = ((refill_idx << log2Ceil(refillCycles)) | refill_cnt.resized).resize(log2Ceil(ramDepth))
    val mem_idx0 = Mux(refill_one_beat, refill_row, b0Row(s0_vaddr).resize(log2Ceil(ramDepth)))
    val mem_idx1 = Mux(refill_one_beat, refill_row, b1Row(s0_vaddr).resize(log2Ceil(ramDepth)))

    val data = tl_out.d.payload.data.asUInt
    array_0.write(mem_idx0, Seq.fill(nWays)(data(wordBits/2-1 downto 0)), wmask, wen)
    array_1.write(mem_idx1, Seq.fill(nWays)(data(wordBits-1 downto wordBits/2)), wmask, wen)

    val rdata_0 = Vec.fill(nWays)(UInt(wordBits/nBanks bits))
    val rdata_1 = Vec.fill(nWays)(UInt(wordBits/nBanks bits))

    if (enableICacheDelay) {
      if (icacheSinglePorted) {
        rdata_0 := array_0.read(RegNext(mem_idx0), RegNext(!wen && s0_ren))
        rdata_1 := array_1.read(RegNext(mem_idx1), RegNext(!wen && s0_ren))
      } else {
        rdata_0 := array_0.read(RegNext(mem_idx0), RegNext(s0_ren))
        rdata_1 := array_1.read(RegNext(mem_idx1), RegNext(s0_ren))
      }
    } else {
      if (icacheSinglePorted) {
        rdata_0 := RegNext(array_0.read(mem_idx0, !wen && s0_ren))
        rdata_1 := RegNext(array_1.read(mem_idx1, !wen && s0_ren))
      } else {
        rdata_0 := RegNext(array_0.read(mem_idx0, io.req.valid))
        rdata_1 := RegNext(array_1.read(mem_idx1, io.req.valid))
      }
    }

    for (w <- 0 until nWays) {
      s2_dout(w) := Cat(rdata_1(w), rdata_0(w)).asUInt
    }
  }
  val s2_tag_hit = c2(c1.insert(s1_tag_hit))
  val s2_hit_way = OHToUInt(s2_tag_hit)
  val s2_bankid = c2(c1.insert(s1_bankid))
  val s2_way_mux = Mux1H(s2_tag_hit, s2_dout)

  val s2_unbanked_data = s2_way_mux
  val sz = s2_way_mux.getWidth
  val s2_bank0_data = s2_way_mux(sz/2-1 downto 0)
  val s2_bank1_data = s2_way_mux(sz-1 downto sz/2)

  val s2_data =
    if (nBanks == 2) {
      Mux(s2_bankid,
        Cat(s2_bank0_data, s2_bank1_data).asUInt,
        Cat(s2_bank1_data, s2_bank0_data).asUInt)
    } else {
      s2_unbanked_data
    }

  io.resp.payload.data := s2_data
  io.resp.payload.ae.assignDontCare()
  io.resp.payload.replay.assignDontCare()
  io.resp.valid := s2_valid && s2_hit

  // edge_out.Get(fromSource = 0, toAddress = block address, lgSize = lgCacheBlockBytes)
  tl_out.a.valid := s2_miss && !refill_valid && !io.s2_kill
  tl_out.a.payload.opcode := Opcode.A.GET
  tl_out.a.payload.param := 0
  tl_out.a.payload.source := 0
  tl_out.a.payload.address := ((refill_paddr >> blockOffBits) << blockOffBits).resized
  tl_out.a.payload.size := lgCacheBlockBytes
  if (tl_out.a.payload.mask != null) tl_out.a.payload.mask.setAll()
  if (tl_out.a.payload.data != null) tl_out.a.payload.data.assignDontCare()
  if (tl_out.a.payload.corrupt != null) tl_out.a.payload.corrupt := False
  tl_out.a.payload.debugId := 0

  io.perf.acquire := tl_out.a.fire

  when (!refill_valid) { invalidated := False }
  when (refill_fire) { refill_valid := True }
  when (refill_done) { refill_valid := False }

  Builder(c01, c1c, c12)

  override def toString: String = BoomCoreStringPrefix(
    "==L1-ICache==",
    "Fetch bytes   : " + cacheParams.fetchBytes,
    "Block bytes   : " + (1 << blockOffBits),
    "Word bits     : " + wordBits,
    "Sets          : " + nSets,
    "Ways          : " + nWays,
    "Refill cycles : " + refillCycles,
    "RAMs          : (" +  wordBits/nBanks + " x " + nSets*refillCycles + ") using " + nBanks + " banks",
    "" + (if (nBanks == 2) "Dual-banked" else "Single-banked"),
    "I-TLB ways    : " + cacheParams.nTLBWays + "\n")
}

object ICache {
  /** The I$'s TileLink master: block-sized Gets on a fetchBytes-wide bus. */
  def busParams(icacheParams: ICacheParams)(implicit p: Parameters): tilelink.BusParameter =
    TLBusParams(
      addressWidth = p.system.paddrBits,
      dataWidth = icacheParams.fetchBytes * 8,
      sourceCount = 1 + (if (icacheParams.prefetch) 1 else 0), // 0=refill, 1=hint
      emits = M2sTransfers(get = SizeRange(p.system.cacheBlockBytes)))
}
