package boom.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.{Opcode, Param}

import rocketchip.config.Parameters
import rocketchip.tile.CoreInterrupts
import rocketchip.tilelink.TLEdge

import boom.common._
import boom.exu.RvfiChannel

/**
 * TileLink slave for formal verification: answers every request with data from
 * the free `rand_data` input and may stall on the free `rand_stall_*` inputs, but
 * always follows the protocol (one transaction at a time, grants are followed by
 * a GrantAck, releases get a ReleaseAck). It never probes.
 */
class FormalTLResponder(p: tilelink.BusParameter, maxTransferBytes: Int) extends Component {
  val io = new Bundle {
    val bus = slave(tilelink.Bus(p))
    val rand_data = in Bits(p.dataWidth bits)
    val rand_stall_a = in Bool()
    val rand_stall_d = in Bool()
  }
  val edge = TLEdge(p, maxTransferBytes)
  val bus = io.bus

  // --- A: one request in flight ---
  val busy     = Reg(Bool()) init False
  val waitE    = Reg(Bool()) init False
  val opcode   = Reg(Opcode.D())
  val param    = Reg(Bits(3 bits))
  val source   = Reg(UInt(p.sourceWidth bits))
  val size     = Reg(UInt(p.sizeWidth bits))
  val beatsLeft = Reg(UInt(log2Up(edge.maxBeats1 + 1) + 1 bits))

  bus.a.ready := !busy && !waitE && !io.rand_stall_a
  when (bus.a.fire) {
    busy   := True
    source := bus.a.payload.source
    size   := bus.a.payload.size
    param  := 0
    val dataBeats = edge.numBeats1(bus.a.payload.size, True).resize(beatsLeft.getWidth)
    beatsLeft := 0
    switch (bus.a.payload.opcode) {
      is (Opcode.A.GET)           { opcode := Opcode.D.ACCESS_ACK_DATA; beatsLeft := dataBeats }
      is (Opcode.A.PUT_FULL_DATA, Opcode.A.PUT_PARTIAL_DATA) { opcode := Opcode.D.ACCESS_ACK }
      is (Opcode.A.ACQUIRE_BLOCK) { opcode := Opcode.D.GRANT_DATA; param := Param.Cap.toT; beatsLeft := dataBeats }
      is (Opcode.A.ACQUIRE_PERM)  { opcode := Opcode.D.GRANT; param := Param.Cap.toT }
    }
  }

  // --- C: releases (BOOM only sends ProbeAcks in response to probes, which never happen) ---
  val relPending = Reg(Bool()) init False
  val relSource  = Reg(UInt(p.sourceWidth bits))
  val relSize    = Reg(UInt(p.sizeWidth bits))
  if (bus.c != null) {
    val (_, _, cDone, _) = edge.count(bus.c)
    bus.c.ready := !relPending
    when (cDone && bus.c.payload.isReleaseKind()) {
      relPending := True
      relSource  := bus.c.payload.source
      relSize    := bus.c.payload.size
    }
  }

  // --- D: a release ack between bursts, else the current A response ---
  val inBurst = Reg(Bool()) init False
  val sendRel = relPending && !inBurst
  bus.d.valid := !io.rand_stall_d && (sendRel || busy)
  bus.d.payload.assignDontCare()
  bus.d.payload.denied := False
  if (bus.d.payload.corrupt != null) bus.d.payload.corrupt := False
  if (bus.d.payload.data != null) bus.d.payload.data := io.rand_data
  bus.d.payload.sink := 0
  when (sendRel) {
    bus.d.payload.opcode := Opcode.D.RELEASE_ACK
    bus.d.payload.param  := 0
    bus.d.payload.source := relSource
    bus.d.payload.size   := relSize
  } otherwise {
    bus.d.payload.opcode := opcode
    bus.d.payload.param  := param
    bus.d.payload.source := source
    bus.d.payload.size   := size
  }
  when (bus.d.fire) {
    when (sendRel) {
      relPending := False
    } otherwise {
      inBurst := beatsLeft =/= 0
      beatsLeft := beatsLeft - 1
      when (beatsLeft === 0) {
        busy := False
        inBurst := False
        when (opcode === Opcode.D.GRANT_DATA || opcode === Opcode.D.GRANT) { waitE := True }
      }
    }
  }

  // --- B / E ---
  if (bus.b != null) {
    bus.b.valid := False
    bus.b.payload.assignDontCare()
  }
  if (bus.e != null) {
    bus.e.ready := True
    when (bus.e.fire) { waitE := False }
  }
}

/**
 * Top level for riscv-formal: a BOOM tile with RVFI enabled, its I$ and D$
 * buses answered by FormalTLResponders whose data/stalls (and the interrupt
 * lines) are free inputs for the wrapper to tie to `rvformal_rand` values.
 */
class BoomFormalTop(resetVector: BigInt)(implicit p: Parameters) extends Component {
  require(p.tile.core.asInstanceOf[BoomCoreParams].enableRVFI, "BoomFormalTop needs enableRVFI")
  val tile = new BoomTile

  val imemResp = new FormalTLResponder(tile.io.imem.p, p.system.cacheBlockBytes)
  val dmemResp = new FormalTLResponder(tile.io.dmem.p, p.system.cacheBlockBytes)

  val io = new Bundle {
    val interrupts = in(new CoreInterrupts(false))
    val imem_rand_data = in Bits(tile.io.imem.p.dataWidth bits)
    val imem_rand_stall_a = in Bool()
    val imem_rand_stall_d = in Bool()
    val dmem_rand_data = in Bits(tile.io.dmem.p.dataWidth bits)
    val dmem_rand_stall_a = in Bool()
    val dmem_rand_stall_d = in Bool()
    val rvfi = out(cloneOf(tile.io.rvfi))
  }

  imemResp.io.bus << tile.io.imem
  dmemResp.io.bus << tile.io.dmem
  imemResp.io.rand_data    := io.imem_rand_data
  imemResp.io.rand_stall_a := io.imem_rand_stall_a
  imemResp.io.rand_stall_d := io.imem_rand_stall_d
  dmemResp.io.rand_data    := io.dmem_rand_data
  dmemResp.io.rand_stall_a := io.dmem_rand_stall_a
  dmemResp.io.rand_stall_d := io.dmem_rand_stall_d

  tile.io.hartid := 0
  tile.io.reset_vector := U(resetVector, tile.paddrBits bits)
  tile.io.interrupts := io.interrupts
  io.rvfi := tile.io.rvfi
}
