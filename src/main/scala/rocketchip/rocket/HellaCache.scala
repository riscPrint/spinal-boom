// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package rocketchip.rocket

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.tile._
import rocketchip.tilelink.ClientMetadata
import rocketchip.util._
import rocketchip.rocket.constants.MemoryOpConstants

case class DCacheParams(
  nSets: Int = 64,
  nWays: Int = 4,
  rowBits: Int = 64,
  subWordBits: Option[Int] = None,
  replacementPolicy: String = "random",
  nTLBSets: Int = 1,
  nTLBWays: Int = 32,
  nTLBBasePageSectors: Int = 4,
  nTLBSuperpages: Int = 4,
  tagECC: Option[String] = None,
  dataECC: Option[String] = None,
  dataECCBytes: Int = 1,
  nMSHRs: Int = 1,
  nSDQ: Int = 17,
  nRPQ: Int = 16,
  nMMIOs: Int = 1,
  blockBytes: Int = 64,
  separateUncachedResp: Boolean = false,
  acquireBeforeRelease: Boolean = false,
  pipelineWayMux: Boolean = false,
  clockGate: Boolean = false,
  scratch: Option[BigInt] = None) extends L1CacheParams {

  require(tagECC.isEmpty && dataECC.isEmpty, "ECC is not supported by this port")

  def dataScratchpadBytes: Int = scratch.map(_ => nSets * blockBytes).getOrElse(0)

  def silentDrop: Boolean = !acquireBeforeRelease

  require((!scratch.isDefined || nWays == 1),
    "Scratchpad only allowed in direct-mapped cache.")
  require((!scratch.isDefined || nMSHRs == 0),
    "Scratchpad only allowed in blocking cache.")
  if (scratch.isEmpty)
    require(isPow2(nSets), s"nSets($nSets) must be pow2")
}

case class ICacheParams(
  nSets: Int = 64,
  nWays: Int = 4,
  rowBits: Int = 128,
  nTLBSets: Int = 1,
  nTLBWays: Int = 32,
  nTLBBasePageSectors: Int = 4,
  nTLBSuperpages: Int = 4,
  cacheIdBits: Int = 0,
  tagECC: Option[String] = None,
  dataECC: Option[String] = None,
  itimAddr: Option[BigInt] = None,
  prefetch: Boolean = false,
  blockBytes: Int = 64,
  latency: Int = 2,
  fetchBytes: Int = 4) extends L1CacheParams {
  require(tagECC.isEmpty && dataECC.isEmpty, "ECC is not supported by this port")
}

trait HasL1ICacheParameters extends HasL1CacheParameters with HasCoreParameters {
  val cacheParams: ICacheParams = tileParams.icache.get
}

case class BTBParams(
  nEntries: Int = 28,
  nMatchBits: Int = 14,
  nPages: Int = 6,
  nRAS: Int = 6,
  updatesOutOfOrder: Boolean = false)

trait HasL1HellaCacheParameters extends HasL1CacheParameters with HasCoreParameters {
  val cacheParams: DCacheParams = tileParams.dcache.get
  val cfg = cacheParams

  def wordBits = coreDataBits
  def wordBytes = coreDataBytes
  def subWordBits = cacheParams.subWordBits.getOrElse(wordBits)
  def subWordBytes = subWordBits / 8
  def wordOffBits = log2UpChisel(wordBytes)
  def beatBytes = cacheBlockBytes / cacheDataBeats
  def beatWords = beatBytes / wordBytes
  def beatOffBits = log2UpChisel(beatBytes)
  def idxMSB = untagBits - 1
  def idxLSB = blockOffBits
  def offsetmsb = idxLSB - 1
  def offsetlsb = wordOffBits
  def rowWords = rowBits / wordBits
  def doNarrowRead = coreDataBits * nWays % rowBits == 0
  def eccBytes = cacheParams.dataECCBytes
  val eccBits = cacheParams.dataECCBytes * 8
  val encBits = eccBits
  val encWordBits = encBits * (wordBits / eccBits)
  def encDataBits = coreDataBits
  def encRowBits = encDataBits * rowWords
  def lrscCycles = coreParams.lrscCycles // ISA requires 16-insn LRSC sequences to succeed
  def lrscBackoff = 3 // disallow LRSC reacquisition briefly
  def blockProbeAfterGrantCycles = 8 // give the processor some time to issue a request after a grant
  def nIOMSHRs = cacheParams.nMMIOs
  def maxUncachedInFlight = cacheParams.nMMIOs
  def dataScratchpadSize = cacheParams.dataScratchpadBytes

