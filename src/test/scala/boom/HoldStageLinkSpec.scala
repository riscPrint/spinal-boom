package boom

import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.pipeline._
import org.scalatest.funsuite.AnyFunSuite
import boom.common.SpinalBoomConfig
import boom.util.HoldStageLink

class HoldStageTestTop extends Component {
  val io = new Bundle {
    val valid, drain = in Vec(Bool(), 3)
    val data = in Vec(UInt(16 bits), 3)
    val ready, flush = in Bool()
    val rewrite = in UInt(16 bits)
    val actualValid, referenceValid = out Vec(Bool(), 6)
    val actualData, referenceData = out Vec(UInt(16 bits), 6)
    val accepted = out Vec(Bool(), 6)
  }
  for (resetValid <- Seq(true, false); lane <- 0 until 3) {
    val index = (if (resetValid) 0 else 3) + lane
    val up, down = Node()
    val DATA = Payload(UInt(16 bits))
    val link = new HoldStageLink(up, down, resetValid)
    up.valid := io.valid(lane)
    up(DATA) := io.data(lane)
    down.ready := io.ready
    link.flushWhen(io.flush)
    link.drainWhen(io.drain(lane))
    if (lane >= 1) link.hold(DATA) := down(DATA) ^ U(0x1200, 16 bits)
    if (lane == 2) link.next(DATA) := link.selected(DATA) + io.rewrite
    io.actualValid(index) := down.isValid
    io.actualData(index) := down(DATA)
    // Exercise both an explicitly requested upstream ready and an out-of-band accept.
    io.accepted(index) := (if (resetValid) up.ready else link.accept)
    Builder(link)

    val valid = Reg(Bool())
    if (resetValid) valid.init(False)
    val data = Reg(UInt(16 bits))
    val selected = UInt(16 bits)
    selected := (if (lane >= 1) data ^ U(0x1200, 16 bits) else data)
    when(io.flush) {
      valid := False
    } elsewhen(io.ready) {
      valid := io.valid(lane)
      selected := io.data(lane)
    } otherwise {
      valid := valid && !io.drain(lane)
    }
    data := (if (lane == 2) selected + io.rewrite else selected)
    io.referenceValid(index) := valid
    io.referenceData(index) := data
  }
}

class HoldStageLinkSpec extends AnyFunSuite {
  test("held links match original per-lane registers including invalid rewrites and reset") {
    SimConfig.withConfig(SpinalBoomConfig()).workspacePath("simWorkspace")
      .compile(new HoldStageTestTop).doSim(seed = 1) { dut =>
        dut.io.valid.foreach(_ #= false)
        dut.io.drain.foreach(_ #= false)
        dut.io.data.foreach(_ #= 0)
        dut.io.ready #= true
        dut.io.flush #= false
        dut.io.rewrite #= 0
        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(8)
        val random = new scala.util.Random(42)
        for (cycle <- 0 until 500) {
          dut.clockDomain.waitFallingEdge()
          // An initial unconditional accept synchronises the uninitialised registers.
          val ready = cycle == 0 || random.nextBoolean()
          val flush = cycle != 0 && cycle % 13 == 0
          dut.io.ready #= ready
          dut.io.flush #= flush
          dut.io.rewrite #= random.nextInt(65536)
          for (lane <- 0 until 3) {
            dut.io.valid(lane) #= random.nextBoolean()
            dut.io.drain(lane) #= random.nextBoolean()
            dut.io.data(lane) #= random.nextInt(65536)
          }
          if (cycle == 170 || cycle == 171) dut.clockDomain.assertReset()
          else dut.clockDomain.deassertReset()
          sleep(1)
          dut.io.accepted.foreach(a => assert(a.toBoolean == (ready && !flush)))
          dut.clockDomain.waitRisingEdge()
          sleep(1)
          for (i <- 0 until 6) {
            assert(dut.io.actualValid(i).toBoolean == dut.io.referenceValid(i).toBoolean,
              s"valid lane $i cycle $cycle")
            assert(dut.io.actualData(i).toInt == dut.io.referenceData(i).toInt,
              s"payload lane $i cycle $cycle")
          }
        }
      }
  }
}
