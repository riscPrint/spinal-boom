package boom.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.tilelink

import rocketchip.config.Parameters
import rocketchip.tile.CoreInterrupts

import boom.common._
import boom.exu.{BoomCoreTrace, RvfiChannel}

/**
 * Simulation SoC: one BOOM tile whose I$ and D$ TileLink clients are merged
 * (Spinal's tilelink.Arbiter) into a single coherent port `mem`, to be served
 * by a memory model. Not part of upstream BOOM.
 *
 * `tohost` reports stores the D$ has irrevocably accepted (store_ack), so a
 * harness can watch the riscv-tests/ACT `tohost` word without forcing that
 * line out of the cache.
 */
class BoomSimTop(resetVector: BigInt, rvfiMonitorDir: Option[String] = None)(implicit p: Parameters) extends Component {
  val tile = new BoomTile

  val dmemNode = tile.io.dmem.p.node
  val imemNode = tile.io.imem.p.node
  // The D$ goes first so the merged node keeps its probe (S2M) parameters.
  val memNode = tilelink.Arbiter.downNodeFrom(Seq(dmemNode, imemNode))
  val arbiter = tilelink.Arbiter(Seq(dmemNode, imemNode), memNode)

  val io = new Bundle {
    val mem = master(tilelink.Bus(memNode))
    val interrupts = in(new CoreInterrupts(false))
    val trace = out(new BoomCoreTrace)
    val rvfi = if (tile.io.rvfi != null) out(cloneOf(tile.io.rvfi)) else null
    val tohost = master(Flow(new Bundle {
      val addr = UInt(tile.coreMaxAddrBits bits)
      val data = UInt(tile.xLen bits)
      val size = UInt(2 bits)
    }))
  }

  arbiter.io.ups(0) << tile.io.dmem
  // rocket's I$ may withdraw a.valid before the beat is accepted (s2_kill). rocket-chip's
  // TLArbiter tolerates that, but Spinal's Arbiter locks onto a stalled requester, so it
  // would then wait on the I$ forever. A register slice keeps the request stable once taken.
  arbiter.io.ups(1).a << tile.io.imem.a.m2sPipe()
  tile.io.imem.d << arbiter.io.ups(1).d
  io.mem << arbiter.io.down

  tile.io.hartid := 0
  tile.io.reset_vector := U(resetVector, tile.paddrBits bits)
  tile.io.interrupts := io.interrupts
  io.trace := tile.io.trace
  if (io.rvfi != null) io.rvfi := tile.io.rvfi

  // Optional riscv-formal RVFI monitor (simulation check of the RVFI stream)
  val rvfiErr = rvfiMonitorDir.map { dir =>
    val mon = new RvfiMonitor(io.rvfi, s"$dir/rvfimon_c${io.rvfi.size}.v")
    mon.drive()
    val err = out Bits(16 bits)
    err := mon.io.errcode
    err.setName("io_rvfi_errcode")
  }

  val storeAck = tile.dcache.io.lsu.store_ack(0)
  io.tohost.valid := storeAck.valid.pull() && !storeAck.payload.is_hella.pull()
  io.tohost.payload.addr := storeAck.payload.addr.pull()
  io.tohost.payload.data := storeAck.payload.data.pull()
  io.tohost.payload.size := storeAck.payload.uop.mem_size.pull()
}
