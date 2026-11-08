//******************************************************************************
// Copyright (c) 2015 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Utility Functions
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.util

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline.{Builder, Node, StageLink}
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common.{MicroOp, HasBoomUOP, BoomModule, HasBoomCoreParameters}
import boom.exu.BrUpdateInfo

/**
 * Object to check if MicroOp was killed due to a branch mispredict.
 * Uses "Fast" branch masks
 */
object IsKilledByBranch {
  def apply(brupdate: BrUpdateInfo, flush: Bool, uop: MicroOp): Bool =
    apply(brupdate, flush, uop.br_mask)

  def apply(brupdate: BrUpdateInfo, flush: Bool, uop_mask: UInt): Bool =
    maskMatch(brupdate.b1.mispredict_mask, uop_mask) || flush

  def apply(brupdate: BrUpdateInfo, flush: Bool, bundle: HasBoomUOP): Bool =
    apply(brupdate, flush, bundle.uop)

  def apply[T <: HasBoomUOP](brupdate: BrUpdateInfo, flush: Bool, bundle: Flow[T]): Bool =
    apply(brupdate, flush, bundle.payload)
}

/**
 * Object to return new MicroOp with a new BR mask given a MicroOp mask
 * and old BR mask.
 */
object GetNewUopAndBrMask {
  def apply(uop: MicroOp, brupdate: BrUpdateInfo)(implicit p: Parameters): MicroOp = {
    val newuop = CombInit(uop)
    newuop.br_mask := uop.br_mask & ~brupdate.b1.resolve_mask
    newuop
  }
}

/**
 * Object to return a BR mask given a MicroOp mask and old BR mask.
 */
object GetNewBrMask {
  def apply(brupdate: BrUpdateInfo, uop: MicroOp): UInt =
    uop.br_mask & ~brupdate.b1.resolve_mask

  def apply(brupdate: BrUpdateInfo, br_mask: UInt): UInt =
    br_mask & ~brupdate.b1.resolve_mask
}

object UpdateBrMask {
  def apply(brupdate: BrUpdateInfo, uop: MicroOp): MicroOp = {
    val out = CombInit(uop)
    out.br_mask := GetNewBrMask(brupdate, uop)
    out
  }
  def apply[T <: HasBoomUOP](brupdate: BrUpdateInfo, bundle: T): T = {
    val out = CombInit(bundle)
    out.uop.br_mask := GetNewBrMask(brupdate, bundle.uop.br_mask)
    out
  }
  def apply[T <: HasBoomUOP](brupdate: BrUpdateInfo, flush: Bool, bundle: Flow[T]): Flow[T] = {
    val out = CombInit(bundle)
    out.payload.uop.br_mask := GetNewBrMask(brupdate, bundle.payload.uop.br_mask)
    out.valid := bundle.valid && !IsKilledByBranch(brupdate, flush, bundle.payload.uop.br_mask)
    out
  }
}

/**
 * Object to check if at least 1 bit matches in two masks
 */
object maskMatch {
  def apply(msk1: UInt, msk2: UInt): Bool = (msk1 & msk2) =/= 0
}

/**
 * Object to clear one bit in a mask given an index
 */
object clearMaskBit {
  def apply(msk: UInt, idx: UInt): UInt = (msk & ~(U(1, msk.getWidth bits) |<< idx))
}

/**
 * Object to increment an input value, wrapping it if
 * necessary.
 */
object WrapAdd {
  // "n" is the number of increments, so we wrap at n-1.
  def apply(value: UInt, amt: UInt, n: Int): UInt = {
    if (isPow2(n)) {
      (value + amt.resize(value.getWidth max amt.getWidth))(log2Ceil(n) - 1 downto 0)
    } else {
      val w = (value.getWidth max amt.getWidth) + 1
      val sum = value.resize(w) + amt.resize(w)
      Mux(sum >= n,
        sum - n,
        sum)
    }
  }
}

/**
 * Object to decrement an input value, wrapping it if
 * necessary.
 */
object WrapSub {
  // "n" is the number of increments, so we wrap to n-1.
  def apply(value: UInt, amt: Int, n: Int): UInt = {
    if (isPow2(n)) {
      (value - amt)(log2Ceil(n) - 1 downto 0)
    } else {
      Mux(value >= amt,
        value - amt,
        U(n - amt) + value)
    }
  }
}

/**
 * Object to increment an input value, wrapping it if
 * necessary.
 */
