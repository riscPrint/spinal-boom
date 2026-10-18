package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

case class BoomTourneyBPDParams(
  nSets: Int = 128,
  histLength: Int = 32
)

class TourneyBranchPredictorBank(params: BoomTourneyBPDParams = BoomTourneyBPDParams())(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  override def nInputs = 2
  override val nSets = params.nSets

  def bimWrite(v: UInt, taken: Bool): UInt = BimWrite(v, taken)
  def compute_folded_hist(hist: UInt, l: Int): UInt = {
    val nChunks = (params.histLength + l - 1) / l
    val hist_chunks = (0 until nChunks) map {i =>
      hist(scala.math.min((i+1)*l, params.histLength)-1 downto i*l).resize(l)
    }
    hist_chunks.reduce(_^_)
  }

  case class TourneyMeta() extends Bundle {
    val ctrs   = Vec.fill(bankWidth)(UInt(2 bits))
  }
  val s3_meta = TourneyMeta()
  override val metaSz = s3_meta.getBitsWidth

  val doing_reset = Reg(Bool()) init True
  val reset_idx = Reg(UInt(log2Ceil(nSets) bits)) init 0
  reset_idx := reset_idx + U(doing_reset).resized
  when (reset_idx === (nSets-1)) { doing_reset := False }

  val data = Seq.fill(bankWidth) { new SyncReadMemC(nSets, UInt(2 bits)) } // TODO make this masked mem
  val mems = Seq(("tourney", nSets, 2*bankWidth))

  val f1_req_idx = compute_folded_hist(io.f1_ghist, log2Ceil(nSets)).resize(s1_idx.getWidth) ^ s1_idx
  val s3_req_rdata = RegNext(Vec(data.map(_.read(f1_req_idx, s1_valid))))
  val s3_resp = Vec.fill(bankWidth)(Bool())

  io.resp := io.resp_in(0)
  for (w <- 0 until bankWidth) {
    s3_resp(w) := Mux(s3_req_rdata(w)(1), io.resp_in(1).f3(w).taken, io.resp_in(0).f3(w).taken)  && !doing_reset
    s3_meta.ctrs(w) := s3_req_rdata(w)

    io.resp.f3(w).taken := s3_resp(w)
  }
  io.f3_meta := s3_meta.asBits.asUInt.resized

  val s1_update_wdata = Vec.fill(bankWidth)(UInt(2 bits))
  val s1_update_wmask = Vec.fill(bankWidth)(Bool())
  val s1_update_meta  = TourneyMeta(); s1_update_meta.assignFromBits(s1_update.payload.meta.asBits.resized)
  val s1_update_index = compute_folded_hist(s1_update.payload.ghist, log2Ceil(nSets)).resize(s1_update_idx.getWidth) ^ s1_update_idx

  for (w <- 0 until bankWidth) {
    s1_update_wmask(w) := False
    s1_update_wdata(w).assignDontCare()

    when (s1_update.payload.br_mask(w) ||
      (s1_update.payload.cfi_idx.valid && s1_update.payload.cfi_idx.payload === w)) {
      when (s1_update.payload.cfi_mispredicted && s1_update.payload.cfi_idx.valid && s1_update.payload.cfi_idx.payload === w) {
        s1_update_wmask(w) := True
        s1_update_wdata(w) := bimWrite(s1_update_meta.ctrs(w), !s1_update_meta.ctrs(w)(1))
      } otherwise {
        s1_update_wmask(w) := True
        s1_update_wdata(w) := bimWrite(s1_update_meta.ctrs(w),  s1_update_meta.ctrs(w)(1))
      }
    }
  }

  for (w <- 0 until bankWidth) {
    data(w).write(
      Mux(doing_reset, reset_idx.resize(s1_update_index.getWidth), s1_update_index),
      Mux(doing_reset, U(2, 2 bits), s1_update_wdata(w)),
      doing_reset || (s1_update_wmask(w) && s1_update.valid && s1_update.payload.is_commit_update))
  }
}
