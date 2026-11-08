package rocketchip.util

import spinal.core._
import spinal.lib._

// Ports of the chisel3.util / rocket-chip util blocks whose exact cycle
// behaviour BOOM depends on (Spinal's StreamFifo/StreamArbiter differ in
// flow/pipe options, chosen-index semantics and round-robin order).

/** chisel3.util.Queue (Mem-based, combinational read). */
class Queue[T <: Data](gen: HardType[T], val entries: Int, pipe: Boolean = false, flow: Boolean = false, hasFlush: Boolean = false) extends Component {
  require(entries > 0)
  val io = new Bundle {
    val enq = slave(Stream(gen()))
    val deq = master(Stream(gen()))
    val count = out UInt(log2Ceil(entries + 1) bits)
    val flush = if (hasFlush) in Bool() else null
  }

  val ptrW = log2Ceil(entries)
  val ram = Mem(gen, entries)
  val enq_ptr = if (entries > 1) Reg(UInt(ptrW bits)) init 0 else U(0, 0 bits)
  val deq_ptr = if (entries > 1) Reg(UInt(ptrW bits)) init 0 else U(0, 0 bits)
  private def inc(r: UInt): Unit = if (entries > 1) {
    if (isPow2(entries)) r := r + 1 else r := Mux(r === entries - 1, U(0, ptrW bits), r + 1)
  }
  val maybe_full = Reg(Bool()) init False
  val ptr_match = enq_ptr === deq_ptr
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full
  val do_enq = CombInit(io.enq.fire)
  val do_deq = CombInit(io.deq.fire)
  val flush = if (hasFlush) io.flush else False

  ram.write(enq_ptr, io.enq.payload, do_enq)
  when(do_enq) { inc(enq_ptr) }
  when(do_deq) { inc(deq_ptr) }
  when(do_enq =/= do_deq) { maybe_full := do_enq }
  when(flush) {
    if (entries > 1) { enq_ptr := 0; deq_ptr := 0 }
    maybe_full := False
  }

  io.deq.valid := !empty
  io.enq.ready := !full
  io.deq.payload := ram.readAsync(deq_ptr)

  if (flow) {
    when(io.enq.valid) { io.deq.valid := True }
    when(empty) {
      io.deq.payload := io.enq.payload
      do_deq := False
      when(io.deq.ready) { do_enq := False }
    }
  }
  if (pipe) {
    when(io.deq.ready) { io.enq.ready := True }
  }

  val cw = log2Ceil(entries + 1)
  val ptr_diff = (enq_ptr - deq_ptr).resize(cw)
  if (isPow2(entries)) {
    io.count := Mux(maybe_full && ptr_match, U(entries, cw bits), U(0, cw bits)) | ptr_diff
  } else {
    io.count := Mux(ptr_match,
      Mux(maybe_full, U(entries, cw bits), U(0, cw bits)),
      Mux(deq_ptr > enq_ptr, U(entries, cw bits) + ptr_diff, ptr_diff))
  }
}

object Queue {
  def apply[T <: Data](enq: Stream[T], entries: Int = 2, pipe: Boolean = false, flow: Boolean = false): Stream[T] = {
    val q = new Queue(HardType(enq.payload), entries, pipe, flow)
    q.io.enq << enq
    q.io.deq
  }
}

private object ArbiterCtrl {
  def apply(request: Seq[Bool]): Seq[Bool] = request.length match {
    case 0 => Seq()
    case 1 => Seq(True)
    case _ => True +: request.tail.init.scanLeft(request.head)(_ || _).map(!_)
  }
}

/** chisel3.util.Arbiter: fixed priority, lowest index first. */
class Arbiter[T <: Data](gen: HardType[T], val n: Int) extends Component {
  val io = new Bundle {
    val in = Vec.fill(n)(slave(Stream(gen())))
    val out = master(Stream(gen()))
    val chosen = OUT UInt(log2Ceil(n) bits)
  }

  io.chosen := n - 1
  io.out.payload := io.in(n - 1).payload
  for (i <- n - 2 to 0 by -1) {
    when(io.in(i).valid) {
      io.chosen := i
      io.out.payload := io.in(i).payload
    }
  }

