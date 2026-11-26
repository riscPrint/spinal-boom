package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

case class TageResp() extends Bundle {
  val ctr = UInt(3 bits)
  val u   = UInt(2 bits)
}

class TageTable(val nRows: Int, val tagSz: Int, val histLength: Int, val uBitPeriod: Int)
  (implicit p: Parameters) extends BoomModule()(p)
  with HasBoomFrontendParameters
{
  require(histLength <= globalHistoryLength)

  val nWrBypassEntries = 2
  val io = new Bundle {
    val f1_req_valid = in Bool()
    val f1_req_pc    = in UInt(vaddrBitsExtended bits)
    val f1_req_ghist = in UInt(globalHistoryLength bits)

    val f2_resp = out(Vec.fill(bankWidth)(Flow(TageResp())))

    val update_mask    = in(Vec.fill(bankWidth)(Bool()))
    val update_taken   = in(Vec.fill(bankWidth)(Bool()))
    val update_alloc   = in(Vec.fill(bankWidth)(Bool()))
    val update_old_ctr = in(Vec.fill(bankWidth)(UInt(3 bits)))

    val update_pc    = in UInt(vaddrBitsExtended bits)
    val update_hist  = in UInt(globalHistoryLength bits)

    val update_u_mask = in(Vec.fill(bankWidth)(Bool()))
    val update_u = in(Vec.fill(bankWidth)(UInt(2 bits)))
  }

  def compute_folded_hist(hist: UInt, l: Int): UInt = {
    val nChunks = (histLength + l - 1) / l
    val hist_chunks = (0 until nChunks) map {i =>
      hist(scala.math.min((i+1)*l, histLength)-1 downto i*l).resize(l)
    }
    hist_chunks.reduce(_^_)
  }

  def compute_tag_and_hash(unhashed_idx: UInt, hist: UInt): (UInt, UInt) = {
    val idx_history = compute_folded_hist(hist, log2Ceil(nRows))
    val idx = (unhashed_idx ^ idx_history.resize(unhashed_idx.getWidth))(log2Ceil(nRows)-1 downto 0)
    val tag_history = compute_folded_hist(hist, tagSz)
    val hi = unhashed_idx >> log2Ceil(nRows)
    val tag = (hi.resize(hi.getWidth max tagSz) ^ tag_history.resize(hi.getWidth max tagSz))(tagSz-1 downto 0)
    (idx, tag)
  }

  def inc_ctr(ctr: UInt, taken: Bool): UInt = {
    Mux(!taken, Mux(ctr === 0, U(0, 3 bits), ctr - 1),
                Mux(ctr === 7, U(7, 3 bits), ctr + 1))
  }

  val doing_reset = Reg(Bool()) init True
  val reset_idx = Reg(UInt(log2Ceil(nRows) bits)) init 0
  reset_idx := reset_idx + U(doing_reset).resized
  when (reset_idx === (nRows-1)) { doing_reset := False }

  case class TageEntry() extends Bundle {
    val valid = Bool() // TODO: Remove this valid bit
    val tag = UInt(tagSz bits)
    val ctr = UInt(3 bits)
  }

  val tageEntrySz = 1 + tagSz + 3

  val (s1_hashed_idx, s1_tag) = compute_tag_and_hash(fetchIdx(io.f1_req_pc), io.f1_req_ghist)

  val us     = new SyncReadVecMem(nRows, bankWidth*2, Bool()).setName(s"tage_u_${histLength}")
  val table  = new SyncReadVecMem(nRows, bankWidth, TageEntry()).setName(s"tage_table_${histLength}")

  val mems = Seq((f"tage_l$histLength", nRows, bankWidth * tageEntrySz))

  val s2_tag       = FixedPipeline.delay(s1_tag, 1)

  val s2_req_rtage = Vec.fill(bankWidth)(TageEntry())
  val s2_req_rus = Vec.fill(bankWidth*2)(Bool())
  val s2_req_rhits = Vec(s2_req_rtage.map(e => e.valid && e.tag === s2_tag && !doing_reset))

  for (w <- 0 until bankWidth) {
    // This bit indicates the TAGE table matched here
    io.f2_resp(w).valid    := s2_req_rhits(w)
    io.f2_resp(w).payload.u   := Cat(s2_req_rus(w*2+1), s2_req_rus(w*2)).asUInt
    io.f2_resp(w).payload.ctr := s2_req_rtage(w).ctr
  }

  val clear_u_ctr = Reg(UInt(log2Ceil(uBitPeriod) + log2Ceil(nRows) + 1 bits)) init 0
  when (doing_reset) { clear_u_ctr := 1 } otherwise { clear_u_ctr := clear_u_ctr + 1 }

  val doing_clear_u = clear_u_ctr(log2Ceil(uBitPeriod)-1 downto 0) === 0
  val clear_u_hi = clear_u_ctr(log2Ceil(uBitPeriod) + log2Ceil(nRows)) === True
  val clear_u_lo = clear_u_ctr(log2Ceil(uBitPeriod) + log2Ceil(nRows)) === False
  val clear_u_idx = clear_u_ctr >> log2Ceil(uBitPeriod)
  val clear_u_mask = (0 until bankWidth*2).map { i => if (i % 2 == 0) clear_u_lo else clear_u_hi }.asUInt

  val (update_idx, update_tag) = compute_tag_and_hash(fetchIdx(io.update_pc), io.update_hist)

  val update_wdata = Vec.fill(bankWidth)(TageEntry())
  val wen = CombInit(doing_reset || io.update_mask.reduce(_||_))
  s2_req_rtage := table.read(s1_hashed_idx, io.f1_req_valid)
  table.write(
    Mux(doing_reset, reset_idx, update_idx),
    Mux(doing_reset, Vec.fill(bankWidth)(TageEntry()).getZero, update_wdata),
    Mux(doing_reset, ~U(0, bankWidth bits), io.update_mask.asUInt).asBools,
    wen)

  val update_u_mask = Vec((0 until bankWidth*2) map {i => io.update_u_mask(i / 2)})
  val update_u_wen = CombInit(doing_reset || doing_clear_u || update_u_mask.reduce(_||_))
  s2_req_rus := us.read(s1_hashed_idx, io.f1_req_valid)
  us.write(
    Mux(doing_reset, reset_idx, Mux(doing_clear_u, clear_u_idx.resize(log2Ceil(nRows)), update_idx)),
    Mux(doing_reset || doing_clear_u, Vec.fill(bankWidth*2)(False), Vec(CatLsbFirst(io.update_u).asBools)),
    Mux(doing_reset, ~U(0, bankWidth*2 bits), Mux(doing_clear_u, clear_u_mask, update_u_mask.asUInt)).asBools,
    update_u_wen)

  val wrbypass_tags    = Reg(Vec.fill(nWrBypassEntries)(UInt(tagSz bits)))
  val wrbypass_idxs    = Reg(Vec.fill(nWrBypassEntries)(UInt(log2Ceil(nRows) bits)))
  val wrbypass         = Reg(Vec.fill(nWrBypassEntries)(Vec.fill(bankWidth)(UInt(3 bits))))
  val wrbypass_enq_idx = Reg(UInt(log2Ceil(nWrBypassEntries) bits)) init 0

  val wrbypass_hits    = Vec((0 until nWrBypassEntries) map { i =>
    !doing_reset &&
    wrbypass_tags(i) === update_tag &&
    wrbypass_idxs(i) === update_idx
  })
  val wrbypass_hit     = wrbypass_hits.reduce(_||_)
  val wrbypass_hit_idx = PriorityEncoder(wrbypass_hits)

  for (w <- 0 until bankWidth) {
    update_wdata(w).ctr   := Mux(io.update_alloc(w),
      Mux(io.update_taken(w), U(4, 3 bits),
                              U(3, 3 bits)
      ),
      Mux(wrbypass_hit,       inc_ctr(wrbypass.at(wrbypass_hit_idx)(w), io.update_taken(w)),
                              inc_ctr(io.update_old_ctr(w), io.update_taken(w))
      )
    )
    update_wdata(w).valid := True
    update_wdata(w).tag   := update_tag
  }

  when (io.update_mask.reduce(_||_)) {
    when (wrbypass_hits.reduce(_||_)) {
      wrbypass.write(wrbypass_hit_idx)(_ := Vec(update_wdata.map(_.ctr)))
    } otherwise {
      wrbypass     .write(wrbypass_enq_idx)(_ := Vec(update_wdata.map(_.ctr)))
      wrbypass_tags.write(wrbypass_enq_idx)(_ := update_tag)
      wrbypass_idxs.write(wrbypass_enq_idx)(_ := update_idx)
      wrbypass_enq_idx := WrapInc(wrbypass_enq_idx, nWrBypassEntries)
    }
  }
}