object WrapInc {
  // "n" is the number of increments, so we wrap at n-1.
  def apply(value: UInt, n: Int): UInt = {
    if (isPow2(n)) {
      if (n == 1) U(0, 0 bits) else (value + 1)(log2Ceil(n) - 1 downto 0)
    } else {
      val wrap = (value === (n - 1))
      Mux(wrap, U(0, value.getWidth bits), value + 1)
    }
  }
}

/**
  * Object for incrementing with a carry bit
  * the n is to denote the bit width without the carry bit
  */
object WrapIncWCarry {
  def apply(value: UInt, n: Int): UInt = {
    if (isPow2(n)) {
      (value.resize(value.getWidth max (log2Ceil(n) + 1)) + 1)(log2Ceil(n) downto 0)
    } else {
      assert(Bool(value.getWidth >= log2Ceil(n) + 1))
      val carry = value(value.getWidth - 1)
      val base = value(value.getWidth - 2 downto 0)
      val wrap = (base === (n - 1))
      Mux(wrap, Cat(~carry, U(0, value.getWidth - 1 bits)).asUInt, Cat(carry, base + 1).asUInt)
    }
  }
}

/**
 * Object to decrement an input value, wrapping it if
 * necessary.
 */
object WrapDec {
  // "n" is the number of increments, so we wrap at n-1.
  def apply(value: UInt, n: Int): UInt = {
    if (isPow2(n)) {
      if (n == 1) U(0, 0 bits) else (value - 1)(log2Ceil(n) - 1 downto 0)
    } else {
      val wrap = (value === 0)
      Mux(wrap, U(n - 1, value.getWidth bits), value - 1)
    }
  }
}

/**
 * Object to mask off lower bits of a PC to align to a "b"
 * Byte boundary.
 */
object AlignPCToBoundary {
  def apply(pc: UInt, b: Int): UInt = {
    // Invert for scenario where pc longer than b
    //   (which would clear all bits above size(b)).
    ~(~pc | U(b - 1, pc.getWidth bits))
  }
}

/**
 * Object to rotate a signal left by one
 */
object RotateL1 {
  def apply(signal: UInt): UInt = {
    val w = signal.getWidth
    Cat(signal(w - 2 downto 0), signal(w - 1)).asUInt
  }
}

/**
 * Object to sext a value to a particular length.
 */
object Sext {
  def apply(x: UInt, length: Int): UInt = {
    if (x.getWidth == length) x
    else Cat(Fill(length - x.getWidth, x(x.getWidth - 1)), x).asUInt
  }
}

/**
 * Object to translate from BOOM's special "packed immediate" to a 32b signed immediate
 * Asking for U-type gives it shifted up 12 bits.
 */
object ImmGen {
  import boom.common.{LONGEST_IMM_SZ, IS_B, IS_I, IS_J, IS_S, IS_U, IS_N}
  def apply(i: UInt, isel: UInt): UInt = {
    val ip = Mux(isel === IS_N.U, U(0, LONGEST_IMM_SZ bits), i)

    val sign = ip(LONGEST_IMM_SZ - 1)
    // Each field is sign-extended to its slot width, as Chisel's Cat of SInts would.
    val i30_20 = Mux(isel === IS_U.U, ip(18 downto 8), Fill(11, sign))
    val i19_12 = Mux(isel === IS_U.U || isel === IS_J.U, ip(7 downto 0), Fill(8, sign))
    val i11    = Mux(isel === IS_U.U, False,
                 Mux(isel === IS_J.U || isel === IS_B.U, ip(8), sign))
    val i10_5  = Mux(isel === IS_U.U, U(0, 5 bits), ip(18 downto 14))
    val i4_1   = Mux(isel === IS_U.U, U(0, 5 bits), ip(13 downto 9))
    val i0     = Mux(isel === IS_S.U || isel === IS_I.U, ip(8), False)

    Cat(sign, i30_20, i19_12, i11, i10_5, i4_1, i0).asUInt
  }
}

/**
 * Object to return the lowest bit position after the head.
 */
object AgePriorityEncoder {
  def apply(in: Seq[Bool], head: UInt): UInt = {
    val n = in.size
    val width = log2Ceil(in.size)
    val n_padded = 1 << width
    val temp_vec = (0 until n_padded).map(i => if (i < n) in(i) && U(i) >= head else False) ++ in
    val idx = PriorityEncoder(temp_vec)
    idx(width - 1 downto 0) //discard msb
  }
}

/**
  * Object to determine whether queue
  * index i0 is older than index i1.
 */
object IsOlder {
  def apply(i0: UInt, i1: UInt, head: UInt): Bool = ((i0 < i1) ^ (i0 < head) ^ (i1 < head))
}