  val grant = ArbiterCtrl(io.in.map(_.valid))
  for ((in, g) <- io.in.zip(grant))
    in.ready := g && io.out.ready
  io.out.valid := !grant.last || io.in.last.valid
}

/** chisel3.util.RRArbiter (LockingRRArbiter with count = 1). */
class RRArbiter[T <: Data](gen: HardType[T], val n: Int, initLastGrant: Boolean = false) extends Component {
  val io = new Bundle {
    val in = Vec.fill(n)(slave(Stream(gen())))
    val out = master(Stream(gen()))
    val chosen = OUT UInt(log2Ceil(n) bits)
  }

  val choice = UInt(log2Ceil(n) bits)
  io.chosen := choice
  io.out.valid := io.in(io.chosen).valid
  io.out.payload := io.in(io.chosen).payload

  val lastGrant = if (initLastGrant) RegNextWhen(io.chosen, io.out.fire) init 0 else RegNextWhen(io.chosen, io.out.fire)
  val grantMask = (0 until n).map(i => U(i) > lastGrant)
  val validMask = io.in.zip(grantMask).map { case (in, g) => in.valid && g }
  val grant: Seq[Bool] = {
    val ctrl = ArbiterCtrl((0 until n).map(i => validMask(i)) ++ io.in.map(_.valid))
    (0 until n).map(i => ctrl(i) && grantMask(i) || ctrl(i + n))
  }

  choice := n - 1
  for (i <- n - 2 to 0 by -1)
    when(io.in(i).valid) { choice := i }
  for (i <- n - 1 to 1 by -1)
    when(validMask(i)) { choice := i }

  for ((in, g) <- io.in.zip(grant))
    in.ready := g && io.out.ready
}

/** chisel3.util.random.LFSR: max-period Fibonacci LFSR, XOR, seed 1. */
object LFSR {
  val tapsMaxPeriod: Map[Int, Set[Int]] = Map(
    2 -> Set(2, 1), 3 -> Set(3, 2), 4 -> Set(4, 3), 5 -> Set(5, 3), 6 -> Set(6, 5), 7 -> Set(7, 6),
    8 -> Set(8, 6, 5, 4), 9 -> Set(9, 5), 10 -> Set(10, 7), 11 -> Set(11, 9), 12 -> Set(12, 11, 8, 6),
    13 -> Set(13, 12, 10, 9), 14 -> Set(14, 13, 11, 9), 15 -> Set(15, 14), 16 -> Set(16, 14, 13, 11),
    17 -> Set(17, 14), 18 -> Set(18, 11), 19 -> Set(19, 18, 17, 14), 20 -> Set(20, 17), 21 -> Set(21, 19),
    22 -> Set(22, 21), 23 -> Set(23, 18), 24 -> Set(24, 23, 21, 20), 25 -> Set(25, 22), 26 -> Set(26, 25, 24, 20),
    27 -> Set(27, 26, 25, 22), 28 -> Set(28, 25), 29 -> Set(29, 27), 30 -> Set(30, 29, 26, 24),
    31 -> Set(31, 28), 32 -> Set(32, 30, 26, 25))

  def apply(width: Int, increment: Bool = True): UInt = {
    val taps = tapsMaxPeriod.getOrElse(width, throw new IllegalArgumentException(s"No LFSR taps for width $width"))
    val state = Vec.fill(width)(Reg(Bool()))
    state.zipWithIndex.foreach { case (s, i) => s.init(Bool(i == 0)) }
    val next = taps.toSeq.sorted.map(i => state(i - 1)).reduce(_ ^ _) +: state.dropRight(1)
    when(increment) { state.zip(next).foreach { case (s, n) => s := n } }
    state.asBits.asUInt
  }
}

/** rocket-chip's LFSR16 (used by RandomReplacement). */
object LFSR16 {
  def apply(increment: Bool = True): UInt = {
    val width = 16
    val lfsr = Reg(UInt(width bits)) init 1
    when(increment) { lfsr := Cat(lfsr(0) ^ lfsr(2) ^ lfsr(3) ^ lfsr(5), lfsr(width - 1 downto 1)).asUInt }
    lfsr
  }
}

