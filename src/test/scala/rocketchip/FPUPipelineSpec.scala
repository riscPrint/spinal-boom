package rocketchip

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite
import rocketchip.config.Parameters
import rocketchip.tile._

class FPUPipelineTestTop(implicit p: Parameters) extends Component {
  val latencies = Seq(1, 2, 3, 4, 6)
  val io = new Bundle {
    val valid = in Bool()
    val a, b, c, integer = in UInt(64 bits)
    val signOp, fmaMode = in UInt(2 bits)
    val validOut = out Vec(Bool(), 12)
    val dataOut = out Vec(UInt(64 bits), 12)
  }
  val input = new FPInput
  input.assignFromBits(B(0, input.getBitsWidth bits))
  input.in1 := hardfloat.recFNFromFN(11, 53, io.a)
  input.in2 := hardfloat.recFNFromFN(11, 53, io.b)
  input.in3 := hardfloat.recFNFromFN(11, 53, io.c)
  input.typeTagIn := 1
  input.typeTagOut := 1
  input.ren3 := io.fmaMode === 0
  input.swap23 := io.fmaMode === 1
  for ((latency, i) <- latencies.zipWithIndex) {
    val unit = new FPUFMAPipe(latency, FType.D)
    unit.io.in.valid := io.valid
    unit.io.in.payload := input
    io.validOut(i) := unit.io.out.valid
    io.dataOut(i) := hardfloat.fNFromRecFN(11, 53, unit.io.out.payload.data)
  }
  for ((latency, i) <- Seq(1, 2, 4).zipWithIndex) {
    val unit = new IntToFP(latency)
    unit.io.in.valid := io.valid
    unit.io.in.payload.assignFromBits(B(0, unit.io.in.payload.getBitsWidth bits))
    unit.io.in.payload.in1 := io.integer
    unit.io.in.payload.typeTagIn := 1
    unit.io.in.payload.wflags := True
    unit.io.in.payload.typ := 2 // signed 64-bit integer
    io.validOut(5 + i) := unit.io.out.valid
    io.dataOut(5 + i) := hardfloat.fNFromRecFN(11, 53, unit.io.out.payload.data)

    val sign = new FPToFP(latency)
    sign.io.in.valid := io.valid
    sign.io.in.payload := input
    sign.io.in.payload.rm := io.signOp.resized
    sign.io.lt := False
    io.validOut(8 + i) := sign.io.out.valid
    io.dataOut(8 + i) := hardfloat.fNFromRecFN(11, 53, sign.io.out.payload.data)
  }
  val toInt = new FPToInt
  toInt.io.in.valid := io.valid
  toInt.io.in.payload := input
  toInt.io.in.payload.wflags := True
  toInt.io.in.payload.rm := 1 // RTZ
  toInt.io.in.payload.typ := 2
  io.validOut(11) := toInt.io.out.valid
  io.dataOut(11) := toInt.io.out.payload.toint
}

class FPUPipelineSpec extends AnyFunSuite {
  test("FP stage latencies preserve arithmetic and back-to-back requests with bubbles") {
    implicit val p: Parameters = TestParams.p
    SimConfig.withConfig(boom.common.SpinalBoomConfig()).workspacePath("simWorkspace")
      .compile(new FPUPipelineTestTop).doSim(seed = 1) { dut =>
        dut.io.valid #= false
        dut.io.a #= 0; dut.io.b #= 0; dut.io.c #= 0; dut.io.integer #= 0
        dut.io.signOp #= 0; dut.io.fmaMode #= 0
        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(8)
        val mask = (BigInt(1) << 64) - 1
        def bits(d: Double): BigInt = BigInt(java.lang.Double.doubleToRawLongBits(d)) & mask
        val latencies = Seq(1, 2, 3, 4, 6) ++ Seq(1, 2, 4) ++ Seq(1, 2, 4) ++ Seq(1)
        var history = Vector.empty[(Boolean, Vector[BigInt])]
        val random = new scala.util.Random(42)
        for (cycle <- 0 until 250) {
          dut.clockDomain.waitFallingEdge()
          val a = random.nextInt().toDouble / 8
          val b = random.nextInt().toDouble / 16
          val c = random.nextInt().toDouble / 32
          val integer = random.nextLong()
          val signOp = cycle % 3
          val fmaMode = cycle % 3
          val valid = cycle % 13 < 9
          dut.io.valid #= valid
          dut.io.a #= bits(a); dut.io.b #= bits(b); dut.io.c #= bits(c)
          dut.io.integer #= BigInt(integer) & mask
          dut.io.signOp #= signOp; dut.io.fmaMode #= fmaMode
          val fma = fmaMode match {
            case 0 => Math.fma(a, b, c)
            case 1 => a + c
            case 2 => a * b
          }
          val sign = signOp match {
            case 0 => bits(b)
            case 1 => ~bits(b)
            case 2 => bits(a) ^ bits(b)
          }
          val signed = (bits(a) & ((BigInt(1) << 63) - 1)) | (sign & (BigInt(1) << 63))
          val expected = Vector.fill(5)(bits(fma)) ++ Vector.fill(3)(bits(integer.toDouble)) ++
            Vector.fill(3)(signed) ++ Vector(BigInt(a.toLong) & mask)
          history = (history :+ (valid, expected)).takeRight(6)
          dut.clockDomain.waitSampling()
          sleep(1)
          for ((latency, i) <- latencies.zipWithIndex if history.size >= latency) {
            val (v, data) = history(history.size - latency)
            assert(dut.io.validOut(i).toBoolean == v, s"valid output $i cycle $cycle latency $latency")
            if (v) assert(dut.io.dataOut(i).toBigInt == data(i), s"data output $i cycle $cycle latency $latency")
          }
        }
      }
  }
}
