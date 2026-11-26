package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

class BIMMeta(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomFrontendParameters
{
  val bims  = Vec.fill(bankWidth)(UInt(2 bits))
}

case class BoomBIMParams(
  nSets: Int = 2048,
  nCols: Int = 8
)

class BIMBranchPredictorBank(params: BoomBIMParams = BoomBIMParams())(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  override val nSets = params.nSets
  val nCols = params.nCols
  val nSetsPerCol = nSets / nCols

  require(isPow2(nSets))
  require(isPow2(nCols))
  require(nCols < nSets)
  require(nCols > 1)

  val nWrBypassEntries = 2

  def bimWrite(v: UInt, taken: Bool): UInt = BimWrite(v, taken)
  val s2_meta           = new BIMMeta
  override val metaSz   = s2_meta.getBitsWidth

  val doing_reset = Reg(Bool()) init True
  val reset_idx = Reg(UInt(log2Ceil(nSetsPerCol) bits)) init 0
  reset_idx := reset_idx + U(doing_reset).resized
  when (reset_idx === (nSetsPerCol-1)) { doing_reset := False }

  val mems = (0 until nCols) map {c => (f"bim_col$c", nSetsPerCol, bankWidth * 2)}

  val s0_col_mask = UIntToOH(s0_idx(log2Ceil(nCols)-1 downto 0), nCols) & Fill(nCols, s0_valid)
  val COL_MASK = pred(0).insert(s0_col_mask)
  val s1_col_mask = pred(1)(COL_MASK)
  val s0_col_idx  = s0_idx >> log2Ceil(nCols)
  val s1_col_idx  = predAt(s0_col_idx, 0, 1)

  val s2_req_rdata_all = Vec.fill(nCols)(Vec.fill(bankWidth)(UInt(2 bits)))
  val s2_req_rdata = Mux1H(pred(2)(COL_MASK), s2_req_rdata_all)

  val s2_resp         = Vec.fill(bankWidth)(Bool())

  val s3_resp = predAt(s2_resp, 2, 3)
  for (w <- 0 until bankWidth) {

    s2_resp(w)        := s2_valid && s2_req_rdata(w)(1) && !doing_reset
    s2_meta.bims(w)   := s2_req_rdata(w)

    io.resp.f2(w).taken := s2_resp(w)
    io.resp.f3(w).taken := s3_resp(w)
  }
  io.f3_meta := predAt(s2_meta.asBits.asUInt, 2, 3).resized

  val s1_update_wdata   = Vec.fill(bankWidth)(UInt(2 bits))
  val s1_update_wmask   = Vec.fill(bankWidth)(Bool())
  val s1_update_meta    = new BIMMeta; s1_update_meta.assignFromBits(s1_update.payload.meta.asBits.resized)
  val s1_update_col_mask = UIntToOH(s1_update_idx(log2Ceil(nCols)-1 downto 0), nCols)
  val s1_update_col_idx = s1_update_idx >> log2Ceil(nCols)

  val wrbypass_idxs = Reg(Vec.fill(nWrBypassEntries)(UInt(log2Ceil(nSets) bits)))
  val wrbypass      = Reg(Vec.fill(nWrBypassEntries)(Vec.fill(bankWidth)(UInt(2 bits))))
  val wrbypass_enq_idx = Reg(UInt(log2Ceil(nWrBypassEntries) bits)) init 0

  val wrbypass_hits = Vec((0 until nWrBypassEntries) map { i =>
    !doing_reset &&
    wrbypass_idxs(i) === s1_update_idx(log2Ceil(nSets)-1 downto 0)
  })
  val wrbypass_hit = wrbypass_hits.reduce(_||_)
  val wrbypass_hit_idx = PriorityEncoder(wrbypass_hits)

  for (w <- 0 until bankWidth) {
    s1_update_wmask(w)         := False
    s1_update_wdata(w).assignDontCare()

    when (s1_update.payload.br_mask(w) ||
      (s1_update.payload.cfi_idx.valid && s1_update.payload.cfi_idx.payload === w)) {
      val was_taken = (
        s1_update.payload.cfi_idx.valid &&
        (s1_update.payload.cfi_idx.payload === w) &&
        (
          (s1_update.payload.cfi_is_br && s1_update.payload.br_mask(w) && s1_update.payload.cfi_taken) ||
          s1_update.payload.cfi_is_jal
        )
      )
      val old_bim_value = Mux(wrbypass_hit, wrbypass.at(wrbypass_hit_idx)(w), s1_update_meta.bims(w))

      s1_update_wmask(w)     := True

      s1_update_wdata(w)     := bimWrite(old_bim_value, was_taken)
    }
  }

  for (c <- 0 until nCols) {
    val (ren, ridx) = (s0_col_mask(c), s0_col_idx)
    val wen = CombInit(doing_reset || (s1_update.valid && s1_update.payload.is_commit_update && s1_update_col_mask(c) && !ren))
    val data = new SyncReadVecMem(nSetsPerCol, bankWidth, UInt(2 bits)).setName(s"bim_col_${c}")
    val r = data.read(ridx, ren && !wen)
    s2_req_rdata_all(c) := RegNext(r)
    val widx = Mux(doing_reset, reset_idx.resize(s1_update_col_idx.getWidth), s1_update_col_idx)
    val wdata = Mux(doing_reset, Vec.fill(bankWidth)(U(2, 2 bits)), s1_update_wdata)
    val wmask = Mux(doing_reset, ~U(0, bankWidth bits), s1_update_wmask.asUInt)
    data.write(widx, wdata, wmask.asBools, wen)
  }
  when (s1_update_wmask.reduce(_||_) && s1_update.valid && s1_update.payload.is_commit_update) {
    when (wrbypass_hit) {
      wrbypass.write(wrbypass_hit_idx)(_ := s1_update_wdata)
    } otherwise {
      wrbypass.write(wrbypass_enq_idx)(_ := s1_update_wdata)
      wrbypass_idxs.write(wrbypass_enq_idx)(_ := s1_update_idx.resized)
      wrbypass_enq_idx := WrapInc(wrbypass_enq_idx, nWrBypassEntries)
    }
  }
}
