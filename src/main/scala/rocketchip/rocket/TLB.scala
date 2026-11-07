// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package rocketchip.rocket

import spinal.core._
import spinal.lib._
import rocketchip.config._
import rocketchip.tile.{CoreBundle, CoreModule}
import rocketchip.util._
import rocketchip.rocket.constants.MemoryOpConstants._

/** =SFENCE=
  * rs1 rs2
  * {{{
  *  0   0 -> flush All
  *  0   1 -> flush by ASID
  *  1   1 -> flush by ADDR
  *  1   0 -> flush by ADDR and ASID
  * }}}
  */
class SFenceReq(implicit p: Parameters) extends CoreBundle()(p) {
  val rs1 = Bool()
  val rs2 = Bool()
  val addr = UInt(vaddrBits bits)
  val asid = UInt((asIdBits max 1) bits) // TODO zero-width
  val hv = Bool()
  val hg = Bool()
}

class TLBReq(val lgMaxSize: Int)(implicit p: Parameters) extends CoreBundle()(p) {
  /** request address from CPU. */
  val vaddr = UInt(vaddrBitsExtended bits)
  /** don't lookup TLB, bypass vaddr as paddr */
  val passthrough = Bool()
  /** granularity */
  val size = UInt(log2Ceil(lgMaxSize + 1) bits)
  /** memory command. */
  val cmd = UInt(M_SZ bits)
  val prv = UInt(PRV.SZ bits)
  /** virtualization mode */
  val v = Bool()
}

class TLBExceptions extends Bundle {
  val ld = Bool()
  val st = Bool()
  val inst = Bool()
}

class TLBResp(val lgMaxSize: Int = 3)(implicit p: Parameters) extends CoreBundle()(p) {
  // lookup responses
  val miss = Bool()
  /** physical address */
  val paddr = UInt(paddrBits bits)
  val gpa = UInt(vaddrBitsExtended bits)
  val gpa_is_pte = Bool()
  /** page fault exception */
  val pf = new TLBExceptions
  /** guest page fault exception */
  val gf = new TLBExceptions
  /** access exception */
  val ae = new TLBExceptions
  /** misaligned access exception */
  val ma = new TLBExceptions
  /** if this address is cacheable */
  val cacheable = Bool()
  /** if caches must allocate this address */
  val must_alloc = Bool()
  /** if this address is prefetchable for caches*/
  val prefetchable = Bool()
  /** size/cmd of request that generated this response*/
  val size = UInt(log2Ceil(lgMaxSize + 1) bits)
  val cmd = UInt(M_SZ bits)
}

class TLBEntryData(implicit p: Parameters) extends CoreBundle()(p) {
  val ppn = UInt(ppnBits bits)
  /** pte.u user */
  val u = Bool()
  /** pte.g global */
  val g = Bool()
  /** access exception. D$ -> PTW -> TLB AE; Alignment failed. */
  val ae_ptw = Bool()
  val ae_final = Bool()
  val ae_stage2 = Bool()
  /** page fault */
  val pf = Bool()
  /** guest page fault */
  val gf = Bool()
  /** supervisor write */
  val sw = Bool()
  /** supervisor execute */
  val sx = Bool()
  /** supervisor read */
  val sr = Bool()
  /** hypervisor write */
  val hw = Bool()
  /** hypervisor excute */
  val hx = Bool()
  /** hypervisor read */
  val hr = Bool()
  /** prot_w */
  val pw = Bool()
  /** prot_x */
  val px = Bool()
  /** prot_r */
  val pr = Bool()

  /** PutPartial */
  val ppp = Bool()
  /** AMO logical */
  val pal = Bool()
  /** AMO arithmetic */
  val paa = Bool()
  /** get/put effects */
  val eff = Bool()
  /** cacheable */
  val c = Bool()
  /** fragmented_superpage support */
  val fragmented_superpage = Bool()
}

/** basic cell for TLB data */
class TLBEntry(val nSectors: Int, val superpage: Boolean, val superpageOnly: Boolean)(implicit p: Parameters) extends CoreBundle()(p) {
  require(nSectors == 1 || !superpage)
  require(!superpageOnly || superpage)

  val level = UInt(log2Ceil(pgLevels) bits)
  /** use vpn as tag */
  val tag_vpn = UInt(vpnBits bits)
  /** tag in vitualization mode */
  val tag_v = Bool()
  /** entry data */
  val data = Vec.fill(nSectors)(UInt((new TLBEntryData).getBitsWidth bits))
  /** valid bit */
  val valid = Vec.fill(nSectors)(Bool())