object IsYoungerMask {
  def apply(i: UInt, head: UInt, n: Integer): UInt = {
    val hi_mask = ~MaskLower(UIntToOH(i, 1 << i.getWidth)(n - 1 downto 0))
    val lo_mask = ~MaskUpper(UIntToOH(head, 1 << head.getWidth)(n - 1 downto 0))
    Mux(i < head, hi_mask & lo_mask, hi_mask | lo_mask)(n - 1 downto 0)
  }
}

/**
 * Set all bits at or below the highest order '1'.
 */
object MaskLower {
  def apply(in: UInt): UInt = {
    val n = in.getWidth
    (0 until n).map(i => in >> i).map(_.resize(n)).reduce(_ | _)
  }
}

/**
 * Set all bits at or above the lowest order '1'.
 */
object MaskUpper {
  def apply(in: UInt): UInt = {
    val n = in.getWidth
    (0 until n).map(i => (in << i)(n - 1 downto 0)).reduce(_ | _)
  }
}

/**
 * Transpose a matrix of Vecs.
 */
object Transpose {
  def apply[T <: Data](in: Seq[Seq[T]]): Seq[Seq[T]] = {
    val n = in(0).size
    (0 until n).map(i => in.map(row => row(i)))
  }
}

/**
  * N-wide one-hot priority encoder.
 */
object SelectFirstN {
  def apply(in: UInt, n: Int): Vec[UInt] = {
    val sels = Vec.fill(n)(UInt(in.getWidth bits))
    var mask = in

    for (i <- 0 until n) {
      sels(i) := PriorityEncoderOH(mask)
      mask = mask & ~sels(i)
    }

    sels
  }
}

/**
 * Connect the first k of n valid input interfaces to k output interfaces.
 */
class Compactor[T <: Data](n: Int, k: Int, gen: HardType[T]) extends Component {
  require(n >= k)

  val io = new Bundle {
    val in  = Vec.fill(n)(slave(Stream(gen())))
    val out = Vec.fill(k)(master(Stream(gen())))
  }

  if (n == k) {
    (io.out zip io.in).foreach { case (o, i) => o << i }
  } else {
    val counts = io.in.map(_.valid).scanLeft(U(1, k bits))((c, e) => Mux(e, (c << 1)(k - 1 downto 0), c))
    val sels = Transpose(counts.map(c => c.asBools.toSeq)) map (col =>
      (col zip io.in.map(_.valid)) map { case (c, v) => c && v })
    val in_readys = counts map (row => (row.asBools.toSeq zip io.out.map(_.ready)) map { case (c, r) => c && r } reduce (_ || _))
    val out_valids = sels map (col => col.reduce(_ || _))
    val out_data = sels map (s => Mux1H(s, io.in.map(_.payload)))

    in_readys zip io.in foreach { case (r, i) => i.ready := r }
    out_valids zip out_data zip io.out foreach { case ((v, d), o) => o.valid := v; o.payload := d }
  }
}

/**
 * Create a queue that can be killed with a branch kill signal.
 * Assumption: enq.valid only high if not killed by branch (so don't check IsKilled on io.enq).
 */
