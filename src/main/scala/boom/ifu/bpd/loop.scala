package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

case class BoomLoopPredictorParams(
  nWays: Int = 4,
  threshold: Int = 7
)

case class LoopMeta() extends Bundle {
  val s_cnt   = UInt(10 bits)
}

case class LoopEntry(tagSz: Int) extends Bundle {
  val tag   = UInt(tagSz bits)
  val conf  = UInt(3 bits)
  val age   = UInt(3 bits)
  val p_cnt = UInt(10 bits)
  val s_cnt = UInt(10 bits)
}

class LoopBranchPredictorColumn(nSets: Int, tagSz: Int, idxW: Int) extends Component {
  val io = new Bundle {
    val f2_req_valid = in Bool()
    val f2_req_idx  = in UInt(idxW bits)
    val f3_req_fire = in Bool()

    val f3_pred_in  = in Bool()
    val f3_pred     = out Bool()
    val f3_meta     = out(LoopMeta())

    val update_mispredict = in Bool()
    val update_repair     = in Bool()
    val update_idx   = in UInt(idxW bits)
    val update_resolve_dir = in Bool()
    val update_meta  = in(LoopMeta())
  }

  val doing_reset = Reg(Bool()) init True
  val reset_idx = Reg(UInt(log2Ceil(nSets) bits)) init 0
  reset_idx := reset_idx + U(doing_reset).resized
  when (reset_idx === (nSets-1)) { doing_reset := False }

  val entries = Reg(Vec.fill(nSets)(LoopEntry(tagSz)))
  val f2_entry = CombInit(entries.at(io.f2_req_idx))
  when (io.update_repair && io.update_idx === io.f2_req_idx) {
    f2_entry.s_cnt := io.update_meta.s_cnt
  } elsewhen (io.update_mispredict && io.update_idx === io.f2_req_idx) {
    f2_entry.s_cnt := 0
  }
  val f3_entry = RegNext(f2_entry)
  val f3_scnt  = Mux(io.update_repair && io.update_idx === RegNext(io.f2_req_idx),
    io.update_meta.s_cnt,
    f3_entry.s_cnt)
  val f3_tag   = RegNext(io.f2_req_idx(tagSz+log2Ceil(nSets)-1 downto log2Ceil(nSets)))

  io.f3_pred := io.f3_pred_in
  io.f3_meta.s_cnt := f3_scnt

  when (f3_entry.tag === f3_tag) {
    when (f3_scnt === f3_entry.p_cnt && f3_entry.conf === 7) {
      io.f3_pred := !io.f3_pred_in
    }
  }

  val f4_fire  = RegNext(io.f3_req_fire)
  val f4_entry = RegNext(f3_entry)
  val f4_tag   = RegNext(f3_tag)
  val f4_scnt  = RegNext(f3_scnt)
  val f4_idx   = RegNext(RegNext(io.f2_req_idx))

  when (f4_fire) {
    when (f4_entry.tag === f4_tag) {
      when (f4_scnt === f4_entry.p_cnt && f4_entry.conf === 7) {
        entries.write(f4_idx) { e => e.age := 7; e.s_cnt := 0 }
      } otherwise {
        entries.write(f4_idx) { e =>
          e.s_cnt := f4_scnt + 1
          e.age   := Mux(f4_entry.age === 7, U(7, 3 bits), f4_entry.age + 1)
        }
      }
    }
  }

  val entry = entries.at(io.update_idx)
  val tag = io.update_idx(tagSz+log2Ceil(nSets)-1 downto log2Ceil(nSets))
  val tag_match = entry.tag === tag
  val ctr_match = entry.p_cnt === io.update_meta.s_cnt
  val wentry = CombInit(entry)

