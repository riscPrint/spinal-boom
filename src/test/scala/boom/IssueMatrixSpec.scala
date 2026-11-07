package boom

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import boom.common._
import boom.exu._
import boom.util.ParallelFindOne

class IssueMatrixSpec extends AnyFunSuite {
  test("ParallelFindOne preserves rounded tree indices") {
    val compiled = SimConfig.withConfig(SpinalBoomConfig("generated/test"))
      .workspacePath("simWorkspace").workspaceName("parallel-find-one").withVerilator.compile(new Component {
        val mask = in UInt(6 bits)
        val high = out UInt(3 bits)
        val low = out UInt(3 bits)
        val valid = out Bool()
        val (hv, hi) = ParallelFindOne(mask, true)
        val (lv, li) = ParallelFindOne(mask, false)
        high := hi; low := li; valid := hv && lv
      })
    compiled.doSim { dut =>
      for (mask <- 0 until 64) {
        dut.mask #= mask
        sleep(1)
        assert(dut.valid.toBoolean == (mask != 0))
        if (mask != 0) {
          assert(dut.high.toInt == 31 - Integer.numberOfLeadingZeros(mask))
          assert(dut.low.toInt == Integer.numberOfTrailingZeros(mask))
        } else {
          assert(dut.high.toInt == 0)
          assert(dut.low.toInt == 7)
        }
      }
    }
  }

  for (full <- Seq(true, false)) test(s"matrix full=$full issues ready entries") {
    implicit val p = BoomTestParams()
    val compiled = SimConfig.withConfig(SpinalBoomConfig("generated/test"))
      .workspacePath("simWorkspace").workspaceName(s"matrix-unit-$full").withVerilator
      .compile(new IssueUnitAgeMatrix(IssueParams(dispatchWidth=2, issueWidth=2,
        numEntries=6, iqType=IQ_ALU, useFullIssueSel=full), 1))
    compiled.doSim { dut =>
      dut.io.flatten.filter(_.isInput).foreach {
        case b: Bool => b #= false
        case b: BitVector => b #= 0
        case _ =>
      }
      dut.io.fu_types.foreach(_(0) #= true)
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(4)
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling(2)
      for (i <- 0 until 2) {
        dut.io.dis_uops(i).valid #= true
        dut.io.dis_uops(i).payload.fu_code(0) #= true
        dut.io.dis_uops(i).payload.rob_idx #= i
      }
      dut.clockDomain.waitSampling()
      dut.io.dis_uops.foreach(_.valid #= false)
      sleep(1)
      assert(dut.io.iss_uops.forall(_.valid.toBoolean))
      assert(dut.io.iss_uops.map(_.payload.rob_idx.toInt).toSet == Set(0, 1))
      dut.clockDomain.waitSampling(4)
    }
  }
}

/** Elaboration only. This entry point does not build or run a simulator. */
object MatrixElaborate extends App {
  for (full <- Seq(true, false); banked <- Seq(false, true)) {
    implicit val p = BoomTestParams()
    val params = IssueParams(dispatchWidth=2, issueWidth=2, numEntries=6,
      iqType=IQ_ALU, useMatrixIssue=true, useFullIssueSel=full)
    SpinalBoomConfig("generated/matrix-unit").generateVerilog(
      IssueUnit(params, 1, banked, false))
  }
}
