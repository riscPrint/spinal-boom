package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

case class BoomGMicroBTBParams(
  nWays: Int = 24,
  tagSz: Int = 12
)

class GMicroBTBBranchPredictorBank(params: BoomGMicroBTBParams = BoomGMicroBTBParams())(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  def bimWrite(v: UInt, taken: Bool): UInt = BimWrite(v, taken)

  override val nWays = params.nWays
  val tagSz = params.tagSz

  case class MicroBTBMeta() extends Bundle {
    val tag   = UInt(tagSz bits)
    val ctr   = UInt(2 bits)
    val is_br = Bool()
    val is_jal = Bool()
  }

  case class MicroBTBPredictMeta() extends Bundle {
    val hit = Bool()
  }
  val s1_meta = MicroBTBPredictMeta()
  override val metaSz = s1_meta.getBitsWidth

  val valids = Vec.fill(nWays)(Reg(Bool()) init False)
  val meta = Reg(Vec.fill(nWays)(MicroBTBMeta()))
  val btb = Reg(Vec.fill(nWays)(UInt(vaddrBitsExtended bits)))
  val mems = Nil

  val s1_req_tag = RegNext(io.f0_pc(tagSz-1 downto 0))

  val s1_hit_oh = (0 until nWays) map { w => valids(w) && meta(w).tag === s1_req_tag }
  val s1_hit = s1_hit_oh.reduce(_||_) && s1_valid

  val s1_hit_meta = Mux1H(s1_hit_oh, meta)
  val s1_hit_btb = Mux1H(s1_hit_oh, btb)
  s1_meta.hit := s1_hit

  for (w <- 0 until bankWidth-1) {
    io.resp.f1(w).predicted_pc.valid := False
    io.resp.f1(w).predicted_pc.payload := 0
    io.resp.f1(w).is_br              := s1_hit_meta.is_br
    io.resp.f1(w).is_jal             := False
    io.resp.f1(w).taken              := False
  }

  io.resp.f1(bankWidth-1).predicted_pc.valid := s1_hit
  io.resp.f1(bankWidth-1).predicted_pc.payload := s1_hit_btb
  io.resp.f1(bankWidth-1).is_br              := s1_hit_meta.is_br && !s1_hit_meta.is_jal
  io.resp.f1(bankWidth-1).is_jal             := s1_hit_meta.is_jal
  io.resp.f1(bankWidth-1).taken              := s1_hit && (s1_hit_meta.is_jal || s1_hit_meta.ctr(1))

  io.f3_meta := RegNext(RegNext(s1_meta.asBits.asUInt)).resized

  val s1_update_pc = RegNext(io.update.payload.pc)
  val s1_update_hit_oh = (0 until nWays) map { w =>
    meta(w).tag === s1_update_pc(tagSz-1 downto 0) && valids(w)
  }
  val s1_update_hit = s1_update_hit_oh.reduce(_||_)
  val s1_update_write_way = Mux(s1_update_hit, OHToUInt(s1_update_hit_oh).resize(16), LFSR(16))

  when (s1_update.valid && s1_update.payload.is_commit_update) {
    val rmeta = meta.at(s1_update_write_way)
    val wmeta = MicroBTBMeta()

    wmeta.tag := s1_update_pc.resized
    when (s1_update.payload.cfi_idx.valid) {
      btb.write(s1_update_write_way)(_ := s1_update.payload.target)
      valids.write(s1_update_write_way)(_ := True)

      val is_jal = s1_update.payload.cfi_is_jal || s1_update.payload.cfi_is_jalr

      wmeta.is_br := (s1_update.payload.br_mask & MaskLower(UIntToOH(s1_update.payload.cfi_idx.payload, bankWidth))) =/= 0 || !is_jal
      wmeta.is_jal := is_jal
      when (s1_update_hit) {
        wmeta.ctr := bimWrite(rmeta.ctr, s1_update.payload.cfi_taken)
      } otherwise {
        wmeta.ctr := Mux(s1_update.payload.cfi_idx.valid && s1_update.payload.cfi_taken, U(3, 2 bits), U(0, 2 bits))
      }
    } otherwise {
      wmeta.is_br := True
      wmeta.is_jal := False
      when (s1_update_hit) {
        wmeta.ctr := bimWrite(rmeta.ctr, False)
      } otherwise {
        wmeta.ctr := 0
      }
    }
    when (s1_update.payload.cfi_idx.valid || s1_update.payload.br_mask =/= 0) {
      meta.write(s1_update_write_way)(_ := wmeta)
    }
  }
}