  require(rowBits >= coreDataBits, s"rowBits($rowBits) < coreDataBits($coreDataBits)")
  if (!usingDataScratchpad)
    require(rowBits == cacheDataBits, s"rowBits($rowBits) != cacheDataBits($cacheDataBits)")
  // would need offset addr for puts if data width < xlen
  require(xLen <= cacheDataBits, s"xLen($xLen) > cacheDataBits($cacheDataBits)")
}

abstract class L1HellaCacheModule(implicit val p: Parameters) extends Component with HasL1HellaCacheParameters

abstract class L1HellaCacheBundle(implicit p: Parameters) extends ParameterizedBundle()(p) with HasL1HellaCacheParameters

/** Bundle definitions for HellaCache interfaces */

trait HasCoreMemOp extends Bundle with HasL1HellaCacheParameters {
  val addr = UInt(coreMaxAddrBits bits)
  val idx  = if (usingVM && untagBits > pgIdxBits) UInt(coreMaxAddrBits bits) else null
  val tag  = UInt(coreParams.dcacheReqTagBits + log2Ceil(dcacheArbPorts) bits)
  val cmd  = UInt(MemoryOpConstants.M_SZ bits)
  val size = UInt(log2Ceil(log2Ceil(coreDataBytes) + 1) bits)
  val signed = Bool()
  val dprv = UInt(PRV.SZ bits)
  val dv = Bool()
}

trait HasCoreData extends Bundle with HasCoreParameters {
  val data = UInt(coreDataBits bits)
  val mask = UInt(coreDataBytes bits)
}

class HellaCacheReqInternal(implicit p: Parameters) extends CoreBundle()(p) with HasCoreMemOp {
  val phys = Bool()
  val no_resp = Bool() // The dcache may omit generating a response for this request
  val no_alloc = Bool()
  val no_xcpt = Bool()
}

class HellaCacheReq(implicit p: Parameters) extends HellaCacheReqInternal()(p) with HasCoreData

class HellaCacheResp(implicit p: Parameters) extends CoreBundle()(p)
  with HasCoreMemOp
  with HasCoreData {
  val replay = Bool()
  val has_data = Bool()
  val data_word_bypass = UInt(coreDataBits bits)
  val data_raw = UInt(coreDataBits bits)
  val store_data = UInt(coreDataBits bits)
}

class AlignmentExceptions extends Bundle {
  val ld = Bool()
  val st = Bool()
}

class HellaCacheExceptions extends Bundle {
  val ma = new AlignmentExceptions
  val pf = new AlignmentExceptions
  val gf = new AlignmentExceptions
  val ae = new AlignmentExceptions
}

class HellaCacheWriteData(implicit p: Parameters) extends CoreBundle()(p) with HasCoreData

class HellaCachePerfEvents extends Bundle {
  val acquire = Bool()
  val release = Bool()
  val grant = Bool()
  val tlbMiss = Bool()
  val blocked = Bool()
  val canAcceptStoreThenLoad = Bool()
  val canAcceptStoreThenRMW = Bool()
  val canAcceptLoadThenLoad = Bool()
  val storeBufferEmptyAfterLoad = Bool()
  val storeBufferEmptyAfterStore = Bool()
}

// interface between D$ and processor/DTLB; master is the processor side
class HellaCacheIO(implicit p: Parameters) extends CoreBundle()(p) with IMasterSlave {
  val req = Stream(new HellaCacheReq)
  val s1_kill = Bool() // kill previous cycle's req
  val s1_data = new HellaCacheWriteData() // data for previous cycle's req
  val s2_nack = Bool() // req from two cycles ago is rejected
  val s2_nack_cause_raw = Bool() // reason for nack is store-load RAW hazard (performance hint)
  val s2_kill = Bool() // kill req from two cycles ago
  val s2_uncached = Bool() // advisory signal that the access is MMIO
  val s2_paddr = UInt(paddrBits bits) // translated address

