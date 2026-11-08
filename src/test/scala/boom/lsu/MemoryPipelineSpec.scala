// Ported reference: see LICENSE.Berkeley and LICENSE.SiFive for license details.
package boom.lsu

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite
import rocketchip.config.Parameters
import rocketchip.util._
import boom.BoomTestParams
import boom.common._
import boom.util._

// Reference copied verbatim from e101625, except the class name.
class ReferenceBankedDataArray(implicit p: Parameters) extends AbstractBoomDataArray {

  val nBanks   = boomParams.numDCacheBanks
  val bankSize = nSets * refillCycles / nBanks
  require (nBanks >= lsuWidth)
  require (bankSize > 0)

  val bankBits    = log2Ceil(nBanks)
  val bankOffBits = log2Ceil(rowWords) + log2Ceil(wordBytes)
  val bidxBits    = log2Ceil(bankSize)
  val bidxOffBits = bankOffBits + bankBits

  //----------------------------------------------------------------------------------------------------

  val s0_rbanks = if (nBanks > 1) Vec(io.read.map(r => (r.payload.addr >> bankOffBits)(bankBits-1 downto 0))) else Vec(U(0, 1 bits))
  val s0_wbank  = if (nBanks > 1) (io.write.payload.addr >> bankOffBits)(bankBits-1 downto 0) else U(0, 1 bits)
  val s0_ridxs  = Vec(io.read.map(r => (r.payload.addr >> bidxOffBits)(bidxBits-1 downto 0)))
  val s0_widx   = (io.write.payload.addr >> bidxOffBits)(bidxBits-1 downto 0)

  val s0_read_valids    = Vec(io.read.map(_.valid))
  val s0_bank_conflicts = pipeMap(w => {
    ((s0_rbanks(w) === s0_wbank) && io.write.valid && Bool(dcacheSinglePorted)) ||
    (0 until w).foldLeft(False)((c,i) => c || io.read(i).valid && s0_rbanks(i) === s0_rbanks(w))
  })
  val s0_do_bank_read   = s0_read_valids zip s0_bank_conflicts map {case (v,c) => v && !c}
  val s0_bank_read_gnts = Transpose(s0_rbanks zip s0_do_bank_read map {case (b,d) => (UIntToOH(b, nBanks) & Fill(nBanks,d)).asBools.toSeq})
  val s0_bank_write_gnt = (UIntToOH(s0_wbank, nBanks) & Fill(nBanks, io.write.valid)).asBools

  //----------------------------------------------------------------------------------------------------

  val s1_rbanks         = RegNext(s0_rbanks)
  val s1_ridxs          = RegNext(s0_ridxs)
  val s1_read_valids    = RegNext(s0_read_valids)
  val s1_pipe_selection = pipeMap(i => Vec(PriorityEncoderOH((0 until lsuWidth).map(j =>
                            if (j < i) s1_read_valids(j) && s1_rbanks(j) === s1_rbanks(i)
                            else if (j == i) True else False))))
  val s1_ridx_match     = pipeMap(i => Vec((0 until lsuWidth).map(j => if (j < i) s1_ridxs(j) === s1_ridxs(i)
                                                    else if (j == i) True else False)))
  val s1_nacks          = pipeMap(w => s1_read_valids(w)
    && (!RegNext(s0_do_bank_read(w)) || (s1_pipe_selection(w).asUInt & ~s1_ridx_match(w).asUInt).orR)
  )
  val s1_bank_selection = pipeMap(w => Mux1H(s1_pipe_selection(w), s1_rbanks))

  //----------------------------------------------------------------------------------------------------

  val s2_bank_selection = RegNext(s1_bank_selection)
  io.s1_nacks          := s1_nacks

  val data_arrays = Seq.tabulate(nBanks) {
    b => new SyncReadVecMem(bankSize, nWays * rowWords, UInt(encDataBits bits)).setName(s"array_${b}")
  }
  val s2_bank_reads = Reg(Vec.fill(nBanks)(Vec.fill(nWays)(UInt(encRowBits bits))))
  for (b <- 0 until nBanks) {
    val array = data_arrays(b)

    val ridx = Mux1H(s0_bank_read_gnts(b), s0_ridxs)
    val way_en = Mux1H(s0_bank_read_gnts(b), io.read.map(_.payload.way_en))
    val write_en = s0_bank_write_gnt(b)
    val write_mask = (0 until nWays).flatMap { w =>
      Mux(io.write.payload.way_en(w), io.write.payload.wmask, U(0, rowWords bits)).asBools
    }
    val read_en = CombInit(s0_bank_read_gnts(b).reduce(_||_))

    val rdata = if (dcacheSinglePorted) {
      assert(!(read_en && write_en))
      array.read(ridx, !write_en && read_en)
    } else {
      array.read(ridx, read_en)
    }
    s2_bank_reads(b).assignFromBits(rdata.asBits)

    val data = Vec.fill(nWays * rowWords)(UInt(encDataBits bits))
    for (w <- 0 until nWays) {
      for (i <- 0 until rowWords) {
        data(w*rowWords+i) := io.write.payload.data(encDataBits*(i+1)-1 downto encDataBits*i)
      }
    }
    array.write(s0_widx, data, write_mask, write_en)
  }
  for (w <- 0 until nWays) {
    for (i <- 0 until lsuWidth) {
      io.resp(i)(w) := s2_bank_reads.at(s2_bank_selection(i))(w)
    }
  }
}

