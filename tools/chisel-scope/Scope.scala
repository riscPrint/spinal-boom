//> using scala 2.13.14
//> using dep org.chipsalliance::chisel:6.5.0
//> using plugin org.chipsalliance:::chisel-plugin:6.5.0
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
class Scope extends Module {
  val c = IO(Input(Bool()))
  val x = IO(Input(UInt(8.W)))
  val sel = IO(Input(UInt(2.W)))
  val a,b,d,s = IO(Output(UInt(8.W)))
  a := 0.U; b := 0.U; d := 0.U; s := 0.U
  when(c) { a := RegNext(x) }.elsewhen(RegNext(x) === 23.U) { b := 1.U }
  switch(sel) { is(1.U) { d := RegNext(x) } }
  when(c) { s := ShiftRegister(x, 2) }
}
object Main extends App {
  java.nio.file.Files.write(java.nio.file.Paths.get("audit-results/chisel-scope.sv"), ChiselStage.emitSystemVerilog(new Scope).getBytes)
  java.nio.file.Files.write(java.nio.file.Paths.get("audit-results/chisel-scope.fir"), ChiselStage.emitCHIRRTL(new Scope).getBytes)
}
