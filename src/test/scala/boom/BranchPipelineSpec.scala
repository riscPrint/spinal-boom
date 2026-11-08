package boom

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import rocketchip.config.Parameters
import boom.common._
import boom.exu.FuncUnitReq
import boom.util.BranchKillablePipeline

class BranchPipelineTestTop(implicit p: Parameters) extends BoomModule {
  val io = new Bundle {
    val valid = in Bool()
    val data = in UInt(16 bits)
    val mask, resolve, mispredict = in UInt(maxBrCount bits)
    val flush = in Bool()
    val outValid = out Vec(Bool(), 5)
    val outMask = out Vec(UInt(maxBrCount bits), 5)
    val outData = out Vec(UInt(16 bits), 5)
  }
  var offset = 0
  for (stages <- Seq(1, 4)) {
    val pipe = new BranchKillablePipeline(new FuncUnitReq(64), stages)
    pipe.io.req.valid := io.valid
    pipe.io.req.payload.assignFromBits(B(0, pipe.io.req.payload.getBitsWidth bits))
    pipe.io.req.payload.uop.br_mask := io.mask
    pipe.io.req.payload.rs1_data := io.data.resized
    pipe.io.brupdate.assignFromBits(B(0, pipe.io.brupdate.getBitsWidth bits))
    pipe.io.brupdate.b1.resolve_mask := io.resolve
    pipe.io.brupdate.b1.mispredict_mask := io.mispredict
    pipe.io.flush := io.flush
    for (s <- 0 until stages) {
      io.outValid(offset + s) := pipe.io.resp(s).valid
      io.outMask(offset + s) := pipe.io.resp(s).payload.uop.br_mask
      io.outData(offset + s) := pipe.io.resp(s).payload.rs1_data.resized
    }
    offset += stages
  }
}

class BranchPipelineSpec extends AnyFunSuite {
  test("branch pipelines kill before resolving and preserve stage and reset timing") {
    implicit val p: Parameters = BoomTestParams()
    SimConfig.withConfig(SpinalBoomConfig()).workspacePath("simWorkspace")
      .compile(new BranchPipelineTestTop).doSim(seed = 1) { dut =>
        dut.io.flatten.filter(_.isInput).foreach {
          case b: Bool => b #= false
          case b: BitVector => b #= 0
          case _ =>
        }
        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(8)
        case class Sample(valid: Boolean, mask: Int, data: Int)
        var states = Seq(Vector.fill(1)(Sample(false, 0, 0)), Vector.fill(4)(Sample(false, 0, 0)))
        val random = new scala.util.Random(42)
        for (cycle <- 0 until 250) {
          dut.clockDomain.waitFallingEdge()
          val input = Sample(cycle % 9 < 6, random.nextInt(256), cycle)
          val resolve = random.nextInt(256)
          // Exercise simultaneous resolve and mispredict of the same branch.
          val mispredict = if (cycle % 3 == 0) resolve else random.nextInt(256)
          val flush = cycle % 19 == 9
          val reset = cycle == 70 || cycle == 71
          dut.io.valid #= input.valid
          dut.io.mask #= input.mask
          dut.io.data #= input.data
          dut.io.resolve #= resolve
          dut.io.mispredict #= mispredict
          dut.io.flush #= flush
          if (reset) dut.clockDomain.assertReset() else dut.clockDomain.deassertReset()
          sleep(1)
          // There must be no combinational kill after the final register.
          for ((sample, i) <- states.flatten.zipWithIndex)
            assert(dut.io.outValid(i).toBoolean == sample.valid, s"late kill at $i cycle $cycle")
          states = states.map { previous =>
            (input +: previous).take(previous.size).map { sample =>
              Sample(sample.valid && !reset && !flush && (sample.mask & mispredict) == 0,
                sample.mask & ~resolve & 255, sample.data)
            }
          }
          dut.clockDomain.waitRisingEdge()
          sleep(1)
          for ((sample, i) <- states.flatten.zipWithIndex) {
            assert(dut.io.outValid(i).toBoolean == sample.valid, s"valid at $i cycle $cycle")
            assert(dut.io.outMask(i).toInt == sample.mask, s"mask at $i cycle $cycle")
            assert(dut.io.outData(i).toInt == sample.data, s"data at $i cycle $cycle")
          }
        }
      }
  }
}
