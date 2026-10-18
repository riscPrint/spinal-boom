// See LICENSE.SiFive for license details.

package rocketchip.rocket

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.tile.{CoreBundle, HasCoreParameters}
import rocketchip.util._

class PMPConfig extends Bundle {
  val l = Bool()
  val res = UInt(2 bits)
  val a = UInt(2 bits)
  val x = Bool()
  val w = Bool()
  val r = Bool()
}

object PMP {
  def lgAlign = 2

  def apply(reg: PMPReg): PMP = {
    val pmp = new PMP()(reg.p)
    pmp.cfg := reg.cfg
    pmp.addr := reg.addr
    pmp.mask := pmp.computeMask
    pmp
  }
}

class PMPReg(implicit p: Parameters) extends CoreBundle()(p) {
  val cfg = new PMPConfig
  val addr = UInt(paddrBits - PMP.lgAlign bits)

  def reset(): Unit = {
    cfg.a := 0
    cfg.l := False
  }

  def readAddr: UInt = if (log2Ceil(pmpGranularity) == PMP.lgAlign) addr else {
    val mask = U((BigInt(1) << (log2Ceil(pmpGranularity) - PMP.lgAlign)) - 1, addr.getWidth bits)
    Mux(napot, addr | (mask >> 1), ~(~addr | mask))
  }
  def napot: Bool = cfg.a(1)
  def torNotNAPOT: Bool = cfg.a(0)
  def tor: Bool = !napot && torNotNAPOT
  def cfgLocked: Bool = cfg.l
  def addrLocked(next: PMPReg): Bool = cfgLocked || next.cfgLocked && next.tor
}

class PMP(implicit p: Parameters) extends PMPReg {
  val mask = UInt(paddrBits bits)

  import PMP._
  def computeMask: UInt = {
    val base = Cat(addr, cfg.a(0)).asUInt | (U(pmpGranularity - 1) >> lgAlign).resize(addr.getWidth + 1)
    Cat(base & ~(base + 1), U((1 << lgAlign) - 1, lgAlign bits)).asUInt.resize(paddrBits)
  }
  private def comparand: UInt = ~(~(addr << lgAlign) | U(pmpGranularity - 1, paddrBits bits))

  private def pow2Match(x: UInt, lgSize: UInt, lgMaxSize: Int): Bool = {
    def eval(a: UInt, b: UInt, m: UInt) = ((a ^ b) & ~m) === 0
    if (lgMaxSize <= log2Ceil(pmpGranularity)) {
      eval(x, comparand, mask)
    } else {
      // break up the circuit; the MSB part will be CSE'd
      val lsbMask = mask | UIntToOH1(lgSize, lgMaxSize).resize(paddrBits)
      val msbMatch = eval(x >> lgMaxSize, comparand >> lgMaxSize, mask >> lgMaxSize)
      val lsbMatch = eval(x(lgMaxSize - 1 downto 0), comparand(lgMaxSize - 1 downto 0), lsbMask(lgMaxSize - 1 downto 0))
      msbMatch && lsbMatch
    }
  }

  private def boundMatch(x: UInt, lsbMask: UInt, lgMaxSize: Int): Bool = {
    if (lgMaxSize <= log2Ceil(pmpGranularity)) {
      x < comparand
    } else {
      // break up the circuit; the MSB part will be CSE'd
      val msbsLess = (x >> lgMaxSize) < (comparand >> lgMaxSize)
      val msbsEqual = ((x >> lgMaxSize) ^ (comparand >> lgMaxSize)) === 0
      val lsbsLess = (x(lgMaxSize - 1 downto 0) | lsbMask.resize(lgMaxSize)) < comparand(lgMaxSize - 1 downto 0)
      msbsLess || (msbsEqual && lsbsLess)
    }
  }

  private def lowerBoundMatch(x: UInt, lgSize: UInt, lgMaxSize: Int): Bool =
    !boundMatch(x, UIntToOH1(lgSize, lgMaxSize), lgMaxSize)

  private def upperBoundMatch(x: UInt, lgMaxSize: Int): Bool =
    boundMatch(x, U(0, lgMaxSize bits), lgMaxSize)

  private def rangeMatch(x: UInt, lgSize: UInt, lgMaxSize: Int, prev: PMP): Bool =
    prev.lowerBoundMatch(x, lgSize, lgMaxSize) && upperBoundMatch(x, lgMaxSize)

  private def pow2Homogeneous(x: UInt, pgLevel: UInt): Bool = {
    val maskHomogeneous = pgLevelMap { idxBits => if (idxBits > paddrBits) False else mask(idxBits - 1) }.read(pgLevel)
    maskHomogeneous || (pgLevelMap { idxBits => ((x ^ comparand) >> idxBits) =/= 0 }.read(pgLevel))
  }

