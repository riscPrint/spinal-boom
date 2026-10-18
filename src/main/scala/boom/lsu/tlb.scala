package boom.lsu

import spinal.core._
import spinal.lib._

import rocketchip.config.{Parameters, MemoryRegion, RegionType}
import rocketchip.rocket._
import rocketchip.tile.CoreBundle
import rocketchip.util._

import boom.common._
import boom.exu.{BrResolutionInfo, Exception, CommitSignals}
import boom.util._

// NBDTLB's EntryData/Entry (inner classes upstream; top-level here so Spinal can clone them).
class NBDTLBEntryData(implicit p: Parameters) extends CoreBundle()(p) {
  val ppn = UInt(ppnBits bits)
  val u = Bool()
  val g = Bool()
  val ae = Bool()
  val pf = Bool() // Spinal port: see ptw_pf_array
  val sw = Bool()
  val sx = Bool()
  val sr = Bool()
  val pw = Bool()
  val px = Bool()
  val pr = Bool()
  val pal = Bool() // AMO logical
  val paa = Bool() // AMO arithmetic
  val eff = Bool() // get/put effects
  val c = Bool()
  val fragmented_superpage = Bool()
}

class NBDTLBEntry(val nSectors: Int, val superpage: Boolean, val superpageOnly: Boolean)(implicit p: Parameters) extends CoreBundle()(p) {
  require(nSectors == 1 || !superpage)
  require(isPow2(nSectors))
  require(!superpageOnly || superpage)

  val level = UInt(log2Ceil(pgLevels) bits)
  val tag = UInt(vpnBits bits)
  val data = Vec.fill(nSectors)(UInt((new NBDTLBEntryData).getBitsWidth bits))
  val valid = Vec.fill(nSectors)(Bool())
  private def toData(x: UInt): NBDTLBEntryData = { val d = new NBDTLBEntryData; d.assignFromBits(x.asBits); d }
  def entry_data: Seq[NBDTLBEntryData] = data.map(toData)

  private def sectorIdx(vpn: UInt): UInt = if (nSectors == 1) U(0, 1 bits) else vpn(log2Ceil(nSectors)-1 downto 0)
  def getData(vpn: UInt): NBDTLBEntryData = toData(if (nSectors == 1) data(0) else data(sectorIdx(vpn)))
  def sectorHit(vpn: UInt): Bool = valid.orR && sectorTagMatch(vpn)
  def sectorTagMatch(vpn: UInt): Bool = ((tag ^ vpn) >> log2Ceil(nSectors)) === 0
  def hit(vpn: UInt): Bool = {
    if (superpage && usingVM) {
      var tagMatch: Bool = valid.head
      for (j <- 0 until pgLevels) {
        val base = vpnBits - (j + 1) * pgLevelBits
        val ignore = level < j || Bool(superpageOnly && j == pgLevels - 1)
        tagMatch = tagMatch && (ignore || tag(base + pgLevelBits - 1 downto base) === vpn(base + pgLevelBits - 1 downto base))
      }
      tagMatch
    } else {
      val idx = sectorIdx(vpn)
      (if (nSectors == 1) valid(0) else valid(idx)) && sectorTagMatch(vpn)
    }
  }
  def ppn(vpn: UInt): UInt = {
    val data = getData(vpn)
    if (superpage && usingVM) {
      var res = data.ppn >> pgLevelBits*(pgLevels - 1)
      for (j <- 1 until pgLevels) {
        val ignore = (level < j) || Bool(superpageOnly && j == pgLevels - 1)
        val w = vpn.getWidth max data.ppn.getWidth
        res = Cat(res, (Mux(ignore, vpn.resize(w), U(0, w bits)) | data.ppn.resize(w))(vpnBits - j*pgLevelBits - 1 downto vpnBits - (j + 1)*pgLevelBits)).asUInt
      }
      res
    } else {
      data.ppn
    }
  }

  def insert(tag: UInt, level: UInt, entry: NBDTLBEntryData): Unit = {
    this.tag := tag
    val lw = log2Ceil(pgLevels - superpageOnly.toInt)
    this.level := level.resize(lw).resized

    val idx = sectorIdx(tag)
    if (nSectors == 1) {
      valid(0) := True
      data(0) := entry.asBits.asUInt
    } else {
      valid(idx) := True
      data(idx) := entry.asBits.asUInt
    }
  }