case class BoomTageParams(
  //                                           nSets, histLen, tagSz
  tableInfo: Seq[Tuple3[Int, Int, Int]] = Seq((  128,       2,     7),
                                              (  128,       4,     7),
                                              (  256,       8,     8),
                                              (  256,      16,     8),
                                              (  128,      32,     9),
                                              (  128,      64,     9)),
  uBitPeriod: Int = 2048
)

class TageBranchPredictorBank(params: BoomTageParams = BoomTageParams())(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  val tageUBitPeriod = params.uBitPeriod
  val tageNTables    = params.tableInfo.size

  case class TageMeta() extends Bundle
  {
    val provider      = Vec.fill(bankWidth)(Flow(UInt(log2Ceil(tageNTables) bits)))
    val alt_differs   = Vec.fill(bankWidth)(Bool())
    val provider_u    = Vec.fill(bankWidth)(UInt(2 bits))
    val provider_ctr  = Vec.fill(bankWidth)(UInt(3 bits))
    val allocate      = Vec.fill(bankWidth)(Flow(UInt(log2Ceil(tageNTables) bits)))
  }

  val f3_meta = TageMeta()
  override val metaSz = f3_meta.getBitsWidth
  require(metaSz <= bpdMaxMetaLength)

  def inc_u(u: UInt, alt_differs: Bool, mispredict: Bool): UInt = {
    Mux(!alt_differs, u,
    Mux(mispredict, Mux(u === 0, U(0, 2 bits), u - 1),
                    Mux(u === 3, U(3, 2 bits), u + 1)))
  }

  val tt = params.tableInfo map {
    case (n, l, s) => {
      val t = new TageTable(n, s, l, params.uBitPeriod)
      t.io.f1_req_valid := s1_valid
      t.io.f1_req_pc    := s1_pc
      t.io.f1_req_ghist := io.f1_ghist
      (t, t.mems)
    }
  }
  val tables = tt.map(_._1)
  val mems = tt.map(_._2).flatten

  val f2_resps = Vec(tables.map(_.io.f2_resp))
  val f3_resps = predAt(f2_resps, 2, 3)

  val s1_update_meta = TageMeta(); s1_update_meta.assignFromBits(s1_update.payload.meta.asBits.resized)
  val s1_update_mispredict_mask = UIntToOH(s1_update.payload.cfi_idx.payload, bankWidth) &
    Fill(bankWidth, s1_update.payload.cfi_mispredicted)

  val s1_update_mask    = Vec.fill(tageNTables)(Vec.fill(bankWidth)(Bool()))
  val s1_update_u_mask  = Vec.fill(tageNTables)(Vec.fill(bankWidth)(UInt(1 bits)))
  s1_update_mask.foreach(_.foreach(_ := False))
  s1_update_u_mask.foreach(_.foreach(_ := 0))

  val s1_update_taken   = Vec.fill(tageNTables)(Vec.fill(bankWidth)(Bool()))
  val s1_update_old_ctr = Vec.fill(tageNTables)(Vec.fill(bankWidth)(UInt(3 bits)))
  val s1_update_alloc   = Vec.fill(tageNTables)(Vec.fill(bankWidth)(Bool()))
  val s1_update_u       = Vec.fill(tageNTables)(Vec.fill(bankWidth)(UInt(2 bits)))

  s1_update_taken.assignDontCare()
  s1_update_old_ctr.assignDontCare()
  s1_update_alloc.assignDontCare()
  s1_update_u.assignDontCare()

  val pw = log2Ceil(tageNTables)
  for (w <- 0 until bankWidth) {
    var s2_provided: Bool = False
    var s2_provider: UInt = U(0, pw bits)
    var s2_alt_provided: Bool = False
    var s2_alt_provider: UInt = U(0, pw bits)
    for (i <- 0 until tageNTables) {
      val hit = f2_resps(i)(w).valid
      s2_alt_provided = s2_alt_provided || (s2_provided && hit)
      s2_provided = s2_provided || hit

      s2_alt_provider = Mux(hit, s2_provider, s2_alt_provider)
      s2_provider = Mux(hit, U(i, pw bits), s2_provider)
    }
    val selection = new Bundle {
      val provided = Bool()
      val provider = UInt(pw bits)
      val alt_provided = Bool()
      val alt_provider = UInt(pw bits)
      val prov = TageResp()
      val alt = TageResp()
    }
    selection.provided := s2_provided
    selection.provider := s2_provider
    selection.alt_provided := s2_alt_provided
    selection.alt_provider := s2_alt_provider
    selection.prov := f2_resps.at(s2_provider)(w).payload
    selection.alt := f2_resps.at(s2_alt_provider)(w).payload
    val s3_selection = predAt(selection, 2, 3)
    val s3_provided = s3_selection.provided
    val s3_provider = s3_selection.provider
    val s3_alt_provided = s3_selection.alt_provided
    val s3_alt_provider = s3_selection.alt_provider
    val prov = s3_selection.prov
    val alt = s3_selection.alt

    io.resp.f3(w).taken := Mux(s3_provided,
      Mux(prov.ctr === 3 || prov.ctr === 4,
        Mux(s3_alt_provided, alt.ctr(2), io.resp_in(0).f3(w).taken),
        prov.ctr(2)),
      io.resp_in(0).f3(w).taken
    )
    f3_meta.provider(w).valid := s3_provided
    f3_meta.provider(w).payload  := s3_provider
    f3_meta.alt_differs(w)    := s3_alt_provided && alt.ctr(2) =/= io.resp.f3(w).taken
    f3_meta.provider_u(w)     := prov.u
    f3_meta.provider_ctr(w)   := prov.ctr

    // Create a mask of tables which did not hit our query, and also contain useless entries
    // and also uses a longer history than the provider
    val allocatable_slots = (
      f3_resps.map(r => !r(w).valid && r(w).payload.u === 0).asUInt &
      ~(MaskLower(UIntToOH(f3_meta.provider(w).payload, tageNTables)) & Fill(tageNTables, f3_meta.provider(w).valid))
    )
    val alloc_lfsr = LFSR(tageNTables max 2)

    val first_entry = PriorityEncoder(allocatable_slots)
    val masked_entry = PriorityEncoder(allocatable_slots & alloc_lfsr.resize(tageNTables))
    val alloc_entry = Mux(allocatable_slots(masked_entry),
      masked_entry,
      first_entry)

    f3_meta.allocate(w).valid := allocatable_slots =/= 0
    f3_meta.allocate(w).payload  := alloc_entry

    val update_was_taken = (s1_update.payload.cfi_idx.valid &&
                            (s1_update.payload.cfi_idx.payload === w) &&
                            s1_update.payload.cfi_taken)
    when (s1_update.payload.br_mask(w) && s1_update.valid && s1_update.payload.is_commit_update) {
      when (s1_update_meta.provider(w).valid) {
        val provider = s1_update_meta.provider(w).payload

        s1_update_mask.write(provider)(_(w) := True)
        s1_update_u_mask.write(provider)(_(w) := 1)

        val new_u = inc_u(s1_update_meta.provider_u(w),
                          s1_update_meta.alt_differs(w),
                          s1_update_mispredict_mask(w))
        s1_update_u      .write(provider)(_(w) := new_u)
        s1_update_taken  .write(provider)(_(w) := update_was_taken)
        s1_update_old_ctr.write(provider)(_(w) := s1_update_meta.provider_ctr(w))
        s1_update_alloc  .write(provider)(_(w) := False)
      }
    }
  }
  when (s1_update.valid && s1_update.payload.is_commit_update && s1_update.payload.cfi_mispredicted && s1_update.payload.cfi_idx.valid) {
    val idx = s1_update.payload.cfi_idx.payload
    val allocate = s1_update_meta.allocate.at(idx)
    when (allocate.valid) {
      s1_update_mask .write(allocate.payload)(_.write(idx)(_ := True))
      s1_update_taken.write(allocate.payload)(_.write(idx)(_ := s1_update.payload.cfi_taken))
      s1_update_alloc.write(allocate.payload)(_.write(idx)(_ := True))

      s1_update_u_mask.write(allocate.payload)(_.write(idx)(_ := 1))
      s1_update_u     .write(allocate.payload)(_.write(idx)(_ := 0))

    } otherwise {
      val provider = s1_update_meta.provider.at(idx)
      val decr_mask = Mux(provider.valid, ~MaskLower(UIntToOH(provider.payload, tageNTables)), U(0, tageNTables bits))

      for (i <- 0 until tageNTables) {
        when (decr_mask(i)) {
          s1_update_u_mask(i).write(idx)(_ := 1)
          s1_update_u     (i).write(idx)(_ := 0)
        }
      }
    }
  }

  val s2_update_mask = updAt(s1_update_mask, 1, 2)
  val s2_update_taken = updAt(s1_update_taken, 1, 2)
  val s2_update_alloc = updAt(s1_update_alloc, 1, 2)
  val s2_update_old_ctr = updAt(s1_update_old_ctr, 1, 2)
  val s2_update_u_mask = updAt(s1_update_u_mask, 1, 2)
  val s2_update_u = updAt(s1_update_u, 1, 2)
  for (i <- 0 until tageNTables) {
    for (w <- 0 until bankWidth) {
      tables(i).io.update_mask(w)    := s2_update_mask(i)(w)
      tables(i).io.update_taken(w)   := s2_update_taken(i)(w)
      tables(i).io.update_alloc(w)   := s2_update_alloc(i)(w)
      tables(i).io.update_old_ctr(w) := s2_update_old_ctr(i)(w)

      tables(i).io.update_u_mask(w) := s2_update_u_mask(i)(w).lsb
      tables(i).io.update_u(w)      := s2_update_u(i)(w)
    }
    tables(i).io.update_pc    := upd(2)(UPDATE).payload.pc
    tables(i).io.update_hist  := upd(2)(UPDATE).payload.ghist
  }

  io.f3_meta := f3_meta.asBits.asUInt.resized
}
