// See LICENSE.SiFive for license details.

package rocketchip.rocket

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.tile.{CoreBundle, HasCoreParameters}
import rocketchip.util._

class BPControl(implicit p: Parameters) extends CoreBundle()(p) {
  val ttype = UInt(4 bits)
  val dmode = Bool()
  val maskmax = UInt(6 bits)
  val reserved = UInt(xLen - (if (coreParams.useBPWatch) 26 else 24) bits)
  val action = UInt((if (coreParams.useBPWatch) 3 else 1) bits)
  val chain = Bool()
  val zero = UInt(2 bits)
  val tmatch = UInt(2 bits)
  val m = Bool()
  val h = Bool()
  val s = Bool()
  val u = Bool()
  val x = Bool()
  val w = Bool()
  val r = Bool()

  def tType = 2
  def maskMax = 4
  def enabled(mstatus: MStatus): Bool = !mstatus.debug && Cat(m, h, s, u).asUInt(mstatus.prv)
}

class TExtra(implicit p: Parameters) extends CoreBundle()(p) {
  def mvalueBits: Int = if (xLen == 32) coreParams.mcontextWidth min 6 else coreParams.mcontextWidth min 13
  def svalueBits: Int = if (xLen == 32) coreParams.scontextWidth min 16 else coreParams.scontextWidth min 34
  def mselectPos: Int = if (xLen == 32) 25 else 50
  def mvaluePos : Int = mselectPos + 1
  def sselectPos: Int = 0
  def svaluePos : Int = 2

  val mvalue  = UInt(mvalueBits bits)
  val mselect = Bool()
  val pad2    = UInt(mselectPos - svalueBits - 2 bits)
  val svalue  = UInt(svalueBits bits)
  val pad1    = UInt(1 bits)
  val sselect = Bool()
}

class BP(implicit p: Parameters) extends CoreBundle()(p) {
  val control = new BPControl
  val address = UInt(vaddrBits bits)
  val textra  = new TExtra

  def contextMatch(mcontext: UInt, scontext: UInt): Bool =
    (if (coreParams.mcontextWidth > 0) (!textra.mselect || (mcontext(textra.mvalueBits - 1 downto 0) === textra.mvalue)) else True) &&
    (if (coreParams.scontextWidth > 0) (!textra.sselect || (scontext(textra.svalueBits - 1 downto 0) === textra.svalue)) else True)

  def mask(dummy: Int = 0): UInt =
    (0 until control.maskMax - 1).scanLeft(control.tmatch(0))((m, i) => m && address(i)).asUInt

  def pow2AddressMatch(x: UInt): Bool =
    (~x | mask().resize(x.getWidth)) === (~address | mask().resize(address.getWidth)).resize(x.getWidth)

  def rangeAddressMatch(x: UInt): Bool =
    (x >= address) ^ control.tmatch(0)

  def addressMatch(x: UInt): Bool =
    Mux(control.tmatch(1), rangeAddressMatch(x), pow2AddressMatch(x))
}

class BPWatch(val n: Int) extends Bundle {
  val valid = Vec.fill(n)(Bool())
  val rvalid = Vec.fill(n)(Bool())
  val wvalid = Vec.fill(n)(Bool())
  val ivalid = Vec.fill(n)(Bool())
  val action = UInt(3 bits)
}

class BreakpointUnit(n: Int)(implicit val p: Parameters) extends Component with HasCoreParameters {
  val io = new Bundle {
    val status = in(new MStatus())
    val bp = in(Vec.fill(n)(new BP))
    val pc = in UInt(vaddrBits bits)
    val ea = in UInt(vaddrBits bits)
    val mcontext = in UInt(coreParams.mcontextWidth bits)
    val scontext = in UInt(coreParams.scontextWidth bits)
    val xcpt_if  = out Bool()
    val xcpt_ld  = out Bool()
    val xcpt_st  = out Bool()
    val debug_if = out Bool()
    val debug_ld = out Bool()
    val debug_st = out Bool()
    val bpwatch  = out(Vec.fill(n)(new BPWatch(1)))
  }

  io.xcpt_if := False
  io.xcpt_ld := False
  io.xcpt_st := False
  io.debug_if := False
  io.debug_ld := False
  io.debug_st := False

  (io.bpwatch zip io.bp).foldLeft((True, True, True)) { case ((ri, wi, xi), (bpw, bp)) =>
    val en = bp.control.enabled(io.status)
    val cx = bp.contextMatch(io.mcontext, io.scontext)
    val r = en && bp.control.r && bp.addressMatch(io.ea) && cx
    val w = en && bp.control.w && bp.addressMatch(io.ea) && cx
    val x = en && bp.control.x && bp.addressMatch(io.pc) && cx
    val end = !bp.control.chain
    val action = bp.control.action

    bpw.action := action.resized
    bpw.valid(0) := False
    bpw.rvalid(0) := False
    bpw.wvalid(0) := False
    bpw.ivalid(0) := False

    when(end && r && ri) { io.xcpt_ld := (action === 0); io.debug_ld := (action === 1); bpw.valid(0) := True; bpw.rvalid(0) := True }
    when(end && w && wi) { io.xcpt_st := (action === 0); io.debug_st := (action === 1); bpw.valid(0) := True; bpw.wvalid(0) := True }
    when(end && x && xi) { io.xcpt_if := (action === 0); io.debug_if := (action === 1); bpw.valid(0) := True; bpw.ivalid(0) := True }

    (end || r, end || w, end || x)
  }
}