  val resp = Flow(new HellaCacheResp)
  val replay_next = Bool()
  val s2_xcpt = new HellaCacheExceptions
  val s2_gpa = UInt(vaddrBitsExtended bits)
  val s2_gpa_is_pte = Bool()
  val uncached_resp = if (tileParams.dcache.get.separateUncachedResp) Stream(new HellaCacheResp) else null
  val ordered = Bool()
  val store_pending = Bool() // there is a store in a store buffer somewhere
  val perf = new HellaCachePerfEvents()

  val keep_clock_enabled = Bool() // should D$ avoid clock-gating itself?
  val clock_enabled = Bool() // is D$ currently being clocked?

  override def asMaster(): Unit = {
    master(req)
    out(s1_kill, s1_data, s2_kill, keep_clock_enabled)
    slave(resp)
    in(s2_nack, s2_nack_cause_raw, s2_uncached, s2_paddr, replay_next, s2_xcpt, s2_gpa, s2_gpa_is_pte,
      ordered, store_pending, perf, clock_enabled)
    if (uncached_resp != null) slave(uncached_resp)
  }
}

/** Metadata array used for all HellaCaches */

class L1Metadata(implicit p: Parameters) extends L1HellaCacheBundle()(p) {
  val coh = new ClientMetadata
  val tag = UInt(tagBits bits)
}

object L1Metadata {
  def apply(tag: UInt, coh: ClientMetadata)(implicit p: Parameters): L1Metadata = {
    val meta = new L1Metadata
    meta.tag := tag.resized
    meta.coh := coh
    meta
  }
}

class L1MetaReadReq(implicit p: Parameters) extends L1HellaCacheBundle()(p) {
  val idx    = UInt(idxBits bits)
  val way_en = UInt(nWays bits)
  val tag    = UInt(tagBits bits)
}

class L1MetaWriteReq(implicit p: Parameters) extends L1MetaReadReq()(p) {
  val data = new L1Metadata
}

class L1MetadataArray[T <: L1Metadata](onReset: () => T)(implicit p: Parameters) extends L1HellaCacheModule()(p) {
  val rstVal = onReset()
  val io = new Bundle {
    val read = slave(Stream(new L1MetaReadReq))
    val write = slave(Stream(new L1MetaWriteReq))
    val resp = out(Vec.fill(nWays)(cloneOf(rstVal)))
  }

  val rst_cnt = Reg(UInt(log2UpChisel(nSets + 1) bits)) init 0
  val rst = rst_cnt < nSets
  val waddr = Mux(rst, rst_cnt.resize(idxBits), io.write.payload.idx)
  val wdata = Mux(rst, rstVal, io.write.payload.data).asBits
  val wmask = Mux(rst || Bool(nWays == 1), S(-1, nWays bits), io.write.payload.way_en.asSInt).asBits
  when(rst) { rst_cnt := rst_cnt + 1 }

  val metabits = rstVal.getBitsWidth
  val tag_array = Mem(Bits(nWays * metabits bits), nSets)
  val wen = rst || io.write.valid
  tag_array.write(waddr, Cat(List.fill(nWays)(wdata): _*), wen, wmask)
  val rdata = tag_array.readSyncChisel(io.read.payload.idx, io.read.fire)
  for (w <- 0 until nWays) io.resp(w).assignFromBits(rdata(w * metabits, metabits bits))

  io.read.ready := !wen // so really this could be a 6T RAM
  io.write.ready := !rst
}

class StoreGen(typ: UInt, addr: UInt, dat: UInt, maxSize: Int) {
  val size = UInt(log2UpChisel(log2UpChisel(maxSize) + 1) bits)
  size := typ.resized
  val dat_padded = dat.resize(maxSize * 8)
  def misaligned: Bool =
    (addr & ((U(1, (1 << size.getWidth) bits) << size) - 1)(log2UpChisel(maxSize) - 1 downto 0).resize(addr.getWidth)).orR