  private def toData(x: UInt): TLBEntryData = { val d = new TLBEntryData; d.assignFromBits(x.asBits); d }
  /** returns all entry data in this entry */
  def entry_data: Seq[TLBEntryData] = data.map(toData)
  /** returns the index of sector */
  private def sectorIdx(vpn: UInt): UInt = if (nSectors == 1) U(0, 1 bits) else vpn(log2Ceil(nSectors) - 1 downto 0)
  private def dataAt(idx: UInt): UInt = if (nSectors == 1) data(0) else data(idx)
  private def validAt(idx: UInt): Bool = if (nSectors == 1) valid(0) else valid(idx)
  /** returns the entry data matched with this vpn*/
  def getData(vpn: UInt): TLBEntryData = toData(dataAt(sectorIdx(vpn)))
  /** returns whether a sector hits */
  def sectorHit(vpn: UInt, virtual: Bool): Bool = valid.orR && sectorTagMatch(vpn, virtual)
  /** returns whether tag matches vpn */
  def sectorTagMatch(vpn: UInt, virtual: Bool): Bool = (((tag_vpn ^ vpn) >> log2Ceil(nSectors)) === 0) && (tag_v === virtual)
  /** returns hit signal */
  def hit(vpn: UInt, virtual: Bool): Bool = {
    if (superpage && usingVM) {
      var tagMatch = valid.head && (tag_v === virtual)
      for (j <- 0 until pgLevels) {
        val base = (pgLevels - 1 - j) * pgLevelBits
        val n = pgLevelBits + (if (j == 0) hypervisorExtraAddrBits else 0)
        val ignore = level < j || Bool(superpageOnly && j == pgLevels - 1)
        tagMatch = tagMatch && (ignore || (tag_vpn ^ vpn)(base + n - 1 downto base) === 0)
      }
      tagMatch
    } else {
      val idx = sectorIdx(vpn)
      validAt(idx) && sectorTagMatch(vpn, virtual)
    }
  }
  /** returns the ppn of the input TLBEntryData */
  def ppn(vpn: UInt, data: TLBEntryData): UInt = {
    val supervisorVPNBits = pgLevels * pgLevelBits
    if (superpage && usingVM) {
      var res = data.ppn >> pgLevelBits * (pgLevels - 1)
      for (j <- 1 until pgLevels) {
        val ignore = level < j || Bool(superpageOnly && j == pgLevels - 1)
        val w = vpn.getWidth max data.ppn.getWidth
        res = Cat(res, (Mux(ignore, vpn.resize(w), U(0, w bits)) | data.ppn.resize(w))(supervisorVPNBits - j * pgLevelBits - 1 downto supervisorVPNBits - (j + 1) * pgLevelBits)).asUInt
      }
      res
    } else {
      data.ppn
    }
  }
  /** does the refill */
  def insert(vpn: UInt, virtual: Bool, level: UInt, entry: TLBEntryData): Unit = {
    this.tag_vpn := vpn
    this.tag_v := virtual
    val lw = log2Ceil(pgLevels - superpageOnly.toInt)
    this.level := level.resize(lw).resized

    val idx = sectorIdx(vpn)
    if (nSectors == 1) {
      valid(0) := True
      data(0) := entry.asBits.asUInt
    } else {
      valid(idx) := True
      data(idx) := entry.asBits.asUInt
    }
  }

  def invalidate(): Unit = { valid.foreach(_ := False) }
  def invalidate(virtual: Bool): Unit = {
    for ((v, e) <- valid zip entry_data)
      when(tag_v === virtual) { v := False }
  }
  def invalidateVPN(vpn: UInt, virtual: Bool): Unit = {
    if (superpage) {
      when(hit(vpn, virtual)) { invalidate() }
    } else {
      when(sectorTagMatch(vpn, virtual)) {
        for (((v, e), i) <- (valid zip entry_data).zipWithIndex)
          when(tag_v === virtual && sectorIdx(vpn) === i) { v := False }
      }
    }
    // For fragmented superpage mappings, we assume the worst (largest)
    // case, and zap entries whose most-significant VPNs match
    when(((tag_vpn ^ vpn) >> (pgLevelBits * (pgLevels - 1))) === 0) {
      for ((v, e) <- valid zip entry_data)
        when(tag_v === virtual && e.fragmented_superpage) { v := False }
    }
  }
  def invalidateNonGlobal(virtual: Bool): Unit = {
    for ((v, e) <- valid zip entry_data)
      when(tag_v === virtual && !e.g) { v := False }
  }
}

/** TLB config */
case class TLBConfig(
  nSets: Int,
  nWays: Int,
  nSectors: Int = 4,
  nSuperpageEntries: Int = 4)

/** PMA checker over the static memory map (rocket's diplomatic PMAChecker). */
class PMAChecker(paddrWidth: Int)(implicit p: Parameters) extends Component {
  val io = new Bundle {
    val paddr = in UInt(paddrWidth bits)
    val resp = out(new Bundle {
      val cacheable = Bool()
      val r = Bool()
      val w = Bool()
      val pp = Bool()
      val al = Bool()
      val aa = Bool()
      val x = Bool()
      val eff = Bool()
    })
  }
  val manager = p.system

  // PMA
  // check exist a slave can consume this address.
  val legal_address = manager.findSafe(io.paddr)
  // check utility to help check SoC property.
  def fastCheck(member: MemoryRegion => Boolean): Bool =
    legal_address && manager.fastProperty(io.paddr, member)