  when (io.update_mispredict && !doing_reset) {

    // Learned, tag match -> decrement confidence
    when (entry.conf === 7 && tag_match) {
      wentry.s_cnt := 0
      wentry.conf  := entry.conf - 1

    // Learned, no tag match -> do nothing? Don't evict super-confident entries?
    } elsewhen (entry.conf === 7 && !tag_match) {

    // Confident, tag match, ctr_match -> increment confidence, reset counter
    } elsewhen (entry.conf =/= 0 && tag_match && ctr_match) {
      wentry.conf  := entry.conf + 1
      wentry.s_cnt := 0

    // Confident, tag match, no ctr match -> zero confidence, reset counter, set previous counter
    } elsewhen (entry.conf =/= 0 && tag_match && !ctr_match) {
      wentry.conf  := 0
      wentry.s_cnt := 0
      wentry.p_cnt := io.update_meta.s_cnt

    // Confident, no tag match, age is 0 -> replace this entry with our own, set our age high to avoid ping-pong
    } elsewhen (entry.conf =/= 0 && !tag_match && entry.age === 0) {
      wentry.tag   := tag
      wentry.conf  := 1
      wentry.s_cnt := 0
      wentry.p_cnt := io.update_meta.s_cnt

    // Confident, no tag match, age > 0 -> decrement age
    } elsewhen (entry.conf =/= 0 && !tag_match && entry.age =/= 0) {
      wentry.age := entry.age - 1

    // Unconfident, tag match, ctr match -> increment confidence
    } elsewhen (entry.conf === 0 && tag_match && ctr_match) {
      wentry.conf  := 1
      wentry.age   := 7
      wentry.s_cnt := 0

    // Unconfident, tag match, no ctr match -> set previous counter
    } elsewhen (entry.conf === 0 && tag_match && !ctr_match) {
      wentry.p_cnt := io.update_meta.s_cnt
      wentry.age   := 7
      wentry.s_cnt := 0

    // Unconfident, no tag match -> set previous counter and tag
    } elsewhen (entry.conf === 0 && !tag_match) {
      wentry.tag   := tag
      wentry.conf  := 1
      wentry.age   := 7
      wentry.s_cnt := 0
      wentry.p_cnt := io.update_meta.s_cnt
    }

    entries.write(io.update_idx)(_ := wentry)
  } elsewhen (io.update_repair && !doing_reset) {
    when (tag_match && !(f4_fire && io.update_idx === f4_idx)) {
      wentry.s_cnt := io.update_meta.s_cnt
      entries.write(io.update_idx)(_ := wentry)
    }
  }

  when (doing_reset) {
    entries.write(reset_idx)(_ := LoopEntry(tagSz).getZero)
  }
}

class LoopBranchPredictorBank(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  val tagSz = 10
  override val nSets = 16

  val columns = Seq.fill(bankWidth) { new LoopBranchPredictorColumn(nSets, tagSz, s2_idx.getWidth) }
  val mems = Nil // TODO fix
  val f3_meta = Vec.fill(bankWidth)(LoopMeta())
  override val metaSz = f3_meta.getBitsWidth

  val update_meta = Vec.fill(bankWidth)(LoopMeta()); update_meta.assignFromBits(s1_update.payload.meta.asBits.resized)

  for (w <- 0 until bankWidth) {
    columns(w).io.f2_req_valid := s2_valid
    columns(w).io.f2_req_idx  := s2_idx
    columns(w).io.f3_req_fire := (s3_valid && s3_mask(w) && io.f3_fire &&
      RegNext(io.resp_in(0).f2(w).predicted_pc.valid && io.resp_in(0).f2(w).is_br))

    columns(w).io.f3_pred_in  := io.resp_in(0).f3(w).taken
    io.resp.f3(w).taken       := columns(w).io.f3_pred

    columns(w).io.update_mispredict      := (s1_update.valid &&
                                             s1_update.payload.br_mask(w) &&
                                             s1_update.payload.is_mispredict_update &&
                                             s1_update.payload.cfi_mispredicted)
    columns(w).io.update_repair          := (s1_update.valid &&
                                             s1_update.payload.br_mask(w) &&
                                            s1_update.payload.is_repair_update)
    columns(w).io.update_idx             := s1_update_idx
    columns(w).io.update_resolve_dir     := s1_update.payload.cfi_taken
    columns(w).io.update_meta            := update_meta(w)

    f3_meta(w) := columns(w).io.f3_meta
  }

  io.f3_meta := f3_meta.asBits.asUInt.resized
}
