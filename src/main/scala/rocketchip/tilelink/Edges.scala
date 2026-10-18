package rocketchip.tilelink

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink._
import rocketchip.util._

/**
 * The parts of rocket-chip's TLEdge helpers that BOOM uses, over
 * spinal.lib.bus.tilelink channels.
 */
case class TLEdge(bus: BusParameter, maxTransferBytes: Int) {
  def beatBytes: Int = bus.dataBytes
  def maxBeats1: Int = (maxTransferBytes / beatBytes - 1) max 0

  def hasDataA(op: Opcode.A.C): Bool = op === Opcode.A.PUT_FULL_DATA || op === Opcode.A.PUT_PARTIAL_DATA
  def hasDataC(op: Opcode.C.C): Bool = op === Opcode.C.PROBE_ACK_DATA || op === Opcode.C.RELEASE_DATA
  def hasDataD(op: Opcode.D.C): Bool = op === Opcode.D.ACCESS_ACK_DATA || op === Opcode.D.GRANT_DATA
  /** Is this D message the start of a transaction that needs a GrantAck (E)? */
  def isRequestD(op: Opcode.D.C): Bool = op === Opcode.D.GRANT || op === Opcode.D.GRANT_DATA

  /** rocket numBeats1: beats-1 of a message, 0 when it carries no data. */
  def numBeats1(size: UInt, hasData: Bool): UInt = {
    val w = log2UpChisel(maxBeats1 + 1)
    if (maxBeats1 == 0) U(0, w bits)
    else {
      val decode = (UIntToOH1(size, log2Ceil(maxTransferBytes)) >> log2Ceil(beatBytes)).resize(w)
      Mux(hasData, decode, U(0, w bits))
    }
  }

  /** rocket TLEdge.firstlastHelper / count: (first, last, done, count). */
  def count(fire: Bool, size: UInt, hasData: Bool): (Bool, Bool, Bool, UInt) = {
    val beats1 = numBeats1(size, hasData)
    val counter = Reg(UInt(beats1.getWidth bits)) init 0
    val counter1 = counter - 1
    val first = counter === 0
    val last = counter === 1 || beats1 === 0
    val done = last && fire
    val cnt = beats1 & ~counter1
    when(fire) {
      counter := Mux(first, beats1, counter1)
    }
    (first, last, done, cnt)
  }

  /** rocket TLEdge.addr_inc: like count, but the count is a byte offset. */
  def addr_inc(fire: Bool, size: UInt, hasData: Bool): (Bool, Bool, Bool, UInt) = {
    val (first, last, done, cnt) = count(fire, size, hasData)
    (first, last, done, (cnt << log2Ceil(beatBytes)).resize(cnt.getWidth + log2Ceil(beatBytes)))
  }

  // Per-channel conveniences (rocket's edge.count(x) / edge.done(x) / edge.hasData(x) on a DecoupledIO)
  def count(c: Stream[ChannelC]): (Bool, Bool, Bool, UInt) = count(c.fire, c.payload.size, hasDataC(c.payload.opcode))
  def count(d: Stream[ChannelD])(implicit dummy: DummyImplicit): (Bool, Bool, Bool, UInt) = count(d.fire, d.payload.size, hasDataD(d.payload.opcode))
  def addr_inc(d: Stream[ChannelD]): (Bool, Bool, Bool, UInt) = addr_inc(d.fire, d.payload.size, hasDataD(d.payload.opcode))
  def done(a: Stream[ChannelA]): Bool = count(a.fire, a.payload.size, hasDataA(a.payload.opcode))._3
  def done(c: Stream[ChannelC])(implicit dummy: DummyImplicit): Bool = count(c)._3
  def numBeats1(a: ChannelA): UInt = numBeats1(a.size, hasDataA(a.opcode))
  def numBeats1(c: ChannelC): UInt = numBeats1(c.size, hasDataC(c.opcode))
  def numBeats1(e: ChannelE): UInt = U(0, log2UpChisel(maxBeats1 + 1) bits)

  //--------------------------------------------------------------------------
  // Message constructors (rocket TLEdgeOut). Each returns a fresh wire; fields a
  // channel does not carry are driven to their idle values.

  /** rocket MaskGen: byte mask of an access of 2^lgSize bytes at addr_lo within a beat. */
  def mask(addr_lo: UInt, lgSize: UInt): Bits = {
    val lgBytes = log2Ceil(beatBytes)
    if (lgBytes == 0) return B(1, 1 bits)
    val sizeOH = UIntToOH(lgSize.resize(log2UpChisel(beatBytes)), log2UpChisel(beatBytes)) | U(1, log2UpChisel(beatBytes) bits)
    def helper(i: Int): Seq[(Bool, Bool)] = {
      if (i == 0) {
        Seq((lgSize >= lgBytes, True))
      } else {
        val sub = helper(i - 1)
        val size = sizeOH(lgBytes - i)
        val bit = addr_lo(lgBytes - i)
        val nbit = !bit
        Seq.tabulate(1 << i) { j =>
          val (sub_acc, sub_eq) = sub(j / 2)
          val eq = sub_eq && (if (j % 2 == 1) bit else nbit)
          val acc = sub_acc || (size && eq)
          (acc, eq)
        }
      }
    }
    helper(lgBytes).map(_._1).asUInt.asBits
  }

  private def newA(): ChannelA = {
    val a = ChannelA(bus)
    a.param := 0
    if (a.mask != null) a.mask.setAll()
    if (a.data != null) a.data := 0
    if (a.corrupt != null) a.corrupt := False
    a.debugId := 0
    a
  }
  private def newC(): ChannelC = {
    val c = ChannelC(bus)
    if (c.data != null) c.data := 0
    c.corrupt := False
    c
  }

  def AcquireBlock(fromSource: UInt, toAddress: UInt, lgSize: UInt, growPermissions: UInt): ChannelA = {
    val a = newA()
    a.opcode  := Opcode.A.ACQUIRE_BLOCK
    a.param   := growPermissions.asBits.resized
    a.size    := lgSize.resized
    a.source  := fromSource.resized
    a.address := toAddress.resized
    if (a.mask != null) a.mask := mask(toAddress, lgSize)
    a
  }

  def Get(fromSource: UInt, toAddress: UInt, lgSize: UInt): ChannelA = {
    val a = newA()
    a.opcode  := Opcode.A.GET
    a.size    := lgSize.resized
    a.source  := fromSource.resized
    a.address := toAddress.resized
    if (a.mask != null) a.mask := mask(toAddress, lgSize)
    a
  }

  def Put(fromSource: UInt, toAddress: UInt, lgSize: UInt, data: Bits): ChannelA = {
    val a = newA()
    a.opcode  := Opcode.A.PUT_FULL_DATA
    a.size    := lgSize.resized
    a.source  := fromSource.resized
    a.address := toAddress.resized
    a.mask    := mask(toAddress, lgSize)
    a.data    := data.resized
    a
  }

  def GrantAck(d: ChannelD): ChannelE = {
    val e = ChannelE(bus)
    e.sink := d.sink
    e
  }

  /** Voluntary release; ReleaseData when `data` is given. */
  def Release(fromSource: UInt, toAddress: UInt, lgSize: UInt, shrinkPermissions: UInt, data: Bits = null): ChannelC = {
    val c = newC()
    c.opcode  := (if (data == null) Opcode.C.RELEASE() else Opcode.C.RELEASE_DATA())
    c.param   := shrinkPermissions.asBits.resized
    c.size    := lgSize.resized
    c.source  := fromSource.resized
    c.address := toAddress.resized
    if (data != null) c.data := data.resized
    c
  }

  /** ProbeAck (no data) in response to probe `b`. */
  def ProbeAck(b: ChannelB, reportPermissions: UInt): ChannelC = {
    val c = newC()
    c.opcode  := Opcode.C.PROBE_ACK
    c.param   := reportPermissions.asBits.resized
    c.size    := b.size
    c.source  := b.source
    c.address := b.address
    c
  }

  /** ProbeAckData. */
  def ProbeAck(fromSource: UInt, toAddress: UInt, lgSize: UInt, reportPermissions: UInt, data: Bits): ChannelC = {
    val c = newC()
    c.opcode  := Opcode.C.PROBE_ACK_DATA
    c.param   := reportPermissions.asBits.resized
    c.size    := lgSize.resized
    c.source  := fromSource.resized
    c.address := toAddress.resized
    c.data    := data.resized
    c
  }
}