  io.resp.cacheable := fastCheck(_.supportsAcquireB)
  io.resp.r := fastCheck(_.supportsGet)
  io.resp.w := fastCheck(_.supportsPutFull)
  io.resp.pp := fastCheck(_.supportsPutPartial)
  io.resp.al := fastCheck(_.supportsLogical)
  io.resp.aa := fastCheck(_.supportsArithmetic)
  io.resp.x := fastCheck(_.executable)
  io.resp.eff := fastCheck(m => Seq(RegionType.PUT_EFFECTS, RegionType.GET_EFFECTS) contains m.regionType)
}

/** The page-homogeneity part of rocket's TLBPageLookup (the only part the TLBs use). */
object TLBPageLookup {
  private case class TLBFixedPermissions(e: Boolean, r: Boolean, w: Boolean, x: Boolean, c: Boolean, a: Boolean, l: Boolean) {
    val useful = r || w || x || c || a || l
  }

  private def groupRegions(managers: Seq[MemoryRegion]): Map[TLBFixedPermissions, Seq[AddressSet]] = {
    val permissions = managers.map { m =>
      (m.address, TLBFixedPermissions(
        e = Seq(RegionType.PUT_EFFECTS, RegionType.GET_EFFECTS) contains m.regionType,
        r = m.supportsGet || m.supportsAcquireB, // if cached, never uses Get
        w = m.supportsPutFull || m.supportsAcquireT, // if cached, never uses Put
        x = m.executable,
        c = m.supportsAcquireB,
        a = m.supportsArithmetic,
        l = m.supportsLogical))
    }
    permissions
      .filter(_._2.useful)
      .groupBy(_._2)
      .map { case (k, seq) => k -> AddressSet.unify(seq.flatMap(_._1)) }
  }

  /** Unmapped memory is considered to be inhomogeneous */
  def homogeneousAt(managers: Seq[MemoryRegion], xLen: Int, cacheBlockBytes: Int, pageSize: BigInt, maxRequestBytes: Int): UInt => Bool = {
    require(isPow2(xLen) && xLen >= 8)
    require(isPow2(cacheBlockBytes) && cacheBlockBytes >= xLen / 8)
    require(isPow2(pageSize) && pageSize >= cacheBlockBytes)
    val grouped = groupRegions(managers).map { case (k, v) => k -> v.filter(_.alignment >= pageSize) }
    val homo = AddressSet.unify(grouped.values.flatten.toList)
    (x: UInt) => homo.map(_.contains(x)).foldLeft(False)(_ || _)
  }

  // Are all pageSize intervals of mapped regions homogeneous?
  def homogeneous(managers: Seq[MemoryRegion], pageSize: BigInt): Boolean =
    groupRegions(managers).values.forall(_.forall(_.alignment >= pageSize))
}

/** rocket-chip TLB; BOOM uses it as its ITLB. */
class TLB(instruction: Boolean, lgMaxSize: Int, cfg: TLBConfig, checkInstructionPMP: Boolean = true)(implicit p: Parameters) extends CoreModule()(p) {
  setDefinitionName(if (instruction) "ITLB" else "DTLB")
  val io = new Bundle {
    /** request from Core */
    val req = slave(Stream(new TLBReq(lgMaxSize)))
    /** response to Core */
    val resp = out(new TLBResp(lgMaxSize))
    /** SFence Input */
    val sfence = slave(Flow(new SFenceReq))
    /** IO to PTW */
    val ptw = master(new TLBPTWIO)
    /** suppress a TLB refill, one cycle after a miss */
    val kill = in Bool()
  }

  val pageGranularityPMPs = pmpGranularity >= (1 << pgIdxBits)
  val vpn = io.req.payload.vaddr(vaddrBits - 1 downto pgIdxBits)
  val nSectorsLog = log2Ceil(cfg.nSectors)
  val nSetsLog = log2Ceil(cfg.nSets)
  /** index for sectored_Entry */
  val memIdx = if (nSetsLog == 0) U(0, 1 bits) else vpn(nSectorsLog + nSetsLog - 1 downto nSectorsLog)
  /** TLB Entry */
  val sectored_entries = Reg(Vec.fill(cfg.nSets)(Vec.fill(cfg.nWays / cfg.nSectors)(new TLBEntry(cfg.nSectors, false, false))))
  /** Superpage Entry */
  val superpage_entries = Reg(Vec.fill(cfg.nSuperpageEntries)(new TLBEntry(1, true, true)))
  /** Special Entry: if PMP granularity is less than page size, need additional "special" entry manage PMP. */
  val special_entry = if (!pageGranularityPMPs) Some(Reg(new TLBEntry(1, true, false))) else None
  val sectored_set: Seq[TLBEntry] = if (cfg.nSets == 1) sectored_entries(0) else sectored_entries(memIdx)
  def ordinary_entries: Seq[TLBEntry] = sectored_set ++ superpage_entries
  def all_entries: Seq[TLBEntry] = ordinary_entries ++ special_entry
  def all_real_entries: Seq[TLBEntry] = sectored_entries.flatMap(_.toSeq) ++ superpage_entries ++ special_entry

