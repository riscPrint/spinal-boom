package rocketchip

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class ScopedRegisterSpec extends AnyFunSuite {
  test("register declarations use relative scope, as measured with Chisel 6.5.0") {
    SpinalConfig(targetDirectory = "simWorkspace/scoped-register-rtl").generateVerilog {
      new Component {
        val c, e = in Bool()
        val x = in UInt(4 bits)
        val rawNext, rawReg, rawInit, rawEnable, scoped, nested, alternate = out UInt(4 bits)
        rawNext := 0; rawReg := 0; rawInit := 0; rawEnable := 0
        scoped := 0; nested := 0; alternate := 0
        when(c) {
          rawNext := RegNext(x).setName("qNext")
          val r = Reg(UInt(4 bits)).setName("qReg")
          r := x
          rawReg := r
          val i = RegInit(U(0, 4 bits)).setName("qInit")
          i := x
          rawInit := i
          rawEnable := RegNextWhen(x, e).setName("qEnable")
          scoped := RegNext(x).setName("qScoped")
          when(e) { nested := RegNext(x).setName("qNested") }
        } otherwise {
          alternate := RegNext(x).setName("qAlternate")
        }
      }.setDefinitionName("ScopedRegisters")
    }
    val src = scala.io.Source.fromFile("simWorkspace/scoped-register-rtl/ScopedRegisters.v")
    val rtl = try src.mkString finally src.close()
    // Chisel 6.5.0 / firtool 1.62.0 also load local RegNext unconditionally.
    // An explicit RegNextWhen enable remains effective.
    assert(rtl.contains("qNext <= x;") && rtl.contains("qReg <= x;") && rtl.contains("qInit <= x;"), rtl)
    assert(rtl.contains("if(e) begin"), rtl)
    assert(rtl.contains("qEnable <= x;"), rtl)
    assert(rtl.contains("qScoped <= x;") && rtl.contains("qNested <= x;") && rtl.contains("qAlternate <= x;"), rtl)
  }
  test("local RegNext registers load across disabled cycles") {
    SimConfig.withVerilator.workspacePath("simWorkspace/scoped-register-sim").compile(new Component {
      val io = new Bundle {
        val c, e = in Bool()
        val x = in UInt(8 bits)
        val a, b, d = out UInt(8 bits)
      }
      val a, b, d = UInt(8 bits)
      a := 0; b := 0; d := 0
      when(io.c) {
        a := RegNext(io.x).init(0)
        when(io.e) { b := RegNext(io.x).init(0) }
      } otherwise {
        when(io.e) { d := RegNext(io.x).init(0) }
      }
      io.a := a; io.b := b; io.d := d
    }).doSim { dut =>
      dut.io.c #= false; dut.io.e #= false; dut.io.x #= 0
      dut.clockDomain.forkStimulus(10)
      var a, b, d = 0
      val random = new scala.util.Random(7)
      for (_ <- 0 until 400) {
        dut.clockDomain.waitFallingEdge()
        val c = random.nextBoolean(); val e = random.nextBoolean(); val x = random.nextInt(256)
        dut.io.c #= c; dut.io.e #= e; dut.io.x #= x
        sleep(1)
        assert(dut.io.a.toInt == (if(c) a else 0))
        assert(dut.io.b.toInt == (if(c && e) b else 0))
        assert(dut.io.d.toInt == (if(!c && e) d else 0))
        dut.clockDomain.waitRisingEdge(); sleep(1)
        a = x
        b = x
        d = x
      }
    }
  }
}
