package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

case class BoomHBIMParams(
  nSets: Int = 2048,
  useLocal: Boolean = false,
  histLength: Int = 32
)

class HBIMBranchPredictorBank(params: BoomHBIMParams = BoomHBIMParams())(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  override val nSets = params.nSets

  require(isPow2(nSets))

  val nWrBypassEntries = 2

  def bimWrite(v: UInt, taken: Bool): UInt = BimWrite(v, taken)
  val s3_meta           = new BIMMeta
  override val metaSz   = s3_meta.getBitsWidth

  val doing_reset = Reg(Bool()) init True
  val reset_idx = Reg(UInt(log2Ceil(nSets) bits)) init 0
  reset_idx := reset_idx + U(doing_reset).resized
  when (reset_idx === (nSets-1)) { doing_reset := False }

  val data  = Seq.fill(bankWidth) { new SyncReadMemC(nSets, UInt(2 bits)) }
  val mems = Seq(("hbim", nSets, bankWidth * 2))
  def compute_folded_hist(hist: UInt, l: Int): UInt = {
    val nChunks = (params.histLength + l - 1) / l
    val hist_chunks = (0 until nChunks) map {i =>
      hist(scala.math.min((i+1)*l, params.histLength)-1 downto i*l).resize(l)
    }
    hist_chunks.reduce(_^_)
  }

  val f1_idx = compute_folded_hist(
    if (params.useLocal) io.f1_lhist else io.f1_ghist,
    log2Ceil(nSets)).resize(s1_idx.getWidth) ^ s1_idx

  val s3_req_rdata    = RegNext(Vec(data.map(_.read(f1_idx, s1_valid))))

  val s3_resp         = Vec.fill(bankWidth)(Bool())

  for (w <- 0 until bankWidth) {
    s3_resp(w)        := s3_valid && s3_req_rdata(w)(1) && !doing_reset
    s3_meta.bims(w)   := s3_req_rdata(w)
  }

  val s1_update_wdata   = Vec.fill(bankWidth)(UInt(2 bits))
  val s1_update_wmask   = Vec.fill(bankWidth)(Bool())
  val s1_update_meta    = new BIMMeta; s1_update_meta.assignFromBits(s1_update.payload.meta.asBits.resized)
  val s1_update_index   = compute_folded_hist(
    if (params.useLocal) s1_update.payload.lhist else s1_update.payload.ghist,
    log2Ceil(nSets)).resize(s1_update_idx.getWidth) ^ s1_update_idx

  val wrbypass_idxs = Reg(Vec.fill(nWrBypassEntries)(UInt(log2Ceil(nSets) bits)))
  val wrbypass      = Reg(Vec.fill(nWrBypassEntries)(Vec.fill(bankWidth)(UInt(2 bits))))
  val wrbypass_enq_idx = Reg(UInt(log2Ceil(nWrBypassEntries) bits)) init 0

  val wrbypass_hits = Vec((0 until nWrBypassEntries) map { i =>
    !doing_reset &&
    wrbypass_idxs(i) === s1_update_index(log2Ceil(nSets)-1 downto 0)
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

  for (w <- 0 until bankWidth) {
    data(w).write(
      Mux(doing_reset, reset_idx.resize(s1_update_index.getWidth), s1_update_index),
      Mux(doing_reset, U(2, 2 bits), s1_update_wdata(w)),
      doing_reset || (s1_update_wmask(w) && s1_update.valid && s1_update.payload.is_commit_update)
    )
  }
  when (s1_update_wmask.reduce(_||_) && s1_update.valid && s1_update.payload.is_commit_update) {
    when (wrbypass_hit) {
      wrbypass.write(wrbypass_hit_idx)(_ := s1_update_wdata)
    } otherwise {
      wrbypass.write(wrbypass_enq_idx)(_ := s1_update_wdata)
      wrbypass_idxs.write(wrbypass_enq_idx)(_ := s1_update_index.resized)
      wrbypass_enq_idx := WrapInc(wrbypass_enq_idx, nWrBypassEntries)
    }
  }

  for (w <- 0 until bankWidth) {
    io.resp.f3(w).taken := s3_resp(w)
  }
  io.f3_meta := s3_meta.asBits.asUInt.resized
}