  def s_ready           = U(0, 2 bits)
  def s_request         = U(1, 2 bits)
  def s_wait            = U(2, 2 bits)
  def s_wait_invalidate = U(3, 2 bits)
  val state = Reg(UInt(2 bits)) init s_ready
  // use vpn as refill_tag
  val r_refill_tag = Reg(UInt(vpnBits bits))
  val r_superpage_repl_addr = Reg(UInt(log2Ceil(superpage_entries.size) bits))
  val r_sectored_repl_addr = Reg(UInt(log2Ceil(sectored_entries.head.size) bits))
  val r_sectored_hit = Reg(Flow(UInt(log2Ceil(sectored_entries.head.size) bits)))
  val r_superpage_hit = Reg(Flow(UInt(log2Ceil(superpage_entries.size) bits)))
  val r_vstage1_en = Reg(Bool())
  val r_stage2_en = Reg(Bool())
  val r_need_gpa = Reg(Bool())
  val r_gpa_valid = Reg(Bool())
  val r_gpa = Reg(UInt(vaddrBits bits))
  val r_gpa_vpn = Reg(UInt(vpnBits bits))
  val r_gpa_is_pte = Reg(Bool())

  /** privilege mode */
  val priv = io.req.payload.prv
  val priv_v = Bool(usingHypervisor) && io.req.payload.v
  val priv_s = priv(0)
  // user mode and supervisor mode
  val priv_uses_vm = priv <= PRV.S
  val satp = Mux(priv_v, io.ptw.vsatp, io.ptw.ptbr)
  val stage1_en = Bool(usingVM) && satp.mode(satp.mode.getWidth - 1)
  /** VS-stage translation enable */
  val vstage1_en = Bool(usingHypervisor) && priv_v && io.ptw.vsatp.mode(io.ptw.vsatp.mode.getWidth - 1)
  /** G-stage translation enable */
  val stage2_en = Bool(usingHypervisor) && priv_v && io.ptw.hgatp.mode(io.ptw.hgatp.mode.getWidth - 1)
  /** Enable Virtual Memory (see rocket-chip for the full list of conditions) */
  val vm_enabled = (stage1_en || stage2_en) && priv_uses_vm && !io.req.payload.passthrough

  // flush guest entries on vsatp.MODE Bare <-> SvXX transitions
  val v_entries_use_stage1 = Reg(Bool()) init False
  val vsatp_mode_mismatch = priv_v && (vstage1_en =/= v_entries_use_stage1) && !io.req.payload.passthrough

  // share a single physical memory attribute checker (unshare if critical path)
  val refill_ppn = if (usingVM) io.ptw.resp.payload.pte.ppn(ppnBits - 1 downto 0) else U(0, ppnBits bits)
  /** refill signal */
  val do_refill = Bool(usingVM) && io.ptw.resp.valid
  /** sfence invalidate refill */
  val invalidate_refill = state.isOneOf(s_request /* don't care */, s_wait_invalidate) || io.sfence.valid
  // PMP
  val mpu_ppn = Mux(do_refill, refill_ppn,
    Mux(vm_enabled && Bool(special_entry.nonEmpty), special_entry.map(e => e.ppn(vpn, e.getData(vpn)).resize(ppnBits)).getOrElse(U(0, ppnBits bits)), (io.req.payload.vaddr >> pgIdxBits).resize(ppnBits)))
  val mpu_physaddr = Cat(mpu_ppn, io.req.payload.vaddr(pgIdxBits - 1 downto 0)).asUInt
  val mpu_priv = Mux(Bool(usingVM) && (do_refill || io.req.payload.passthrough /* PTW */), U(PRV.S, 3 bits), Cat(io.ptw.status.debug, priv).asUInt)
  val pmp = new PMPChecker(lgMaxSize)
  pmp.io.addr := mpu_physaddr
  pmp.io.size := io.req.payload.size
  pmp.io.pmp := io.ptw.pmp
  pmp.io.prv := mpu_priv.resized

  val pma = new PMAChecker(mpu_physaddr.getWidth)
  pma.io.paddr := mpu_physaddr
  // todo: using DataScratchpad doesn't support cacheable.
  val cacheable = pma.io.resp.cacheable && Bool(instruction || !usingDataScratchpad)
  val homogeneous = TLBPageLookup.homogeneousAt(p.system.managers, xLen, cacheBlockBytes, BigInt(1) << pgIdxBits, 1 << lgMaxSize)(mpu_physaddr)
  // In M mode, if access DM address(debug module program buffer)
  val deny_access_to_debug = mpu_priv <= PRV.M && p.system.debugModule.map(dmp => dmp.address.contains(mpu_physaddr)).getOrElse(False)
  val prot_r = pma.io.resp.r && !deny_access_to_debug && pmp.io.r
  val prot_w = pma.io.resp.w && !deny_access_to_debug && pmp.io.w
  val prot_pp = pma.io.resp.pp
  val prot_al = pma.io.resp.al
  val prot_aa = pma.io.resp.aa
  // The BOOM frontend checks instruction PMP per parcel after translation.
  val prot_x = pma.io.resp.x && !deny_access_to_debug && (if (instruction && !checkInstructionPMP) True else pmp.io.x)
  val prot_eff = pma.io.resp.eff

