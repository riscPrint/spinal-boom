package rocketchip

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import rocketchip.util.FixedPipeline

class PipelineTestTop extends Component {
  val io = new Bundle {
    val valid = in Bool()
    val data = in UInt(16 bits)
    val outValid = out Vec(Bool(), 12)
    val outData = out Vec(UInt(16 bits), 12)
    val delayed = out UInt(16 bits)
  }
  for ((latency, i) <- Seq(0, 1, 4).zipWithIndex; mode <- 0 until 4) {
    val result = FixedPipeline(io.valid, io.data, latency,
      holdPayload = mode % 2 == 0, resetValid = mode < 2)
    io.outValid(i * 4 + mode) := result.valid
    io.outData(i * 4 + mode) := result.payload
  }
  io.delayed := FixedPipeline.delay(io.data, 4)
}

class PipelineSpec extends AnyFunSuite {
  test("fixed pipelines preserve bubbles, payload enables and valid reset policy") {
    SimConfig.withConfig(boom.common.SpinalBoomConfig()).workspacePath("simWorkspace")
      .compile(new PipelineTestTop).doSim(seed = 1) { dut =>
        dut.io.valid #= false
        dut.io.data #= 0
        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(8)
        // None denotes an uninitialised payload; it is checked once first written.
        case class Sample(valid: Boolean, data: Option[Int])
        val states = Array.tabulate(12)(i => Vector.fill(Seq(0, 1, 4)(i / 4))(Sample(false, None)))
        var history = Vector.empty[Int]
        val random = new scala.util.Random(42)
        for (cycle <- 0 until 200) {
          dut.clockDomain.waitFallingEdge()
          val valid = cycle % 11 < 7
          val data = random.nextInt(65536)
          val reset = cycle == 70 || cycle == 71
          if (reset) dut.clockDomain.assertReset() else dut.clockDomain.deassertReset()
          dut.io.valid #= valid
          dut.io.data #= data
          for (i <- states.indices) {
            val mode = i % 4
            val incoming = Sample(valid, Some(data)) +: states(i)
            states(i) = states(i).indices.map { s =>
              Sample(if (reset && mode < 2) false else incoming(s).valid,
                if (mode % 2 == 1 || incoming(s).valid) incoming(s).data else states(i)(s).data)
            }.toVector
          }
          history = (history :+ data).takeRight(4)
          dut.clockDomain.waitRisingEdge()
          sleep(1)
          for (i <- states.indices) {
            val expected = states(i).lastOption.getOrElse(Sample(valid, Some(data)))
            assert(dut.io.outValid(i).toBoolean == expected.valid, s"valid output $i cycle $cycle")
            expected.data.foreach(d => assert(dut.io.outData(i).toInt == d, s"payload output $i cycle $cycle"))
          }
          if (history.size == 4) assert(dut.io.delayed.toInt == history.head)
        }
      }
  }
}
