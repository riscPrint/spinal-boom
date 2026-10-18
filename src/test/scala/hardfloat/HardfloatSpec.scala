package hardfloat

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import scala.util.Random

/** Combinational test wrapper: IEEE in, IEEE out, through the recoded units. */
class HFTop(expW: Int, sigW: Int) extends Component {
  val w = expW + sigW
  val io = new Bundle {
    val a, b, c = IN UInt(w bits)
    val rm = IN UInt(3 bits)
    val fma = OUT UInt(w bits)
    val fmaFlags = OUT Bits(5 bits)
    val lt, eq = OUT Bool()
    val toInt = OUT UInt(64 bits)
    val toIntFlags = OUT Bits(3 bits)
    val fromInt = OUT UInt(w bits)
    val intIn = IN UInt(64 bits)
    // div/sqrt
    val dsValid = IN Bool()
    val dsSqrt = IN Bool()
    val dsReady = OUT Bool()
    val dsOutValid = OUT Bool()
    val dsOut = OUT UInt(w bits)
  }
  val ra = recFNFromFN(expW, sigW, io.a)
  val rb = recFNFromFN(expW, sigW, io.b)
  val rc = recFNFromFN(expW, sigW, io.c)
  val fma = new MulAddRecFN(expW, sigW)
  fma.io.op := 0; fma.io.a := ra; fma.io.b := rb; fma.io.c := rc
  fma.io.roundingMode := io.rm; fma.io.detectTininess := consts.tininess_afterRounding
  io.fma := fNFromRecFN(expW, sigW, fma.io.out); io.fmaFlags := fma.io.exceptionFlags
  val cmp = new CompareRecFN(expW, sigW)
  cmp.io.a := ra; cmp.io.b := rb; cmp.io.signaling := False
  io.lt := cmp.io.lt; io.eq := cmp.io.eq
  val toI = new RecFNToIN(expW, sigW, 64)
  toI.io.in := ra; toI.io.roundingMode := io.rm; toI.io.signedOut := True
  io.toInt := toI.io.out; io.toIntFlags := toI.io.intExceptionFlags
  val fromI = new INToRecFN(64, expW, sigW)
  fromI.io.signedIn := True; fromI.io.in := io.intIn; fromI.io.roundingMode := io.rm
  fromI.io.detectTininess := consts.tininess_afterRounding
  io.fromInt := fNFromRecFN(expW, sigW, fromI.io.out)
  val ds = new DivSqrtRecFN_small(expW, sigW, 0)
  ds.io.inValid := io.dsValid; ds.io.sqrtOp := io.dsSqrt; ds.io.a := ra; ds.io.b := rb
  ds.io.roundingMode := io.rm; ds.io.detectTininess := consts.tininess_afterRounding
  io.dsReady := ds.io.inReady
  io.dsOutValid := ds.io.outValid_div || ds.io.outValid_sqrt
  io.dsOut := fNFromRecFN(expW, sigW, ds.io.out)
}

class HardfloatSpec extends AnyFunSuite {
  def compiled(expW: Int, sigW: Int) =
    SimConfig.withConfig(boom.common.SpinalBoomConfig()).workspacePath("simWorkspace").compile(new HFTop(expW, sigW))

  def randDouble(r: Random): Double = r.nextInt(8) match {
    case 0 => java.lang.Double.longBitsToDouble(r.nextLong()) // anything incl. NaN/subnormal
    case 1 => (r.nextDouble() - 0.5) * math.pow(2, r.nextInt(2100) - 1050)
    case 2 => Seq(0.0, -0.0, Double.PositiveInfinity, Double.NegativeInfinity, Double.MinPositiveValue, Double.MaxValue)(r.nextInt(6))
    case _ => (r.nextDouble() - 0.5) * math.pow(2, r.nextInt(80) - 40)
  }
  def bitsD(d: Double) = BigInt(java.lang.Double.doubleToRawLongBits(d)) & ((BigInt(1) << 64) - 1)
  def fromBits(b: BigInt) = java.lang.Double.longBitsToDouble(b.toLong)
  def sameD(hw: BigInt, ref: Double) =
    if (ref.isNaN) fromBits(hw).isNaN && hw == BigInt("7ff8000000000000", 16) else hw == bitsD(ref)

  test("double precision vs Java (RNE)") {
    compiled(11, 53).doSim(seed = 1) { dut =>
      val r = new Random(42)
      dut.io.rm #= 0; dut.io.dsValid #= false; dut.io.dsSqrt #= false
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(2)
      var fails = 0
      for (i <- 0 until 20000) {
        val a = randDouble(r); val b = randDouble(r); val c = randDouble(r); val li = r.nextLong() >> r.nextInt(64)
        dut.io.a #= bitsD(a); dut.io.b #= bitsD(b); dut.io.c #= bitsD(c); dut.io.intIn #= BigInt(li) & ((BigInt(1) << 64) - 1)
        sleep(1)
        val fmaRef = Math.fma(a, b, c)
        if (i < 3) println(s"sample fma($a,$b,$c) hw=${fromBits(dut.io.fma.toBigInt)} ref=$fmaRef")
        if (!sameD(dut.io.fma.toBigInt, fmaRef)) { fails += 1; if (fails < 10) println(f"FMA $a%s*$b%s+$c%s = ${fromBits(dut.io.fma.toBigInt)}%s ref $fmaRef%s") }
        if (!(a.isNaN || b.isNaN)) {
          if (dut.io.lt.toBoolean != (a < b) || dut.io.eq.toBoolean != (a == b)) { fails += 1; if (fails < 10) println(s"CMP $a $b") }
        }
        if (!a.isNaN && math.abs(a) < 9.0e18) {
          val ref = math.rint(a).toLong
          if (dut.io.toInt.toBigInt != (BigInt(ref) & ((BigInt(1) << 64) - 1))) { fails += 1; if (fails < 10) println(s"TOINT $a ${dut.io.toInt.toBigInt} ref $ref") }
        }
        if (!sameD(dut.io.fromInt.toBigInt, li.toDouble)) { fails += 1; if (fails < 10) println(s"FROMINT $li") }
      }
      // div / sqrt
      for (i <- 0 until 3000) {
        val a = randDouble(r); val b = randDouble(r); val sq = r.nextBoolean()
        dut.io.a #= bitsD(a); dut.io.b #= bitsD(b); dut.io.dsSqrt #= sq; dut.io.dsValid #= true
        dut.clockDomain.waitSamplingWhere(dut.io.dsReady.toBoolean)
        dut.io.dsValid #= false
        dut.clockDomain.waitSamplingWhere(dut.io.dsOutValid.toBoolean)
        val ref = if (sq) math.sqrt(a) else a / b
        if (i < 3) println(s"sample ds sqrt=$sq ($a,$b) hw=${fromBits(dut.io.dsOut.toBigInt)} ref=$ref")
        if (!sameD(dut.io.dsOut.toBigInt, ref)) { fails += 1; if (fails < 20) println(s"DS sqrt=$sq $a $b got ${fromBits(dut.io.dsOut.toBigInt)} ref $ref") }
      }
      assert(fails == 0)
    }
  }
}