  def invalidate(): Unit = { valid.foreach(_ := False) }
  def invalidateVPN(vpn: UInt): Unit = {
    if (superpage) {
       when (hit(vpn)) { invalidate() }
    } else {
      when (sectorTagMatch(vpn)) {
        if (nSectors == 1) valid(0) := False else valid(sectorIdx(vpn)) := False
      }

      // For fragmented superpage mappings, we assume the worst (largest)
      // case, and zap entries whose most-significant VPNs match
       when (((tag ^ vpn) >> (pgLevelBits * (pgLevels - 1))) === 0) {
         for ((v, e) <- valid zip entry_data)
           when (e.fragmented_superpage) { v := False }
       }
    }
  }
  def invalidateNonGlobal(): Unit = {
     for ((v, e) <- valid zip entry_data)
       when (!e.g) { v := False }
  }
}

class NBDTLB(instruction: Boolean, lgMaxSize: Int, cfg: TLBConfig)(implicit p: Parameters) extends BoomModule()(p)
  with rocketchip.rocket.constants.MemoryOpConstants {
  require(!instruction)
  val io = new Bundle {
    val req = Vec.fill(lsuWidth)(slave(Stream(new TLBReq(lgMaxSize))))
    val miss_rdy = out Bool()
    val resp = out(Vec.fill(lsuWidth)(new TLBResp))
    val sfence = slave(Flow(new SFenceReq))
    val ptw = master(new TLBPTWIO)
    val kill = in Bool()
  }

  def widthMap[T <: Data](f: Int => T): Vec[T] = Vec((0 until lsuWidth).map(f))

  val pageGranularityPMPs = pmpGranularity >= (1 << pgIdxBits)
  val sectored_entries = Reg(Vec.fill((cfg.nSets * cfg.nWays) / cfg.nSectors)(new NBDTLBEntry(cfg.nSectors, false, false)))
  val superpage_entries = Reg(Vec.fill(cfg.nSuperpageEntries)(new NBDTLBEntry(1, true, true)))
  val special_entry = (!pageGranularityPMPs).option(Reg(new NBDTLBEntry(1, true, false)))
  def ordinary_entries: Seq[NBDTLBEntry] = sectored_entries ++ superpage_entries
  def all_entries: Seq[NBDTLBEntry] = ordinary_entries ++ special_entry

  val s_ready           = U(0, 2 bits)
  val s_request         = U(1, 2 bits)
  val s_wait            = U(2, 2 bits)
  val s_wait_invalidate = U(3, 2 bits)
  val state = Reg(UInt(2 bits)) init s_ready
  val r_refill_tag = Reg(UInt(vpnBits bits))
  val r_superpage_repl_addr = Reg(UInt(log2Ceil(superpage_entries.size) bits))
  val r_sectored_repl_addr = Reg(UInt(log2Ceil(sectored_entries.size) bits))
  val r_sectored_hit_addr = Reg(UInt(log2Ceil(sectored_entries.size) bits))
  val r_sectored_hit = Reg(Bool())

  val priv = if (instruction) io.ptw.status.prv else io.ptw.status.dprv
  val priv_s = priv(0)
  val priv_uses_vm = priv <= PRV.S
  val vm_enabled = widthMap(w => Bool(usingVM) && io.ptw.ptbr.mode(io.ptw.ptbr.mode.getWidth-1) && priv_uses_vm && !io.req(w).payload.passthrough)

  // share a single physical memory attribute checker (unshare if critical path)
  val vpn = widthMap(w => io.req(w).payload.vaddr(vaddrBits-1 downto pgIdxBits))
  val refill_ppn = io.ptw.resp.payload.pte.ppn(ppnBits-1 downto 0)
  val do_refill = Bool(usingVM) && io.ptw.resp.valid
  val invalidate_refill = state.isOneOf(s_request /* don't care */, s_wait_invalidate) || io.sfence.valid
  val mpu_ppn = widthMap(w =>
                Mux(do_refill, refill_ppn,
                Mux(vm_enabled(w) && Bool(special_entry.nonEmpty), special_entry.map(_.ppn(vpn(w)).resize(ppnBits)).getOrElse(U(0, ppnBits bits)),
                  (io.req(w).payload.vaddr >> pgIdxBits).resize(ppnBits))))
  val mpu_physaddr = widthMap(w => Cat(mpu_ppn(w), io.req(w).payload.vaddr(pgIdxBits-1 downto 0)).asUInt.resize(paddrBits))
  val mpu_priv = widthMap(w =>
    Mux(Bool(usingVM) && (do_refill || io.req(w).payload.passthrough /* PTW */), U(PRV.S, 3 bits), Cat(io.ptw.status.debug, priv).asUInt))
  val pmp = Seq.fill(lsuWidth) { new PMPChecker(lgMaxSize) }
  for (w <- 0 until lsuWidth) {
    pmp(w).io.addr := mpu_physaddr(w)
    pmp(w).io.size := io.req(w).payload.size
    pmp(w).io.pmp := io.ptw.pmp
    pmp(w).io.prv := mpu_priv(w).resized
  }
  val legal_address = widthMap(w => p.system.findSafe(mpu_physaddr(w)))
  def fastCheck(member: MemoryRegion => Boolean, w: Int): Bool =
    legal_address(w) && p.system.fastProperty(mpu_physaddr(w), member)
  val cacheable = widthMap(w => fastCheck(_.supportsAcquireT, w) && Bool(instruction || !usingDataScratchpad))
  val homogeneous = widthMap(w => TLBPageLookup.homogeneousAt(p.system.managers, xLen, cacheBlockBytes, BigInt(1) << pgIdxBits, 1 << lgMaxSize)(mpu_physaddr(w)))
  val deny_access_to_debug = widthMap(w =>
    mpu_priv(w) <= PRV.M && p.system.debugModule.map(_.address.contains(mpu_physaddr(w))).getOrElse(False))
  val prot_r   = widthMap(w => fastCheck(_.supportsGet, w) && !deny_access_to_debug(w) && pmp(w).io.r)
  val prot_w   = widthMap(w => fastCheck(_.supportsPutFull, w) && !deny_access_to_debug(w) && pmp(w).io.w)
  val prot_al  = widthMap(w => fastCheck(_.supportsLogical, w))
  val prot_aa  = widthMap(w => fastCheck(_.supportsArithmetic, w))
  val prot_x   = widthMap(w => fastCheck(_.executable, w) && !deny_access_to_debug(w) && pmp(w).io.x)
  val prot_eff = widthMap(w => fastCheck(m => Seq(RegionType.PUT_EFFECTS, RegionType.GET_EFFECTS) contains m.regionType, w))

  val sector_hits = widthMap(w => Vec(sectored_entries.map(_.sectorHit(vpn(w)))))
  val superpage_hits = widthMap(w => Vec(superpage_entries.map(_.hit(vpn(w)))))
  val hitsVec = widthMap(w => Vec(all_entries.map(vm_enabled(w) && _.hit(vpn(w)))))
  val real_hits = widthMap(w => hitsVec(w).asUInt)
  val hits = widthMap(w => Cat(!vm_enabled(w), real_hits(w)).asUInt)
  val ppn = widthMap(w => Mux1H(hitsVec(w) :+ !vm_enabled(w), all_entries.map(_.ppn(vpn(w)).resize(ppnBits)) :+ vpn(w)(ppnBits-1 downto 0)))

    // permission bit arrays
  when (do_refill) {
    val pte = io.ptw.resp.payload.pte
    val newEntry = new NBDTLBEntryData
    newEntry.ppn := pte.ppn.resized
    newEntry.c := cacheable(0)
    newEntry.u := pte.u
    newEntry.g := pte.g
    newEntry.ae := io.ptw.resp.payload.ae_final
    newEntry.pf := io.ptw.resp.payload.pf
    newEntry.sr := pte.sr()
    newEntry.sw := pte.sw()
    newEntry.sx := pte.sx()
    newEntry.pr := prot_r(0)
    newEntry.pw := prot_w(0)
    newEntry.px := prot_x(0)
    newEntry.pal := prot_al(0)
    newEntry.paa := prot_aa(0)
    newEntry.eff := prot_eff(0)
    newEntry.fragmented_superpage := io.ptw.resp.payload.fragmented_superpage

    when (Bool(special_entry.nonEmpty) && !io.ptw.resp.payload.homogeneous) {
      special_entry.foreach(_.insert(r_refill_tag, io.ptw.resp.payload.level, newEntry))
    } elsewhen (io.ptw.resp.payload.level < (pgLevels-1)) {
      for ((e, i) <- superpage_entries.zipWithIndex) when (r_superpage_repl_addr === i) {
        e.insert(r_refill_tag, io.ptw.resp.payload.level, newEntry)
      }
    } otherwise {
      val waddr = Mux(r_sectored_hit, r_sectored_hit_addr, r_sectored_repl_addr)
      for ((e, i) <- sectored_entries.zipWithIndex) when (waddr === i) {
        when (!r_sectored_hit) { e.invalidate() }
        e.insert(r_refill_tag, U(0, 1 bits), newEntry)
      }
    }
  }

  val entries = widthMap(w => Vec(all_entries.map(_.getData(vpn(w)))))
  val normal_entries = widthMap(w => Vec(ordinary_entries.map(_.getData(vpn(w)))))
  val nPhysicalEntries = 1 + special_entry.size
  val ne = all_entries.size
  val zeroE = U(0, ne bits)
  val zeroA = U(0, ne + 1 bits)
  val ptw_ae_array = widthMap(w => Cat(False, entries(w).map(_.ae).asUInt).asUInt)
  val priv_rw_ok   = widthMap(w => Mux(!priv_s || io.ptw.status.sum, entries(w).map(_.u).asUInt, zeroE) | Mux(priv_s, ~entries(w).map(_.u).asUInt, zeroE))
  val priv_x_ok    = widthMap(w => Mux(priv_s, ~entries(w).map(_.u).asUInt, entries(w).map(_.u).asUInt))
  val r_array      = widthMap(w => Cat(True, priv_rw_ok(w) & (entries(w).map(_.sr).asUInt | Mux(io.ptw.status.mxr, entries(w).map(_.sx).asUInt, zeroE))).asUInt)
  val w_array      = widthMap(w => Cat(True, priv_rw_ok(w) & entries(w).map(_.sw).asUInt).asUInt)
  val x_array      = widthMap(w => Cat(True, priv_x_ok(w)  & entries(w).map(_.sx).asUInt).asUInt)
  val pr_array     = widthMap(w => Cat(Fill(nPhysicalEntries, prot_r(w))   , normal_entries(w).map(_.pr).asUInt).asUInt & ~ptw_ae_array(w))
  val pw_array     = widthMap(w => Cat(Fill(nPhysicalEntries, prot_w(w))   , normal_entries(w).map(_.pw).asUInt).asUInt & ~ptw_ae_array(w))
  val px_array     = widthMap(w => Cat(Fill(nPhysicalEntries, prot_x(w))   , normal_entries(w).map(_.px).asUInt).asUInt & ~ptw_ae_array(w))
  val eff_array    = widthMap(w => Cat(Fill(nPhysicalEntries, prot_eff(w)) , normal_entries(w).map(_.eff).asUInt).asUInt)
  val c_array      = widthMap(w => Cat(Fill(nPhysicalEntries, cacheable(w)), normal_entries(w).map(_.c).asUInt).asUInt)
  val paa_array    = widthMap(w => Cat(Fill(nPhysicalEntries, prot_aa(w))  , normal_entries(w).map(_.paa).asUInt).asUInt)
  val pal_array    = widthMap(w => Cat(Fill(nPhysicalEntries, prot_al(w))  , normal_entries(w).map(_.pal).asUInt).asUInt)
  val paa_array_if_cached = widthMap(w => paa_array(w) | Mux(Bool(usingAtomicsInCache), c_array(w), zeroA))
  val pal_array_if_cached = widthMap(w => pal_array(w) | Mux(Bool(usingAtomicsInCache), c_array(w), zeroA))
  // Chisel: Cat(Bool << (nPhysicalEntries-1), ...): the shifted Bool is nPhysicalEntries wide
  val prefetchable_array  = widthMap(w => Cat((U(cacheable(w) && homogeneous(w)) << (nPhysicalEntries-1)).resize(nPhysicalEntries), normal_entries(w).map(_.c).asUInt).asUInt)

  val misaligned = widthMap(w => (io.req(w).payload.vaddr & (UIntToOH(io.req(w).payload.size) - 1).resize(vaddrBitsExtended)).orR)
  val bad_va = widthMap(w => if (!usingVM || (minPgLevels == pgLevels && vaddrBits == vaddrBitsExtended)) False else vm_enabled(w) && {
    val nPgLevelChoices = pgLevels - minPgLevels + 1
    val minVAddrBits = pgIdxBits + minPgLevels * pgLevelBits
    (for (i <- 0 until nPgLevelChoices) yield {
      val mask = U((BigInt(1) << vaddrBitsExtended) - (BigInt(1) << (minVAddrBits + i * pgLevelBits - 1)), vaddrBitsExtended bits)
      val maskedVAddr = io.req(w).payload.vaddr & mask
      io.ptw.ptbr.additionalPgLevels === i && !(maskedVAddr === 0 || maskedVAddr === mask)
    }).orR
  })

  val cmd_lrsc           = widthMap(w => Bool(usingAtomics) && io.req(w).payload.cmd.isOneOf(M_XLR, M_XSC))
  val cmd_amo_logical    = widthMap(w => Bool(usingAtomics) && isAMOLogical(io.req(w).payload.cmd))
  val cmd_amo_arithmetic = widthMap(w => Bool(usingAtomics) && isAMOArithmetic(io.req(w).payload.cmd))
  val cmd_read           = widthMap(w => isRead(io.req(w).payload.cmd))
  val cmd_write          = widthMap(w => isWrite(io.req(w).payload.cmd))
  val cmd_write_perms    = widthMap(w => cmd_write(w) ||
    Bool(coreParams.haveCFlush) && io.req(w).payload.cmd === M_FLUSH_ALL) // not a write, but needs write permissions

  val lrscAllowed = widthMap(w => Mux(Bool(usingDataScratchpad || usingAtomicsOnlyForIO), zeroA, c_array(w)))
  val ae_array = widthMap(w =>
    Mux(misaligned(w), eff_array(w), zeroA) |
    Mux(cmd_lrsc(w)  , ~lrscAllowed(w), zeroA))
  val ae_valid_array = widthMap(w => Cat(if (special_entry.isEmpty) True.asUInt else Cat(True, Fill(special_entry.size, !do_refill)).asUInt,
    Fill(normal_entries(w).size, True)).asUInt)
  val ae_ld_array = widthMap(w => Mux(cmd_read(w), ae_array(w) | ~pr_array(w), zeroA))
  val ae_st_array = widthMap(w =>
    Mux(cmd_write_perms(w)   , ae_array(w) | ~pw_array(w), zeroA) |
    Mux(cmd_amo_logical(w)   , ~pal_array_if_cached(w), zeroA) |
    Mux(cmd_amo_arithmetic(w), ~paa_array_if_cached(w), zeroA))
  val must_alloc_array = widthMap(w =>
    Mux(cmd_amo_logical(w)   , ~paa_array(w), zeroA) |
    Mux(cmd_amo_arithmetic(w), ~pal_array(w), zeroA) |
    Mux(cmd_lrsc(w)          , ~zeroA, zeroA))
  val ma_ld_array = widthMap(w => Mux(misaligned(w) && cmd_read(w) , ~eff_array(w), zeroA))
  val ma_st_array = widthMap(w => Mux(misaligned(w) && cmd_write(w), ~eff_array(w), zeroA))
  // Upstream BOOM ignores the PTW's pf, which current rocket-chip uses to report a leaf PTE
  // with reserved bits (or another invalid encoding) while leaving its permission bits as
  // they are; such a page was then accessible to loads/stores. Fold it in as rocket's TLB does.
  val ptw_pf_array = widthMap(w => Cat(False, entries(w).map(_.pf).asUInt).asUInt)
  val pf_ld_array = widthMap(w => Mux(cmd_read(w)       , ~(r_array(w) | ptw_ae_array(w)) | ptw_pf_array(w), zeroA))
  val pf_st_array = widthMap(w => Mux(cmd_write_perms(w), ~(w_array(w) | ptw_ae_array(w)) | ptw_pf_array(w), zeroA))
  val pf_inst_array = widthMap(w => ~(x_array(w) | ptw_ae_array(w)) | ptw_pf_array(w))

  val tlb_hit = widthMap(w => real_hits(w).orR)
  val tlb_miss = widthMap(w => vm_enabled(w) && !bad_va(w) && !tlb_hit(w))

  val sectored_plru = new PseudoLRU(sectored_entries.size)
  val superpage_plru = new PseudoLRU(superpage_entries.size)
  for (w <- 0 until lsuWidth) {
    when (io.req(w).valid && vm_enabled(w)) {
      when (sector_hits(w).orR) { sectored_plru.access(OHToUInt(sector_hits(w))) }
      when (superpage_hits(w).orR) { superpage_plru.access(OHToUInt(superpage_hits(w))) }
    }
  }

  // Superpages create the possibility that two entries in the TLB may match.
  // This corresponds to a software bug, but we can't return complete garbage;
  // we must return either the old translation or the new translation.  This
  // isn't compatible with the Mux1H approach.  So, flush the TLB and report
  // a miss on duplicate entries.
  val multipleHits = widthMap(w => PopCountAtLeast(real_hits(w), 2))

  io.miss_rdy := state === s_ready
  for (w <- 0 until lsuWidth) {
    io.req(w).ready    := True
    io.resp(w).gpa.assignDontCare()
    io.resp(w).gpa_is_pte.assignDontCare()
    io.resp(w).gf.ld   := False
    io.resp(w).gf.st   := False
    io.resp(w).gf.inst := False
    io.resp(w).pf.ld   := (bad_va(w) && cmd_read(w)) || (pf_ld_array(w) & hits(w)).orR
    io.resp(w).pf.st   := (bad_va(w) && cmd_write_perms(w)) || (pf_st_array(w) & hits(w)).orR
    io.resp(w).pf.inst := bad_va(w) || (pf_inst_array(w) & hits(w)).orR
    io.resp(w).ae.ld   := (ae_valid_array(w) & ae_ld_array(w) & hits(w)).orR
    io.resp(w).ae.st   := (ae_valid_array(w) & ae_st_array(w) & hits(w)).orR
    io.resp(w).ae.inst := (ae_valid_array(w) & ~px_array(w)   & hits(w)).orR
    io.resp(w).ma.ld   := (ma_ld_array(w) & hits(w)).orR
    io.resp(w).ma.st   := (ma_st_array(w) & hits(w)).orR
    io.resp(w).ma.inst := False // this is up to the pipeline to figure out
    io.resp(w).cacheable    := (c_array(w) & hits(w)).orR
    io.resp(w).must_alloc   := (must_alloc_array(w) & hits(w)).orR
    io.resp(w).prefetchable := (prefetchable_array(w) & hits(w)).orR && Bool(p.system.managers.forall(m => !m.supportsAcquireB || m.supportsHint))
    io.resp(w).miss  := do_refill || tlb_miss(w) || multipleHits(w)
    io.resp(w).paddr := Cat(ppn(w), io.req(w).payload.vaddr(pgIdxBits-1 downto 0)).asUInt.resized
    io.resp(w).size := io.req(w).payload.size.resized
    io.resp(w).cmd := io.req(w).payload.cmd
  }

  io.ptw.req.valid := state === s_request
  io.ptw.req.payload.assignDontCare()
  io.ptw.req.payload.valid := !io.kill
  io.ptw.req.payload.bits.addr := r_refill_tag

  if (usingVM) {
    val sfence = io.sfence.valid
    for (w <- 0 until lsuWidth) {
      when (io.req(w).fire && tlb_miss(w) && state === s_ready) {
        state := s_request
        r_refill_tag := vpn(w)

        r_superpage_repl_addr := replacementEntry(superpage_entries, superpage_plru.way)
        r_sectored_repl_addr  := replacementEntry(sectored_entries, sectored_plru.way)
        r_sectored_hit_addr   := OHToUInt(sector_hits(w))
        r_sectored_hit        := sector_hits(w).orR
      }
    }
    when (state === s_request) {
      when (sfence) { state := s_ready }
      when (io.ptw.req.ready) { state := Mux(sfence, s_wait_invalidate, s_wait) }
      when (io.kill) { state := s_ready }
    }
    when (state === s_wait && sfence) {
      state := s_wait_invalidate
    }
    when (io.ptw.resp.valid) {
      state := s_ready
    }

    when (sfence) {
      for (w <- 0 until lsuWidth) {
        assert(!io.sfence.payload.rs1 || (io.sfence.payload.addr >> pgIdxBits) === vpn(w))
        for (e <- all_entries) {
          when (io.sfence.payload.rs1) { e.invalidateVPN(vpn(w)) }
          .elsewhen (io.sfence.payload.rs2) { e.invalidateNonGlobal() }
          .otherwise { e.invalidate() }
        }
      }
    }
    when (multipleHits.orR || ClockDomain.current.isResetActive) {
      all_entries.foreach(_.invalidate())
    }
  }

  def replacementEntry(set: Seq[NBDTLBEntry], alt: UInt): UInt = {
    val valids = set.map(_.valid.orR).asUInt
    Mux(valids.andR, alt.resize(log2Ceil(set.size)), PriorityEncoder(~valids))
  }
}
