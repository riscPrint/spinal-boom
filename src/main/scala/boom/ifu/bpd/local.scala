package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

case class LocalBranchUpdate(localHistoryLength: Int, vaddrBitsExtended: Int) extends Bundle {
  val valid      = Bool()
  val mispredict = Bool()
  val repair     = Bool()
  val pc         = UInt(vaddrBitsExtended bits)
  val lhist      = UInt(localHistoryLength bits)
}

abstract class AbstractLocalBranchPredictorBank(implicit p: Parameters) extends BoomModule()(p)
  with HasBoomFrontendParameters
{
  val io = new Bundle {
    val f0_valid = in Bool()
    val f0_pc    = in UInt(vaddrBitsExtended bits)

    val f1_lhist  = out UInt(localHistoryLength bits)

    val f3_lhist = out UInt(localHistoryLength bits)

    val f3_taken_br = in Bool()
    val f3_fire = in Bool()

    val update = in(LocalBranchUpdate(localHistoryLength, vaddrBitsExtended))
  }
}

class NullLocalBranchPredictorBank(implicit p: Parameters) extends AbstractLocalBranchPredictorBank
{
  io.f1_lhist := 0
  io.f3_lhist := 0
}

class LocalBranchPredictorBank(implicit p: Parameters) extends AbstractLocalBranchPredictorBank
{
  override val nSets = localHistoryNSets

  val doing_reset = Reg(Bool()) init True
  val reset_idx = Reg(UInt(log2Ceil(nSets) bits)) init 0
  reset_idx := reset_idx + U(doing_reset).resized
  when (reset_idx === (nSets-1)) { doing_reset := False }

  val entries = new SyncReadMemC(nSets, UInt(localHistoryLength bits))

  val s0_idx = fetchIdx(io.f0_pc)
  val s1_rhist = entries.read(s0_idx, io.f0_valid)
  val s3_rhist = FixedPipeline.delay(s1_rhist, 2)
  val s3_idx = FixedPipeline.delay(s0_idx, 3)
  io.f1_lhist := s1_rhist
  io.f3_lhist := s3_rhist

  val f3_do_update    = Reg(Bool())
  val f3_update_idx   = Reg(UInt(log2Ceil(nSets) bits))
  val f3_update_lhist = Reg(UInt(localHistoryLength bits))

  f3_do_update := False
  f3_update_idx.assignDontCare()
  f3_update_lhist.assignDontCare()

  val s1_update = FixedPipeline.delay(io.update, 1)
  val s1_update_idx = fetchIdx(s1_update.pc)

  when (s1_update.valid &&
        (s1_update.mispredict || s1_update.repair)) {
    f3_do_update    := True
    f3_update_idx   := s1_update_idx.resized
    f3_update_lhist := s1_update.lhist

  } elsewhen (io.f3_fire) {

    f3_do_update    := True
    f3_update_idx   := s3_idx.resized
    f3_update_lhist := (s3_rhist << 1).resize(localHistoryLength) | U(io.f3_taken_br).resized

  }

  entries.write(Mux(doing_reset, reset_idx, f3_update_idx),
                Mux(doing_reset, U(0, localHistoryLength bits), f3_update_lhist),
                doing_reset || f3_do_update)
}