  def mask: UInt = {
    var res = U(1, 1 bits)
    for (i <- 0 until log2UpChisel(maxSize)) {
      val w = res.getWidth max (1 << i)
      val upper = Mux(addr(i), res.resize(w), U(0, w bits)) | Mux(size >= (i + 1), U((BigInt(1) << (1 << i)) - 1, w bits), U(0, w bits))
      val lower = Mux(addr(i), U(0, res.getWidth bits), res)
      res = Cat(upper, lower).asUInt
    }
    res
  }

  protected def genData(i: Int): UInt =
    if (i >= log2UpChisel(maxSize)) dat_padded
    else Mux(size === i, Fill(1 << (log2UpChisel(maxSize) - i), dat_padded((8 << i) - 1 downto 0)), genData(i + 1))

  def data: UInt = genData(0)
  def wordData: UInt = genData(2)
}

class LoadGen(typ: UInt, signed: Bool, addr: UInt, dat: UInt, zero: Bool, maxSize: Int) {
  private val size = new StoreGen(typ, addr, dat, maxSize).size

  private def genData(logMinSize: Int): UInt = {
    var res = dat
    for (i <- log2UpChisel(maxSize) - 1 to logMinSize by -1) {
      val pos = 8 << i
      val shifted = Mux(addr(i), res(2 * pos - 1 downto pos), res(pos - 1 downto 0))
      val doZero = Bool(i == 0) && zero
      val zeroed = Mux(doZero, U(0, pos bits), shifted)
      res = Cat(Mux(size === i || doZero, Fill(8 * maxSize - pos, signed && zeroed(pos - 1)), res(8 * maxSize - 1 downto pos)), zeroed).asUInt
    }
    res
  }

  def wordData: UInt = genData(2)
  def data: UInt = genData(0)
}

class AMOALU(operandBits: Int)(implicit p: Parameters) extends Component with MemoryOpConstants {
  val minXLen = 32
  val widths = (0 to log2Ceil(operandBits / minXLen)).map(minXLen << _)

  val io = new Bundle {
    val mask = IN UInt(operandBits / 8 bits)
    val cmd = IN UInt(M_SZ bits)
    val lhs = IN UInt(operandBits bits)
    val rhs = IN UInt(operandBits bits)
    val out = OUT UInt(operandBits bits)
    val out_unmasked = OUT UInt(operandBits bits)
  }

  val max = io.cmd === M_XA_MAX.U || io.cmd === M_XA_MAXU.U
  val min = io.cmd === M_XA_MIN.U || io.cmd === M_XA_MINU.U
  val add = io.cmd === M_XA_ADD.U
  val logic_and = io.cmd === M_XA_OR.U || io.cmd === M_XA_AND.U
  val logic_xor = io.cmd === M_XA_XOR.U || io.cmd === M_XA_OR.U

  val adder_out = {
    // partition the carry chain to support sub-xLen addition
    val mask = ~(U(0, operandBits bits) +: widths.init.map(w => (U(!io.mask(w / 8 - 1)) << (w - 1)).resize(operandBits))).reduce(_ | _)
    (io.lhs & mask) + (io.rhs & mask)
  }

  val less = {
    // break up the comparator so the lower parts will be CSE'd
    def isLessUnsigned(x: UInt, y: UInt, n: Int): Bool = {
      if (n == minXLen) x(n - 1 downto 0) < y(n - 1 downto 0)
      else x(n - 1 downto n / 2) < y(n - 1 downto n / 2) || x(n - 1 downto n / 2) === y(n - 1 downto n / 2) && isLessUnsigned(x, y, n / 2)
    }

    def isLess(x: UInt, y: UInt, n: Int): Bool = {
      val signed = {
        val mask = M_XA_MIN.value ^ M_XA_MINU.value
        (io.cmd & U(mask, M_SZ bits)) === U(M_XA_MIN.value & mask, M_SZ bits)
      }
      Mux(x(n - 1) === y(n - 1), isLessUnsigned(x, y, n), Mux(signed, x(n - 1), y(n - 1)))
    }

    PriorityMux(widths.reverse.map(w => (io.mask(w / 8 / 2), isLess(io.lhs, io.rhs, w))))
  }

