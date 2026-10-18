// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package rocketchip.rocket

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.tile._
import rocketchip.util._
import rocketchip.rocket.constants.ScalarOpConstants.{X, Y, N}

import scala.collection.mutable.LinkedHashMap
import Instructions._
import CustomInstructions._

// Register-layout bundles: fields are declared MSB-first as in rocket-chip and
// are packed/unpacked with ChiselLayout wherever the encoding is architectural.

class MStatus extends Bundle {
  // not truly part of mstatus, but convenient
  val debug = Bool()
  val cease = Bool()
  val wfi = Bool()
  val isa = UInt(32 bits)

  val dprv = UInt(PRV.SZ bits) // effective prv for data accesses
  val dv = Bool() // effective v for data accesses
  val prv = UInt(PRV.SZ bits)
  val v = Bool()

  val sd = Bool()
  val zero2 = UInt(23 bits)
  val mpv = Bool()
  val gva = Bool()
  val mbe = Bool()
  val sbe = Bool()
  val sxl = UInt(2 bits)
  val uxl = UInt(2 bits)
  val sd_rv32 = Bool()
  val zero1 = UInt(8 bits)
  val tsr = Bool()
  val tw = Bool()
  val tvm = Bool()
  val mxr = Bool()
  val sum = Bool()
  val mprv = Bool()
  val xs = UInt(2 bits)
  val fs = UInt(2 bits)
  val mpp = UInt(2 bits)
  val vs = UInt(2 bits)
  val spp = UInt(1 bits)
  val mpie = Bool()
  val ube = Bool()
  val spie = Bool()
  val upie = Bool()
  val mie = Bool()
  val hie = Bool()
  val sie = Bool()
  val uie = Bool()
}

class MNStatus extends Bundle {
  val mpp   = UInt(2 bits)
  val zero3 = UInt(3 bits)
  val mpv   = Bool()
  val zero2 = UInt(3 bits)
  val mie   = Bool()
  val zero1 = UInt(3 bits)
}

class HStatus extends Bundle {
  val zero6 = UInt(30 bits)
  val vsxl = UInt(2 bits)
  val zero5 = UInt(9 bits)
  val vtsr = Bool()
  val vtw = Bool()
  val vtvm = Bool()
  val zero3 = UInt(2 bits)
  val vgein = UInt(6 bits)
  val zero2 = UInt(2 bits)
  val hu = Bool()
  val spvp = Bool()
  val spv = Bool()
  val gva = Bool()
  val vsbe = Bool()
  val zero1 = UInt(5 bits)
}

class DCSR extends Bundle {
  val xdebugver = UInt(2 bits)
  val zero4 = UInt(2 bits)
  val zero3 = UInt(12 bits)
  val ebreakm = Bool()
  val ebreakh = Bool()
  val ebreaks = Bool()
  val ebreaku = Bool()
  val zero2 = Bool()
  val stopcycle = Bool()
  val stoptime = Bool()
  val cause = UInt(3 bits)
  val v = Bool()
  val zero1 = UInt(2 bits)
  val step = Bool()
  val prv = UInt(PRV.SZ bits)
}

class MIP(implicit p: Parameters) extends CoreBundle()(p) {
  val lip = Vec.fill(coreParams.nLocalInterrupts)(Bool())
  val zero1 = Bool()
  val debug = Bool() // keep in sync with CSR.debugIntCause
  val rocc = Bool()
  val sgeip = Bool()
  val meip = Bool()
  val vseip = Bool()
  val seip = Bool()
  val ueip = Bool()
  val mtip = Bool()
  val vstip = Bool()
  val stip = Bool()
  val utip = Bool()
  val msip = Bool()
  val vssip = Bool()
  val ssip = Bool()
  val usip = Bool()
}

class Envcfg extends Bundle {
  val stce = Bool() // only for menvcfg/henvcfg
  val pbmte = Bool() // only for menvcfg/henvcfg
  val zero54 = UInt(54 bits)
  val cbze = Bool()
  val cbcfe = Bool()
  val cbie = UInt(2 bits)
  val zero3 = UInt(3 bits)
  val fiom = Bool()
  def write(wdata: UInt): Unit = {
    val new_envcfg = ChiselLayout.fromUInt(new Envcfg, wdata)
    fiom := new_envcfg.fiom // only FIOM is writable currently
  }
}

class PTBR(implicit p: Parameters) extends CoreBundle()(p) {
  def additionalPgLevels: UInt = mode(log2Ceil(pgLevels - minPgLevels + 1) - 1 downto 0)
  def pgLevelsToMode(i: Int): Int = (xLen, i) match {
    case (32, 2) => 1
    case (64, x) if x >= 3 && x <= 6 => x + 5
  }
  val (modeBits, maxASIdBits) = xLen match {
    case 32 => (1, 9)
    case 64 => (4, 16)
  }
  require(!usingVM || modeBits + maxASIdBits + maxPAddrBits - pgIdxBits == xLen)

  val mode = UInt(modeBits bits)
  val asid = UInt(maxASIdBits bits)
  val ppn = UInt(maxPAddrBits - pgIdxBits bits)
}

object PRV {
  val SZ = 2
  val U = 0
  val S = 1
  val H = 2
  val M = 3
}

object CSR {
  // commands
  val SZ = 3
  def X = BitPat.dontCare(SZ)
  def N = BitPat(0, SZ)
  def R = BitPat(2, SZ)
  def I = BitPat(4, SZ)
  def W = BitPat(5, SZ)
  def S = BitPat(6, SZ)
  def C = BitPat(7, SZ)

  // mask a CSR cmd with a valid bit
  def maskCmd(valid: Bool, cmd: UInt): UInt = {
    // all commands less than CSR.I are treated by CSRFile as NOPs
    cmd & ~Mux(valid, U(0, SZ bits), CSR.I.U)
  }

  val ADDRSZ = 12

  def modeLSB: Int = 8
  def mode(addr: Int): Int = (addr >> modeLSB) % (1 << PRV.SZ)
  def mode(addr: UInt): UInt = addr(modeLSB + PRV.SZ - 1 downto modeLSB)

  def busErrorIntCause = 128
  def debugIntCause = 14 // keep in sync with MIP.debug
  def debugTriggerCause = {
    val res = debugIntCause
    require(!(Causes.all contains res))
    res
  }
  def rnmiIntCause = 13  // NMI: Higher numbers = higher priority, must not reuse debugIntCause
  def rnmiBEUCause = 12

  val firstCtr = CSRs.cycle
  val firstCtrH = CSRs.cycleh
  val firstHPC = CSRs.hpmcounter3
  val firstHPCH = CSRs.hpmcounter3h
  val firstHPE = CSRs.mhpmevent3
  val firstMHPC = CSRs.mhpmcounter3
  val firstMHPCH = CSRs.mhpmcounter3h
  val firstHPM = 3
  val nCtr = 32
  val nHPM = nCtr - firstHPM
  val hpmWidth = 40

  val maxPMPs = 16
}

/** Master side is the CSRFile. */
class PerfCounterIO(implicit p: Parameters) extends CoreBundle with IMasterSlave {
  val eventSel = UInt(xLen bits)
  val inc = UInt(log2Ceil(1 + retireWidth) bits)
  override def asMaster(): Unit = { out(eventSel); in(inc) }
}

class TracedInstruction(implicit p: Parameters) extends CoreBundle {
  val valid = Bool()
  val iaddr = UInt(coreMaxAddrBits bits)
  val insn = UInt(iLen bits)
  val priv = UInt(3 bits)
  val exception = Bool()
  val interrupt = Bool()
  val cause = UInt(xLen bits)
  val tval = UInt((coreMaxAddrBits max iLen) bits)
  val wdata = if (traceHasWdata) UInt((vLen max xLen) bits) else null
}

/** Master side is the CSRFile (it drives the decode verdicts). */
class CSRDecodeIO(implicit p: Parameters) extends CoreBundle with IMasterSlave {
  val inst = UInt(iLen bits)

  def csr_addr: UInt = (inst >> 20)(CSR.ADDRSZ - 1 downto 0)

  val fp_illegal = Bool()
  val vector_illegal = Bool()
  val fp_csr = Bool()
  val vector_csr = Bool()
  val rocc_illegal = Bool()
  val read_illegal = Bool()
  val write_illegal = Bool()
  val write_flush = Bool()
  val system_illegal = Bool()
  val virtual_access_illegal = Bool()
  val virtual_system_illegal = Bool()

  override def asMaster(): Unit = {
    in(inst)
    out(fp_illegal, vector_illegal, fp_csr, vector_csr, rocc_illegal, read_illegal, write_illegal,
      write_flush, system_illegal, virtual_access_illegal, virtual_system_illegal)
  }
}

