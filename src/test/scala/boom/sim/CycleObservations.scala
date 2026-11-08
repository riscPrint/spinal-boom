package boom.sim

import spinal.core._
import boom.system.BoomSimTop

/** Test-only output ports. This adds no drivers to core signals. */
class CycleObservations(dut: BoomSimTop) extends Area {
  private val tile = dut.tile
  private val core = tile.core
  private val rob = core.rob
  private def port[T <: Data](d: T): T = { val o = out(cloneOf(d)); o := d.pull(); o }
  private def ports[T <: Data](s: scala.collection.Seq[T]): Vec[T] = {
    val o = out(Vec.fill(s.size)(cloneOf(s.head)))
    for (i <- s.indices) o(i) := s(i).pull()
    o
  }
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

  traceLdst.setName("konata_traceLdst")
  traceDstType.setName("konata_traceDstType")
  redirectValid.setName("konata_redirectValid")
  redirectPc.setName("konata_redirectPc")
  dcS2Valid.setName("konata_dcS2Valid")
  dcS2Hit.setName("konata_dcS2Hit")
  dcS2Nack.setName("konata_dcS2Nack")
  dcS2Type.setName("konata_dcS2Type")
  lsuNackValid.setName("konata_lsuNackValid")
}