/**
 * rocket TLArbiter with the lowestIndexFirst policy: the winner keeps the sink
 * for the rest of a multi-beat message. `beats1` gives each source's beats-1.
 */
object TLArbiter {
  def lowest[T <: Data](sink: Stream[T], sources: Seq[(UInt, Stream[T])]): Unit = {
    if (sources.isEmpty) {
      sink.valid := False
      sink.payload.assignDontCare()
    } else if (sources.size == 1) {
      sink << sources.head._2
    } else {
      val beatsIn = sources.map(_._1)
      val sourcesIn = sources.map(_._2)
      val bw = beatsIn.map(_.getWidth).max

      // The number of beats which remain to be sent
      val beatsLeft = Reg(UInt(bw bits)) init 0
      val idle = beatsLeft === 0
      val latch = idle && sink.ready // winner (if any) claims sink

      // Who wants access to the sink?
      val valids = sourcesIn.map(_.valid)
      // Arbitrate amongst the requests (lowest index first)
      val readys = (~((leftOR(valids.asUInt) << 1).resize(valids.size))).asBools
      // Which request wins arbitration?
      val winner = (readys zip valids) map { case (r, v) => r && v }

      // Track remaining beats
      val maskedBeats = (winner zip beatsIn) map { case (w, b) => Mux(w, b.resize(bw), U(0, bw bits)) }
      val initBeats = maskedBeats.reduce(_ | _) // no winner => 0 beats
      beatsLeft := Mux(latch, initBeats, beatsLeft - sink.fire.asUInt.resize(bw))

      // The one-hot source granted access in the previous cycle
      val state = RegInit(Vec.fill(sources.size)(False))
      val muxState = Mux(idle, Vec(winner), state)
      state := muxState

      val allowed = Mux(idle, Vec(readys.toList), state)
      (sourcesIn zip allowed) foreach { case (s, r) =>
        s.ready := sink.ready && r
      }
      val sinkValid = Mux(idle, valids.reduce(_ || _), Mux1H(state, valids))
      sink.valid := sinkValid
      sink.payload := Mux1H(muxState, sourcesIn.map(_.payload))
    }
  }

  def lowestFromSeq[T <: Data](sink: Stream[T], sources: Seq[Stream[T]], beats1: T => UInt): Unit =
    lowest(sink, sources.map(s => (beats1(s.payload), s)))
}

object TLBusParams {
  /** Bus parameters for a master emitting `emits`, talking to slaves that may `probe`. */
  def apply(addressWidth: Int, dataWidth: Int, sourceCount: Int, emits: M2sTransfers, probe: SizeRange = SizeRange.none, sinkWidth: Int = 0): BusParameter = {
    val m = M2sParameters(
      addressWidth = addressWidth,
      dataWidth = dataWidth,
      masters = List(M2sAgent(name = null, mapping = List(M2sSource(id = SizeMapping(0, sourceCount max 1), emits = emits))))
    )
    val s = if (probe.none) S2mParameters.none() else S2mParameters(List(S2mAgent(
      name = null, sinkId = SizeMapping(0, 1 << sinkWidth), emits = S2mTransfers(probe = probe))))
    NodeParameters(m, s).toBusParameter()
  }
}