  private def pgLevelMap[T](f: Int => T): Seq[T] = (0 until pgLevels).map { i =>
    f(pgIdxBits + (pgLevels - 1 - i) * pgLevelBits)
  }

  private def rangeHomogeneous(x: UInt, pgLevel: UInt, prev: PMP): Bool = {
    val beginsAfterLower = !(x < prev.comparand)
    val beginsAfterUpper = !(x < comparand)

    val pgMask = pgLevelMap { idxBits => U(((BigInt(1) << paddrBits) - (BigInt(1) << idxBits)) max 0, paddrBits bits) }.read(pgLevel)
    val endsBeforeLower = (x & pgMask) < (prev.comparand & pgMask)
    val endsBeforeUpper = (x & pgMask) < (comparand & pgMask)
    endsBeforeLower || beginsAfterUpper || (beginsAfterLower && endsBeforeUpper)
  }

  // returns whether this PMP completely contains, or contains none of, a page
  def homogeneous(x: UInt, pgLevel: UInt, prev: PMP): Bool =
    Mux(napot, pow2Homogeneous(x, pgLevel), !torNotNAPOT || rangeHomogeneous(x, pgLevel, prev))

  // returns whether this matching PMP fully contains the access
  def aligned(x: UInt, lgSize: UInt, lgMaxSize: Int, prev: PMP): Bool = if (lgMaxSize <= log2Ceil(pmpGranularity)) True else {
    val lsbMask = UIntToOH1(lgSize, lgMaxSize)
    val straddlesLowerBound = ((x >> lgMaxSize) ^ (prev.comparand >> lgMaxSize)) === 0 && (prev.comparand(lgMaxSize - 1 downto 0) & ~x(lgMaxSize - 1 downto 0)) =/= 0
    val straddlesUpperBound = ((x >> lgMaxSize) ^ (comparand >> lgMaxSize)) === 0 && (comparand(lgMaxSize - 1 downto 0) & (x(lgMaxSize - 1 downto 0) | lsbMask)) =/= 0
    val rangeAligned = !(straddlesLowerBound || straddlesUpperBound)
    val pow2Aligned = (lsbMask & ~mask(lgMaxSize - 1 downto 0)) === 0
    Mux(napot, pow2Aligned, rangeAligned)
  }

  // returns whether this PMP matches at least one byte of the access
  def hit(x: UInt, lgSize: UInt, lgMaxSize: Int, prev: PMP): Bool =
    Mux(napot, pow2Match(x, lgSize, lgMaxSize), torNotNAPOT && rangeMatch(x, lgSize, lgMaxSize, prev))
}

class PMPHomogeneityChecker(pmps: Seq[PMP])(implicit p: Parameters) {
  def apply(addr: UInt, pgLevel: UInt): Bool = {
    pmps.foldLeft((True, new PMP().getZero)) { case ((h, prev), pmp) =>
      (h && pmp.homogeneous(addr, pgLevel, prev), pmp)
    }._1
  }
}

class PMPChecker(lgMaxSize: Int)(implicit val p: Parameters) extends Component with HasCoreParameters {
  setDefinitionName(s"PMPChecker_s${lgMaxSize}")
  val io = new Bundle {
    val prv = in UInt(PRV.SZ bits)
    val pmp = in(Vec.fill(nPMPs)(new PMP))
    val addr = in UInt(paddrBits bits)
    val size = in UInt(log2Ceil(lgMaxSize + 1) bits)
    val r = out Bool()
    val w = out Bool()
    val x = out Bool()
  }

  val default = if (io.pmp.isEmpty) True else io.prv > PRV.S
  val pmp0 = new PMP().getZero
  pmp0.cfg.r := default
  pmp0.cfg.w := default
  pmp0.cfg.x := default

  val res = (io.pmp zip (pmp0 +: io.pmp)).reverse.foldLeft(pmp0) { case (prev, (pmp, prevPMP)) =>
    val hit = pmp.hit(io.addr, io.size, lgMaxSize, prevPMP)
    val ignore = default && !pmp.cfg.l
    val aligned = pmp.aligned(io.addr, io.size, lgMaxSize, prevPMP)

    val cur = CombInit(pmp)
    cur.cfg.r := aligned && (pmp.cfg.r || ignore)
    cur.cfg.w := aligned && (pmp.cfg.w || ignore)
    cur.cfg.x := aligned && (pmp.cfg.x || ignore)
    Mux(hit, cur, prev)
  }

  io.r := res.cfg.r
  io.w := res.cfg.w
  io.x := res.cfg.x
}
