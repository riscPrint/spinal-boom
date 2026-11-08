package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

case class BoomBTBParams(
  nSets: Int = 128,
  nWays: Int = 2,
  offsetSz: Int = 13,
  extendedNSets: Int = 128,
  useFlops: Boolean = false
)

class BTBBranchPredictorBank(params: BoomBTBParams = BoomBTBParams())(implicit p: Parameters) extends BranchPredictorBank()(p)
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

  val s1_meta = BTBPredictMeta()
  val f3_meta = predAt(s1_meta, 1, 3)

  io.f3_meta := f3_meta.asBits.asUInt.resized

  override val metaSz = s1_meta.getBitsWidth

  val doing_reset = Reg(Bool()) init True
  val reset_idx   = Reg(UInt(log2Ceil(nSets) bits)) init 0
  reset_idx := reset_idx + U(doing_reset).resized
  when (reset_idx === (nSets-1)) { doing_reset := False }

  val mems = (((0 until nWays) map ({w:Int => Seq(
    (f"btb_meta_way$w", nSets, bankWidth * btbMetaSz),
    (f"btb_data_way$w", nSets, bankWidth * btbEntrySz))})).flatten ++ Seq(("ebtb", extendedNSets, vaddrBitsExtended)))
  val s1_req_rmeta = Vec.fill(nWays)(Vec.fill(bankWidth)(BTBMeta()))
  val s1_req_rbtb  = Vec.fill(nWays)(Vec.fill(bankWidth)(BTBEntry()))
  val s1_req_rebtb = UInt(vaddrBitsExtended bits)

  val s1_req_tag   = s1_idx >> log2Ceil(nSets)

  val s1_resp   = Vec.fill(bankWidth)(Flow(UInt(vaddrBitsExtended bits)))
  val s1_is_br  = Vec.fill(bankWidth)(Bool())
  val s1_is_jal = Vec.fill(bankWidth)(Bool())

  val s1_hit_ohs = Vec((0 until bankWidth) map { i =>
    Vec((0 until nWays) map { w =>
      s1_req_rmeta(w)(i).tag === s1_req_tag(tagSz-1 downto 0)
    })
  })
  val s1_hits     = s1_hit_ohs.map { oh => oh.reduce(_||_) }
  val HITS = pred(1).insert(Vec(s1_hits))
  val s1_hit_ways = s1_hit_ohs.map { oh => PriorityEncoder(oh) }
  val s1_targs    = Vec.fill(nWays)(Vec.fill(bankWidth)(UInt(vaddrBitsExtended bits)))
  for (w <- 0 until bankWidth) {
    for (b <- 0 until nWays) {
      val entry_btb = CombInit(s1_req_rbtb(b)(w))
      s1_targs(b)(w) := Mux(entry_btb.extended,
        s1_req_rebtb,
        (s1_pc.asSInt + S(w << 1) + entry_btb.offset).asUInt.resized)
    }

    val entry_meta = s1_req_rmeta.at(s1_hit_ways(w))(w)
    s1_resp(w).valid := !doing_reset && s1_valid && s1_hits(w)
    s1_resp(w).payload  := s1_targs.at(s1_hit_ways(w))(w)
    s1_is_br(w)  := !doing_reset && s1_resp(w).valid &&  entry_meta.is_br
    s1_is_jal(w) := !doing_reset && s1_resp(w).valid && !entry_meta.is_br

    io.resp.f1(w) := io.resp_in(0).f1(w)
    io.resp.f2(w) := io.resp_in(0).f2(w)
    io.resp.f3(w) := io.resp_in(0).f3(w)
    // Response ages ride the free-running prediction links; the hit selects them.
    val f2_resp   = predAt(s1_resp(w), 1, 2)
    val f2_is_br  = predAt(s1_is_br(w), 1, 2)
    val f2_is_jal = predAt(s1_is_jal(w), 1, 2)
    val f3_resp   = predAt(io.resp.f2(w).predicted_pc, 2, 3)
    val f3_is_br  = predAt(io.resp.f2(w).is_br, 2, 3)
    val f3_is_jal = predAt(io.resp.f2(w).is_jal, 2, 3)
    val f3_jal_taken = predAt(s1_is_jal(w), 1, 3)
    when (pred(2)(HITS)(w)) {
      io.resp.f2(w).predicted_pc := f2_resp
      io.resp.f2(w).is_br        := f2_is_br
      io.resp.f2(w).is_jal       := f2_is_jal
      when (f2_is_jal) {
        io.resp.f2(w).taken      := True
      }
    }
    when (pred(3)(HITS)(w)) {
      io.resp.f3(w).predicted_pc := f3_resp
      io.resp.f3(w).is_br        := f3_is_br
      io.resp.f3(w).is_jal       := f3_is_jal
      when (f3_jal_taken) {
        io.resp.f3(w).taken      := True
      }
    }
  }

  val alloc_way = if (nWays > 1) {
    val r_metas = Cat(CatLsbFirst(s1_req_rmeta.map { w => CatLsbFirst(w.map(_.tag)) }), s1_req_tag(tagSz-1 downto 0)).asUInt
    val l = log2Ceil(nWays)
    val nChunks = (r_metas.getWidth + l - 1) / l
    val chunks = (0 until nChunks) map { i =>
      r_metas(scala.math.min((i+1)*l, r_metas.getWidth)-1 downto i*l).resize(l)
    }
    chunks.reduce(_^_)
  } else {
    U(0, 0 bits)
  }
  s1_meta.write_way := Mux(s1_hits.reduce(_||_),
    PriorityEncoder(s1_hit_ohs.map(_.asUInt).reduce(_|_)),
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

  if (params.useFlops) {
    for (w <- 0 until nWays) {
      val meta = Reg(Vec.fill(nSets)(Vec.fill(bankWidth)(BTBMeta())))
      val btb  = Reg(Vec.fill(nSets)(Vec.fill(bankWidth)(BTBEntry())))
      s1_req_rmeta(w) := meta.at(s1_idx)
      s1_req_rbtb(w)  := btb.at(s1_idx)
      for (i <- 0 until bankWidth) {
        when (doing_reset || s1_update_meta.write_way === w || Bool(nWays == 1)) {
          when (doing_reset || s1_update_wbtb_mask(i)) {
            btb.write(Mux(doing_reset, reset_idx.resize(s1_update_idx.getWidth), s1_update_idx))(_(i) := Mux(doing_reset,
              BTBEntry().getZero, s1_update_wbtb_data))
          }
          when (doing_reset || s1_update_wmeta_mask(i)) {
            meta.write(Mux(doing_reset, reset_idx.resize(s1_update_idx.getWidth), s1_update_idx))(_(i) := Mux(doing_reset,
              BTBMeta().getZero, s1_update_wmeta_data(i)))
          }
        }
      }
    }
    val ebtb = Reg(Vec.fill(extendedNSets)(UInt(vaddrBitsExtended bits)))
    s1_req_rebtb := ebtb.at(s1_idx)

  } else {
    for (w <- 0 until nWays) {
      val meta = new SyncReadVecMem(nSets, bankWidth, BTBMeta()).setName(s"btb_meta_way_${w}")
      val btb  = new SyncReadVecMem(nSets, bankWidth, BTBEntry()).setName(s"btb_data_way_${w}")
      s1_req_rmeta(w) := meta.read(s0_idx, s0_valid)
      s1_req_rbtb(w)  := btb.read(s0_idx, s0_valid)

      val wen = doing_reset || s1_update_meta.write_way === w || Bool(nWays == 1)
      btb.write(
        Mux(doing_reset, reset_idx.resize(s1_update_idx.getWidth), s1_update_idx),
        Mux(doing_reset, Vec.fill(bankWidth)(BTBEntry().getZero),
                         Vec.fill(bankWidth)(s1_update_wbtb_data)),
        Mux(doing_reset, ~U(0, bankWidth bits), s1_update_wbtb_mask).asBools,
        wen
      )
      meta.write(
        Mux(doing_reset, reset_idx.resize(s1_update_idx.getWidth), s1_update_idx),
        Mux(doing_reset, Vec.fill(bankWidth)(BTBMeta().getZero),
                         s1_update_wmeta_data),
        Mux(doing_reset, ~U(0, bankWidth bits), s1_update_wmeta_mask).asBools,
        wen
      )
    }
    val ebtb = new SyncReadMemC(extendedNSets, UInt(vaddrBitsExtended bits)).setName("btb_ebtb")
    s1_req_rebtb := ebtb.read(s0_idx, s0_valid)
    ebtb.write(s1_update_idx, s1_update.payload.target, s1_update_wbtb_mask =/= 0 && offset_is_extended)
  }
}
