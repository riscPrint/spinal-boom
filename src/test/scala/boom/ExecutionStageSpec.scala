package boom

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.pipeline.Payload
import org.scalatest.funsuite.AnyFunSuite
import rocketchip.config.Parameters
import boom.common._
import boom.exu.{ALUExeUnit, ExecutionUnit, MemExeUnit, UniqueExeUnit}
import boom.util.{IsKilledByBranch, UpdateBrMask}

class ExecutionStageTestTop(val clearP3: Boolean)(implicit p: Parameters) extends ExecutionUnit("Test") {
  val squash, rebusy = in Bool()
  val operand = in UInt(64 bits)
  val brfIndex = in UInt(1 + ldqAddrSz bits)
  val actual, reference = out Vec(Flow(new MicroOp), 3)
  val actualOperand, referenceOperand = out UInt(64 bits)
  io_ready_fu_types.foreach(_ := False)
  replayArb(squash, rebusy, clearP3)
  val OPERAND = Payload(UInt(64 bits))
  rrdDown(OPERAND) := operand
  actualOperand := exe(OPERAND)
  rrdCtrl.bypass(UOP).ldq_idx := brfIndex
  actual(0) := arb_uop
  actual(1) := rrd_uop
  actual(2) := exe_uop

  val oldArb, oldRrd, oldExe = Reg(Flow(new MicroOp))
  oldArb.valid := io_iss_uop.valid && !IsKilledByBranch(io_brupdate, io_kill, io_iss_uop.payload)
  oldArb.payload := UpdateBrMask(io_brupdate, io_iss_uop.payload)
  oldRrd.valid := oldArb.valid && !IsKilledByBranch(io_brupdate, io_kill, oldArb.payload)
  oldRrd.payload := UpdateBrMask(io_brupdate, oldArb.payload)
  oldExe.valid := oldRrd.valid && !IsKilledByBranch(io_brupdate, io_kill, oldRrd.payload)
  oldExe.payload := UpdateBrMask(io_brupdate, oldRrd.payload)
  oldExe.payload.ldq_idx := brfIndex
  when(squash || rebusy) {
    oldArb.valid := oldArb.valid && !IsKilledByBranch(io_brupdate, io_kill, oldArb.payload) && !rebusy
    oldArb.payload := UpdateBrMask(io_brupdate, oldArb.payload)
    oldArb.payload.iw_p1_bypass_hint := False
    oldArb.payload.iw_p2_bypass_hint := False
    if (clearP3) oldArb.payload.iw_p3_bypass_hint := False
    oldRrd.valid := False
  }
  reference(0) := oldArb
  reference(1) := oldRrd
  reference(2) := oldExe
  referenceOperand := RegNext(operand)
}

class ExecutionStageSpec extends AnyFunSuite {
  test("integer execution operand taps elaborate with RVFI enabled") {
    val tile = BoomConfigs.small()
    implicit val p: Parameters = BoomTestParams(tile.copy(core = tile.core.copy(enableRVFI = true)))
    SpinalBoomConfig("generated/test").generateVerilog(new MemExeUnit(hasAGen = true, hasDGen = true))
    SpinalBoomConfig("generated/test").generateVerilog(
      new UniqueExeUnit(hasCSR = true, hasMul = true, hasDiv = true, hasIfpu = true))
    SpinalBoomConfig("generated/test").generateVerilog(new ALUExeUnit(0))
  }

  for (clearP3 <- Seq(false, true)) test(s"execution stages preserve replay and invalid payloads, clearP3=$clearP3") {
    implicit val p: Parameters = BoomTestParams()
    SimConfig.withConfig(SpinalBoomConfig()).workspacePath("simWorkspace")
      .compile(new ExecutionStageTestTop(clearP3)).doSim(seed = 1) { dut =>
        val random = new scala.util.Random(42)
        def randomise(): Unit = Seq[Data](dut.io_iss_uop, dut.io_brupdate, dut.io_status, dut.io_fcsr_rm,
          dut.io_kill, dut.squash, dut.rebusy, dut.operand, dut.brfIndex).flatMap(_.flatten).foreach {
          case b: Bool => b #= random.nextBoolean()
          case b: SInt => b #= (BigInt(b.getBitsWidth, random) - (BigInt(1) << (b.getBitsWidth - 1)))
          case b: BitVector => b #= BigInt(b.getBitsWidth, random)
          case _ =>
        }
        randomise()
        dut.squash #= false
        dut.rebusy #= false
        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(8)
        for (cycle <- 0 until 500) {
          dut.clockDomain.waitFallingEdge()
          randomise()
          // Prime every uninitialised stage, then mix invalid, replay, kill and resolve.
          if (cycle < 4) { dut.squash #= false; dut.rebusy #= false }
          if (cycle == 170 || cycle == 171) dut.clockDomain.assertReset()
          else dut.clockDomain.deassertReset()
          dut.clockDomain.waitRisingEdge()
          sleep(1)
          if (cycle >= 4) {
            assert(dut.actualOperand.toBigInt == dut.referenceOperand.toBigInt)
            for ((actual, reference) <- dut.actual.zip(dut.reference);
                 (a, r) <- actual.flatten.zip(reference.flatten)) {
              def value(d: BaseType): BigInt = d match {
                case b: Bool => BigInt(if (b.toBoolean) 1 else 0)
                case b: BitVector => b.toBigInt
              }
              assert(value(a) == value(r), s"${a.getName()} cycle $cycle")
            }
          }
        }
      }
  }
}
