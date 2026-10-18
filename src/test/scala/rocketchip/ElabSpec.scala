package rocketchip

import spinal.core._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite
import rocketchip.config._
import rocketchip.tile._
import rocketchip.rocket._
import rocketchip.tilelink.ClientMetadata

case class TestCoreParams(
  fetchWidth: Int = 4, decodeWidth: Int = 1, retireWidth: Int = 1,
  nPerfCounters: Int = 2, nBreakpoints: Int = 1, nPMPs: Int = 8
) extends CoreParams {
  val bootFreqHz: BigInt = 0
  val useVM = true
  val useHypervisor = false
  val useUser = true
  val useSupervisor = false
  val useDebug = true
  val useAtomics = true
  val useAtomicsOnlyForIO = false
  val useCompressed = true
  val useRVE = false
  val useConditionalZero = false
  val useZba = true
  val useZbb = true
  val useZbs = true
  val mulDiv = Some(MulDivParams(divEarlyOut = true))
  val fpu = Some(FPUParams(sfmaLatency = 4, dfmaLatency = 4))
  val instBits = 16
  val nLocalInterrupts = 0
  val useNMI = false
  val pmpGranularity = 4
  val useBPWatch = false
  val mcontextWidth = 0
  val scontextWidth = 0
  val haveBasicCounters = true
  val haveFSDirty = true
  val misaWritable = false
  val haveCFlush = false
  val nL2TLBEntries = 512
  val nL2TLBWays = 1
  val nPTECacheEntries = 0
  val mtvecInit = Some(BigInt(0))
  val mtvecWritable = true
  val traceHasWdata = false
  val xLen = 64
  val pgLevels = 3
  def lrscCycles = 80
}

case class TestTileParams(core: CoreParams = TestCoreParams()) extends TileParams {
  val icache = Some(ICacheParams(rowBits = 64, fetchBytes = 8))
  val dcache = Some(DCacheParams(rowBits = 64, nMSHRs = 2))
  val btb = None
  val tileId = 0
  val blockerCtrlAddr = None
}

object TestParams {
  implicit val p: Parameters = Parameters(TestTileParams(), SystemParams(paddrBits = 32,
    memoryMap = Seq(MemoryRegion("mem", Seq(AddressSet(0x80000000L, 0x0fffffffL)), RegionType.CACHED, executable = true,
      supportsAcquireT = true, supportsAcquireB = true))))
}

class RocketElabTop(implicit p: Parameters) extends Component {
  val events = new EventSets(Seq(new EventSet((m, h) => (m & h).orR, Seq(("a", () => False), ("b", () => True)))))
  val csr = new CSRFile(events)
  val alu = new ALU
  val muldiv = new MulDiv(MulDivParams(divEarlyOut = true), 64)
  val pmul = new PipelinedMultiplier(64, 3)
  val f2i = new FPToInt
  val i2f = new IntToFP(2)
  val f2f = new FPToFP(4)
  val dfma = new FPUFMAPipe(4, FType.D)
  val sfma = new FPUFMAPipe(4, FType.S)
  val amo = new AMOALU(64)
  val pmp = new PMPChecker(3)
  val bpu = new BreakpointUnit(1)
  val arb = new HellaCacheArbiter(2)
  val meta = new L1MetadataArray(() => L1Metadata(U(0), ClientMetadata.onReset))
  val fdec = new FPUDecoder
  val itlb = new TLB(true, 3, TLBConfig(1, 32))
  val ptw = new PTW(2)
  val rvc = new RVCExpander
  // Expose everything so nothing is pruned
  Seq(csr, alu, muldiv, pmul, f2i, i2f, f2f, dfma, sfma, amo, pmp, bpu, arb, meta, fdec, itlb, ptw, rvc).foreach { c =>
    c.getAllIo.foreach { io =>
      if (io.isInput) { io := io.getZero } else { val o = out(cloneOf(io)); o := io }
    }
  }
}

class ElabSpec extends AnyFunSuite {
  test("rocket units elaborate") {
    import TestParams._
    boom.common.SpinalBoomConfig("generated/test").generateVerilog(new RocketElabTop)
  }
}