class CSRFile(
  perfEventSets: EventSets = new EventSets(Seq()),
  customCSRs: Seq[CustomCSR] = Nil,
  roccCSRs: Seq[CustomCSR] = Nil,
  hasBeu: Boolean = false)(implicit p: Parameters)
  extends CoreModule()(p)
  with HasCoreParameters {

  require(!usingVector, "The Spinal port of CSRFile does not implement the vector CSRs")
  private val CL = ChiselLayout

  val io = new Bundle {
    val interrupts = in(new CoreInterrupts(hasBeu))
    val hartid = in UInt(hartIdLen bits)
    val rw = new Bundle {
      val addr = in UInt(CSR.ADDRSZ bits)
      val cmd = in UInt(CSR.SZ bits)
      val rdata = out UInt(xLen bits)
      val wdata = in UInt(xLen bits)
    }

    val decode = Vec.fill(decodeWidth)(master(new CSRDecodeIO))

    val csr_stall = out Bool() // stall retire for wfi
    val rw_stall = out Bool() // stall rw, rw will have no effect while rw_stall
    val eret = out Bool()
    val trap_return = out Bool()
    val singleStep = out Bool()
    val status = out(new MStatus())
    val hstatus = out(new HStatus())
    val gstatus = out(new MStatus())
    val ptbr = out(new PTBR())
    val hgatp = out(new PTBR())
    val vsatp = out(new PTBR())
    val evec = out UInt(vaddrBitsExtended bits)
    val exception = in Bool()
    val retire = in UInt(log2UpChisel(1 + retireWidth) bits)
    val cause = in UInt(xLen bits)
    val pc = in UInt(vaddrBitsExtended bits)
    val tval = in UInt(vaddrBitsExtended bits)
    val htval = in UInt(((maxSVAddrBits + 1) min xLen) bits)
    val mhtinst_read_pseudo = in Bool()
    val gva = in Bool()
    val time = out UInt(xLen bits)
    val fcsr_rm = out UInt(FPConstants.RM_SZ bits)
    val fcsr_flags = slave(Flow(Bits(FPConstants.FLAGS_SZ bits)))
    val set_fs_dirty = if (coreParams.haveFSDirty) in Bool() else null
    val rocc_interrupt = in Bool()
    val interrupt = out Bool()
    val interrupt_cause = out UInt(xLen bits)
    val bp = out(Vec.fill(nBreakpoints)(new BP))
    val pmp = out(Vec.fill(nPMPs)(new PMP))
    val counters = Vec.fill(nPerfCounters)(master(new PerfCounterIO))
    val csrw_counter = out UInt(CSR.nCtr bits)
    val inhibit_cycle = out Bool()
    val inst = in(Vec.fill(retireWidth)(UInt(iLen bits)))
    val trace = out(Vec.fill(retireWidth)(new TracedInstruction))
    val mcontext = out UInt(coreParams.mcontextWidth bits)
    val scontext = out UInt(coreParams.scontextWidth bits)
    val fiom = out Bool()

    val customCSRs = Vec.fill(CSRFile.this.customCSRs.size)(master(new CustomCSRIO))
    val roccCSRs = Vec.fill(CSRFile.this.roccCSRs.size)(master(new CustomCSRIO))
  }

  val resetActive = ClockDomain.current.isResetActive

  io.rw_stall := False

  val reset_mstatus = new MStatus().getZero
  reset_mstatus.mpp := PRV.M
  reset_mstatus.prv := PRV.M
  reset_mstatus.xs := (if (usingRoCC) 3 else 0)
  val reg_mstatus = Reg(new MStatus) init (reset_mstatus)

  val new_prv = CombInit(reg_mstatus.prv)
  reg_mstatus.prv := legalizePrivilege(new_prv)

  val reset_dcsr = new DCSR().getZero
  reset_dcsr.xdebugver := 1
  reset_dcsr.prv := PRV.M
  val reg_dcsr = Reg(new DCSR) init (reset_dcsr)

  val (supported_interrupts, delegable_interrupts) = {
    val sup = new MIP
    sup.usip := False
    sup.ssip := Bool(usingSupervisor)
    sup.vssip := Bool(usingHypervisor)
    sup.msip := True
    sup.utip := False
    sup.stip := Bool(usingSupervisor)
    sup.vstip := Bool(usingHypervisor)
    sup.mtip := True
    sup.ueip := False
    sup.seip := Bool(usingSupervisor)
    sup.vseip := Bool(usingHypervisor)
    sup.meip := True
    sup.sgeip := False
    sup.rocc := Bool(usingRoCC)
    sup.debug := False
    sup.zero1 := False
    sup.lip foreach { _ := True }
    val hasBuserror = io.interrupts.buserror != null && !usingNMI
    val supAsUInt = CL.asUInt(sup)
    val supW = if (hasBuserror) (CSR.busErrorIntCause + 1) max supAsUInt.getWidth else supAsUInt.getWidth
    val supported_high_interrupts = if (hasBuserror) U(BigInt(1) << CSR.busErrorIntCause, supW bits) else U(0, supW bits)

    val del = CombInit(sup)
    del.msip := False
    del.mtip := False
    del.meip := False

    (supAsUInt.resize(supW) | supported_high_interrupts, CL.asUInt(del))
  }
  val delegable_base_exceptions = Seq(
    Causes.misaligned_fetch,
    Causes.fetch_page_fault,
    Causes.breakpoint,
    Causes.load_page_fault,
    Causes.store_page_fault,
    Causes.misaligned_load,
    Causes.misaligned_store,
    Causes.illegal_instruction,
    Causes.user_ecall
  )
  val delegable_hypervisor_exceptions = Seq(
    Causes.virtual_supervisor_ecall,
    Causes.fetch_guest_page_fault,
    Causes.load_guest_page_fault,
    Causes.virtual_instruction,
    Causes.store_guest_page_fault
  )
  val delegable_exceptions = U((
    delegable_base_exceptions
      ++ (if (usingHypervisor) delegable_hypervisor_exceptions else Seq())
    ).map(BigInt(1) << _).sum, xLen bits)

  val hs_delegable_exceptions = U(Seq(
    Causes.misaligned_fetch,
    Causes.fetch_access,
    Causes.illegal_instruction,
    Causes.breakpoint,
    Causes.misaligned_load,
    Causes.load_access,
    Causes.misaligned_store,
    Causes.store_access,
    Causes.user_ecall,
    Causes.fetch_page_fault,
    Causes.load_page_fault,
    Causes.store_page_fault).map(BigInt(1) << _).sum, xLen bits)

  val (hs_delegable_interrupts, mideleg_always_hs) = {
    val always = new MIP().getZero
    always.vssip := Bool(usingHypervisor)
    always.vstip := Bool(usingHypervisor)
    always.vseip := Bool(usingHypervisor)

    val deleg = CombInit(always)
    deleg.lip.foreach { _ := Bool(usingHypervisor) }

    (CL.asUInt(deleg).resize(xLen), CL.asUInt(always).resize(xLen))
  }

  val reg_debug = Reg(Bool()) init False
  val reg_dpc = Reg(UInt(vaddrBitsExtended bits))
  val reg_dscratch0 = Reg(UInt(xLen bits))
  val reg_dscratch1 = if (p.system.debugModule.map(_.nDscratch).getOrElse(1) > 1) Some(Reg(UInt(xLen bits))) else None
  val reg_singleStepped = Reg(Bool())

  val reg_mcontext = if (coreParams.mcontextWidth > 0) Some(Reg(UInt(coreParams.mcontextWidth bits)) init 0) else None
  val reg_scontext = if (coreParams.scontextWidth > 0) Some(Reg(UInt(coreParams.scontextWidth bits)) init 0) else None

  val reg_tselect = Reg(UInt(log2UpChisel(nBreakpoints) bits))
  val reg_bp = Reg(Vec.fill(1 << log2UpChisel(nBreakpoints))(new BP))
  val reg_pmp = Reg(Vec.fill(nPMPs)(new PMPReg))

  val reg_mie = Reg(UInt(xLen bits))
  val (reg_mideleg, read_mideleg) = {
    val reg = Reg(UInt(xLen bits))
    (reg, Mux(Bool(usingSupervisor), reg & delegable_interrupts.resize(xLen) | mideleg_always_hs, U(0, xLen bits)))
  }
  val (reg_medeleg, read_medeleg) = {
    val reg = Reg(UInt(xLen bits))
    (reg, Mux(Bool(usingSupervisor), reg & delegable_exceptions, U(0, xLen bits)))
  }
  val reg_mip = Reg(new MIP)
  val reg_mepc = Reg(UInt(vaddrBitsExtended bits))
  val reg_mcause = Reg(UInt(xLen bits)) init 0
  val reg_mtval = Reg(UInt(vaddrBitsExtended bits))
  val reg_mtval2 = Reg(UInt(((maxSVAddrBits + 1) min xLen) bits))
  val reg_mscratch = Reg(UInt(xLen bits))
  val mtvecWidth = paddrBits min xLen
  val reg_mtvec = mtvecInit match {
    case Some(addr) => Reg(UInt(mtvecWidth bits)) init (U(addr, mtvecWidth bits))
    case None => Reg(UInt(mtvecWidth bits))
  }

  val reset_mnstatus = new MNStatus().getZero
  reset_mnstatus.mpp := PRV.M
  val reg_mnscratch = Reg(UInt(xLen bits))
  val reg_mnepc = Reg(UInt(vaddrBitsExtended bits))
  val reg_mncause = Reg(UInt(xLen bits)) init 0
  val reg_mnstatus = Reg(new MNStatus) init (reset_mnstatus)
  val reg_rnmie = Reg(Bool()) init True
  val nmie = reg_rnmie

  val reg_menvcfg = Reg(new Envcfg) init (new Envcfg().getZero)
  val reg_senvcfg = Reg(new Envcfg) init (new Envcfg().getZero)
  val reg_henvcfg = Reg(new Envcfg) init (new Envcfg().getZero)

  val delegable_counters = U((BigInt(1) << (nPerfCounters + CSR.firstHPM)) - 1, 32 bits)
  val (reg_mcounteren, read_mcounteren) = {
    val reg = Reg(UInt(32 bits))
    (reg, Mux(Bool(usingUser), reg & delegable_counters, U(0, 32 bits)))
  }
  val (reg_scounteren, read_scounteren) = {
    val reg = Reg(UInt(32 bits))
    (reg, Mux(Bool(usingSupervisor), reg & delegable_counters, U(0, 32 bits)))
  }

  val (reg_hideleg, read_hideleg) = {
    val reg = Reg(UInt(xLen bits))
    (reg, Mux(Bool(usingHypervisor), reg & hs_delegable_interrupts, U(0, xLen bits)))
  }
  val (reg_hedeleg, read_hedeleg) = {
    val reg = Reg(UInt(xLen bits))
    (reg, Mux(Bool(usingHypervisor), reg & hs_delegable_exceptions, U(0, xLen bits)))
  }
  val hs_delegable_counters = delegable_counters
  val (reg_hcounteren, read_hcounteren) = {
    val reg = Reg(UInt(32 bits))
    (reg, Mux(Bool(usingHypervisor), reg & hs_delegable_counters, U(0, 32 bits)))
  }
  val reg_hstatus = Reg(new HStatus) init (new HStatus().getZero)
  val reg_hgatp = Reg(new PTBR)
  val reg_htval = Reg(cloneOf(reg_mtval2))
  val read_hvip = CL.asUInt(reg_mip).resize(xLen) & hs_delegable_interrupts
  val read_hie = reg_mie & hs_delegable_interrupts

  val (reg_vstvec, read_vstvec) = {
    val reg = Reg(UInt(vaddrBitsExtended bits))
    (reg, formTVec(reg).sextTo(xLen))
  }
  val reg_vsstatus = Reg(new MStatus)
  val reg_vsscratch = Reg(UInt(xLen bits))
  val reg_vsepc = Reg(UInt(vaddrBitsExtended bits))
  val reg_vscause = Reg(UInt(xLen bits))
  val reg_vstval = Reg(UInt(vaddrBitsExtended bits))
  val reg_vsatp = Reg(new PTBR)

  val reg_sepc = Reg(UInt(vaddrBitsExtended bits))
  val reg_scause = Reg(UInt(xLen bits))
  val reg_stval = Reg(UInt(vaddrBitsExtended bits))
  val reg_sscratch = Reg(UInt(xLen bits))
  val reg_stvec = Reg(UInt((if (usingHypervisor) vaddrBitsExtended else vaddrBits) bits))
  val reg_satp = Reg(new PTBR)
  val reg_wfi = Reg(Bool()) init False // rocket clocks this from the ungated clock; this port has no clock gating

  val reg_fflags = Reg(UInt(5 bits))
  val reg_frm = Reg(UInt(3 bits))

  val reg_mtinst_read_pseudo = Reg(Bool())
  val reg_htinst_read_pseudo = Reg(Bool())
  // XLEN=32: 0x00002000
  // XLEN=64: 0x00003000
  val Seq(read_mtinst, read_htinst) = Seq(reg_mtinst_read_pseudo, reg_htinst_read_pseudo).map(r =>
    Cat(r, if (xLen == 32) False else r, U(0, 12 bits)).asUInt)

  val reg_mcountinhibit = Reg(UInt(CSR.firstHPM + nPerfCounters bits)) init 0
  io.inhibit_cycle := reg_mcountinhibit(0)
  val reg_instret = WideCounter(64, io.retire, inhibit = reg_mcountinhibit(2))
  val reg_cycle = if (enableCommitLog) WideCounter(64, io.retire, inhibit = reg_mcountinhibit(0))
    else WideCounter(64, U(!io.csr_stall), inhibit = reg_mcountinhibit(0))
  val reg_hpmevent = io.counters.map(c => Reg(UInt(xLen bits)) init 0)
  (io.counters zip reg_hpmevent) foreach { case (c, e) => c.eventSel := e }
  val reg_hpmcounter = io.counters.zipWithIndex.map { case (c, i) =>
    WideCounter(CSR.hpmWidth, c.inc, reset = false, inhibit = reg_mcountinhibit(CSR.firstHPM + i))
  }

  val mip = CombInit(reg_mip)
  (mip.lip zip io.interrupts.lip).foreach { case (l, i) => l := i }
  mip.mtip := io.interrupts.mtip
  mip.msip := io.interrupts.msip
  mip.meip := io.interrupts.meip
  // seip is the OR of reg_mip.seip and the actual line from the PLIC
  if (io.interrupts.seip != null) mip.seip := reg_mip.seip || io.interrupts.seip
  // Simimlar sort of thing would apply if the PLIC had a VSEIP line:
  //io.interrupts.vseip.foreach { mip.vseip := reg_mip.vseip || _ }
  mip.rocc := io.rocc_interrupt
  val read_mip = (CL.asUInt(mip).resize(supported_interrupts.getWidth) & supported_interrupts).resize(xLen)
  val read_hip = read_mip & hs_delegable_interrupts
  val high_interrupts =
    if (usingNMI || io.interrupts.buserror == null) U(0, xLen bits)
    else (U(io.interrupts.buserror) << CSR.busErrorIntCause)

  val pending_interrupts = {
    val w = high_interrupts.getWidth max xLen
    high_interrupts.resize(w) | (read_mip & reg_mie).resize(w)
  }
  val d_interrupts = U(io.interrupts.debug) << CSR.debugIntCause
  val (nmi_interrupts, nmiFlag) =
    if (io.interrupts.nmi != null) {
      val nmi = io.interrupts.nmi
      val a = (U(nmi.rnmi && reg_rnmie) << CSR.rnmiIntCause).resize(CSR.rnmiIntCause + 1)
      val b = if (io.interrupts.buserror != null) (U(io.interrupts.buserror) << CSR.rnmiBEUCause).resize(CSR.rnmiIntCause + 1) else U(0, CSR.rnmiIntCause + 1 bits)
      (a | b, !io.interrupts.debug && nmi.rnmi && reg_rnmie)
    } else (U(0, 1 bits), False)
  val pw = pending_interrupts.getWidth
  val m_interrupts = Mux(nmie && (reg_mstatus.prv <= PRV.S || reg_mstatus.mie), ~(~pending_interrupts | read_mideleg.resize(pw)), U(0, pw bits))
  val s_interrupts = Mux(nmie && (reg_mstatus.v || reg_mstatus.prv < PRV.S || (reg_mstatus.prv === PRV.S && reg_mstatus.sie)), pending_interrupts & read_mideleg.resize(pw) & ~read_hideleg.resize(pw), U(0, pw bits))
  val vs_interrupts = Mux(nmie && (reg_mstatus.v && (reg_mstatus.prv < PRV.S || reg_mstatus.prv === PRV.S && reg_vsstatus.sie)), pending_interrupts & read_hideleg.resize(pw), U(0, pw bits))
  val (anyInterrupt, whichInterrupt) = chooseInterrupt(Seq(vs_interrupts, s_interrupts, m_interrupts, nmi_interrupts, d_interrupts))
  val interruptMSB = BigInt(1) << (xLen - 1)
  val interruptCause = U(interruptMSB, xLen bits) + (U(nmiFlag) << (xLen - 2)).resize(xLen) + whichInterrupt.resize(xLen)
  io.interrupt := (anyInterrupt && !io.singleStep || reg_singleStepped) && !(reg_debug || io.status.cease)
  io.interrupt_cause := interruptCause
  (io.bp zip reg_bp.take(nBreakpoints)).foreach { case (o, r) => o := r }
  io.mcontext := reg_mcontext.getOrElse(U(0, 0 bits))
  io.scontext := reg_scontext.getOrElse(U(0, 0 bits))
  io.fiom := (reg_mstatus.prv < PRV.M && reg_menvcfg.fiom) || (reg_mstatus.prv < PRV.S && reg_senvcfg.fiom) || (reg_mstatus.v && reg_henvcfg.fiom)
  (io.pmp zip reg_pmp.map(PMP(_))).foreach { case (o, r) => o := r }

  val isaMaskString =
    (if (usingMulDiv) "M" else "") +
      (if (usingAtomics) "A" else "") +
      (if (fLen >= 32) "F" else "") +
      (if (fLen >= 64) "D" else "") +
      (if (coreParams.hasV) "V" else "") +
      (if (usingCompressed) "C" else "")
  val isaString = (if (coreParams.useRVE) "E" else "I") +
    isaMaskString +
    (if (customIsaExt.isDefined || usingRoCC) "X" else "") +
    (if (usingSupervisor) "S" else "") +
    (if (usingHypervisor) "H" else "") +
    (if (usingUser) "U" else "")
  val isaMax = (BigInt(log2Ceil(xLen) - 4) << (xLen - 2)) | isaStringToMask(isaString)
  val reg_misa = Reg(UInt(xLen bits)) init (U(isaMax, xLen bits))
  val read_mstatus = CL.asUInt(io.status)(xLen - 1 downto 0)
  val read_mtvec = formTVec(reg_mtvec).padTo(xLen)
  val read_stvec = formTVec(reg_stvec).sextTo(xLen)

  val read_mapping = LinkedHashMap[Int, UInt](
    CSRs.tselect -> reg_tselect,
    CSRs.tdata1 -> CL.asUInt(reg_bp(reg_tselect).control),
    CSRs.tdata2 -> reg_bp(reg_tselect).address.sextTo(xLen),
    CSRs.tdata3 -> CL.asUInt(reg_bp(reg_tselect).textra),
    CSRs.misa -> reg_misa,
    CSRs.mstatus -> read_mstatus,
    CSRs.mtvec -> read_mtvec,
    CSRs.mip -> read_mip,
    CSRs.mie -> reg_mie,
    CSRs.mscratch -> reg_mscratch,
    CSRs.mepc -> readEPC(reg_mepc).sextTo(xLen),
    CSRs.mtval -> reg_mtval.sextTo(xLen),
    CSRs.mcause -> reg_mcause,
    CSRs.mhartid -> io.hartid)

  val debug_csrs = if (!usingDebug) LinkedHashMap[Int, UInt]() else LinkedHashMap[Int, UInt](
    CSRs.dcsr -> CL.asUInt(reg_dcsr),
    CSRs.dpc -> readEPC(reg_dpc).sextTo(xLen),
    CSRs.dscratch0 -> reg_dscratch0) ++
    reg_dscratch1.map(r => CSRs.dscratch1 -> r)

  val read_mnstatus = new MNStatus().getZero
  read_mnstatus.mpp := reg_mnstatus.mpp
  read_mnstatus.mpv := reg_mnstatus.mpv
  read_mnstatus.mie := reg_rnmie
  val nmi_csrs = if (!usingNMI) LinkedHashMap[Int, UInt]() else LinkedHashMap[Int, UInt](
    CustomCSRs.mnscratch -> reg_mnscratch,
    CustomCSRs.mnepc -> readEPC(reg_mnepc).sextTo(xLen),
    CustomCSRs.mncause -> reg_mncause,
    CustomCSRs.mnstatus -> CL.asUInt(read_mnstatus))

  val context_csrs = LinkedHashMap[Int, UInt]() ++
    reg_mcontext.map(r => CSRs.mcontext -> r) ++
    reg_scontext.map(r => CSRs.scontext -> r)

  val read_fcsr = Cat(reg_frm, reg_fflags).asUInt
  val fp_csrs = LinkedHashMap[Int, UInt]() ++
    usingFPU.option(CSRs.fflags -> reg_fflags) ++
    usingFPU.option(CSRs.frm -> reg_frm) ++
    (usingFPU || usingVector).option(CSRs.fcsr -> read_fcsr)

  val vector_csrs = LinkedHashMap[Int, UInt]()

  read_mapping ++= debug_csrs
  read_mapping ++= nmi_csrs
  read_mapping ++= context_csrs
  read_mapping ++= fp_csrs
  read_mapping ++= vector_csrs

  if (coreParams.haveBasicCounters) {
    read_mapping += CSRs.mcountinhibit -> reg_mcountinhibit
    read_mapping += CSRs.mcycle -> reg_cycle.value
    read_mapping += CSRs.minstret -> reg_instret.value

    for (((e, c), i) <- (reg_hpmevent.padTo(CSR.nHPM, U(0, 1 bits))
      zip reg_hpmcounter.map(x => x.value).padTo(CSR.nHPM, U(0, 1 bits))).zipWithIndex) {
      read_mapping += (i + CSR.firstHPE) -> e // mhpmeventN
      read_mapping += (i + CSR.firstMHPC) -> c // mhpmcounterN
      read_mapping += (i + CSR.firstHPC) -> c // hpmcounterN
      if (xLen == 32) {
        read_mapping += (i + CSR.firstMHPCH) -> (c >> 32) // mhpmcounterNh
        read_mapping += (i + CSR.firstHPCH) -> (c >> 32) // hpmcounterNh
      }
    }

    if (usingUser) {
      read_mapping += CSRs.mcounteren -> read_mcounteren
    }
    read_mapping += CSRs.cycle -> reg_cycle.value
    read_mapping += CSRs.instret -> reg_instret.value

    if (xLen == 32) {
      read_mapping += CSRs.mcycleh -> (reg_cycle.value >> 32)
      read_mapping += CSRs.minstreth -> (reg_instret.value >> 32)
      read_mapping += CSRs.cycleh -> (reg_cycle.value >> 32)
      read_mapping += CSRs.instreth -> (reg_instret.value >> 32)
    }
  }

  if (usingUser) {
    read_mapping += CSRs.menvcfg -> CL.asUInt(reg_menvcfg)
    if (xLen == 32)
      read_mapping += CSRs.menvcfgh -> (CL.asUInt(reg_menvcfg) >> 32)
  }

  val sie_mask = {
    val sgeip_mask = new MIP().getZero
    sgeip_mask.sgeip := True
    read_mideleg & ~(hs_delegable_interrupts | CL.asUInt(sgeip_mask).resize(xLen))
  }
  if (usingSupervisor) {
    val read_sie = reg_mie & sie_mask
    val read_sip = read_mip & sie_mask
    val read_sstatus = new MStatus().getZero
    read_sstatus.sd := io.status.sd
    read_sstatus.uxl := io.status.uxl
    read_sstatus.sd_rv32 := io.status.sd_rv32
    read_sstatus.mxr := io.status.mxr
    read_sstatus.sum := io.status.sum
    read_sstatus.xs := io.status.xs
    read_sstatus.fs := io.status.fs
    read_sstatus.vs := io.status.vs
    read_sstatus.spp := io.status.spp
    read_sstatus.spie := io.status.spie
    read_sstatus.sie := io.status.sie

    read_mapping += CSRs.sstatus -> CL.asUInt(read_sstatus)(xLen - 1 downto 0)
    read_mapping += CSRs.sip -> read_sip
    read_mapping += CSRs.sie -> read_sie
    read_mapping += CSRs.sscratch -> reg_sscratch
    read_mapping += CSRs.scause -> reg_scause
    read_mapping += CSRs.stval -> reg_stval.sextTo(xLen)
    read_mapping += CSRs.satp -> CL.asUInt(reg_satp)
    read_mapping += CSRs.sepc -> readEPC(reg_sepc).sextTo(xLen)
    read_mapping += CSRs.stvec -> read_stvec
    read_mapping += CSRs.scounteren -> read_scounteren
    read_mapping += CSRs.mideleg -> read_mideleg
    read_mapping += CSRs.medeleg -> read_medeleg
    read_mapping += CSRs.senvcfg -> CL.asUInt(reg_senvcfg)
  }

  val pmpCfgPerCSR = xLen / CL.width(new PMPConfig())
  def pmpCfgIndex(i: Int) = (xLen / 32) * (i / pmpCfgPerCSR)
  if (reg_pmp.nonEmpty) {
    require(reg_pmp.size <= CSR.maxPMPs)
    val zeroPMP = new PMP().getZero
    val read_pmp = reg_pmp.map(r => (r.cfg, r.readAddr)) ++ Seq.fill(CSR.maxPMPs - reg_pmp.size)((zeroPMP.cfg, zeroPMP.readAddr))
    for (i <- 0 until read_pmp.size by pmpCfgPerCSR)
      read_mapping += (CSRs.pmpcfg0 + pmpCfgIndex(i)) -> CatLsbFirst(read_pmp.map(x => CL.asUInt(x._1)).slice(i, i + pmpCfgPerCSR)).asUInt
    for ((pmp, i) <- read_pmp.zipWithIndex)
      read_mapping += (CSRs.pmpaddr0 + i) -> pmp._2
  }

  // implementation-defined CSRs
  def generateCustomCSR(csr: CustomCSR, csr_io: CustomCSRIO) = {
    require(csr.mask >= 0 && csr.mask.bitLength <= xLen)
    require(!read_mapping.contains(csr.id))
    val reg = csr.init.map(init => Reg(UInt(xLen bits)) init (U(init, xLen bits))).getOrElse(Reg(UInt(xLen bits)))
    val read = io.rw.cmd =/= CSR.N.U && io.rw.addr === csr.id
    csr_io.ren := read
    when(read && csr_io.stall) { io.rw_stall := True }
    read_mapping += csr.id -> reg
    reg
  }
  val reg_custom = customCSRs.zip(io.customCSRs).map(t => generateCustomCSR(t._1, t._2))
  val reg_rocc = roccCSRs.zip(io.roccCSRs).map(t => generateCustomCSR(t._1, t._2))

  if (usingHypervisor) {
    read_mapping += CSRs.mtinst -> read_mtinst
    read_mapping += CSRs.mtval2 -> reg_mtval2

    val read_hstatus = CL.asUInt(io.hstatus)(xLen - 1 downto 0)

    read_mapping += CSRs.hstatus -> read_hstatus
    read_mapping += CSRs.hedeleg -> read_hedeleg
    read_mapping += CSRs.hideleg -> read_hideleg
    read_mapping += CSRs.hcounteren -> read_hcounteren
    read_mapping += CSRs.hgatp -> CL.asUInt(reg_hgatp)
    read_mapping += CSRs.hip -> read_hip
    read_mapping += CSRs.hie -> read_hie
    read_mapping += CSRs.hvip -> read_hvip
    read_mapping += CSRs.hgeie -> U(0, 1 bits)
    read_mapping += CSRs.hgeip -> U(0, 1 bits)
    read_mapping += CSRs.htval -> reg_htval
    read_mapping += CSRs.htinst -> read_htinst
    read_mapping += CSRs.henvcfg -> CL.asUInt(reg_henvcfg)
    if (xLen == 32)
      read_mapping += CSRs.henvcfgh -> (CL.asUInt(reg_henvcfg) >> 32)

    val read_vsie = (read_hie & read_hideleg) >> 1
    val read_vsip = (read_hip & read_hideleg) >> 1
    val read_vsepc = readEPC(reg_vsepc).sextTo(xLen)
    val read_vstval = reg_vstval.sextTo(xLen)
    val read_vsstatus = CL.asUInt(io.gstatus)(xLen - 1 downto 0)

    read_mapping += CSRs.vsstatus -> read_vsstatus
    read_mapping += CSRs.vsip -> read_vsip
    read_mapping += CSRs.vsie -> read_vsie
    read_mapping += CSRs.vsscratch -> reg_vsscratch
    read_mapping += CSRs.vscause -> reg_vscause
    read_mapping += CSRs.vstval -> read_vstval
    read_mapping += CSRs.vsatp -> CL.asUInt(reg_vsatp)
    read_mapping += CSRs.vsepc -> read_vsepc
    read_mapping += CSRs.vstvec -> read_vstvec
  }

  // mimpid, marchid, mvendorid, and mconfigptr are 0 unless overridden by customCSRs
  Seq(CSRs.mimpid, CSRs.marchid, CSRs.mvendorid, CSRs.mconfigptr).foreach(id => read_mapping.getOrElseUpdate(id, U(0, 1 bits)))

  val decoded_addr: Map[Int, Bool] = {
    val addr = Cat(io.status.v, io.rw.addr).asUInt
    val pats = for (((k, _), i) <- read_mapping.zipWithIndex)
      yield (BitPat.lit(k), (0 until read_mapping.size).map(j => BitPat(if (i == j) 1 else 0, 1)))
    val decoded = DecodeLogic(addr, Seq.fill(read_mapping.size)(X), pats)
    val unvirtualized_mapping = (for (((k, _), v) <- read_mapping zip decoded) yield k -> v.lsb).toMap

    for ((k, v) <- unvirtualized_mapping) yield k -> {
      val alt: Option[Bool] = CSR.mode(k) match {
        // hcontext was assigned an unfortunate address; it lives where a
        // hypothetical vscontext will live.  Exclude them from the S/VS remapping.
        // (on separate lines so scala-lint doesnt do something stupid)
        case _ if k == CSRs.scontext => None
        case _ if k == CSRs.hcontext => None
        // When V=1, if a corresponding VS CSR exists, access it instead...
        case PRV.H => unvirtualized_mapping.lift(k - (1 << CSR.modeLSB))
        // ...and don't access the original S-mode version.
        case PRV.S => unvirtualized_mapping.contains(k + (1 << CSR.modeLSB)).option(False)
        case _ => None
      }
      alt.map(Mux(reg_mstatus.v, _, v)).getOrElse(v)
    }
  }

  val wdata = readModifyWriteCSR(io.rw.cmd, io.rw.rdata, io.rw.wdata)

  val system_insn = io.rw.cmd === CSR.I.U
  val hlsv = Seq(HLV_B, HLV_BU, HLV_H, HLV_HU, HLV_W, HLV_WU, HLV_D, HSV_B, HSV_H, HSV_W, HSV_D, HLVX_HU, HLVX_WU)
  val decode_table = Seq(        ECALL->       List(Y,N,N,N,N,N,N,N,N),
                                 EBREAK->      List(N,Y,N,N,N,N,N,N,N),
                                 MRET->        List(N,N,Y,N,N,N,N,N,N),
                                 CEASE->       List(N,N,N,Y,N,N,N,N,N),
                                 WFI->         List(N,N,N,N,Y,N,N,N,N)) ++
    usingDebug.option(           DRET->        List(N,N,Y,N,N,N,N,N,N)) ++
    usingNMI.option(             MNRET->       List(N,N,Y,N,N,N,N,N,N)) ++
    coreParams.haveCFlush.option(CFLUSH_D_L1-> List(N,N,N,N,N,N,N,N,N)) ++
    usingSupervisor.option(      SRET->        List(N,N,Y,N,N,N,N,N,N)) ++
    usingVM.option(              SFENCE_VMA->  List(N,N,N,N,N,Y,N,N,N)) ++
    usingHypervisor.option(      HFENCE_VVMA-> List(N,N,N,N,N,N,Y,N,N)) ++
    usingHypervisor.option(      HFENCE_GVMA-> List(N,N,N,N,N,N,N,Y,N)) ++
    (if (usingHypervisor)        hlsv.map(_->  List(N,N,N,N,N,N,N,N,Y)) else Seq())
  val (insn_call, insn_break, insn_ret, insn_cease, insn_wfi) = {
    val insn = U(ECALL.value, 32 bits) | (io.rw.addr << 20).resize(32)
    val d = DecodeLogic(insn, decode_table(0)._2.map(x => X), decode_table).map(system_insn && _.lsb)
    (d(0), d(1), d(2), d(3), d(4))
  }

  for (io_dec <- io.decode) {
    val addr = io_dec.inst(31 downto 20)

    def decodeAny(m: LinkedHashMap[Int, UInt]): Bool = m.map { case (k, _) => addr === k }.reduce(_ || _)
    def decodeFast(s: Seq[Int]): Bool = DecodeLogic(addr, s.map(BitPat.lit(_)), (read_mapping.toMap -- s).keys.toList.map(BitPat.lit(_)))

    val d = DecodeLogic(io_dec.inst, decode_table(0)._2.map(x => X), decode_table).map(_.lsb)
    val is_break = d(1)
    val is_ret = d(2)
    val is_wfi = d(4)
    val is_sfence = d(5)
    val is_hfence_vvma = d(6)
    val is_hfence_gvma = d(7)
    val is_hlsv = d(8)
    val is_counter = (addr.inRange(U(CSR.firstCtr), U(CSR.firstCtr + CSR.nCtr)) || addr.inRange(U(CSR.firstCtrH), U(CSR.firstCtrH + CSR.nCtr)))

    val allow_wfi = Bool(!usingSupervisor) || reg_mstatus.prv > PRV.S || !reg_mstatus.tw && (!reg_mstatus.v || !reg_hstatus.vtw)
    val allow_sfence_vma = Bool(!usingVM) || reg_mstatus.prv > PRV.S || !Mux(reg_mstatus.v, reg_hstatus.vtvm, reg_mstatus.tvm)
    val allow_hfence_vvma = Bool(!usingHypervisor) || !reg_mstatus.v && (reg_mstatus.prv >= PRV.S)
    val allow_hlsv = Bool(!usingHypervisor) || !reg_mstatus.v && (reg_mstatus.prv >= PRV.S || reg_hstatus.hu)
    val allow_sret = Bool(!usingSupervisor) || reg_mstatus.prv > PRV.S || !Mux(reg_mstatus.v, reg_hstatus.vtsr, reg_mstatus.tsr)
    val counter_addr = addr(log2Ceil(read_mcounteren.getWidth) - 1 downto 0)
    val allow_counter = (reg_mstatus.prv > PRV.S || read_mcounteren(counter_addr)) &&
      (!Bool(usingSupervisor) || reg_mstatus.prv >= PRV.S || read_scounteren(counter_addr)) &&
      (!Bool(usingHypervisor) || !reg_mstatus.v || read_hcounteren(counter_addr))
    io_dec.fp_illegal := io.status.fs === 0 || reg_mstatus.v && reg_vsstatus.fs === 0 || !reg_misa('f' - 'a')
    io_dec.vector_illegal := io.status.vs === 0 || reg_mstatus.v && reg_vsstatus.vs === 0 || !Bool(usingVector)
    io_dec.fp_csr := decodeFast(fp_csrs.keys.toList)
    io_dec.vector_csr := decodeFast(vector_csrs.keys.toList)
    io_dec.rocc_illegal := io.status.xs === 0 || reg_mstatus.v && reg_vsstatus.xs === 0 || !reg_misa('x' - 'a')
    val csr_addr_legal = reg_mstatus.prv >= CSR.mode(addr) ||
      Bool(usingHypervisor) && !reg_mstatus.v && reg_mstatus.prv === PRV.S && CSR.mode(addr) === PRV.H
    val csr_exists = decodeAny(read_mapping)
    io_dec.read_illegal := !csr_addr_legal ||
      !csr_exists ||
      ((addr === CSRs.satp || addr === CSRs.hgatp) && !allow_sfence_vma) ||
      is_counter && !allow_counter ||
      decodeFast(debug_csrs.keys.toList) && !reg_debug ||
      decodeFast(vector_csrs.keys.toList) && io_dec.vector_illegal ||
      io_dec.fp_csr && io_dec.fp_illegal
    io_dec.write_illegal := addr(11 downto 10).andR
    io_dec.write_flush := {
      val addr_m = addr | U(PRV.M << CSR.modeLSB, 12 bits)
      !(addr_m >= CSRs.mscratch && addr_m <= CSRs.mtval)
    }
    io_dec.system_illegal := !csr_addr_legal && !is_hlsv ||
      is_wfi && !allow_wfi ||
      is_ret && !allow_sret ||
      is_ret && addr(10) && addr(7) && !reg_debug ||
      (is_sfence || is_hfence_gvma) && !allow_sfence_vma ||
      is_hfence_vvma && !allow_hfence_vvma ||
      is_hlsv && !allow_hlsv

    io_dec.virtual_access_illegal := reg_mstatus.v && csr_exists && (
      CSR.mode(addr) === PRV.H ||
        is_counter && read_mcounteren(counter_addr) && (!read_hcounteren(counter_addr) || !reg_mstatus.prv(0) && !read_scounteren(counter_addr)) ||
        CSR.mode(addr) === PRV.S && !reg_mstatus.prv(0) ||
        addr === CSRs.satp && reg_mstatus.prv(0) && reg_hstatus.vtvm)

    io_dec.virtual_system_illegal := reg_mstatus.v && (
      is_hfence_vvma ||
        is_hfence_gvma ||
        is_hlsv ||
        is_wfi && (!reg_mstatus.prv(0) || !reg_mstatus.tw && reg_hstatus.vtw) ||
        is_ret && CSR.mode(addr) === PRV.S && (!reg_mstatus.prv(0) || reg_hstatus.vtsr) ||
        is_sfence && (!reg_mstatus.prv(0) || reg_hstatus.vtvm))
  }

  val cause =
    Mux(insn_call, (U(Causes.user_ecall, xLen bits) + Mux(reg_mstatus.prv(0) && reg_mstatus.v, U(PRV.H, 2 bits), reg_mstatus.prv).resize(xLen)),
      Mux(insn_break, U(Causes.breakpoint, xLen bits), io.cause))
  val cause_lsbs = cause(log2Ceil(1 + CSR.busErrorIntCause) - 1 downto 0)
  val cause_deleg_lsbs = cause(log2Ceil(xLen) - 1 downto 0)
  val causeIsDebugInt = cause(xLen - 1) && cause_lsbs === CSR.debugIntCause
  val causeIsDebugTrigger = !cause(xLen - 1) && cause_lsbs === CSR.debugTriggerCause
  val causeIsDebugBreak = !cause(xLen - 1) && insn_break && Cat(reg_dcsr.ebreakm, reg_dcsr.ebreakh, reg_dcsr.ebreaks, reg_dcsr.ebreaku).asUInt(reg_mstatus.prv)
  val trapToDebug = Bool(usingDebug) && (reg_singleStepped || causeIsDebugInt || causeIsDebugTrigger || causeIsDebugBreak || reg_debug)
  val debugEntry = p.system.debugModule.map(_.debugEntry).getOrElse(BigInt(0x800))
  val debugException = p.system.debugModule.map(_.debugException).getOrElse(BigInt(0x808))
  val debugTVec = Mux(reg_debug, Mux(insn_break, U(debugEntry, xLen bits), U(debugException, xLen bits)), U(debugEntry, xLen bits))
  val delegate = Bool(usingSupervisor) && reg_mstatus.prv <= PRV.S && Mux(cause(xLen - 1), read_mideleg(cause_deleg_lsbs), read_medeleg(cause_deleg_lsbs))
  val delegateVS = reg_mstatus.v && delegate && Mux(cause(xLen - 1), read_hideleg(cause_deleg_lsbs), read_hedeleg(cause_deleg_lsbs))
  def mtvecBaseAlign = 2
  def mtvecInterruptAlign = {
    require(CL.width(new MIP) <= xLen)
    log2Ceil(xLen)
  }
  val notDebugTVec = {
    val base = Mux(delegate, Mux(delegateVS, read_vstvec, read_stvec), read_mtvec)
    val interruptOffset = cause(mtvecInterruptAlign - 1 downto 0) << mtvecBaseAlign
    val interruptVec = Cat(base >> (mtvecInterruptAlign + mtvecBaseAlign), interruptOffset).asUInt
    val doVector = base(0) && cause(cause.getWidth - 1) && (cause_lsbs >> mtvecInterruptAlign) === 0
    Mux(doVector, interruptVec, (base >> mtvecBaseAlign) << mtvecBaseAlign)
  }

  val causeIsRnmiInt = cause(xLen - 1) && cause(xLen - 2) && (cause_lsbs === CSR.rnmiIntCause || cause_lsbs === CSR.rnmiBEUCause)
  val causeIsRnmiBEU = cause(xLen - 1) && cause(xLen - 2) && cause_lsbs === CSR.rnmiBEUCause
  val causeIsNmi = causeIsRnmiInt
  val nmiTVecInt = if (io.interrupts.nmi != null) io.interrupts.nmi.rnmi_interrupt_vector.resize(xLen) else U(0, xLen bits)
  val nmiTVecXcpt = if (io.interrupts.nmi != null) io.interrupts.nmi.rnmi_exception_vector.resize(xLen) else U(0, xLen bits)
  val trapToNmiInt = Bool(usingNMI) && causeIsNmi
  val trapToNmiXcpt = Bool(usingNMI) && !nmie
  val trapToNmi = trapToNmiInt || trapToNmiXcpt
  val nmiTVec = (Mux(causeIsNmi, nmiTVecInt, nmiTVecXcpt) >> 1) << 1

  val tvec = Mux(trapToDebug, debugTVec, Mux(trapToNmi, nmiTVec.resize(xLen), notDebugTVec.resize(xLen)))
  io.evec := tvec.resized
  io.ptbr := reg_satp
  io.hgatp := reg_hgatp
  io.vsatp := reg_vsatp
  io.eret := insn_call || insn_break || insn_ret
  io.trap_return := insn_ret
  io.singleStep := reg_dcsr.step && !reg_debug
  io.status := reg_mstatus
  io.status.sd := io.status.fs.andR || io.status.xs.andR || io.status.vs.andR
  io.status.debug := reg_debug
  io.status.isa := reg_misa.resized
  io.status.uxl := (if (usingUser) log2Ceil(xLen) - 4 else 0)
  io.status.sxl := (if (usingSupervisor) log2Ceil(xLen) - 4 else 0)
  io.status.dprv := Mux(reg_mstatus.mprv && !reg_debug, reg_mstatus.mpp, reg_mstatus.prv)
  io.status.dv := reg_mstatus.v || Mux(reg_mstatus.mprv && !reg_debug, reg_mstatus.mpv, False)
  io.status.sd_rv32 := Bool(xLen == 32) && io.status.sd
  io.status.mpv := reg_mstatus.mpv
  io.status.gva := reg_mstatus.gva
  io.hstatus := reg_hstatus
  io.hstatus.vsxl := (if (usingSupervisor) log2Ceil(xLen) - 4 else 0)
  io.gstatus := reg_vsstatus
  io.gstatus.sd := io.gstatus.fs.andR || io.gstatus.xs.andR || io.gstatus.vs.andR
  io.gstatus.uxl := (if (usingUser) log2Ceil(xLen) - 4 else 0)
  io.gstatus.sd_rv32 := Bool(xLen == 32) && io.gstatus.sd

  val exception = insn_call || insn_break || io.exception
  assert(PopCount(Seq(insn_ret, insn_call, insn_break, io.exception)) <= 1, "these conditions must be mutually exclusive")

  when(insn_wfi && !io.singleStep && !reg_debug) { reg_wfi := True }
  when(pending_interrupts.orR || io.interrupts.debug || exception) { reg_wfi := False }
  if (io.interrupts.nmi != null) when(io.interrupts.nmi.rnmi) { reg_wfi := False }

  when(io.retire(0) || exception) { reg_singleStepped := True }
  when(!io.singleStep) { reg_singleStepped := False }
  assert(!io.singleStep || io.retire <= 1)
  assert(!reg_singleStepped || io.retire === 0)

  val epc = formEPC(io.pc)
  val tval = Mux(insn_break, epc, io.tval)

  when(exception) {
    when(trapToDebug) {
      when(!reg_debug) {
        reg_mstatus.v := False
        reg_debug := True
        reg_dpc := epc
        reg_dcsr.cause := Mux(reg_singleStepped, U(4, 3 bits), Mux(causeIsDebugInt, U(3, 3 bits), Mux(causeIsDebugTrigger, U(2, 3 bits), U(1, 3 bits))))
        reg_dcsr.prv := trimPrivilege(reg_mstatus.prv)
        reg_dcsr.v := reg_mstatus.v
        new_prv := PRV.M
      }
    }.elsewhen(trapToNmiInt) {
      when(reg_rnmie) {
        reg_mstatus.v := False
        reg_mnstatus.mpv := reg_mstatus.v
        reg_rnmie := False
        reg_mnepc := epc
        reg_mncause := U(BigInt(1) << (xLen - 1), xLen bits) | Mux(causeIsRnmiBEU, U(3, xLen bits), U(2, xLen bits))
        reg_mnstatus.mpp := trimPrivilege(reg_mstatus.prv)
        new_prv := PRV.M
      }
    }.elsewhen(delegateVS && nmie) {
      reg_mstatus.v := True
      reg_vsstatus.spp := reg_mstatus.prv.resized
      reg_vsepc := epc
      reg_vscause := Mux(cause(xLen - 1), Cat(cause(xLen - 1 downto 2), U(1, 2 bits)).asUInt, cause)
      reg_vstval := tval
      reg_vsstatus.spie := reg_vsstatus.sie
      reg_vsstatus.sie := False
      new_prv := PRV.S
    }.elsewhen(delegate && nmie) {
      reg_mstatus.v := False
      reg_hstatus.spvp := Mux(reg_mstatus.v, reg_mstatus.prv(0), reg_hstatus.spvp)
      reg_hstatus.gva := io.gva
      reg_hstatus.spv := reg_mstatus.v
      reg_sepc := epc
      reg_scause := cause
      reg_stval := tval
      reg_htval := io.htval
      reg_htinst_read_pseudo := io.mhtinst_read_pseudo
      reg_mstatus.spie := reg_mstatus.sie
      reg_mstatus.spp := reg_mstatus.prv.resized
      reg_mstatus.sie := False
      new_prv := PRV.S
    }.otherwise {
      reg_mstatus.v := False
      reg_mstatus.mpv := reg_mstatus.v
      reg_mstatus.gva := io.gva
      reg_mepc := epc
      reg_mcause := cause
      reg_mtval := tval
      reg_mtval2 := io.htval
      reg_mtinst_read_pseudo := io.mhtinst_read_pseudo
      reg_mstatus.mpie := reg_mstatus.mie
      reg_mstatus.mpp := trimPrivilege(reg_mstatus.prv)
      reg_mstatus.mie := False
      new_prv := PRV.M
    }
  }

  when(insn_ret) {
    val ret_prv = UInt(PRV.SZ bits)
    ret_prv.assignDontCare()
    when(Bool(usingSupervisor) && !io.rw.addr(9)) {
      when(!reg_mstatus.v) {
        reg_mstatus.sie := reg_mstatus.spie
        reg_mstatus.spie := True
        reg_mstatus.spp := PRV.U
        ret_prv := reg_mstatus.spp.resized
        reg_mstatus.v := Bool(usingHypervisor) && reg_hstatus.spv
        io.evec := readEPC(reg_sepc)
        reg_hstatus.spv := False
      }.otherwise {
        reg_vsstatus.sie := reg_vsstatus.spie
        reg_vsstatus.spie := True
        reg_vsstatus.spp := PRV.U
        ret_prv := reg_vsstatus.spp.resized
        reg_mstatus.v := Bool(usingHypervisor)
        io.evec := readEPC(reg_vsepc)
      }
    }.elsewhen(Bool(usingDebug) && io.rw.addr(10) && io.rw.addr(7)) {
      ret_prv := reg_dcsr.prv
      reg_mstatus.v := Bool(usingHypervisor) && reg_dcsr.v && reg_dcsr.prv <= PRV.S
      reg_debug := False
      io.evec := readEPC(reg_dpc)
    }.elsewhen(Bool(usingNMI) && io.rw.addr(10) && !io.rw.addr(7)) {
      ret_prv := reg_mnstatus.mpp
      reg_mstatus.v := Bool(usingHypervisor) && reg_mnstatus.mpv && reg_mnstatus.mpp <= PRV.S
      reg_rnmie := True
      io.evec := readEPC(reg_mnepc)
    }.otherwise {
      reg_mstatus.mie := reg_mstatus.mpie
      reg_mstatus.mpie := True
      reg_mstatus.mpp := legalizePrivilege(U(PRV.U, 2 bits))
      reg_mstatus.mpv := False
      ret_prv := reg_mstatus.mpp
      reg_mstatus.v := Bool(usingHypervisor) && reg_mstatus.mpv && reg_mstatus.mpp <= PRV.S
      io.evec := readEPC(reg_mepc)
    }

    new_prv := ret_prv
    when(Bool(usingUser) && ret_prv <= PRV.S) {
      reg_mstatus.mprv := False
    }
  }

  io.time := reg_cycle.value.resized
  io.csr_stall := reg_wfi || io.status.cease
  io.status.cease := RegNextWhen(True, insn_cease) init False
  io.status.wfi := reg_wfi

  for ((cio, reg) <- io.customCSRs zip reg_custom) {
    cio.wen := False
    cio.wdata := wdata
    cio.value := reg
  }

  for ((cio, reg) <- io.roccCSRs zip reg_rocc) {
    cio.wen := False
    cio.wdata := wdata
    cio.value := reg
  }

  io.rw.rdata := Mux1H(for ((k, v) <- read_mapping.toSeq) yield decoded_addr(k) -> v.resize(xLen))

  val set_fs_dirty = CombInit(if (io.set_fs_dirty != null) io.set_fs_dirty else False)
  if (coreParams.haveFSDirty) {
    when(set_fs_dirty) {
      assert(reg_mstatus.fs > 0)
      when(reg_mstatus.v) { reg_vsstatus.fs := 3 }
      reg_mstatus.fs := 3
    }
  }

  io.fcsr_rm := reg_frm
  when(io.fcsr_flags.valid) {
    reg_fflags := reg_fflags | io.fcsr_flags.payload.asUInt
    set_fs_dirty := True
  }

  val csr_wen = io.rw.cmd.isOneOf(CSR.S, CSR.C, CSR.W) && !io.rw_stall
  io.csrw_counter := Mux(Bool(coreParams.haveBasicCounters) && csr_wen && (io.rw.addr.inRange(U(CSRs.mcycle), U(CSRs.mcycle + CSR.nCtr)) || io.rw.addr.inRange(U(CSRs.mcycleh), U(CSRs.mcycleh + CSR.nCtr))),
    UIntToOH(io.rw.addr(log2Ceil(CSR.nCtr + nPerfCounters) - 1 downto 0)).resize(CSR.nCtr), U(0, CSR.nCtr bits))
  when(csr_wen) {
    val scause_mask = U((BigInt(1) << (xLen - 1)) + 31, xLen bits) /* only implement 5 LSBs and MSB */
    val satp_valid_modes = 0 +: (minPgLevels to pgLevels).map(new PTBR().pgLevelsToMode(_))

    when(decoded_addr(CSRs.mstatus)) {
      val new_mstatus = CL.fromUInt(new MStatus(), wdata)
      reg_mstatus.mie := new_mstatus.mie
      reg_mstatus.mpie := new_mstatus.mpie

      if (usingUser) {
        reg_mstatus.mprv := new_mstatus.mprv
        reg_mstatus.mpp := legalizePrivilege(new_mstatus.mpp)
        if (usingSupervisor) {
          reg_mstatus.spp := new_mstatus.spp
          reg_mstatus.spie := new_mstatus.spie
          reg_mstatus.sie := new_mstatus.sie
          reg_mstatus.tw := new_mstatus.tw
          reg_mstatus.tsr := new_mstatus.tsr
        }
        if (usingVM) {
          reg_mstatus.mxr := new_mstatus.mxr
          reg_mstatus.sum := new_mstatus.sum
          reg_mstatus.tvm := new_mstatus.tvm
        }
        if (usingHypervisor) {
          reg_mstatus.mpv := new_mstatus.mpv
          reg_mstatus.gva := new_mstatus.gva
        }
      }

      if (usingSupervisor || usingFPU) reg_mstatus.fs := formFS(new_mstatus.fs)
      reg_mstatus.vs := formVS(new_mstatus.vs)
    }
    when(decoded_addr(CSRs.misa)) {
      val mask = U(isaStringToMask(isaMaskString), xLen bits)
      val f = wdata('f' - 'a')
      // suppress write if it would cause the next fetch to be misaligned
      when(!Bool(usingCompressed) || !io.pc(1) || wdata('c' - 'a')) {
        if (coreParams.misaWritable)
          reg_misa := ~(~wdata | (U(!f) << ('d' - 'a')).resize(xLen)) & mask | reg_misa & ~mask
      }
    }
    when(decoded_addr(CSRs.mip)) {
      // MIP should be modified based on the value in reg_mip, not the value
      // in read_mip, since read_mip.seip is the OR of reg_mip.seip and
      // io.interrupts.seip.  We don't want the value on the PLIC line to
      // inadvertently be OR'd into read_mip.seip.
      val new_mip = CL.fromUInt(new MIP, readModifyWriteCSR(io.rw.cmd, CL.asUInt(reg_mip).resize(xLen), io.rw.wdata))
      if (usingSupervisor) {
        reg_mip.ssip := new_mip.ssip
        reg_mip.stip := new_mip.stip
        reg_mip.seip := new_mip.seip
      }
      if (usingHypervisor) {
        reg_mip.vssip := new_mip.vssip
      }
    }
    when(decoded_addr(CSRs.mie)) { reg_mie := wdata & supported_interrupts.resize(xLen) }
    when(decoded_addr(CSRs.mepc)) { reg_mepc := formEPC(wdata).resized }
    when(decoded_addr(CSRs.mscratch)) { reg_mscratch := wdata }
    if (mtvecWritable)
      when(decoded_addr(CSRs.mtvec)) { reg_mtvec := wdata.resized }
    when(decoded_addr(CSRs.mcause)) { reg_mcause := wdata & U((BigInt(1) << (xLen - 1)) + (BigInt(1) << whichInterrupt.getWidth) - 1, xLen bits) }
    when(decoded_addr(CSRs.mtval)) { reg_mtval := wdata.resized }

    if (usingNMI) {
      val new_mnstatus = CL.fromUInt(new MNStatus(), wdata)
      when(decoded_addr(CustomCSRs.mnscratch)) { reg_mnscratch := wdata }
      when(decoded_addr(CustomCSRs.mnepc)) { reg_mnepc := formEPC(wdata).resized }
      when(decoded_addr(CustomCSRs.mncause)) { reg_mncause := wdata & U((BigInt(1) << (xLen - 1)) + BigInt(3), xLen bits) }
      when(decoded_addr(CustomCSRs.mnstatus)) {
        reg_mnstatus.mpp := legalizePrivilege(new_mnstatus.mpp)
        reg_mnstatus.mpv := Bool(usingHypervisor) && new_mnstatus.mpv
        reg_rnmie := reg_rnmie | new_mnstatus.mie // mnie bit settable but not clearable from software
      }
    }

    for (((e, c), i) <- (reg_hpmevent zip reg_hpmcounter).zipWithIndex) {
      writeCounter(i + CSR.firstMHPC, c, wdata)
      when(decoded_addr(i + CSR.firstHPE)) { e := perfEventSets.maskEventSelector(wdata) }
    }
    if (coreParams.haveBasicCounters) {
      when(decoded_addr(CSRs.mcountinhibit)) { reg_mcountinhibit := (wdata & ~U(2, xLen bits)).resized } // mcountinhibit bit [1] is tied zero
      writeCounter(CSRs.mcycle, reg_cycle, wdata)
      writeCounter(CSRs.minstret, reg_instret, wdata)
    }

    if (usingFPU) {
      when(decoded_addr(CSRs.fflags)) { set_fs_dirty := True; reg_fflags := wdata.resized }
      when(decoded_addr(CSRs.frm)) { set_fs_dirty := True; reg_frm := wdata.resized }
      when(decoded_addr(CSRs.fcsr)) {
        set_fs_dirty := True
        reg_fflags := wdata.resized
        reg_frm := (wdata >> reg_fflags.getWidth).resized
      }
    }
    if (usingDebug) {
      when(decoded_addr(CSRs.dcsr)) {
        val new_dcsr = CL.fromUInt(new DCSR(), wdata)
        reg_dcsr.step := new_dcsr.step
        reg_dcsr.ebreakm := new_dcsr.ebreakm
        if (usingSupervisor) reg_dcsr.ebreaks := new_dcsr.ebreaks
        if (usingUser) reg_dcsr.ebreaku := new_dcsr.ebreaku
        if (usingUser) reg_dcsr.prv := legalizePrivilege(new_dcsr.prv)
        if (usingHypervisor) reg_dcsr.v := new_dcsr.v
      }
      when(decoded_addr(CSRs.dpc)) { reg_dpc := formEPC(wdata).resized }
      when(decoded_addr(CSRs.dscratch0)) { reg_dscratch0 := wdata }
      reg_dscratch1.foreach { r =>
        when(decoded_addr(CSRs.dscratch1)) { r := wdata }
      }
    }
    if (usingSupervisor) {
      when(decoded_addr(CSRs.sstatus)) {
        val new_sstatus = CL.fromUInt(new MStatus(), wdata)
        reg_mstatus.sie := new_sstatus.sie
        reg_mstatus.spie := new_sstatus.spie
        reg_mstatus.spp := new_sstatus.spp
        reg_mstatus.fs := formFS(new_sstatus.fs)
        reg_mstatus.vs := formVS(new_sstatus.vs)
        if (usingVM) {
          reg_mstatus.mxr := new_sstatus.mxr
          reg_mstatus.sum := new_sstatus.sum
        }
      }
      when(decoded_addr(CSRs.sip)) {
        val new_sip = CL.fromUInt(new MIP(), (read_mip & ~read_mideleg) | (wdata & read_mideleg))
        reg_mip.ssip := new_sip.ssip
      }
      when(decoded_addr(CSRs.satp)) {
        if (usingVM) {
          val new_satp = CL.fromUInt(new PTBR(), wdata)
          when(new_satp.mode.isOneOf(satp_valid_modes.map(m => U(m, new_satp.mode.getWidth bits)))) {
            reg_satp.mode := new_satp.mode & satp_valid_modes.reduce(_ | _)
            reg_satp.ppn := new_satp.ppn(ppnBits - 1 downto 0).resized
            if (asIdBits > 0) reg_satp.asid := new_satp.asid(asIdBits - 1 downto 0).resized
          }
        }
      }
      when(decoded_addr(CSRs.sie)) { reg_mie := (reg_mie & ~sie_mask) | (wdata & sie_mask) }
      when(decoded_addr(CSRs.sscratch)) { reg_sscratch := wdata }
      when(decoded_addr(CSRs.sepc)) { reg_sepc := formEPC(wdata).resized }
      when(decoded_addr(CSRs.stvec)) { reg_stvec := wdata.resized }
      when(decoded_addr(CSRs.scause)) { reg_scause := wdata & scause_mask }
      when(decoded_addr(CSRs.stval)) { reg_stval := wdata.resized }
      when(decoded_addr(CSRs.mideleg)) { reg_mideleg := wdata }
      when(decoded_addr(CSRs.medeleg)) { reg_medeleg := wdata }
      when(decoded_addr(CSRs.scounteren)) { reg_scounteren := wdata.resized }
      when(decoded_addr(CSRs.senvcfg)) { reg_senvcfg.write(wdata) }
    }

    if (usingHypervisor) {
      when(decoded_addr(CSRs.hstatus)) {
        val new_hstatus = CL.fromUInt(new HStatus(), wdata)
        reg_hstatus.gva := new_hstatus.gva
        reg_hstatus.spv := new_hstatus.spv
        reg_hstatus.spvp := new_hstatus.spvp
        reg_hstatus.hu := new_hstatus.hu
        reg_hstatus.vtvm := new_hstatus.vtvm
        reg_hstatus.vtw := new_hstatus.vtw
        reg_hstatus.vtsr := new_hstatus.vtsr
        reg_hstatus.vsxl := new_hstatus.vsxl
      }
      when(decoded_addr(CSRs.hideleg)) { reg_hideleg := wdata }
      when(decoded_addr(CSRs.hedeleg)) { reg_hedeleg := wdata }
      when(decoded_addr(CSRs.hgatp)) {
        val new_hgatp = CL.fromUInt(new PTBR(), wdata)
        val valid_modes = 0 +: (minPgLevels to pgLevels).map(new_hgatp.pgLevelsToMode(_))
        when(new_hgatp.mode.isOneOf(valid_modes.map(m => U(m, new_hgatp.mode.getWidth bits)))) {
          reg_hgatp.mode := new_hgatp.mode & valid_modes.reduce(_ | _)
        }
        reg_hgatp.ppn := Cat(new_hgatp.ppn(ppnBits - 1 downto 2), U(0, 2 bits)).asUInt.resized
        if (vmIdBits > 0) reg_hgatp.asid := new_hgatp.asid(vmIdBits - 1 downto 0).resized
      }
      when(decoded_addr(CSRs.hip)) {
        val new_hip = CL.fromUInt(new MIP(), (read_mip & ~hs_delegable_interrupts) | (wdata & hs_delegable_interrupts))
        reg_mip.vssip := new_hip.vssip
      }
      when(decoded_addr(CSRs.hie)) { reg_mie := (reg_mie & ~hs_delegable_interrupts) | (wdata & hs_delegable_interrupts) }
      when(decoded_addr(CSRs.hvip)) {
        val new_sip = CL.fromUInt(new MIP(), (read_mip & ~hs_delegable_interrupts) | (wdata & hs_delegable_interrupts))
        reg_mip.vssip := new_sip.vssip
        reg_mip.vstip := new_sip.vstip
        reg_mip.vseip := new_sip.vseip
      }
      when(decoded_addr(CSRs.hcounteren)) { reg_hcounteren := wdata.resized }
      when(decoded_addr(CSRs.htval)) { reg_htval := wdata.resized }
      when(decoded_addr(CSRs.mtval2)) { reg_mtval2 := wdata.resized }

      val write_mhtinst_read_pseudo = wdata(13) && (if (xLen == 32) True else wdata(12))
      when(decoded_addr(CSRs.mtinst)) { reg_mtinst_read_pseudo := write_mhtinst_read_pseudo }
      when(decoded_addr(CSRs.htinst)) { reg_htinst_read_pseudo := write_mhtinst_read_pseudo }

      when(decoded_addr(CSRs.vsstatus)) {
        val new_vsstatus = CL.fromUInt(new MStatus(), wdata)
        reg_vsstatus.sie := new_vsstatus.sie
        reg_vsstatus.spie := new_vsstatus.spie
        reg_vsstatus.spp := new_vsstatus.spp
        reg_vsstatus.mxr := new_vsstatus.mxr
        reg_vsstatus.sum := new_vsstatus.sum
        reg_vsstatus.fs := formFS(new_vsstatus.fs)
        reg_vsstatus.vs := formVS(new_vsstatus.vs)
      }
      when(decoded_addr(CSRs.vsip)) {
        val new_vsip = CL.fromUInt(new MIP(), (read_hip & ~read_hideleg) | ((wdata << 1).resize(xLen) & read_hideleg))
        reg_mip.vssip := new_vsip.vssip
      }
      when(decoded_addr(CSRs.vsatp)) {
        val new_vsatp = CL.fromUInt(new PTBR(), wdata)
        val mode_ok = new_vsatp.mode.isOneOf(satp_valid_modes.map(m => U(m, new_vsatp.mode.getWidth bits)))
        when(mode_ok) {
          reg_vsatp.mode := new_vsatp.mode & satp_valid_modes.reduce(_ | _)
        }
        when(mode_ok || !reg_mstatus.v) {
          reg_vsatp.ppn := new_vsatp.ppn((vpnBits min new_vsatp.ppn.getWidth) - 1 downto 0).resized
          if (asIdBits > 0) reg_vsatp.asid := new_vsatp.asid(asIdBits - 1 downto 0).resized
        }
      }
      when(decoded_addr(CSRs.vsie)) { reg_mie := (reg_mie & ~read_hideleg) | ((wdata << 1).resize(xLen) & read_hideleg) }
      when(decoded_addr(CSRs.vsscratch)) { reg_vsscratch := wdata }
      when(decoded_addr(CSRs.vsepc)) { reg_vsepc := formEPC(wdata).resized }
      when(decoded_addr(CSRs.vstvec)) { reg_vstvec := wdata.resized }
      when(decoded_addr(CSRs.vscause)) { reg_vscause := wdata & scause_mask }
      when(decoded_addr(CSRs.vstval)) { reg_vstval := wdata.resized }
      when(decoded_addr(CSRs.henvcfg)) { reg_henvcfg.write(wdata) }
    }
    if (usingUser) {
      when(decoded_addr(CSRs.mcounteren)) { reg_mcounteren := wdata.resized }
      when(decoded_addr(CSRs.menvcfg)) { reg_menvcfg.write(wdata) }
    }
    if (nBreakpoints > 0) {
      when(decoded_addr(CSRs.tselect)) { reg_tselect := wdata.resized }

      for ((bp, i) <- reg_bp.zipWithIndex) {
        when(reg_tselect === i && (!bp.control.dmode || reg_debug)) {
          when(decoded_addr(CSRs.tdata2)) { bp.address := wdata.resized }
          when(decoded_addr(CSRs.tdata3)) {
            if (coreParams.mcontextWidth > 0) {
              bp.textra.mselect := wdata(bp.textra.mselectPos)
              bp.textra.mvalue := (wdata >> bp.textra.mvaluePos).resized
            }
            if (coreParams.scontextWidth > 0) {
              bp.textra.sselect := wdata(bp.textra.sselectPos)
              bp.textra.svalue := (wdata >> bp.textra.svaluePos).resized
            }
          }
          when(decoded_addr(CSRs.tdata1)) {
            CL.assign(bp.control, wdata)

            val prevChain = if (i == 0) False else reg_bp(i - 1).control.chain
            val prevDMode = if (i == 0) False else reg_bp(i - 1).control.dmode
            val nextChain = if (i >= nBreakpoints - 1) True else reg_bp(i + 1).control.chain
            val nextDMode = if (i >= nBreakpoints - 1) True else reg_bp(i + 1).control.dmode
            val newBPC = CL.fromUInt(new BPControl, readModifyWriteCSR(io.rw.cmd, CL.asUInt(bp.control), io.rw.wdata))
            val dMode = newBPC.dmode && reg_debug && (prevDMode || !prevChain)
            bp.control.dmode := dMode
            when(dMode || (newBPC.action > 1)) { bp.control.action := newBPC.action }.otherwise { bp.control.action := 0 }
            bp.control.chain := newBPC.chain && !(prevChain || nextChain) && (dMode || !nextDMode)
          }
        }
      }
    }
    reg_mcontext.foreach { r => when(decoded_addr(CSRs.mcontext)) { r := wdata.resized } }
    reg_scontext.foreach { r => when(decoded_addr(CSRs.scontext)) { r := wdata.resized } }
    if (reg_pmp.nonEmpty) for (((pmp, next), i) <- (reg_pmp zip (reg_pmp.tail :+ reg_pmp.last)).zipWithIndex) {
      val cfgW = CL.width(pmp.cfg)
      require(xLen % cfgW == 0)
      when(decoded_addr(CSRs.pmpcfg0 + pmpCfgIndex(i)) && !pmp.cfgLocked) {
        val newCfg = CL.fromUInt(new PMPConfig(), wdata >> ((i * cfgW) % xLen))
        pmp.cfg := newCfg
        // disallow unreadable but writable PMPs
        pmp.cfg.w := newCfg.w && newCfg.r
        // can't select a=NA4 with coarse-grained PMPs
        if (log2Ceil(pmpGranularity) > PMP.lgAlign)
          pmp.cfg.a := Cat(newCfg.a(1), newCfg.a.orR).asUInt
      }
      when(decoded_addr(CSRs.pmpaddr0 + i) && !pmp.addrLocked(next)) {
        pmp.addr := wdata.resized
      }
    }
    def writeCustomCSR(cio: CustomCSRIO, csr: CustomCSR, reg: UInt) = {
      val mask = U(csr.mask, xLen bits)
      when(decoded_addr(csr.id)) {
        reg := (wdata & mask) | (reg & ~mask)
        cio.wen := True
      }
    }
    for (((cio, csr), reg) <- io.customCSRs.zip(customCSRs).zip(reg_custom)) {
      writeCustomCSR(cio, csr, reg)
    }
    for (((cio, csr), reg) <- io.roccCSRs.zip(roccCSRs).zip(reg_rocc)) {
      writeCustomCSR(cio, csr, reg)
    }
  }

  def setCustomCSR(cio: CustomCSRIO, csr: CustomCSR, reg: UInt) = {
    val mask = U(csr.mask, xLen bits)
    when(cio.set) {
      reg := (cio.sdata & mask) | (reg & ~mask)
    }
  }
  for (((cio, csr), reg) <- io.customCSRs.zip(customCSRs).zip(reg_custom)) {
    setCustomCSR(cio, csr, reg)
  }
  for (((cio, csr), reg) <- io.roccCSRs.zip(roccCSRs).zip(reg_rocc)) {
    setCustomCSR(cio, csr, reg)
  }

  when(resetActive) {
    reg_satp.mode := 0
    reg_vsatp.mode := 0
    reg_hgatp.mode := 0
  }
  if (!usingVM) {
    reg_satp.mode := 0
    reg_satp.ppn := 0
    reg_satp.asid := 0
  }
  if (!usingHypervisor) {
    reg_vsatp.mode := 0
    reg_vsatp.ppn := 0
    reg_vsatp.asid := 0
    reg_hgatp.mode := 0
    reg_hgatp.ppn := 0
    reg_hgatp.asid := 0
  }
  if (!(asIdBits > 0)) {
    reg_satp.asid := 0
    reg_vsatp.asid := 0
  }
  if (!(vmIdBits > 0)) {
    reg_hgatp.asid := 0
  }
  reg_vsstatus.xs := (if (usingRoCC) 3 else 0)

  if (nBreakpoints <= 1) reg_tselect := 0
  for (bpc <- reg_bp map { _.control }) {
    bpc.ttype := bpc.tType
    bpc.maskmax := bpc.maskMax
    bpc.reserved := 0
    bpc.zero := 0
    bpc.h := False
    if (!usingSupervisor) bpc.s := False
    if (!usingUser) bpc.u := False
    if (!usingSupervisor && !usingUser) bpc.m := True
    when(resetActive) {
      bpc.action := 0
      bpc.dmode := False
      bpc.chain := False
      bpc.r := False
      bpc.w := False
      bpc.x := False
    }
  }
  for (bpx <- reg_bp map { _.textra }) {
    if (coreParams.mcontextWidth == 0) bpx.mselect := False
    if (coreParams.scontextWidth == 0) bpx.sselect := False
  }
  for (bp <- reg_bp drop nBreakpoints)
    bp := bp.getZero
  for (pmp <- reg_pmp) {
    pmp.cfg.res := 0
    when(resetActive) { pmp.reset() }
  }

  for (((t, insn), i) <- (io.trace zip io.inst).zipWithIndex) {
    t.exception := io.retire >= i && exception
    t.valid := io.retire > i || t.exception
    t.insn := insn
    t.iaddr := io.pc.resized
    t.priv := Cat(reg_debug, reg_mstatus.prv).asUInt
    t.cause := cause
    t.interrupt := cause(xLen - 1)
    t.tval := io.tval.resized
    if (t.wdata != null) t.wdata.assignDontCare()
  }

  def chooseInterrupt(masksIn: Seq[UInt]): (Bool, UInt) = {
    val nonstandard = supported_interrupts.getWidth - 1 to 12 by -1
    // MEI, MSI, MTI,  SEI, SSI, STI, VSEI, VSSI, VSTI, UEI, USI, UTI
    val standard = Seq(11, 3, 7, 9, 1, 5, 10, 2, 6, 8, 0, 4)
    val priority = nonstandard ++ standard
    val masks = masksIn.reverse
    val any = masks.flatMap(m => priority.filter(_ < m.getWidth).map(i => m(i))).reduce(_ || _)
    val idxW = log2UpChisel(priority.max + 1)
    val which = PriorityMux(masks.flatMap(m => priority.filter(_ < m.getWidth).map(i => (m(i), U(i, idxW bits)))))
    (any, which)
  }

  def readModifyWriteCSR(cmd: UInt, rdata: UInt, wdata: UInt): UInt = {
    (Mux(cmd(1), rdata, U(0, rdata.getWidth bits)) | wdata) & ~Mux(cmd(1 downto 0).andR, wdata, U(0, wdata.getWidth bits))
  }

  def legalizePrivilege(priv: UInt): UInt =
    if (usingSupervisor) Mux(priv === PRV.H, U(PRV.U, 2 bits), priv)
    else if (usingUser) Fill(2, priv(0))
    else U(PRV.M, 2 bits)

  def trimPrivilege(priv: UInt): UInt =
    if (usingSupervisor) priv
    else legalizePrivilege(priv)

  def writeCounter(lo: Int, ctr: WideCounter, wdata: UInt): Unit = {
    if (xLen == 32) {
      val hi = lo + CSRs.mcycleh - CSRs.mcycle
      when(decoded_addr(lo)) { ctr := Cat(ctr.value(ctr.getWidth - 1 downto 32), wdata).asUInt }
      when(decoded_addr(hi)) { ctr := Cat(wdata(ctr.getWidth - 33 downto 0), ctr.value(31 downto 0)).asUInt }
    } else {
      when(decoded_addr(lo)) { ctr := wdata(ctr.getWidth - 1 downto 0) }
    }
  }
  def formEPC(x: UInt): UInt = ~(~x | U(if (usingCompressed) 1 else 3, x.getWidth bits))
  def readEPC(x: UInt): UInt = ~(~x | Mux(reg_misa('c' - 'a'), U(1, x.getWidth bits), U(3, x.getWidth bits)))
  def formTVec(x: UInt): UInt = x andNot Mux(x(0), U((((BigInt(1) << mtvecInterruptAlign) - 1) << mtvecBaseAlign) | 2, x.getWidth bits), U(2, x.getWidth bits))
  def isaStringToMask(s: String): Int = s.map(x => 1 << (x - 'A')).foldLeft(0)(_ | _)
  def formFS(fs: UInt): UInt = if (coreParams.haveFSDirty) fs else Fill(2, fs.orR)
  def formVS(vs: UInt): UInt = if (usingVector) vs else U(0, 2 bits)
}