  // hit check
  val sector_hits = sectored_set.map(_.sectorHit(vpn, priv_v))
  val superpage_hits = superpage_entries.map(_.hit(vpn, priv_v))
  val hitsVec = all_entries.map(vm_enabled && _.hit(vpn, priv_v))
  val real_hits = hitsVec.asUInt
  val hits = Cat(!vm_enabled, real_hits).asUInt

  // use ptw response to refill
  // permission bit arrays
  when(do_refill) {
    val pte = io.ptw.resp.payload.pte
    val refill_v = r_vstage1_en || r_stage2_en
    val newEntry = new TLBEntryData
    newEntry.ppn := pte.ppn.resized
    newEntry.c := cacheable
    newEntry.u := pte.u
    newEntry.g := pte.g && pte.v
    newEntry.ae_ptw := io.ptw.resp.payload.ae_ptw
    newEntry.ae_final := io.ptw.resp.payload.ae_final
    newEntry.ae_stage2 := io.ptw.resp.payload.ae_final && io.ptw.resp.payload.gpa_is_pte && r_stage2_en
    newEntry.pf := io.ptw.resp.payload.pf
    newEntry.gf := io.ptw.resp.payload.gf
    newEntry.hr := io.ptw.resp.payload.hr
    newEntry.hw := io.ptw.resp.payload.hw
    newEntry.hx := io.ptw.resp.payload.hx
    newEntry.sr := pte.sr()
    newEntry.sw := pte.sw()
    newEntry.sx := pte.sx()
    newEntry.pr := prot_r
    newEntry.pw := prot_w
    newEntry.px := prot_x
    newEntry.ppp := prot_pp
    newEntry.pal := prot_al
    newEntry.paa := prot_aa
    newEntry.eff := prot_eff
    newEntry.fragmented_superpage := io.ptw.resp.payload.fragmented_superpage
    // refill special_entry
    when(Bool(special_entry.nonEmpty) && !io.ptw.resp.payload.homogeneous) {
      special_entry.foreach(_.insert(r_refill_tag, refill_v, io.ptw.resp.payload.level, newEntry))
    }.elsewhen(io.ptw.resp.payload.level < (pgLevels - 1)) {
      for ((e, i) <- superpage_entries.zipWithIndex) when(r_superpage_repl_addr === i) {
        e.insert(r_refill_tag, refill_v, io.ptw.resp.payload.level, newEntry)
        when(invalidate_refill) { e.invalidate() }
      }
      // refill sectored_hit
    }.otherwise {
      val r_memIdx = if (nSetsLog == 0) U(0, 1 bits) else r_refill_tag(nSectorsLog + nSetsLog - 1 downto nSectorsLog)
      val waddr = Mux(r_sectored_hit.valid, r_sectored_hit.payload, r_sectored_repl_addr)
      for ((set, s) <- sectored_entries.zipWithIndex) when(r_memIdx === s || Bool(cfg.nSets == 1)) {
        for ((e, i) <- set.zipWithIndex) when(waddr === i) {
          when(!r_sectored_hit.valid) { e.invalidate() }
          e.insert(r_refill_tag, refill_v, U(0, 1 bits), newEntry)
          when(invalidate_refill) { e.invalidate() }
        }
      }
    }

    r_gpa_valid := io.ptw.resp.payload.gpa.valid
    r_gpa := io.ptw.resp.payload.gpa.payload
    r_gpa_is_pte := io.ptw.resp.payload.gpa_is_pte
  }

  // get all entries data.
  val entries = all_entries.map(_.getData(vpn))
  val normal_entries = entries.take(ordinary_entries.size)
  // parallel query PPN from [[all_entries]], if VM not enabled return VPN instead
  val ppn = Mux1H(hitsVec :+ !vm_enabled, ((all_entries zip entries).map { case (entry, data) => entry.ppn(vpn, data).resize(ppnBits) }) :+ vpn(ppnBits - 1 downto 0))

