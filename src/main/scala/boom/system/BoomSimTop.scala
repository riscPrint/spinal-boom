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
class BoomSimTop(resetVector: BigInt, rvfiMonitorDir: Option[String] = None, konataProbe: Boolean = false)
                (implicit p: Parameters) extends Component {
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

  /** Pipeline-stage signals for the simulation's Konata tracer (boom.sim.KonataTracer). */
  val konata = if (konataProbe) new KonataProbe(tile) else null

  val storeAck = tile.dcache.io.lsu.store_ack(0)
  io.tohost.valid := storeAck.valid.pull() && !storeAck.payload.is_hella.pull()
  io.tohost.payload.addr := storeAck.payload.addr.pull()
  io.tohost.payload.data := storeAck.payload.data.pull()
  io.tohost.payload.size := storeAck.payload.uop.mem_size.pull()
}

/**
 * Brings out (as top-level ports) the signals a simulation needs to follow every
 * instruction through the pipeline: fetch-buffer enqueue, the decode and rename2
 * slots, issue grants, ROB writeback / commit, and the events that kill
 * instructions (branch mispredict, ROB rollback, front-end redirect).
 * Simulation only: not part of upstream BOOM.
 */
class KonataProbe(tile: BoomTile) extends Area {
  private val core = tile.core
  private val fb   = tile.frontend.fb
  private val rob  = core.rob

  private def port[T <: Data](d: T): T = { val o = out(cloneOf(d)); o := d.pull(); o }
  private def ports[T <: Data](s: scala.collection.Seq[T]): Vec[T] = {
    val o = out(Vec.fill(s.size)(cloneOf(s.head)))
    (o zip s).foreach { case (a, b) => a := b.pull() }
    o
  }
  private val issUops = Seq(core.mem_iss_unit, core.alu_iss_unit, core.unq_iss_unit, core.fp_pipeline.issue_unit)
    .flatMap(_.io.iss_uops)

  // Fetch buffer enqueue (in_mask already includes enq.valid)
  val fbMask  = ports(fb.in_mask)
  val fbPc    = ports(fb.in_uops.map(_.debug_pc))
  val fbInst  = ports(fb.in_uops.map(_.debug_inst))
  val fbDoEnq = port(fb.do_enq)
  val fbClear = port(fb.io.clear)
  // Decode slots
  val decValid = ports(core.dec_valids)
  val decFire  = ports(core.dec_fire)
  val decPc    = ports(core.dec_uops.map(_.debug_pc))
  // Rename2 / dispatch slots
  val disValid  = ports(core.dis_valids)
  val disFire   = ports(core.dis_fire)
  val disPc     = ports(core.dis_uops.map(_.debug_pc))
  val disRobIdx = ports(core.dis_uops.map(_.rob_idx))
  // Issue grants
  val issValid  = ports(issUops.map(_.valid))
  val issRobIdx = ports(issUops.map(_.payload.rob_idx))
  // ROB writeback (exe units, LSU, FP) and store busy-clears
  val wbValid   = ports(rob.io.wb_resps.map(_.valid))
  val wbRobIdx  = ports(rob.io.wb_resps.map(_.payload.uop.rob_idx))
  val clrValid  = ports(rob.io.lsu_clr_bsy.map(_.valid))
  val clrRobIdx = ports(rob.io.lsu_clr_bsy.map(_.payload))
  // Commit
  val comValid  = ports(rob.io.commit.valids)
  val comRobIdx = ports(rob.io.commit.uops.map(_.rob_idx))
  // Kills
  val rollback      = port(rob.io.rollback)
  val mispredict    = port(core.brupdate.b2.mispredict)
  val mispRobIdx    = port(core.brupdate.b2.uop.rob_idx)
  val redirectFlush = port(core.io.ifu.redirect_flush)

  // Cycle-equivalence observations. Only instantiated with the existing sim-only probe.
  // The architectural trace is one cycle behind ROB commit, including destination metadata.
  val traceLdst = ports(rob.io.commit.uops.map(u => RegNext(u.ldst.pull())))
  val traceDstType = ports(rob.io.commit.uops.map(u => RegNext(u.dst_rtype.pull())))
  val redirectValid = port(core.io.ifu.redirect_val)
  val redirectPc = port(core.io.ifu.redirect_pc)
  val dcS2Valid = ports(tile.dcache.s2_valid)
  val dcS2Hit = ports(tile.dcache.s2_hit)
  val dcS2Nack = ports(tile.dcache.s2_nack)
  val dcS2Type = port(tile.dcache.s2_type)
  val lsuNackValid = ports(tile.dcache.io.lsu.nack.map(_.valid))
}
