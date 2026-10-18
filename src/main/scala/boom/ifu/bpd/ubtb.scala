package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

case class BoomMicroBTBParams(
  nSets: Int = 256,
  offsetSz: Int = 13
)

class MicroBTBBranchPredictorBank(params: BoomMicroBTBParams)(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  override val nSets         = params.nSets
  val tagSz         = vaddrBitsExtended - log2Ceil(nSets) - log2Ceil(fetchWidth) - 1
  val offsetSz      = params.offsetSz
  val nWrBypassEntries = 2

  def bimWrite(v: UInt, taken: Bool): UInt = BimWrite(v, taken)

  require(isPow2(nSets))

  case class MicroBTBEntry() extends Bundle {
    val offset   = SInt(offsetSz bits)
  }
  val btbEntrySz = offsetSz

  case class MicroBTBMeta() extends Bundle {
    val is_br = Bool()
    val tag   = UInt(tagSz bits)
    val ctr   = UInt(2 bits)
  }
  val btbMetaSz = tagSz + 1 + 2

  case class MicroBTBPredictMeta() extends Bundle {
    val ctrs  = Vec.fill(bankWidth)(UInt(2 bits))
  }

  val s1_meta = MicroBTBPredictMeta()
  override val metaSz = s1_meta.getBitsWidth

  val doing_reset = Reg(Bool()) init True
  val reset_idx   = Reg(UInt(log2Ceil(nSets) bits)) init 0
  reset_idx := reset_idx + U(doing_reset).resized
  when (reset_idx === (nSets-1)) { doing_reset := False }

  val meta     = new SyncReadVecMem(nSets, bankWidth, MicroBTBMeta())
  val btb      = new SyncReadVecMem(nSets, bankWidth, MicroBTBEntry())

  val mems = Seq(("ubtb_meta", nSets, bankWidth * btbMetaSz),
                 ("ubtb_data", nSets, bankWidth * btbEntrySz))

  val s1_req_rbtb  = btb.read(s0_idx , s0_valid)
  val s1_req_rmeta = meta.read(s0_idx, s0_valid)
  val s1_req_tag   = s1_idx >> log2Ceil(nSets)

  val s1_resp   = Vec.fill(bankWidth)(Flow(UInt(vaddrBitsExtended bits)))
  val s1_taken  = Vec.fill(bankWidth)(Bool())
  val s1_is_br  = Vec.fill(bankWidth)(Bool())
  val s1_is_jal = Vec.fill(bankWidth)(Bool())

  for (w <- 0 until bankWidth) {
    s1_resp(w).valid := !doing_reset && s1_valid && s1_req_tag(tagSz-1 downto 0) === s1_req_rmeta(w).tag
    s1_resp(w).payload  := (s1_pc.asSInt + S(w << 1) + s1_req_rbtb(w).offset).asUInt.resized
    s1_is_br(w)  := !doing_reset && s1_resp(w).valid &&  s1_req_rmeta(w).is_br
    s1_is_jal(w) := !doing_reset && s1_resp(w).valid && !s1_req_rmeta(w).is_br
    s1_taken(w)  := !doing_reset && (!s1_req_rmeta(w).is_br || s1_req_rmeta(w).ctr(1))
    s1_meta.ctrs(w) := s1_req_rmeta(w).ctr
  }

  for (w <- 0 until bankWidth) {
    io.resp.f1(w).predicted_pc := s1_resp(w)
    io.resp.f1(w).is_br        := s1_is_br(w)
    io.resp.f1(w).is_jal       := s1_is_jal(w)
    io.resp.f1(w).taken        := s1_taken(w)

    io.resp.f2(w) := RegNext(io.resp.f1(w))
    io.resp.f3(w) := RegNext(io.resp.f2(w))
  }
  io.f3_meta := RegNext(RegNext(s1_meta.asBits.asUInt)).resized

  val s1_update_cfi_idx = s1_update.payload.cfi_idx.payload
  val s1_update_meta    = MicroBTBPredictMeta(); s1_update_meta.assignFromBits(s1_update.payload.meta.asBits.resized)

  val wrbypass_idxs    = Reg(Vec.fill(nWrBypassEntries)(UInt(log2Ceil(nSets) bits)))
  val wrbypass         = Reg(Vec.fill(nWrBypassEntries)(Vec.fill(bankWidth)(MicroBTBMeta())))
  val wrbypass_enq_idx = Reg(UInt(log2Ceil(nWrBypassEntries) bits)) init 0

  val wrbypass_hits = Vec((0 until nWrBypassEntries) map { i =>
    !doing_reset &&
    wrbypass_idxs(i) === s1_update_idx(log2Ceil(nSets)-1 downto 0)
  })
  val wrbypass_hit  = wrbypass_hits.reduce(_||_)
  val wrbypass_hit_idx = PriorityEncoder(wrbypass_hits)

  val new_offset_value = (s1_update.payload.target.asSInt -
    (s1_update.payload.pc + (s1_update.payload.cfi_idx.payload << 1).resized).asSInt)

  val s1_update_wbtb_data     = MicroBTBEntry()
  s1_update_wbtb_data.offset := new_offset_value.resized
  val s1_update_wbtb_mask = (UIntToOH(s1_update_cfi_idx, bankWidth) &
    Fill(bankWidth, s1_update.payload.cfi_idx.valid && s1_update.valid && s1_update.payload.cfi_taken && s1_update.payload.is_commit_update))

  val s1_update_wmeta_mask = ((s1_update_wbtb_mask | s1_update.payload.br_mask) &
    Fill(bankWidth, s1_update.valid && s1_update.payload.is_commit_update))
  val s1_update_wmeta_data = Vec.fill(bankWidth)(MicroBTBMeta())
  for (w <- 0 until bankWidth) {
    s1_update_wmeta_data(w).tag     := (s1_update_idx >> log2Ceil(nSets)).resized
    s1_update_wmeta_data(w).is_br   := s1_update.payload.br_mask(w)

    val was_taken =  (s1_update.payload.cfi_idx.valid && s1_update.payload.cfi_idx.payload === w &&
      (
        (s1_update.payload.cfi_is_br && s1_update.payload.cfi_taken) ||
        s1_update.payload.cfi_is_jal
      )
    )
    val old_bim_value = Mux(wrbypass_hit, wrbypass.at(wrbypass_hit_idx)(w).ctr, s1_update_meta.ctrs(w))
    s1_update_wmeta_data(w).ctr     := bimWrite(old_bim_value, was_taken)
  }

  btb.write(
    Mux(doing_reset, reset_idx.resize(s1_update_idx.getWidth), s1_update_idx),
    Mux(doing_reset, Vec.fill(bankWidth)(MicroBTBEntry().getZero), Vec.fill(bankWidth)(s1_update_wbtb_data)),
    Mux(doing_reset, ~U(0, bankWidth bits), s1_update_wbtb_mask).asBools
  )
  meta.write(
    Mux(doing_reset, reset_idx.resize(s1_update_idx.getWidth), s1_update_idx),
    Mux(doing_reset, Vec.fill(bankWidth)(MicroBTBMeta().getZero), s1_update_wmeta_data),
    Mux(doing_reset, ~U(0, bankWidth bits), s1_update_wmeta_mask).asBools
  )

  when (s1_update_wmeta_mask =/= 0) {
    when (wrbypass_hit) {
      wrbypass.write(wrbypass_hit_idx)(_ := s1_update_wmeta_data)
    } otherwise {
      wrbypass.write(wrbypass_enq_idx)(_ := s1_update_wmeta_data)
      wrbypass_idxs.write(wrbypass_enq_idx)(_ := s1_update_idx.resized)
      wrbypass_enq_idx := WrapInc(wrbypass_enq_idx, nWrBypassEntries)
    }
  }
}