  val nPhysicalEntries = 1 + special_entry.size
  val ne = entries.size
  // generally PTW misaligned load exception.
  val ptw_ae_array = Cat(False, entries.map(_.ae_ptw).asUInt).asUInt
  val final_ae_array = Cat(False, entries.map(_.ae_final).asUInt).asUInt
  val ptw_pf_array = Cat(False, entries.map(_.pf).asUInt).asUInt
  val ptw_gf_array = Cat(False, entries.map(_.gf).asUInt).asUInt
  val sum = Mux(priv_v, io.ptw.gstatus.sum, io.ptw.status.sum)
  // if in hypervisor/machine mode, cannot read/write user entries.
  // if in superviosr/user mode, "If the SUM bit in the sstatus register is set, supervisor mode software may also access pages with U=1.(from spec)"
  val zeroE = U(0, ne bits)
  val priv_rw_ok = Mux(!priv_s || sum, entries.map(_.u).asUInt, zeroE) | Mux(priv_s, ~entries.map(_.u).asUInt, zeroE)
  // if in hypervisor/machine mode, other than user pages, all pages are executable.
  // if in superviosr/user mode, only user page can execute.
  val priv_x_ok = Mux(priv_s, ~entries.map(_.u).asUInt, entries.map(_.u).asUInt)
  val stage1_bypass = Fill(ne, Bool(usingHypervisor)) & (Fill(ne, !stage1_en) | entries.map(_.ae_stage2).asUInt)
  val mxr = io.ptw.status.mxr | Mux(priv_v, io.ptw.gstatus.mxr, False)
  // "The vsstatus field MXR, which makes execute-only pages readable, only overrides VS-stage page protection.(from spec)"
  val r_array = Cat(True, (priv_rw_ok & (entries.map(_.sr).asUInt | Mux(mxr, entries.map(_.sx).asUInt, zeroE))) | stage1_bypass).asUInt
  val w_array = Cat(True, (priv_rw_ok & entries.map(_.sw).asUInt) | stage1_bypass).asUInt
  val x_array = Cat(True, (priv_x_ok & entries.map(_.sx).asUInt) | stage1_bypass).asUInt
  val stage2_bypass = Fill(ne, !stage2_en)
  val hr_array = Cat(True, entries.map(_.hr).asUInt | Mux(io.ptw.status.mxr, entries.map(_.hx).asUInt, zeroE) | stage2_bypass).asUInt
  val hw_array = Cat(True, entries.map(_.hw).asUInt | stage2_bypass).asUInt
  val hx_array = Cat(True, entries.map(_.hx).asUInt | stage2_bypass).asUInt
  // These array is for each TLB entries.
  // user mode can read: PMA OK, TLB OK, AE OK
  val pr_array = Cat(Fill(nPhysicalEntries, prot_r), normal_entries.map(_.pr).asUInt).asUInt & ~(ptw_ae_array | final_ae_array)
  // user mode can write: PMA OK, TLB OK, AE OK
  val pw_array = Cat(Fill(nPhysicalEntries, prot_w), normal_entries.map(_.pw).asUInt).asUInt & ~(ptw_ae_array | final_ae_array)
  // user mode can write: PMA OK, TLB OK, AE OK
  val px_array = Cat(Fill(nPhysicalEntries, prot_x), normal_entries.map(_.px).asUInt).asUInt & ~(ptw_ae_array | final_ae_array)
  // put effect
  val eff_array = Cat(Fill(nPhysicalEntries, prot_eff), normal_entries.map(_.eff).asUInt).asUInt
  // cacheable
  val c_array = Cat(Fill(nPhysicalEntries, cacheable), normal_entries.map(_.c).asUInt).asUInt
  // put partial
  val ppp_array = Cat(Fill(nPhysicalEntries, prot_pp), normal_entries.map(_.ppp).asUInt).asUInt
  // atomic arithmetic
  val paa_array = Cat(Fill(nPhysicalEntries, prot_aa), normal_entries.map(_.paa).asUInt).asUInt
  // atomic logic
  val pal_array = Cat(Fill(nPhysicalEntries, prot_al), normal_entries.map(_.pal).asUInt).asUInt
  val aw = ne + 1
  val zeroA = U(0, aw bits)
  val ppp_array_if_cached = ppp_array | c_array
  val paa_array_if_cached = paa_array | (if (usingAtomicsInCache) c_array else zeroA)
  val pal_array_if_cached = pal_array | (if (usingAtomicsInCache) c_array else zeroA)
  val prefetchable_array = Cat((U(cacheable && homogeneous) << (nPhysicalEntries - 1)).resize(nPhysicalEntries), normal_entries.map(_.c).asUInt).asUInt

  // vaddr misaligned: vaddr[1:0]=b00
  val misaligned = (io.req.payload.vaddr & (UIntToOH(io.req.payload.size) - 1).resize(vaddrBitsExtended)).orR
  def badVA(guestPA: Boolean): Bool = {
    val additionalPgLevels = (if (guestPA) io.ptw.hgatp else satp).additionalPgLevels
    val extraBits = if (guestPA) hypervisorExtraAddrBits else 0
    val signed = !guestPA
    val nPgLevelChoices = pgLevels - minPgLevels + 1
    val minVAddrBits = pgIdxBits + minPgLevels * pgLevelBits + extraBits
    (for (i <- 0 until nPgLevelChoices) yield {
      val maskV = (BigInt(1) << vaddrBitsExtended) - (BigInt(1) << (minVAddrBits + i * pgLevelBits - signed.toInt))
      val mask = U(maskV, vaddrBitsExtended bits)
      val maskedVAddr = io.req.payload.vaddr & mask
      additionalPgLevels === i && !(maskedVAddr === 0 || Bool(signed) && maskedVAddr === mask)
    }).orR
  }
  val bad_gpa =
    if (!usingHypervisor) False
    else vm_enabled && !stage1_en && badVA(true)
  val bad_va =
    if (!usingVM || (minPgLevels == pgLevels && vaddrBits == vaddrBitsExtended)) False
    else vm_enabled && stage1_en && badVA(false)