class BankedReadComparisonTop(implicit p: Parameters) extends BoomModule {
  val current = new BoomBankedDataArray
  val reference = new ReferenceBankedDataArray
  val io = new Bundle {
    val read = in(cloneOf(current.io.read))
    val write = in(cloneOf(current.io.write))
    val nackEqual = out Bool()
    val dataEqual = out Bool()
  }
  for (array <- Seq(current, reference)) {
    array.io.read := io.read
    array.io.write := io.write
  }
  io.nackEqual := current.io.s1_nacks.asBits === reference.io.s1_nacks.asBits
  io.dataEqual := current.io.resp.asBits === reference.io.resp.asBits
}

class MemoryPipelineSpec extends AnyFunSuite {
  for (singlePorted <- Seq(false, true)) {
    test(s"banked read pipeline matches the pre-port implementation, singlePorted=$singlePorted") {
      val base = BoomConfigs.large()
      implicit val p: Parameters = BoomTestParams(base.copy(core = base.core.copy(dcacheSinglePorted = singlePorted)))
      SimConfig.withConfig(SpinalBoomConfig()).workspacePath("simWorkspace")
        .workspaceName(s"banked-read-$singlePorted").compile(new BankedReadComparisonTop).doSim(seed = 1) { dut =>
          dut.io.flatten.filter(_.isInput).foreach {
            case b: Bool => b #= false
            case b: BitVector => b #= 0
            case _ =>
          }
          dut.clockDomain.forkStimulus(10)
          dut.clockDomain.waitSampling(8)
          val random = new scala.util.Random(8)
          val rows = dut.current.nSets * dut.current.refillCycles
          def tick(): Unit = { dut.clockDomain.waitRisingEdge(); sleep(1) }
          // Initialise every bank/way, then warm every bank's synchronous read output.
          for (row <- 0 until rows) {
            dut.clockDomain.waitFallingEdge()
            dut.io.write.valid #= true
            dut.io.write.payload.addr #= row * (1 << dut.current.rowOffBits)
            dut.io.write.payload.way_en #= (1 << dut.current.nWays) - 1
            dut.io.write.payload.wmask #= (1 << dut.current.rowWords) - 1
            dut.io.write.payload.data #= BigInt(dut.current.encRowBits, random)
            tick()
          }
          for (row <- 0 until 16) {
            dut.clockDomain.waitFallingEdge()
            dut.io.write.valid #= false
            for (r <- dut.io.read) {
              r.valid #= true
              r.payload.addr #= row * (1 << dut.current.rowOffBits)
              r.payload.way_en #= (1 << dut.current.nWays) - 1
            }
            tick()
          }
          for (cycle <- 0 until 400) {
            dut.clockDomain.waitFallingEdge()
            val row = random.nextInt(rows)
            dut.io.write.valid #= cycle % 3 == 0
            dut.io.write.payload.addr #= row * (1 << dut.current.rowOffBits)
            dut.io.write.payload.data #= BigInt(dut.current.encRowBits, random)
            dut.io.write.payload.way_en #= random.nextInt(1 << dut.current.nWays)
            dut.io.write.payload.wmask #= random.nextInt(1 << dut.current.rowWords)
            for ((r, lane) <- dut.io.read.zipWithIndex) {
              r.valid #= cycle % 7 != lane
              // Include same-row sharing, same-bank different rows and write conflicts.
              val readRow = if (cycle % 4 == 0) row else if (cycle % 4 == 1) (row + lane * dut.current.nBanks) % rows else random.nextInt(rows)
              r.payload.addr #= readRow * (1 << dut.current.rowOffBits)
              r.payload.way_en #= (1 << dut.current.nWays) - 1
            }
            tick()
            assert(dut.io.nackEqual.toBoolean, s"nack differs at cycle $cycle")
            assert(dut.io.dataEqual.toBoolean, s"read data differs at cycle $cycle")
          }
        }
    }
  }
}