class BranchKillableQueue[T <: Data with HasBoomUOP](gen: HardType[T], entries: Int, flush_fn: MicroOp => Bool = u => True, fastDeq: Boolean = false)
  (implicit p: Parameters)
  extends BoomModule()(p)
  with HasBoomCoreParameters
{
  val io = new Bundle {
    val enq     = slave(Stream(gen()))
    val deq     = master(Stream(gen()))

    val brupdate  = in(new BrUpdateInfo())
    val flush   = in Bool()

    val empty   = out Bool()
    val count   = out UInt(log2Ceil(entries) bits)
  }

  if (fastDeq && entries > 1) {
    // Pipeline dequeue selection so the mux gets an entire cycle
    val main = new BranchKillableQueue(gen, entries - 1, flush_fn, false)
    val out_reg = Reg(gen())
    val out_valid = Reg(Bool()) init False
    val out_uop = Reg(new MicroOp)

    main.io.enq << io.enq
    main.io.brupdate := io.brupdate
    main.io.flush := io.flush
    io.empty := main.io.empty && !out_valid
    io.count := (main.io.count.resize(log2Ceil(entries)) + U(out_valid).resize(log2Ceil(entries)))

    io.deq.valid := out_valid
    io.deq.payload := out_reg
    io.deq.payload.uop := out_uop

    out_uop := UpdateBrMask(io.brupdate, out_uop)
    out_valid := out_valid && !IsKilledByBranch(io.brupdate, False, out_uop) && !(io.flush && flush_fn(out_uop))

    main.io.deq.ready := False
    when(io.deq.fire || !out_valid) {
      out_valid := main.io.deq.valid && !IsKilledByBranch(io.brupdate, False, main.io.deq.payload.uop) && !(io.flush && flush_fn(main.io.deq.payload.uop))
      out_reg := main.io.deq.payload
      out_uop := UpdateBrMask(io.brupdate, main.io.deq.payload.uop)
      main.io.deq.ready := True
    }

  } else {
    val ram     = Mem(gen, entries)
    val valids  = Vec.fill(entries)(Reg(Bool()) init False)
    val uops    = Reg(Vec.fill(entries)(new MicroOp))

    val ptrW = log2Up(entries)
    val enq_ptr = Counter(entries)
    val deq_ptr = Counter(entries)
    val maybe_full = Reg(Bool()) init False

    val ptr_match = enq_ptr.value === deq_ptr.value
    io.empty := ptr_match && !maybe_full
    val full = ptr_match && maybe_full
    val do_enq = CombInit(io.enq.fire && !IsKilledByBranch(io.brupdate, False, io.enq.payload.uop) && !(io.flush && flush_fn(io.enq.payload.uop)))
    val do_deq = CombInit((io.deq.ready || !valids(deq_ptr.value)) && !io.empty)

    for (i <- 0 until entries) {
      val mask = uops(i).br_mask
      val uop  = uops(i)
      valids(i) := valids(i) && !IsKilledByBranch(io.brupdate, False, mask) && !(io.flush && flush_fn(uop))
      when(valids(i)) {
        uops(i).br_mask := GetNewBrMask(io.brupdate, mask)
      }
    }

    ram.write(enq_ptr.value, io.enq.payload, do_enq)
    when(do_enq) {
      valids(enq_ptr.value)       := True
      uops(enq_ptr.value)         := io.enq.payload.uop
      uops(enq_ptr.value).br_mask := GetNewBrMask(io.brupdate, io.enq.payload.uop)
      enq_ptr.increment()
    }

    when(do_deq) {
      valids(deq_ptr.value) := False
      deq_ptr.increment()
    }

    when(do_enq =/= do_deq) {
      maybe_full := do_enq
    }

    io.enq.ready := !full

    val out = cloneOf(io.enq.payload)
    out             := ram.readAsync(deq_ptr.value)
    out.uop         := uops(deq_ptr.value)
    io.deq.valid    := !io.empty && valids(deq_ptr.value)
    io.deq.payload  := out

    val cw = log2Ceil(entries)
    val ptr_diff = (enq_ptr.value - deq_ptr.value)
    if (isPow2(entries)) {
      io.count := Cat(maybe_full && ptr_match, ptr_diff).asUInt.resized
    }
    else {
      io.count := Mux(ptr_match,
        Mux(maybe_full,
          U(entries).resize(cw), U(0, cw bits)),
        Mux(deq_ptr.value > enq_ptr.value,
          (U(entries) + ptr_diff).resize(cw), ptr_diff.resize(cw)))
    }
  }
}

class BranchKillablePipeline[T <: Data with HasBoomUOP](gen: HardType[T], stages: Int)
  (implicit p: Parameters)
  extends BoomModule()(p)
  with HasBoomCoreParameters
{
  val io = new Bundle {
    val req = slave(Flow(gen()))
    val flush = in Bool()
    val brupdate = in(new BrUpdateInfo)
    val resp = out(Vec.fill(stages)(Flow(gen())))
  }
  require(stages > 0)
  val nodes = Vector.fill(stages + 1)(Node())
  val request = nodes.head.insert(io.req.payload)
  nodes.head.valid := io.req.valid
  val links = (0 until stages).flatMap { i =>
    val boundary = Node()
    Vector(BranchPipeline.boundary(nodes(i), boundary, request, io.brupdate, io.flush),
      StageLink(boundary, nodes(i + 1)))
  }
  for (i <- 0 until stages) {
    io.resp(i).valid := nodes(i + 1).isValid
    io.resp(i).payload := nodes(i + 1)(request)
  }
  Builder(links)
  // The original uses an explicit synchronous reset assignment, not init(False).
  nodes.tail.foreach { node =>
    node.valid.removeInitAssignments()
    when(ClockDomain.current.isResetActive) { node.valid := False }
  }
}

object BoomCoreStringPrefix {
  /**
  * Add prefix to BOOM strings (currently only adds the hartId)
  */
  def apply(strs: String*)(implicit p: Parameters): String = {
    val prefix = "[C" + s"${p.tile.tileId}" + "] "
    strs.map(str => prefix + str + "\n").mkString("")
  }
}