  val cmd_lrsc = Bool(usingAtomics) && io.req.payload.cmd.isOneOf(M_XLR, M_XSC)
  val cmd_amo_logical = Bool(usingAtomics) && isAMOLogical(io.req.payload.cmd)
  val cmd_amo_arithmetic = Bool(usingAtomics) && isAMOArithmetic(io.req.payload.cmd)
  val cmd_put_partial = io.req.payload.cmd === M_PWR.U
  val cmd_read = isRead(io.req.payload.cmd)
  val cmd_readx = Bool(usingHypervisor) && io.req.payload.cmd === M_HLVX.U
  val cmd_write = isWrite(io.req.payload.cmd)
  val cmd_write_perms = cmd_write ||
    io.req.payload.cmd.isOneOf(M_FLUSH_ALL, M_WOK) // not a write, but needs write permissions

  val lrscAllowed = Mux(Bool(usingDataScratchpad || usingAtomicsOnlyForIO), zeroA, c_array)
  val ae_array =
    Mux(misaligned, eff_array, zeroA) |
      Mux(cmd_lrsc, ~lrscAllowed, zeroA)

  // access exception needs SoC information from PMA
  val ae_ld_array = Mux(cmd_read, ae_array | ~Mux(cmd_readx, pr_array & px_array, pr_array), zeroA)
  val ae_st_array =
    Mux(cmd_write_perms, ae_array | ~pw_array, zeroA) |
      Mux(cmd_put_partial, ~ppp_array_if_cached, zeroA) |
      Mux(cmd_amo_logical, ~pal_array_if_cached, zeroA) |
      Mux(cmd_amo_arithmetic, ~paa_array_if_cached, zeroA)
  val must_alloc_array =
    Mux(cmd_put_partial, ~ppp_array, zeroA) |
      Mux(cmd_amo_logical, ~pal_array, zeroA) |
      Mux(cmd_amo_arithmetic, ~paa_array, zeroA) |
      Mux(cmd_lrsc, ~zeroA, zeroA)
  val pf_ld_array = Mux(cmd_read, ((~Mux(cmd_readx, x_array, r_array) & ~ptw_ae_array) | ptw_pf_array) & ~ptw_gf_array, zeroA)
  val pf_st_array = Mux(cmd_write_perms, ((~w_array & ~ptw_ae_array) | ptw_pf_array) & ~ptw_gf_array, zeroA)
  val pf_inst_array = ((~x_array & ~ptw_ae_array) | ptw_pf_array) & ~ptw_gf_array
  val gf_ld_array = Mux(priv_v && cmd_read, (~Mux(cmd_readx, hx_array, hr_array) & ~ptw_ae_array) | ptw_gf_array, zeroA)
  val gf_st_array = Mux(priv_v && cmd_write_perms, (~hw_array & ~ptw_ae_array) | ptw_gf_array, zeroA)
  val gf_inst_array = Mux(priv_v, (~hx_array & ~ptw_ae_array) | ptw_gf_array, zeroA)

  val gpa_hits = {
    val need_gpa_mask = if (instruction) gf_inst_array else gf_ld_array | gf_st_array
    val hit_mask = Fill(ordinary_entries.size, r_gpa_valid && r_gpa_vpn === vpn).resize(all_entries.size) | Fill(all_entries.size, !vstage1_en)
    hit_mask | ~need_gpa_mask(all_entries.size - 1 downto 0)
  }

  val tlb_hit_if_not_gpa_miss = real_hits.orR
  val tlb_hit = (real_hits & gpa_hits).orR
  // leads to s_request
  val tlb_miss = vm_enabled && !vsatp_mode_mismatch && !bad_va && !tlb_hit

  val sectored_plru = new SetAssocLRU(cfg.nSets, sectored_entries.head.size, "plru")
  val superpage_plru = new PseudoLRU(superpage_entries.size)
  when(io.req.valid && vm_enabled) {
    // replace
    when(sector_hits.orR) { sectored_plru.access(memIdx, OHToUInt(sector_hits)) }
    when(superpage_hits.orR) { superpage_plru.access(OHToUInt(superpage_hits)) }
  }

  // Superpages create the possibility that two entries in the TLB may match.
  // This corresponds to a software bug, but we can't return complete garbage;
  // we must return either the old translation or the new translation.  This
  // isn't compatible with the Mux1H approach.  So, flush the TLB and report
  // a miss on duplicate entries.
  val multipleHits = PopCountAtLeast(real_hits, 2)

