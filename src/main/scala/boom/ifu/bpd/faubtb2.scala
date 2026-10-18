package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

case class BoomFA2MicroBTBParams(
  nWays: Int = 24,
  tagSz: Int = 12,
  useDualEntries: Boolean = true
)

class FA2MicroBTBBranchPredictorBank(params: BoomFA2MicroBTBParams = BoomFA2MicroBTBParams())(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  override val nWays         = params.nWays
  val tagSz         = params.tagSz
  require(tagSz <= vaddrBitsExtended - log2Ceil(fetchWidth) - 1)
  val nWrBypassEntries = 2
  val useDualEntries = params.useDualEntries

  def bimWrite(v: UInt, taken: Bool): UInt = BimWrite(v, taken)

  case class MicroBTBMeta() extends Bundle {
    val tag   = UInt(tagSz bits)
    val ctr   = UInt(2 bits)
    val cfi_idx = UInt(log2Ceil(bankWidth) bits)
    val br_mask = UInt(bankWidth bits)
    val jal_mask = UInt(bankWidth bits)
  }

  case class MicroBTBPredictMeta() extends Bundle {
    val hit = Bool()
  }

  val s1_meta = MicroBTBPredictMeta()
  override val metaSz = s1_meta.getBitsWidth

  val nEntries = if (useDualEntries) 2 else 1
  val valids   = Vec.fill(nWays)(Reg(Bool()) init False)
  val meta     = Reg(Vec.fill(nWays)(MicroBTBMeta()))
  val btb      = Reg(Vec.fill(nWays)(Vec.fill(nEntries)(UInt(vaddrBitsExtended bits))))

  val mems = Nil

  val s1_req_tag   = s1_idx

  val s1_resp   = Vec.fill(bankWidth)(Flow(UInt(vaddrBitsExtended bits)))
  val s1_taken  = Vec.fill(bankWidth)(Bool())
  val s1_is_br  = Vec.fill(bankWidth)(Bool())
  val s1_is_jal = Vec.fill(bankWidth)(Bool())

  val s1_hit_oh = (0 until nWays) map { w => valids(w) && meta(w).tag === s1_req_tag(tagSz-1 downto 0) }
  val s1_hit = s1_hit_oh.reduce(_||_) && s1_valid

  val s1_hit_meta = Mux1H(s1_hit_oh, meta)
  val s1_hit_btb = Mux1H(s1_hit_oh, btb)
  for (w <- 0 until bankWidth) {
    val is_cfi = s1_hit_meta.cfi_idx === w
    s1_resp(w).valid := s1_hit && ((is_cfi && s1_hit_meta.br_mask(w)) || s1_hit_meta.jal_mask(w))
    s1_resp(w).payload  := (if (useDualEntries) s1_hit_btb(U(s1_hit_meta.br_mask(w))) else s1_hit_btb(0))
    s1_is_br(w)      := s1_hit && s1_hit_meta.br_mask(w)
    s1_is_jal(w)     := s1_hit && s1_hit_meta.jal_mask(w)
    s1_taken(w)      := s1_hit && (s1_is_jal(w) || s1_hit_meta.ctr(1))
  }
  s1_meta.hit := s1_hit

  for (w <- 0 until bankWidth) {
    io.resp.f1(w).predicted_pc := s1_resp(w)
    io.resp.f1(w).is_br        := s1_is_br(w)
    io.resp.f1(w).is_jal       := s1_is_jal(w)
    io.resp.f1(w).taken        := s1_taken(w)

    io.resp.f2(w) := RegNext(io.resp.f1(w))
    io.resp.f3(w) := RegNext(io.resp.f2(w))
  }
  io.f3_meta := RegNext(RegNext(s1_meta.asBits.asUInt)).resized

  val s1_update_hit_oh = (0 until nWays) map { w =>
    meta(w).tag === s1_update_idx(tagSz-1 downto 0) && valids(w)
  }
  val s1_update_hit = s1_update_hit_oh.reduce(_||_)
  // Note: upstream uses a 16-bit LFSR as the way index on a miss. Chisel
  // truncates the index to 5 bits, so ways 24..31 allocate nothing.
  val lfsr = LFSR(16)
  val s1_update_write_way = Mux(s1_update_hit, OHToUInt(s1_update_hit_oh).resize(16), lfsr)

  when (s1_update.valid && s1_update.payload.is_commit_update) {
    when (s1_update.payload.cfi_idx.valid) {
      if (useDualEntries)
        btb.write(s1_update_write_way)(_.write(U(s1_update.payload.cfi_is_br))(_ := s1_update.payload.target))
      else
        btb.write(s1_update_write_way)(_(0) := s1_update.payload.target)
      valids.write(s1_update_write_way)(_ := True)
    }
    val rmeta = meta.at(s1_update_write_way)
    val wmeta = MicroBTBMeta()
    wmeta.tag := s1_update_idx.resized
    when (!s1_update_hit) {
      wmeta.br_mask := s1_update.payload.br_mask
      wmeta.ctr := Mux(s1_update.payload.cfi_idx.valid && s1_update.payload.cfi_taken, U(3, 2 bits), U(0, 2 bits))
      wmeta.jal_mask := UIntToOH(s1_update.payload.cfi_idx.payload, bankWidth) & Fill(bankWidth, s1_update.payload.cfi_idx.valid && (s1_update.payload.cfi_is_jal || s1_update.payload.cfi_is_jalr))
    } otherwise {
      wmeta.br_mask := rmeta.br_mask | s1_update.payload.br_mask
      wmeta.ctr := Mux(s1_update.payload.cfi_idx.valid,
        Mux(s1_update.payload.cfi_idx.payload === rmeta.cfi_idx, bimWrite(rmeta.ctr, s1_update.payload.cfi_taken),
                                                                Mux(s1_update.payload.cfi_taken, U(3, 2 bits), U(0, 2 bits))),
        Mux(s1_update.payload.br_mask(rmeta.cfi_idx)            , bimWrite(rmeta.ctr, False),
                                                                rmeta.ctr)
      )
      wmeta.jal_mask := rmeta.jal_mask | UIntToOH(s1_update.payload.cfi_idx.payload, bankWidth) & Fill(bankWidth, s1_update.payload.cfi_idx.valid && (s1_update.payload.cfi_is_jal || s1_update.payload.cfi_is_jalr))
    }
    wmeta.cfi_idx := Mux(s1_update.payload.cfi_idx.valid, s1_update.payload.cfi_idx.payload, rmeta.cfi_idx)
    when (s1_update.payload.cfi_idx.valid || s1_update.payload.br_mask =/= 0) {
      meta.write(s1_update_write_way)(_ := wmeta)
    }
  }
}