  val minmax = Mux(Mux(less, min, max), io.lhs, io.rhs)
  val logic =
    Mux(logic_and, io.lhs & io.rhs, U(0, operandBits bits)) |
      Mux(logic_xor, io.lhs ^ io.rhs, U(0, operandBits bits))
  val out =
    Mux(add, adder_out,
      Mux(logic_and || logic_xor, logic,
        minmax))

  val wmask = CatLsbFirst(io.mask.asBools.map(b => Fill(8, b))).asUInt
  io.out := wmask & out | ~wmask & io.lhs
  io.out_unmasked := out
}

class HellaCacheArbiter(n: Int)(implicit p: Parameters) extends Component {
  val io = new Bundle {
    val requestor = Vec.fill(n)(slave(new HellaCacheIO))
    val mem = master(new HellaCacheIO)
  }

  if (n == 1) {
    io.mem <> io.requestor.head
  } else {
    val idW = log2UpChisel(n)
    val s1_id = Reg(UInt(idW bits))
    val s2_id = RegNext(s1_id)

    io.mem.keep_clock_enabled := io.requestor.map(_.keep_clock_enabled).reduce(_ || _)

    io.mem.req.valid := io.requestor.map(_.req.valid).reduce(_ || _)
    io.requestor(0).req.ready := io.mem.req.ready
    for (i <- 1 until n)
      io.requestor(i).req.ready := io.requestor(i - 1).req.ready && !io.requestor(i - 1).req.valid

    for (i <- n - 1 to 0 by -1) {
      val req = io.requestor(i).req
      def connect_s0() = {
        io.mem.req.payload := req.payload
        io.mem.req.payload.tag := Cat(req.payload.tag, U(i, idW bits)).asUInt.resized
        s1_id := i
      }
      def connect_s1() = {
        io.mem.s1_kill := io.requestor(i).s1_kill
        io.mem.s1_data := io.requestor(i).s1_data
      }
      def connect_s2() = {
        io.mem.s2_kill := io.requestor(i).s2_kill
      }

      if (i == n - 1) {
        connect_s0()
        connect_s1()
        connect_s2()
      } else {
        when(req.valid) { connect_s0() }
        when(s1_id === i) { connect_s1() }
        when(s2_id === i) { connect_s2() }
      }
    }

    if (io.mem.uncached_resp != null) io.mem.uncached_resp.ready := False

    for (i <- 0 until n) {
      val resp = io.requestor(i).resp
      val tag_hit = io.mem.resp.payload.tag(idW - 1 downto 0) === i
      resp.valid := io.mem.resp.valid && tag_hit
      io.requestor(i).s2_xcpt := io.mem.s2_xcpt
      io.requestor(i).s2_gpa := io.mem.s2_gpa
      io.requestor(i).s2_gpa_is_pte := io.mem.s2_gpa_is_pte
      io.requestor(i).ordered := io.mem.ordered
      io.requestor(i).store_pending := io.mem.store_pending
      io.requestor(i).perf := io.mem.perf
      io.requestor(i).s2_nack := io.mem.s2_nack && s2_id === i
      io.requestor(i).s2_nack_cause_raw := io.mem.s2_nack_cause_raw
      io.requestor(i).s2_uncached := io.mem.s2_uncached
      io.requestor(i).s2_paddr := io.mem.s2_paddr
      io.requestor(i).clock_enabled := io.mem.clock_enabled
      resp.payload := io.mem.resp.payload
      resp.payload.tag := (io.mem.resp.payload.tag >> idW).resized

      io.requestor(i).replay_next := io.mem.replay_next

      if (io.requestor(i).uncached_resp != null) {
        val uncached_resp = io.requestor(i).uncached_resp
        val uncached_tag_hit = io.mem.uncached_resp.payload.tag(idW - 1 downto 0) === i
        uncached_resp.valid := io.mem.uncached_resp.valid && uncached_tag_hit
        when(uncached_resp.ready && uncached_tag_hit) {
          io.mem.uncached_resp.ready := True
        }
        uncached_resp.payload := io.mem.uncached_resp.payload
        uncached_resp.payload.tag := (io.mem.uncached_resp.payload.tag >> idW).resized
      }
    }
  }
}
