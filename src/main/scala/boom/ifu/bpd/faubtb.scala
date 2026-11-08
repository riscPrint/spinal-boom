package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

case class BoomFAMicroBTBParams(
  nWays: Int = 16
)

class FAMicroBTBBranchPredictorBank(params: BoomFAMicroBTBParams = BoomFAMicroBTBParams())(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  override val nWays         = params.nWays
  val tagSz         = vaddrBitsExtended - log2Ceil(fetchWidth) - 1
  val nWrBypassEntries = 2

  def bimWrite(v: UInt, taken: Bool): UInt = BimWrite(v, taken)

  require(isPow2(nWays))

  case class MicroBTBMeta() extends Bundle {
    val is_br = Bool()
    val tag   = UInt(tagSz bits)
    val ctr   = UInt(2 bits)
  }

  case class MicroBTBPredictMeta() extends Bundle {
    val hits  = Vec.fill(bankWidth)(Bool())
    val write_way = UInt(log2Ceil(nWays) bits)
  }

  val s1_meta = MicroBTBPredictMeta()
  override val metaSz = s1_meta.getBitsWidth

  val meta     = RegInit(Vec.fill(nWays)(Vec.fill(bankWidth)(MicroBTBMeta())).getZero)
  val btb      = Reg(Vec.fill(nWays)(Vec.fill(bankWidth)(UInt(vaddrBitsExtended bits))))

  val mems = Nil

  val s1_req_tag   = s1_idx

  val s1_resp   = Vec.fill(bankWidth)(Flow(UInt(vaddrBitsExtended bits)))
  val s1_taken  = Vec.fill(bankWidth)(Bool())
  val s1_is_br  = Vec.fill(bankWidth)(Bool())
  val s1_is_jal = Vec.fill(bankWidth)(Bool())

  val s1_hit_ohs = Vec((0 until bankWidth) map { i =>
    Vec((0 until nWays) map { w =>
      meta(w)(i).tag === s1_req_tag(tagSz-1 downto 0)
    })
  })
  val s1_hits     = s1_hit_ohs.map { oh => oh.reduce(_||_) }
  val s1_hit_ways = s1_hit_ohs.map { oh => PriorityEncoder(oh) }

  for (w <- 0 until bankWidth) {
    val entry_meta = meta.at(s1_hit_ways(w))(w)
    s1_resp(w).valid := s1_valid && s1_hits(w)
    s1_resp(w).payload  := btb.at(s1_hit_ways(w))(w)
    s1_is_br(w)      := s1_resp(w).valid &&  entry_meta.is_br
    s1_is_jal(w)     := s1_resp(w).valid && !entry_meta.is_br
    s1_taken(w)      := !entry_meta.is_br || entry_meta.ctr(1)

    s1_meta.hits(w)     := s1_hits(w)
  }
  val alloc_way = {
    val r_metas = Cat(CatLsbFirst(meta.map(e => CatLsbFirst(e.map(_.tag)))), s1_idx(tagSz-1 downto 0)).asUInt
    val l = log2Ceil(nWays)
    val nChunks = (r_metas.getWidth + l - 1) / l
    val chunks = (0 until nChunks) map { i =>
      r_metas(scala.math.min((i+1)*l, r_metas.getWidth)-1 downto i*l).resize(l)
    }
    chunks.reduce(_^_)
  }
  s1_meta.write_way := Mux(s1_hits.reduce(_||_),
    PriorityEncoder(s1_hit_ohs.map(_.asUInt).reduce(_|_)),
    alloc_way)

  for (w <- 0 until bankWidth) {
    io.resp.f1(w).predicted_pc := s1_resp(w)
    io.resp.f1(w).is_br        := s1_is_br(w)
    io.resp.f1(w).is_jal       := s1_is_jal(w)
    io.resp.f1(w).taken        := s1_taken(w)

    io.resp.f2(w) := predAt(io.resp.f1(w), 1, 2)
    io.resp.f3(w) := predAt(io.resp.f2(w), 2, 3)
  }
  io.f3_meta := predAt(s1_meta.asBits.asUInt, 1, 3).resized

  val s1_update_cfi_idx = s1_update.payload.cfi_idx.payload
  val s1_update_meta    = MicroBTBPredictMeta(); s1_update_meta.assignFromBits(s1_update.payload.meta.asBits.resized)
  val s1_update_write_way = s1_update_meta.write_way

  val s1_update_wbtb_data = s1_update.payload.target
  val s1_update_wbtb_mask = (UIntToOH(s1_update_cfi_idx, bankWidth) &
    Fill(bankWidth, s1_update.payload.cfi_idx.valid && s1_update.valid && s1_update.payload.cfi_taken && s1_update.payload.is_commit_update))

  val s1_update_wmeta_mask = ((s1_update_wbtb_mask | s1_update.payload.br_mask) &
    Fill(bankWidth, s1_update.valid && s1_update.payload.is_commit_update))

  // Write the BTB with the target
  when (s1_update.valid && s1_update.payload.cfi_taken && s1_update.payload.cfi_idx.valid && s1_update.payload.is_commit_update) {
    btb.write(s1_update_write_way)(_.write(s1_update_cfi_idx)(_ := s1_update_wbtb_data))
  }

  // Write the meta
  for (w <- 0 until bankWidth) {
    when (s1_update.valid && s1_update.payload.is_commit_update &&
      (s1_update.payload.br_mask(w) ||
        (s1_update_cfi_idx === w && s1_update.payload.cfi_taken && s1_update.payload.cfi_idx.valid))) {
      val was_taken = (s1_update_cfi_idx === w && s1_update.payload.cfi_idx.valid &&
        (s1_update.payload.cfi_taken || s1_update.payload.cfi_is_jal))

      meta.write(s1_update_write_way) { m =>
        m(w).is_br := s1_update.payload.br_mask(w)
        m(w).tag   := s1_update_idx.resized
        m(w).ctr   := Mux(!s1_update_meta.hits(w),
          Mux(was_taken, U(3, 2 bits), U(0, 2 bits)),
          bimWrite(m(w).ctr, was_taken)
        )
      }
    }
  }
}