  // only pull up req.ready when this is s_ready state.
  io.req.ready := state === s_ready
  // page fault
  io.resp.pf.ld := (bad_va && cmd_read) || (pf_ld_array & hits).orR
  io.resp.pf.st := (bad_va && cmd_write_perms) || (pf_st_array & hits).orR
  io.resp.pf.inst := bad_va || (pf_inst_array & hits).orR
  // guest page fault
  io.resp.gf.ld := (bad_gpa && cmd_read) || (gf_ld_array & hits).orR
  io.resp.gf.st := (bad_gpa && cmd_write_perms) || (gf_st_array & hits).orR
  io.resp.gf.inst := bad_gpa || (gf_inst_array & hits).orR
  // access exception
  io.resp.ae.ld := (ae_ld_array & hits).orR
  io.resp.ae.st := (ae_st_array & hits).orR
  io.resp.ae.inst := (~px_array & hits).orR
  // misaligned
  io.resp.ma.ld := misaligned && cmd_read
  io.resp.ma.st := misaligned && cmd_write
  io.resp.ma.inst := False // this is up to the pipeline to figure out
  io.resp.cacheable := (c_array & hits).orR
  io.resp.must_alloc := (must_alloc_array & hits).orR
  io.resp.prefetchable := (prefetchable_array & hits).orR && Bool(p.system.managers.forall(m => !m.supportsAcquireB || m.supportsHint))
  io.resp.miss := do_refill || vsatp_mode_mismatch || tlb_miss || multipleHits
  io.resp.paddr := Cat(ppn, io.req.payload.vaddr(pgIdxBits - 1 downto 0)).asUInt
  io.resp.size := io.req.payload.size
  io.resp.cmd := io.req.payload.cmd
  io.resp.gpa_is_pte := vstage1_en && r_gpa_is_pte
  io.resp.gpa := {
    val page = Mux(!vstage1_en, Cat(bad_gpa, vpn).asUInt, (r_gpa >> pgIdxBits).resize(vpnBits + 1))
    val offset = Mux(io.resp.gpa_is_pte, r_gpa(pgIdxBits - 1 downto 0), io.req.payload.vaddr(pgIdxBits - 1 downto 0))
    Cat(page, offset).asUInt.resized
  }

  io.ptw.req.valid := state === s_request
  io.ptw.req.payload.valid := !io.kill
  io.ptw.req.payload.bits.addr := r_refill_tag
  io.ptw.req.payload.bits.vstage1 := r_vstage1_en
  io.ptw.req.payload.bits.stage2 := r_stage2_en
  io.ptw.req.payload.bits.need_gpa := r_need_gpa

  if (usingVM) {
    when(io.ptw.req.fire && io.ptw.req.payload.valid) {
      r_gpa_valid := False
      r_gpa_vpn := r_refill_tag
    }

    val sfence = io.sfence.valid
    // this is [[s_ready]]
    // handle miss/hit at the first cycle.
    // if miss, request PTW(L2TLB).
    when(io.req.fire && tlb_miss) {
      state := s_request
      r_refill_tag := vpn
      r_need_gpa := tlb_hit_if_not_gpa_miss
      r_vstage1_en := vstage1_en
      r_stage2_en := stage2_en
      r_superpage_repl_addr := replacementEntry(superpage_entries, superpage_plru.way)
      r_sectored_repl_addr := replacementEntry(sectored_set, sectored_plru.way(memIdx))
      r_sectored_hit.valid := sector_hits.orR
      r_sectored_hit.payload := OHToUInt(sector_hits)
      r_superpage_hit.valid := superpage_hits.orR
      r_superpage_hit.payload := OHToUInt(superpage_hits)
    }
    // Handle SFENCE.VMA when send request to PTW.
    when(state === s_request) {
      // SFENCE.VMA will kill TLB entries based on rs1 and rs2. It will take 1 cycle.
      when(sfence) { state := s_ready }
      // here should be io.ptw.req.fire, but assert(io.ptw.req.ready === true.B)
      // fire -> s_wait
      when(io.ptw.req.ready) { state := Mux(sfence, s_wait_invalidate, s_wait) }
      // If CPU kills request(frontend.s2_redirect)
      when(io.kill) { state := s_ready }
    }
    // sfence in refill will results in invalidate
    when(state === s_wait && sfence) {
      state := s_wait_invalidate
    }
    // after CPU acquire response, go back to s_ready.
    when(io.ptw.resp.valid) {
      state := s_ready
    }

    // SFENCE processing logic.
    when(sfence) {
      assert(!io.sfence.payload.rs1 || (io.sfence.payload.addr >> pgIdxBits) === vpn)
      for (e <- all_real_entries) {
        val hv = Bool(usingHypervisor) && io.sfence.payload.hv
        val hg = Bool(usingHypervisor) && io.sfence.payload.hg
        when(!hg && io.sfence.payload.rs1) { e.invalidateVPN(vpn, hv) }
          .elsewhen(!hg && io.sfence.payload.rs2) { e.invalidateNonGlobal(hv) }
          .otherwise { e.invalidate(hv || hg) }
      }
    }
    when(io.req.fire && vsatp_mode_mismatch) {
      all_real_entries.foreach(_.invalidate(True))
      v_entries_use_stage1 := vstage1_en
    }
    when(multipleHits || ClockDomain.current.isResetActive) {
      all_real_entries.foreach(_.invalidate())
    }
  }

  /** Decides which entry to be replaced: an invalid entry if there is one, else `alt`. */
  def replacementEntry(set: Seq[TLBEntry], alt: UInt): UInt = {
    val valids = set.map(_.valid.orR).asUInt
    Mux(valids.andR, alt, PriorityEncoder(~valids))
  }
}
