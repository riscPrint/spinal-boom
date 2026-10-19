package boom.lsu

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

import boom.BoomTestParams
import boom.common._

/** Directed tests of the non-blocking data TLB, driving its PTW port by hand. */
class NBDTLBSpec extends AnyFunSuite {
  lazy val compiled = {
    implicit val p = BoomTestParams()
    SimConfig.withConfig(SpinalBoomConfig("generated/test")).workspacePath("simWorkspace").workspaceName("nbdtlb")
      .withVerilator.compile(new NBDTLB(false, 3, rocketchip.rocket.TLBConfig(p.tile.dcache.get.nTLBSets, p.tile.dcache.get.nTLBWays)))
  }

  val vaddr = BigInt("40001000", 16)

  /**
   * Miss on `vaddr`, let the walk start, optionally sfence.vma (flush all) while the walk
   * is in flight, return a valid 4 KiB leaf, then look `vaddr` up again.
   * Returns whether the second lookup missed.
   */
  def secondLookupMisses(sfenceDuringWalk: Boolean): Boolean = {
    var missed = false
    compiled.doSim { dut =>
      dut.io.flatten.filter(_.isInput).foreach {
        case b: Bool => b #= false
        case b: BitVector => b #= 0
        case _ =>
      }
      dut.io.ptw.ptbr.mode #= 8 // Sv39
      dut.io.ptw.status.prv #= 1
      dut.io.ptw.status.dprv #= 1
      dut.io.ptw.req.ready #= true
      dut.io.req(0).payload.size #= 3
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(4)
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling(2)

      // load to vaddr: misses and starts a walk
      dut.io.req(0).valid #= true
      dut.io.req(0).payload.vaddr #= vaddr
      sleep(1)
      assert(dut.io.resp(0).miss.toBoolean, "first lookup should miss")
      dut.clockDomain.waitSampling()
      dut.io.req(0).valid #= false
      dut.clockDomain.waitSampling() // s_request; ptw.req fires
      assert(dut.io.ptw.req.valid.toBoolean)
      dut.clockDomain.waitSampling(3) // s_wait

      if (sfenceDuringWalk) {
        dut.io.sfence.valid #= true
        dut.clockDomain.waitSampling()
        dut.io.sfence.valid #= false
      }
      dut.clockDomain.waitSampling(2)

      // the walk returns a valid leaf for vaddr
      val pte = dut.io.ptw.resp.payload.pte
      dut.io.ptw.resp.valid #= true
      pte.v #= true; pte.r #= true; pte.w #= true; pte.a #= true; pte.d #= true
      pte.ppn #= BigInt("80001", 16)
      dut.io.ptw.resp.payload.level #= 2
      dut.io.ptw.resp.payload.homogeneous #= true
      dut.clockDomain.waitSampling()
      dut.io.ptw.resp.valid #= false
      dut.clockDomain.waitSampling()

      dut.io.req(0).valid #= true
      sleep(1)
      missed = dut.io.resp(0).miss.toBoolean
    }
    missed
  }

  test("a refill completes and the page then hits") {
    assert(!secondLookupMisses(sfenceDuringWalk = false))
  }

  test("sfence.vma during a walk drops the walk's refill") {
    assert(secondLookupMisses(sfenceDuringWalk = true))
  }
}
