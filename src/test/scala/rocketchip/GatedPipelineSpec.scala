package rocketchip

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite
import rocketchip.util.GatedPipeline

/** Each helper output beside the hand-written `when(E) { q := x }` chain it replaces. */
class GatedPipelineTestTop extends Component {
  val io = new Bundle {
    val data = in UInt(16 bits)
    val en = in Bits(3 bits)
    val outer = in Bool()
    val got = out Vec(UInt(16 bits), 4)
    val want = out Vec(UInt(16 bits), 4)
    val v = in Bool()
    val gotFlow, wantFlow = out(Flow(UInt(16 bits)))
  }
  val e = io.en.asBools
  // Shared enable, two stages.
  io.got(0) := GatedPipeline.delay(io.data, e(0), 2)
  // Three stages with different enables.
  io.got(1) := GatedPipeline.delay(io.data, Seq(e(0), e(1), e(2)))
  // One stage, then a stage sharing another chain's enable.
  io.got(2) := GatedPipeline.delay(GatedPipeline.delay(io.data, e(1), 1), e(0), 1)
  // Called inside a when: the enable passed in is the whole condition.
  val inner = UInt(16 bits)
  inner := 0
  when(io.outer) { inner := GatedPipeline.delay(io.data, io.outer && e(2), 1) }
  io.got(3) := inner

  val s0, s1, m0, m1, m2, c0, c1, w0 = Reg(UInt(16 bits))
  when(e(0)) { s0 := io.data; s1 := s0 }
  when(e(0)) { m0 := io.data }
  when(e(1)) { m1 := m0 }
  when(e(2)) { m2 := m1 }
  when(e(1)) { c0 := io.data }
  when(e(0)) { c1 := c0 }
  val ref = UInt(16 bits)
  ref := 0
  when(io.outer) {
    when(e(2)) { w0 := io.data }
    ref := w0
  }
  io.want(0) := s1
  io.want(1) := m2
  io.want(2) := c1
  io.want(3) := ref

  // Reset-valid flow, two stages under one enable (the D-cache read-tag shape).
  io.gotFlow := GatedPipeline.flow(io.v, io.data, e(1), 2)
  val f0v, f1v = Reg(Bool()) init False
  val f0d, f1d = Reg(UInt(16 bits))
  when(e(1)) { f0v := io.v; f0d := io.data; f1v := f0v; f1d := f0d }
  io.wantFlow.valid := f1v
  io.wantFlow.payload := f1d
}

class GatedPipelineSpec extends AnyFunSuite {
  test("gated stages match enabled register chains, held values included") {
    SimConfig.withConfig(boom.common.SpinalBoomConfig()).workspacePath("simWorkspace")
      .compile(new GatedPipelineTestTop).doSim(seed = 1) { dut =>
        dut.io.data #= 0
        dut.io.en #= 7
        dut.io.outer #= true
        dut.io.v #= false
        dut.clockDomain.forkStimulus(10)
        val random = new scala.util.Random(7)
        var held = 0
        for (cycle <- 0 until 400) {
          dut.clockDomain.waitFallingEdge()
          dut.io.data #= random.nextInt(65536)
          // Fill every register first, then randomise the enables.
          dut.io.en #= (if (cycle < 4) 7 else random.nextInt(8))
          dut.io.outer #= (cycle < 4 || random.nextInt(4) != 0)
          dut.io.v #= random.nextBoolean()
          dut.clockDomain.waitRisingEdge()
          sleep(1)
          if (cycle >= 4) for (i <- 0 until 4) {
            assert(dut.io.got(i).toInt == dut.io.want(i).toInt, s"case $i cycle $cycle")
          }
          // Valid compares from reset; payload once it has been written.
          assert(dut.io.gotFlow.valid.toBoolean == dut.io.wantFlow.valid.toBoolean, s"flow valid cycle $cycle")
          if (cycle >= 4 && dut.io.wantFlow.valid.toBoolean)
            assert(dut.io.gotFlow.payload.toInt == dut.io.wantFlow.payload.toInt, s"flow cycle $cycle")
          if (cycle >= 4 && dut.io.en.toInt != 7) held += 1
        }
        assert(held > 100)
      }
  }

  test("gated stages add no valid, reset or ready register") {
    val report = SpinalConfig(targetDirectory = "simWorkspace/gated-rtl").generateVerilog {
      new Component {
        val io = new Bundle {
          val d = in UInt(8 bits); val e0, e1 = in Bool(); val q = out UInt(8 bits)
        }
        io.q := GatedPipeline.delay(io.d, Seq(io.e0, io.e1))
      }.setDefinitionName("GatedRtl")
    }
    val rtl = scala.io.Source.fromFile("simWorkspace/gated-rtl/GatedRtl.v").mkString
    val regs = "(?m)^\\s*reg\\s+.*$".r.findAllIn(rtl).toList
    assert(regs.size == 2 && regs.forall(_.contains("[7:0]")), rtl)
    // reset is only the clock-domain port; no register uses it.
    assert("reset".r.findAllIn(rtl).size == 1, rtl)
    assert(rtl.contains("if(io_e0) begin") && rtl.contains("if(io_e1) begin"), rtl)
  }

  test("RegNext declared inside a when is free-running, so it is not a gated stage") {
    SpinalConfig(targetDirectory = "simWorkspace/gated-rtl").generateVerilog {
      new Component {
        val c, d = in Bool(); val x = in UInt(4 bits); val a, b = out UInt(4 bits)
        a := 0; b := 0
        when(c) { a := RegNext(x) }
        when(c) { when(RegNext(d)) { b := x } }
      }.setDefinitionName("RegNextScope")
    }
    val rtl = scala.io.Source.fromFile("simWorkspace/gated-rtl/RegNextScope.v").mkString
    assert("(?s)always @\\(posedge clk\\) begin\\s*x_regNext <= x;".r.findFirstIn(rtl).isDefined, rtl)
    assert("(?s)always @\\(posedge clk\\) begin\\s*d_regNext <= d;".r.findFirstIn(rtl).isDefined, rtl)
  }
}
