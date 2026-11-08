package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

case class BoomSlowBTBParams(
  nSets: Int = 128,
  nWays: Int = 2,
  offsetSz: Int = 13,
  extendedNSets: Int = 128,
  singlePorted: Boolean = false
)

class SlowBTBBranchPredictorBank(params: BoomSlowBTBParams = BoomSlowBTBParams())(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  override val nSets         = params.nSets
  override val nWays         = params.nWays
  val tagSz         = vaddrBitsExtended - log2Ceil(nSets) - log2Ceil(fetchWidth) - 1
  val offsetSz      = params.offsetSz
  val extendedNSets = params.extendedNSets

  require(isPow2(nSets))
  require(isPow2(extendedNSets) || extendedNSets == 0)
  require(extendedNSets <= nSets)
  require(extendedNSets >= 1)

  case class BTBEntry() extends Bundle {
    val offset   = SInt(offsetSz bits)
    val extended = Bool()
  }
  val btbEntrySz = offsetSz + 1

  case class BTBMeta() extends Bundle {
    val is_br = Bool()
    val tag   = UInt(tagSz bits)
  }
  val btbMetaSz = tagSz + 1

  case class BTBPredictMeta() extends Bundle {
    val write_way = UInt(log2Ceil(nWays) bits)
  }

  val s2_meta = BTBPredictMeta()
  val f3_meta = predAt(s2_meta, 2, 3)

  io.f3_meta := f3_meta.asBits.asUInt.resized

  override val metaSz = s2_meta.getBitsWidth

  val doing_reset = Reg(Bool()) init True
  val reset_idx   = Reg(UInt(log2Ceil(nSets) bits)) init 0
  reset_idx := reset_idx + U(doing_reset).resized
  when (reset_idx === (nSets-1)) { doing_reset := False }

  val mems = (((0 until nWays) map ({w:Int => Seq(
    (f"btb_meta_way$w", nSets, bankWidth * btbMetaSz),
    (f"btb_data_way$w", nSets, bankWidth * btbEntrySz))})).flatten ++ Seq(("ebtb", extendedNSets, vaddrBitsExtended)))
  val s2_req_rmeta = Vec.fill(nWays)(Vec.fill(bankWidth)(BTBMeta()))
  val s2_req_rbtb  = Vec.fill(nWays)(Vec.fill(bankWidth)(BTBEntry()))
  val s2_req_rebtb = UInt(vaddrBitsExtended bits)

  val s1_req_tag   = s1_idx >> log2Ceil(nSets)
  val s2_req_tag   = predAt(s1_req_tag, 1, 2)

  val s2_resp   = Vec.fill(bankWidth)(Flow(UInt(vaddrBitsExtended bits)))
  val s2_is_br  = Vec.fill(bankWidth)(Bool())
  val s2_is_jal = Vec.fill(bankWidth)(Bool())

  val s2_hit_ohs = Vec((0 until bankWidth) map { i =>
    Vec((0 until nWays) map { w =>
      s2_req_rmeta(w)(i).tag === s2_req_tag(tagSz-1 downto 0)
    })
  })
  val s2_hits     = s2_hit_ohs.map { oh => oh.reduce(_||_) }
  val s2_hit_ways = s2_hit_ohs.map { oh => PriorityEncoder(oh) }
  val s2_targs    = Vec.fill(nWays)(Vec.fill(bankWidth)(UInt(vaddrBitsExtended bits)))
  for (w <- 0 until bankWidth) {
    for (b <- 0 until nWays) {
      val entry_btb = CombInit(s2_req_rbtb(b)(w))
      s2_targs(b)(w) := Mux(entry_btb.extended,
        s2_req_rebtb,
        (s2_pc.asSInt + S(w << 1) + entry_btb.offset).asUInt.resized)
    }

    val entry_meta = s2_req_rmeta.at(s2_hit_ways(w))(w)
    s2_resp(w).valid := !doing_reset && s2_valid && s2_hits(w)
    s2_resp(w).payload  := s2_targs.at(s2_hit_ways(w))(w)
    s2_is_br(w)  := !doing_reset && s2_resp(w).valid &&  entry_meta.is_br
    s2_is_jal(w) := !doing_reset && s2_resp(w).valid && !entry_meta.is_br

    io.resp.f1(w) := io.resp_in(0).f1(w)
    io.resp.f2(w) := io.resp_in(0).f2(w)
    io.resp.f3(w) := io.resp_in(0).f3(w)
    val f3_resp = predAt(s2_resp(w), 2, 3)
    when (predAt(s2_hits(w), 2, 3)) {
      io.resp.f3(w).predicted_pc := f3_resp
    }
  }

  val alloc_way = if (nWays > 1) {
    val r_metas = Cat(CatLsbFirst(s2_req_rmeta.map { w => CatLsbFirst(w.map(_.tag)) }), s2_req_tag(tagSz-1 downto 0)).asUInt
    val l = log2Ceil(nWays)
    val nChunks = (r_metas.getWidth + l - 1) / l
    val chunks = (0 until nChunks) map { i =>
      r_metas(scala.math.min((i+1)*l, r_metas.getWidth)-1 downto i*l).resize(l)
    }
    chunks.reduce(_^_)
  } else {
    U(0, 0 bits)
  }
  s2_meta.write_way := Mux(s2_hits.reduce(_||_),
    PriorityEncoder(s2_hit_ohs.map(_.asUInt).reduce(_|_)),
    alloc_way).resized

  val s1_update_cfi_idx = s1_update.payload.cfi_idx.payload
  val s1_update_meta    = BTBPredictMeta(); s1_update_meta.assignFromBits(s1_update.payload.meta.asBits.resized)

  val max_offset_value = Cat(False, ~U(0, offsetSz-1 bits)).asSInt
  val min_offset_value = Cat(True,  U(0, offsetSz-1 bits)).asSInt
  val new_offset_value = (s1_update.payload.target.asSInt -
    (s1_update.payload.pc + (s1_update.payload.cfi_idx.payload << 1).resized).asSInt)
  val offset_is_extended = (new_offset_value > max_offset_value ||
                            new_offset_value < min_offset_value)

  val s1_update_wbtb_data  = BTBEntry()
  s1_update_wbtb_data.extended := offset_is_extended
  s1_update_wbtb_data.offset   := new_offset_value.resized
  val s1_update_wbtb_mask = (UIntToOH(s1_update_cfi_idx, bankWidth) &
    Fill(bankWidth, s1_update.payload.cfi_idx.valid && s1_update.valid && s1_update.payload.cfi_taken && s1_update.payload.is_commit_update))

  val s1_update_wmeta_mask = ((s1_update_wbtb_mask | s1_update.payload.br_mask) &
    (Fill(bankWidth, s1_update.valid && s1_update.payload.is_commit_update) |
     (Fill(bankWidth, s1_update.valid) & s1_update.payload.btb_mispredicts)
    )
  )
  val s1_update_wmeta_data = Vec.fill(bankWidth)(BTBMeta())

  for (w <- 0 until bankWidth) {
    s1_update_wmeta_data(w).tag     := Mux(s1_update.payload.btb_mispredicts(w), U(0, tagSz bits), (s1_update_idx >> log2Ceil(nSets)).resize(tagSz))
    s1_update_wmeta_data(w).is_br   := s1_update.payload.br_mask(w)
  }

  val ebtb = new SyncReadMemC(extendedNSets, UInt(vaddrBitsExtended bits))

  val ewen = CombInit(s1_update_wbtb_mask =/= 0 && offset_is_extended)
  val rebtb = if (params.singlePorted) ebtb.read(s1_idx, s1_valid && !ewen) else ebtb.read(s1_idx, s1_valid)
  s2_req_rebtb := rebtb
  ebtb.write(s1_update_idx, s1_update.payload.target, ewen)

  for (w <- 0 until nWays) {
    val meta = new SyncReadVecMem(nSets, bankWidth, BTBMeta()).setName(s"slowbtb_meta_way_${w}")
    val btb  = new SyncReadVecMem(nSets, bankWidth, BTBEntry()).setName(s"slowbtb_data_way_${w}")

    val btbwen  = CombInit(doing_reset || ((s1_update_meta.write_way === w || Bool(nWays == 1)) && s1_update_wbtb_mask =/= 0))
    val metawen = CombInit(doing_reset || ((s1_update_meta.write_way === w || Bool(nWays == 1)) && s1_update_wmeta_mask =/= 0))

    val rbtb = if (params.singlePorted) btb.read(s1_idx, s1_valid && !btbwen) else btb.read(s1_idx, s1_valid)
    s2_req_rbtb(w) := rbtb

    btb.write(
      Mux(doing_reset, reset_idx.resize(s1_update_idx.getWidth), s1_update_idx),
      Mux(doing_reset, Vec.fill(bankWidth)(BTBEntry().getZero), Vec.fill(bankWidth)(s1_update_wbtb_data)),
      Mux(doing_reset, ~U(0, bankWidth bits), s1_update_wbtb_mask).asBools,
      btbwen)

    val rmeta = if (params.singlePorted) meta.read(s1_idx, s1_valid && !metawen) else meta.read(s1_idx, s1_valid)
    when (FixedPipeline.delay(btbwen || metawen, 1) && Bool(params.singlePorted)) {
      s2_req_rmeta(w) := Vec.fill(bankWidth)(BTBMeta()).getZero
    } otherwise {
      s2_req_rmeta(w) := rmeta
    }
    meta.write(
      Mux(doing_reset, reset_idx.resize(s1_update_idx.getWidth), s1_update_idx),
      Mux(doing_reset, Vec.fill(bankWidth)(BTBMeta().getZero), s1_update_wmeta_data),
      Mux(doing_reset, ~U(0, bankWidth bits), s1_update_wmeta_mask).asBools,
      metawen)
  }
}
