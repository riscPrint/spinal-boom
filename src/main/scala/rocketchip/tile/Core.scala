// See LICENSE.SiFive for license details.

package rocketchip.tile

import spinal.core._
import rocketchip.config._
import rocketchip.rocket._
import rocketchip.util._

// These parameters can be varied per-core
trait CoreParams {
  val bootFreqHz: BigInt
  val useVM: Boolean
  val useHypervisor: Boolean
  val useUser: Boolean
  val useSupervisor: Boolean
  val useDebug: Boolean
  val useAtomics: Boolean
  val useAtomicsOnlyForIO: Boolean
  val useCompressed: Boolean
  val useVector: Boolean = false
  val vectorUseDCache: Boolean = false
  val useRVE: Boolean
  val useConditionalZero: Boolean
  val useZba: Boolean
  val useZbb: Boolean
  val useZbs: Boolean
  val mulDiv: Option[MulDivParams]
  val fpu: Option[FPUParams]
  val fetchWidth: Int
  val decodeWidth: Int
  val retireWidth: Int
  val instBits: Int
  val nLocalInterrupts: Int
  val useNMI: Boolean
  val nPMPs: Int
  val pmpGranularity: Int
  val nBreakpoints: Int
  val useBPWatch: Boolean
  val mcontextWidth: Int
  val scontextWidth: Int
  val nPerfCounters: Int
  val haveBasicCounters: Boolean
  val haveFSDirty: Boolean
  val misaWritable: Boolean
  val haveCFlush: Boolean
  val nL2TLBEntries: Int
  val nL2TLBWays: Int
  val nPTECacheEntries: Int
  val mtvecInit: Option[BigInt]
  val mtvecWritable: Boolean
  val traceHasWdata: Boolean
  val xLen: Int
  val pgLevels: Int
  def customIsaExt: Option[String] = None
  def customCSRs(implicit p: Parameters): CustomCSRs = new CustomCSRs

  def hasSupervisorMode: Boolean = useSupervisor || useVM
  def instBytes: Int = instBits / 8
  def fetchBytes: Int = fetchWidth * instBytes
  def lrscCycles: Int

  def dcacheReqTagBits: Int = 6

  def minFLen: Int = 32

  def vLen: Int = 0
  def eLen: Int = 0
  def vfLen: Int = 0
  def vfh: Boolean = false
  def vExts: Seq[String] = Nil
  def hasV: Boolean = vLen >= 128 && eLen >= 64 && vfLen >= 64
  def vMemDataBits: Int = 0

  def useBitmanip = useZba && useZbb && useZbs
}

trait TileParams {
  val core: CoreParams
  val icache: Option[ICacheParams]
  val dcache: Option[DCacheParams]
  val btb: Option[BTBParams]
  val tileId: Int // may not be hartid
  val blockerCtrlAddr: Option[BigInt]
}

/** These parameters values are not computed based on diplomacy negotiation. */
trait HasNonDiplomaticTileParameters {
  implicit val p: Parameters
  def tileParams: TileParams = p.tile

  def usingVM: Boolean = tileParams.core.useVM
  def usingUser: Boolean = tileParams.core.useUser || usingSupervisor
  def usingSupervisor: Boolean = tileParams.core.hasSupervisorMode
  def usingHypervisor: Boolean = usingVM && tileParams.core.useHypervisor
  def usingDebug: Boolean = tileParams.core.useDebug
  def usingRoCC: Boolean = p.system.nRoCC > 0
  def usingBTB: Boolean = tileParams.btb.isDefined && tileParams.btb.get.nEntries > 0
  def usingPTW: Boolean = usingVM
  def usingDataScratchpad: Boolean = false

  def xLen: Int = tileParams.core.xLen
  def xBytes: Int = xLen / 8
  def iLen: Int = 32
  def pgIdxBits: Int = 12
  def pgLevelBits: Int = 10 - log2Ceil(xLen / 32)
  def pgLevels: Int = tileParams.core.pgLevels
  def maxSVAddrBits: Int = pgIdxBits + pgLevels * pgLevelBits
  def maxHypervisorExtraAddrBits: Int = 2
  def hypervisorExtraAddrBits: Int = if (usingHypervisor) maxHypervisorExtraAddrBits else 0
  def maxHVAddrBits: Int = maxSVAddrBits + hypervisorExtraAddrBits
  def minPgLevels: Int = {
    val res = xLen match { case 32 => 2; case 64 => 3 }
    require(pgLevels >= res)
    res
  }
  def asIdBits: Int = p.system.asIdBits
  def vmIdBits: Int = p.system.vmIdBits
  lazy val maxPAddrBits: Int = {
    require(xLen == 32 || xLen == 64, s"Only XLENs of 32 or 64 are supported, but got $xLen")
    ((xLen, usingVM): @unchecked) match {
      case (_, false) => xLen
      case (32, true) => 34
      case (64, true) => 56
    }
  }

  def tileId: Int = tileParams.tileId

  def cacheBlockBytes: Int = p.system.cacheBlockBytes
  def lgCacheBlockBytes: Int = log2Up(cacheBlockBytes)
  def masterPortBeatBytes: Int = p.system.beatBytes

  //                  Core   PTW                DTIM                    coprocessors
  def dcacheArbPorts: Int = 1 + usingVM.toInt + usingDataScratchpad.toInt + p.system.nRoCC +
    (tileParams.core.useVector && tileParams.core.vectorUseDCache).toInt
}

