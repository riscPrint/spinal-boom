package boom

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite
import rocketchip.config.Parameters
import boom.common._
import boom.exu.{BrUpdateInfo, ExeUnitResp}
import boom.util.{BranchPipeline, UpdateBrMask}

class WritebackTapTestTop(implicit p: Parameters) extends BoomModule {
  val io = new Bundle {
    val input = slave(Flow(new ExeUnitResp(64)))
    val brupdate = in(new BrUpdateInfo)
    val flush = in Bool()
    val raw, killed, rawReference, killedReference = master(Flow(new ExeUnitResp(64)))
  }
  val (raw, killed) = BranchPipeline.writebackTap(io.input, io.brupdate, io.flush)
  io.raw := raw
  io.killed := killed
  io.rawReference := RegNext(io.input)
  io.killedReference := RegNext(UpdateBrMask(io.brupdate, io.flush, io.input))
}

class WritebackTapSpec extends AnyFunSuite {
  test("writeback tap preserves raw and killed registers on every cycle") {
    implicit val p: Parameters = BoomTestParams()
    SimConfig.withConfig(SpinalBoomConfig()).workspacePath("simWorkspace")
      .compile(new WritebackTapTestTop).doSim(seed = 1) { dut =>
        def randomInputs(random: scala.util.Random): Unit = {
          dut.io.flatten.filter(_.isInput).foreach {
            case b: Bool => b #= random.nextBoolean()
            case b: SInt => b #= (BigInt(b.getBitsWidth, random) - (BigInt(1) << (b.getBitsWidth - 1)))
            case b: BitVector => b #= BigInt(b.getBitsWidth, random)
            case _ =>
          }
        }
        val random = new scala.util.Random(42)
        randomInputs(random)
        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(8)
        for (cycle <- 0 until 250) {
          dut.clockDomain.waitFallingEdge()
          randomInputs(random)
          if (cycle == 70 || cycle == 71) dut.clockDomain.assertReset()
          else dut.clockDomain.deassertReset()
          dut.clockDomain.waitRisingEdge()
          sleep(1)
          for ((actual, reference) <- Seq(dut.io.raw -> dut.io.rawReference,
                                         dut.io.killed -> dut.io.killedReference)) {
            assert(actual.valid.toBoolean == reference.valid.toBoolean)
            for ((a, r) <- actual.payload.flatten.zip(reference.payload.flatten)) {
              val av = a match { case b: Bool => BigInt(if (b.toBoolean) 1 else 0); case b: BitVector => b.toBigInt }
              val rv = r match { case b: Bool => BigInt(if (b.toBoolean) 1 else 0); case b: BitVector => b.toBigInt }
              assert(av == rv, s"${a.getName()} cycle $cycle")
            }
          }
        }
      }
  }
}