/** rocket-chip util.Random */
object Random {
  def apply(mod: Int, random: UInt): UInt = {
    if (isPow2(mod)) { if (mod == 1) U(0, 1 bits) else random(log2Ceil(mod) - 1 downto 0) }
    else PriorityEncoder(partition(apply(1 << log2UpChisel(mod * 8), random), mod))
  }
  private def partition(value: UInt, slices: Int): Seq[Bool] =
    Seq.tabulate(slices)(i => value < U(((i + 1) << value.getWidth) / slices))
}

abstract class ReplacementPolicy {
  def nBits: Int
  def perSet: Boolean
  def way: UInt
  def miss: Unit
  def hit: Unit
  def access(touch_way: UInt): Unit
  def access(touch_ways: Seq[Flow[UInt]]): Unit
  def state_read: UInt
  def get_next_state(state: UInt, touch_way: UInt): UInt
  def get_next_state(state: UInt, touch_ways: Seq[Flow[UInt]]): UInt = {
    touch_ways.foldLeft(state)((prev, touch_way) => Mux(touch_way.valid, get_next_state(prev, touch_way.payload), prev))
  }
  def get_replace_way(state: UInt): UInt
}

class RandomReplacement(n_ways: Int) extends ReplacementPolicy {
  private val replace = Bool()
  replace := False
  def nBits = 16
  def perSet = false
  private val lfsr = LFSR16(replace)
  def state_read = CombInit(lfsr)

  def way = Random(n_ways, lfsr)
  def miss = replace := True
  def hit = {}
  def access(touch_way: UInt) = {}
  def access(touch_ways: Seq[Flow[UInt]]) = {}
  def get_next_state(state: UInt, touch_way: UInt) = U(0)
  def get_replace_way(state: UInt) = way
}

class PseudoLRU(n_ways: Int) extends ReplacementPolicy {
  // Pseudo-LRU tree algorithm: https://en.wikipedia.org/wiki/Pseudo-LRU#Tree-PLRU
  def nBits = n_ways - 1
  def perSet = true
  private val state_reg = if (nBits == 0) Reg(UInt(0 bits)) else Reg(UInt(nBits bits)) init 0
  def state_read = CombInit(state_reg)

  def access(touch_way: UInt): Unit = {
    state_reg := get_next_state(state_reg, touch_way)
  }
  def access(touch_ways: Seq[Flow[UInt]]): Unit = {
    when(touch_ways.map(_.valid).orR) {
      state_reg := get_next_state(state_reg, touch_ways)
    }
  }

  def get_next_state(state: UInt, touch_way: UInt, tree_nways: Int): UInt = {
    require(state.getWidth == (tree_nways - 1), s"wrong state bits width ${state.getWidth} for $tree_nways ways")
    require(touch_way.getWidth == (log2Ceil(tree_nways) max 1), s"wrong encoded way width ${touch_way.getWidth} for $tree_nways ways")

    if (tree_nways > 2) {
      // we are at a branching node in the tree, so recurse
      val right_nways: Int = 1 << (log2Ceil(tree_nways) - 1)  // number of ways in the right sub-tree
      val left_nways: Int = tree_nways - right_nways          // number of ways in the left sub-tree
      val set_left_older = !touch_way(log2Ceil(tree_nways) - 1)
      val left_subtree_state = state(tree_nways - 3 downto right_nways - 1)
      val right_subtree_state = state(right_nways - 2 downto 0)

      if (left_nways > 1) {
        Cat(set_left_older,
          Mux(set_left_older,
            left_subtree_state,
            get_next_state(left_subtree_state, touch_way(log2Ceil(left_nways) - 1 downto 0), left_nways)),
          Mux(set_left_older,
            get_next_state(right_subtree_state, touch_way(log2Ceil(right_nways) - 1 downto 0), right_nways),
            right_subtree_state)).asUInt
      } else {
        Cat(set_left_older,
          Mux(set_left_older,
            get_next_state(right_subtree_state, touch_way(log2Ceil(right_nways) - 1 downto 0), right_nways),
            right_subtree_state)).asUInt
      }
    } else if (tree_nways == 2) {
      (!touch_way(0)).asUInt
    } else {
      U(0, 1 bits)
    }
  }

  def get_next_state(state: UInt, touch_way: UInt): UInt = {
    val touch_way_sized = if (log2Ceil(n_ways) == 0) U(0, 1 bits) // rocket's extract(-1, 0) is a 1-bit zero
                          else if (touch_way.getWidth < log2Ceil(n_ways)) touch_way.resize(log2Ceil(n_ways))
                          else touch_way(log2Ceil(n_ways) - 1 downto 0)
    get_next_state(state, touch_way_sized, n_ways)
  }