/** Values that rocket-chip derives from the tile's TileLink edge. */
trait HasTileParameters extends HasNonDiplomaticTileParameters {
  lazy val paddrBits: Int = {
    val bits = p.system.paddrBits
    require(bits <= maxPAddrBits, s"Requested $bits paddr bits, but since xLen is $xLen only $maxPAddrBits will fit")
    bits
  }
  def vaddrBits: Int =
    if (usingVM) {
      val v = maxHVAddrBits
      require(v == xLen || xLen > v && v > paddrBits)
      v
    } else {
      // since virtual addresses sign-extend but physical addresses
      // zero-extend, make room for a zero sign bit for physical addresses
      (paddrBits + 1) min xLen
    }
  def vpnBits: Int = vaddrBits - pgIdxBits
  def ppnBits: Int = paddrBits - pgIdxBits
  def vpnBitsExtended: Int = vpnBits + (if (vaddrBits < xLen) 1 + usingHypervisor.toInt else 0)
  def vaddrBitsExtended: Int = vpnBitsExtended + pgIdxBits
}

trait HasCoreParameters extends HasTileParameters {
  val coreParams: CoreParams = tileParams.core

  val minFLen = coreParams.fpu.map(_ => coreParams.minFLen).getOrElse(0)
  val fLen = coreParams.fpu.map(_.fLen).getOrElse(0)

  val usingMulDiv = coreParams.mulDiv.nonEmpty
  val usingFPU = coreParams.fpu.nonEmpty
  val usingAtomics = coreParams.useAtomics
  val usingAtomicsOnlyForIO = coreParams.useAtomicsOnlyForIO
  val usingAtomicsInCache = usingAtomics && !usingAtomicsOnlyForIO
  val usingCompressed = coreParams.useCompressed
  val usingVector = coreParams.useVector
  val usingNMI = coreParams.useNMI
  val usingConditionalZero = coreParams.useConditionalZero

  val retireWidth = coreParams.retireWidth
  val fetchWidth = coreParams.fetchWidth
  val decodeWidth = coreParams.decodeWidth

  val fetchBytes = coreParams.fetchBytes
  val coreInstBits = coreParams.instBits
  val coreInstBytes = coreInstBits / 8
  val coreDataBits = xLen max fLen max vMemDataBits
  val coreDataBytes = coreDataBits / 8
  def coreMaxAddrBits = paddrBits max vaddrBitsExtended

  val nBreakpoints = coreParams.nBreakpoints
  val nPMPs = coreParams.nPMPs
  val pmpGranularity = coreParams.pmpGranularity
  val nPerfCounters = coreParams.nPerfCounters
  val mtvecInit = coreParams.mtvecInit
  val mtvecWritable = coreParams.mtvecWritable
  val customIsaExt = coreParams.customIsaExt
  val traceHasWdata = coreParams.traceHasWdata

  def vLen = coreParams.vLen
  def eLen = coreParams.eLen
  def vfLen = coreParams.vfLen
  def vMemDataBits = if (usingVector) coreParams.vMemDataBits else 0
  def maxVLMax = vLen

  if (coreParams.useVM) {
    if (coreParams.xLen == 32) {
      require(coreParams.pgLevels == 2)
    } else {
      require(coreParams.pgLevels >= 3)
    }
  }

  lazy val hartIdLen: Int = p.system.maxHartIdBits
  lazy val resetVectorLen: Int = {
    val externalLen = paddrBits
    require(externalLen <= xLen, s"External reset vector length ($externalLen) must be <= XLEN ($xLen)")
    require(externalLen <= vaddrBitsExtended, s"External reset vector length ($externalLen) must be <= virtual address bit width ($vaddrBitsExtended)")
    externalLen
  }

  // Print out log of committed instructions and their writeback values.
  // Requires post-processing due to out-of-order writebacks.
  val enableCommitLog = false
}

abstract class CoreModule(implicit val p: Parameters) extends Component with HasCoreParameters

abstract class CoreBundle(implicit p: Parameters) extends ParameterizedBundle()(p) with HasCoreParameters

trait L1CacheParams {
  def nSets: Int
  def nWays: Int
  def rowBits: Int
  def nTLBSets: Int
  def nTLBWays: Int
  def blockBytes: Int // TODO this is ignored in favor of p(CacheBlockBytes) in BaseTile
}

trait HasL1CacheParameters extends HasTileParameters {
  val cacheParams: L1CacheParams

  def nSets = cacheParams.nSets
  def blockOffBits = lgCacheBlockBytes
  def idxBits = log2Up(cacheParams.nSets)
  def untagBits = blockOffBits + idxBits
  def pgUntagBits = if (usingVM) untagBits min pgIdxBits else untagBits
  def tagBits = paddrBits - pgUntagBits
  def nWays = cacheParams.nWays
  def wayBits = log2Up(nWays)
  def isDM = nWays == 1
  def rowBits = cacheParams.rowBits
  def rowBytes = rowBits / 8
  def rowOffBits = log2Up(rowBytes)
  def nTLBSets = cacheParams.nTLBSets
  def nTLBWays = cacheParams.nTLBWays

  def cacheDataBits = cacheParams.rowBits
  def cacheDataBytes = cacheDataBits / 8
  def cacheDataBeats = (cacheBlockBytes * 8) / cacheDataBits
  def refillCycles = cacheDataBeats
}