  def get_replace_way(state: UInt, tree_nways: Int): UInt = {
    require(state.getWidth == (tree_nways - 1), s"wrong state bits width ${state.getWidth} for $tree_nways ways")

    if (tree_nways > 2) {
      val right_nways: Int = 1 << (log2Ceil(tree_nways) - 1)
      val left_nways: Int = tree_nways - right_nways
      val left_subtree_older = state(tree_nways - 2)
      val left_subtree_state = state(tree_nways - 3 downto right_nways - 1)
      val right_subtree_state = state(right_nways - 2 downto 0)

      if (left_nways > 1) {
        val l = get_replace_way(left_subtree_state, left_nways)
        val r = get_replace_way(right_subtree_state, right_nways)
        val w = l.getWidth max r.getWidth
        Cat(left_subtree_older, Mux(left_subtree_older, l.resize(w), r.resize(w))).asUInt
      } else {
        val r = get_replace_way(right_subtree_state, right_nways)
        Cat(left_subtree_older, Mux(left_subtree_older, U(0, r.getWidth bits), r)).asUInt
      }
    } else if (tree_nways == 2) {
      state(0).asUInt
    } else {
      U(0, 1 bits)
    }
  }

  def get_replace_way(state: UInt): UInt = get_replace_way(state, n_ways)

  def way = get_replace_way(state_reg)
  def miss = access(way)
  def hit = {}
}

class SetAssocLRU(n_sets: Int, n_ways: Int, policy: String) {
  val logic = policy.toLowerCase match {
    case "plru" => new PseudoLRU(n_ways)
    case t => throw new IllegalArgumentException(s"unknown Replacement Policy type $t")
  }
  val state_vec =
    if (logic.nBits == 0) Reg(Vec.fill(n_sets)(UInt(0 bits)))
    else Vec.fill(n_sets)(Reg(UInt(logic.nBits bits)) init 0)

  def access(set: UInt, touch_way: UInt): Unit = {
    state_vec(set.resized) := logic.get_next_state(state_vec(set.resized), touch_way)
  }

  def access(sets: Seq[UInt], touch_ways: Seq[Flow[UInt]]): Unit = {
    require(sets.size == touch_ways.size, "internal consistency check: should be same number of simultaneous updates for sets and touch_ways")
    for (set <- 0 until n_sets) {
      val set_touch_ways = (sets zip touch_ways).map { case (touch_set, touch_way) =>
        FixedPipeline(touch_way.valid && (touch_set === set), touch_way.payload, 0)
      }
      when(set_touch_ways.map(_.valid).orR) {
        state_vec(set) := logic.get_next_state(state_vec(set), set_touch_ways)
      }
    }
  }

  def way(set: UInt): UInt = logic.get_replace_way(state_vec(set.resized))
}

/** rocket-chip util.ParityCode (the only ECC code this port supports). */
class ParityCode {
  def width(w0: Int): Int = w0 + 1
  def encode(x: UInt, poison: Bool = False): UInt = Cat(x.xorR ^ poison, x).asUInt
  case class Decoding(uncorrected: UInt, uncorrectable: Bool) {
    def corrected: UInt = uncorrected
    def correctable: Bool = False
    def error: Bool = correctable || uncorrectable
  }
  def decode(y: UInt): Decoding = Decoding(y(y.getWidth - 2 downto 0), y.xorR)
}

/** rocket-chip PopCountAtLeast */
object PopCountAtLeast {
  private def two(x: UInt): (Bool, Bool) = x.getWidth match {
    case 1 => (x.lsb, False)
    case n =>
      val half = x.getWidth / 2
      val (leftOne, leftTwo) = two(x(half - 1 downto 0))
      val (rightOne, rightTwo) = two(x(x.getWidth - 1 downto half))
      (leftOne || rightOne, leftTwo || rightTwo || (leftOne && rightOne))
  }
  def apply(x: UInt, n: Int): Bool = n match {
    case 0 => True
    case 1 => x.orR
    case 2 => two(x)._2
    case 3 => PopCount(x) >= n
  }
}
