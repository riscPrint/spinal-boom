//******************************************************************************
// Copyright (c) 2013 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Functional Units
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// If regfile bypassing is disabled, then the functional unit must do its own
// bypassing in here on the WB stage (i.e., bypassing the io.resp.data)

package boom.exu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._
import rocketchip.tile
import rocketchip.rocket.{PipelinedMultiplier, MulDiv}
import rocketchip.rocket.ALU._

import boom.common._
import boom.ifu._
import boom.util._

/**
 * Bundle for signals sent to the functional unit
 *
 * @param dataWidth width of the data sent to the functional unit
 */
class FuncUnitReq(val dataWidth: Int)(implicit p: Parameters) extends BoomBundle
  with HasBoomUOP
{
  val numOperands = 3

  val rs1_data = UInt(dataWidth bits)
  val rs2_data = UInt(dataWidth bits)
  val rs3_data = UInt(dataWidth bits) // only used for FMA units
  val ftq_info = Vec.fill(2)(new FTQInfo) // Need this-pc and next-pc for JALR
  val pred_data = Bool()
  val imm_data = UInt(xLen bits) // only used for integer ALU and AGen units
}

class BrInfoBundle(implicit p: Parameters) extends BoomBundle
{
  val ldq_idx = UInt(1+ldqAddrSz bits)
  val stq_idx = UInt(1+stqAddrSz bits)
  val rxq_idx = UInt(log2Ceil(numRxqEntries) bits)
}

/**
 * Branch resolution information given from the branch unit
 */
class BrResolutionInfo(implicit p: Parameters) extends BoomBundle with HasBoomUOP
{
  val mispredict = Bool()
  val taken      = Bool()                     // which direction did the branch go?
  val cfi_type   = UInt(CFI_SZ bits)

  // Info for recalculating the pc for this branch
  val pc_sel     = UInt(2 bits)

  val jalr_target = UInt(vaddrBitsExtended bits)
  val target_offset = SInt(21 bits)
}

class BrUpdateInfo(implicit p: Parameters) extends BoomBundle
{
  // On the first cycle we get masks to kill registers
  val b1 = new BrUpdateMasks
  // On the second cycle we get indices to reset pointers
  val b2 = new BrResolutionInfo
}

class BrUpdateMasks(implicit p: Parameters) extends BoomBundle
{
  val resolve_mask = UInt(maxBrCount bits)
  val mispredict_mask = UInt(maxBrCount bits)
}

/**
 * Abstract top level functional unit class that wraps a lower level hand made functional unit
 *
 * @param dataWidth width of the data being operated on in the functional unit
 * @param isAluUnit does this functional unit have a branch unit?
 */
abstract class FunctionalUnit(
  val dataWidth: Int,
  val isAluUnit: Boolean = false,
  val needsFcsr: Boolean = false)
  (implicit p: Parameters) extends BoomModule
{
  val io = new Bundle {
    val kill   = in Bool()

    val req    = slave(Stream(new FuncUnitReq(dataWidth)))
    val resp   = master(Stream(new ExeUnitResp(dataWidth)))

    val brupdate = in(new BrUpdateInfo())

    // only used by the fpu unit
    val fcsr_rm = if (needsFcsr) in UInt(tile.FPConstants.RM_SZ bits) else null

    // only used by branch unit
    val brinfo     = if (isAluUnit) master(Flow(new BrResolutionInfo)) else null
  }
  io.resp.payload.fflags.valid := False
  io.resp.payload.fflags.payload.assignDontCare()
  io.resp.payload.predicated   := False

  /** Chisel's `pipe.io.req := io.req` (Decoupled into Valid). */
  def connectPipe(pipe: BranchKillablePipeline[FuncUnitReq]): Unit = {
    pipe.io.req.valid   := io.req.valid
    pipe.io.req.payload := io.req.payload
    pipe.io.flush := io.kill
    pipe.io.brupdate := io.brupdate
  }
}

/**
 * Functional unit that wraps RocketChips ALU
 *
 * @param dataWidth width of the data being operated on in the functional unit
 */
class ALUUnit(dataWidth: Int)(implicit p: Parameters)
  extends FunctionalUnit(
    isAluUnit = true,
    dataWidth = dataWidth)
  with boom.ifu.HasBoomFrontendParameters
{
  io.req.ready := True
  val uop = io.req.payload.uop

  // immediate generation
  val imm_xprlen = io.req.payload.imm_data //ImmGen(uop.imm_packed, uop.imm_sel)

  // operand 1 select

  // Get the uop PC for jumps
  val block_pc = AlignPCToBoundary(io.req.payload.ftq_info(0).pc, icBlockBytes)
  val uop_pc = (block_pc | uop.pc_lob.resized) - Mux(uop.edge_inst, U(2, vaddrBitsExtended bits), U(0, vaddrBitsExtended bits))
  val op1_shamt = Mux(uop.fcn_op === FN_ADD, io.req.payload.uop.pimm(2 downto 1), U(0, 2 bits))
  val op1_shl = (Mux(!uop.fcn_dw, // shaddw
    io.req.payload.rs1_data(31 downto 0).resize(xLen), io.req.payload.rs1_data) << op1_shamt).resize(xLen)

  val op1_data = MuxLookup(uop.op1_sel, U(0, xLen bits), Seq(
    OP1_RS1.U    -> io.req.payload.rs1_data,
    OP1_PC.U     -> Sext(uop_pc, xLen),
    OP1_RS1SHL.U -> op1_shl
  ))

  // operand 2 select
  val op2_oh = UIntToOH(Mux(uop.op2_sel(0), // rs1
    io.req.payload.rs2_data, imm_xprlen)(log2Ceil(xLen)-1 downto 0), xLen)
  val op2_data = MuxLookup(uop.op2_sel, U(0, xLen bits), Seq(
    OP2_IMM.U   -> Sext(imm_xprlen, xLen),
    OP2_IMMC.U  -> io.req.payload.uop.prs1(4 downto 0).resize(xLen),
    OP2_RS2.U   -> io.req.payload.rs2_data,
    OP2_NEXT.U  -> Mux(uop.is_rvc, U(2, xLen bits), U(4, xLen bits)),
    OP2_RS2OH.U -> op2_oh,
    OP2_IMMOH.U -> op2_oh
  ))

  val alu = new rocketchip.rocket.ALU()

  alu.io.in1 := op1_data
  alu.io.in2 := op2_data
  alu.io.fn  := uop.fcn_op
  alu.io.dw  := Mux(uop.op1_sel === OP1_RS1SHL, DW_64.U, uop.fcn_dw.asUInt)

  val rs1 = io.req.payload.rs1_data
  val rs2 = io.req.payload.rs2_data
  val br_eq  = (rs1 === rs2)
  val br_ltu = (rs1 < rs2)
  val br_lt  = (~(rs1(xLen-1) ^ rs2(xLen-1)) & br_ltu |
                rs1(xLen-1) & ~rs2(xLen-1))

  val pc_sel = MuxLookup(uop.br_type, PC_PLUS4.U, Seq(
                        B_N.U   -> PC_PLUS4.U,
                        B_NE.U  -> Mux(!br_eq,  PC_BRJMP.U, PC_PLUS4.U),
                        B_EQ.U  -> Mux( br_eq,  PC_BRJMP.U, PC_PLUS4.U),
                        B_GE.U  -> Mux(!br_lt,  PC_BRJMP.U, PC_PLUS4.U),
                        B_GEU.U -> Mux(!br_ltu, PC_BRJMP.U, PC_PLUS4.U),
                        B_LT.U  -> Mux( br_lt,  PC_BRJMP.U, PC_PLUS4.U),
                        B_LTU.U -> Mux( br_ltu, PC_BRJMP.U, PC_PLUS4.U),
                        B_J.U   -> PC_BRJMP.U,
                        B_JR.U  -> PC_JALR.U
                        ))

  val is_taken = io.req.valid &&
                   (uop.br_type =/= B_N) &&
                   (pc_sel =/= PC_PLUS4)

  // Branch/Jump Target Calculation
  // For jumps we read the FTQ, and can calculate the target
  // For branches we emit the offset for the core to redirect if necessary
  val target_offset = imm_xprlen(20 downto 0).asSInt

  def encodeVirtualAddress(a0: UInt, ea: UInt): UInt = if (vaddrBitsExtended == vaddrBits) {
    ea
  } else {
    // Efficient means to compress 64-bit VA into vaddrBits+1 bits.
    // (VA is bad if VA(vaddrBits) != VA(vaddrBits-1)).
    val a = a0.asSInt >> vaddrBits
    val msb = Mux(a === 0 || a === -1, ea(vaddrBits), !ea(vaddrBits-1))
    Cat(msb, ea(vaddrBits-1 downto 0)).asUInt
  }

  // "mispredict" means that a branch has been resolved and it must be killed
  val mispredict = CombInit(False)

  val is_br          = io.req.valid && uop.is_br && !uop.is_sfb
  val is_jal         = io.req.valid && uop.is_jal
  val is_jalr        = io.req.valid && uop.is_jalr

  val jalr_target_base = io.req.payload.rs1_data.asSInt
  val jalr_target_xlen = UInt(xLen bits)
  jalr_target_xlen := (jalr_target_base + target_offset.resize(xLen)).asUInt
  val jalr_target_enc = encodeVirtualAddress(jalr_target_xlen, jalr_target_xlen)
  val jalr_target = (jalr_target_enc.asSInt & S(-2, jalr_target_enc.getWidth bits)).asUInt

  val cfi_idx = (uop.pc_lob ^ Mux(io.req.payload.ftq_info(0).entry.start_bank === 1,
    U(1 << log2Ceil(bankBytes), uop.pc_lob.getWidth bits), U(0, uop.pc_lob.getWidth bits)))(log2Ceil(fetchWidth) downto 1)

  when (is_br || is_jalr) {
    when (pc_sel === PC_PLUS4) {
      mispredict := uop.taken
    }
    when (pc_sel === PC_BRJMP) {
      mispredict := !uop.taken
    }
    when (pc_sel === PC_JALR) {
      mispredict := (!io.req.payload.ftq_info(1).valid ||
                     (io.req.payload.ftq_info(1).pc =/= jalr_target.resized) ||
                     !io.req.payload.ftq_info(0).entry.cfi_idx.valid ||
                     (io.req.payload.ftq_info(0).entry.cfi_idx.payload =/= cfi_idx))
    }
  }

  val brinfo = Flow(new BrResolutionInfo)

  // note: jal doesn't allocate a branch-mask, so don't clear a br-mask bit
  brinfo.valid               := is_br || is_jalr
  brinfo.payload.mispredict  := mispredict
  brinfo.payload.uop         := uop
  brinfo.payload.cfi_type    := Mux(is_jalr, CFI_JALR.U,
                                Mux(is_br  , CFI_BR.U, CFI_X.U))
  brinfo.payload.taken       := is_taken
  brinfo.payload.pc_sel      := pc_sel
  brinfo.payload.jalr_target := jalr_target.resized
  brinfo.payload.target_offset := target_offset

  io.brinfo := brinfo

  // Response
  val alu_out = Mux(io.req.payload.uop.is_sfb_shadow && io.req.payload.pred_data,
      Mux(io.req.payload.uop.ldst_is_rs1, io.req.payload.rs1_data, io.req.payload.rs2_data),
      Mux(io.req.payload.uop.is_mov, io.req.payload.rs2_data, alu.io.out))
  io.resp.valid := io.req.valid
  io.resp.payload.uop := io.req.payload.uop
  io.resp.payload.data := Mux(io.req.payload.uop.is_sfb_br, (pc_sel === PC_BRJMP).asUInt.resize(dataWidth), alu_out.resize(dataWidth))
  io.resp.payload.predicated := io.req.payload.uop.is_sfb_shadow && io.req.payload.pred_data
  assert(io.resp.ready)
}

/**
 * Functional unit to wrap lower level FPU
 *
 * Currently, bypassing is unsupported!
 * All FP instructions are padded out to the max latency unit for easy
 * write-port scheduling.
 */
class FPUUnit(implicit p: Parameters)
  extends FunctionalUnit(
    dataWidth = 65,
    needsFcsr = true)
{
  io.req.ready := True
  val numStages = dfmaLatency

  val pipe = new BranchKillablePipeline(new FuncUnitReq(dataWidth), numStages)
  connectPipe(pipe)
  val fpu = new FPU()
  fpu.io.req.valid            := io.req.valid
  fpu.io.req.payload.uop      := io.req.payload.uop
  fpu.io.req.payload.rs1_data := io.req.payload.rs1_data
  fpu.io.req.payload.rs2_data := io.req.payload.rs2_data
  fpu.io.req.payload.rs3_data := io.req.payload.rs3_data
  fpu.io.req.payload.fcsr_rm  := io.fcsr_rm

  io.resp.valid           := pipe.io.resp(numStages-1).valid
  io.resp.payload.uop     := pipe.io.resp(numStages-1).payload.uop
  io.resp.payload.data    := fpu.io.resp.payload.data
  io.resp.payload.fflags.valid   := io.resp.valid
  io.resp.payload.fflags.payload := fpu.io.resp.payload.fflags.payload
}

/**
 * Int to FP conversion functional unit
 *
 * @param latency the amount of stages to delay by
 */
class IntToFPUnit(latency: Int)(implicit p: Parameters)
  extends FunctionalUnit(
    dataWidth = 65,
    needsFcsr = true)
  with tile.HasFPUParameters
{
  val io_req = io.req.payload

  io.req.ready := True
  val pipe = new BranchKillablePipeline(new FuncUnitReq(dataWidth), latency)
  connectPipe(pipe)
  val fp_ctrl = io_req.uop.fp_ctrl
  val fp_rm = Mux(io_req.uop.fp_rm === 7, io.fcsr_rm, io_req.uop.fp_rm)

  val tag = fp_ctrl.typeTagIn
  val unboxed_in1 = unbox(io_req.rs1_data, tag, None)

  assert (!(io.req.valid && fp_ctrl.fromint && unboxed_in1(xLen)),
    "[func] IntToFP integer input has 65th high-order bit set!")

  assert (!(io.req.valid && !fp_ctrl.fromint),
    "[func] Only support fromInt micro-ops.")

  // Chisel builds an FPInput and bulk-connects it to the IntToFPInput port;
  // in1 is then overridden with the raw integer operand.
  val ifpu = new tile.IntToFP(intToFpLatency)
  ifpu.io.in.valid := io.req.valid
  ifpu.io.in.payload.assignCtrlSigs(fp_ctrl)
  ifpu.io.in.payload.rm  := fp_rm
  ifpu.io.in.payload.typ := io_req.uop.fp_typ
  ifpu.io.in.payload.in1 := io_req.rs1_data.resized
  val out_double = Pipe(io.req.valid, fp_ctrl.typeTagOut === D, intToFpLatency).payload

  io.resp.valid           := pipe.io.resp(latency-1).valid
  io.resp.payload.uop     := pipe.io.resp(latency-1).payload.uop
  io.resp.payload.data    := box(ifpu.io.out.payload.data, out_double)
  io.resp.payload.fflags.valid   := io.resp.valid
  io.resp.payload.fflags.payload := ifpu.io.out.payload.exc.asUInt
}

/**
 * Divide functional unit.
 *
 * @param dataWidth data to be passed into the functional unit
 */
class DivUnit(dataWidth: Int)(implicit p: Parameters)
  extends FunctionalUnit(dataWidth = dataWidth)
{
  // We don't use the iterative multiply functionality here.
  // Instead we use the PipelinedMultiplier
  val div = new MulDiv(mulDivParams, width = dataWidth)

  val req = Reg(Flow(new MicroOp()))
  req.valid init False

  when (io.req.fire) {
    req.valid   := !IsKilledByBranch(io.brupdate, io.kill, io.req.payload)
    req.payload := UpdateBrMask(io.brupdate, io.req.payload.uop)
  } otherwise {
    req.valid   := !IsKilledByBranch(io.brupdate, io.kill, req.payload) && req.valid
    req.payload := UpdateBrMask(io.brupdate, req.payload)
  }

  // request
  div.io.req.valid    := io.req.valid && !IsKilledByBranch(io.brupdate, io.kill, io.req.payload)
  div.io.req.payload.dw  := io.req.payload.uop.fcn_dw.asUInt
  div.io.req.payload.fn  := io.req.payload.uop.fcn_op
  div.io.req.payload.in1 := io.req.payload.rs1_data
  div.io.req.payload.in2 := io.req.payload.rs2_data
  div.io.req.payload.tag.assignDontCare()
  io.req.ready        := div.io.req.ready && !req.valid

  // handle pipeline kills and branch misspeculations
  div.io.kill         := (req.valid && IsKilledByBranch(io.brupdate, io.kill, req.payload))

  // response
  div.io.resp.ready   := io.resp.ready
  io.resp.valid       := div.io.resp.valid && req.valid
  io.resp.payload.data   := div.io.resp.payload.data
  io.resp.payload.uop    := req.payload
  when (io.resp.fire) {
    req.valid := False
  }
}

/**
 * Pipelined multiplier functional unit that wraps around the RocketChip pipelined multiplier
 *
 * @param numStages number of pipeline stages
 * @param dataWidth size of the data being passed into the functional unit
 */
class PipelinedMulUnit(numStages: Int, dataWidth: Int)(implicit p: Parameters)
  extends FunctionalUnit(dataWidth = dataWidth)
{
  io.req.ready := True
  val imul = new PipelinedMultiplier(xLen, numStages)
  val pipe = new BranchKillablePipeline(new FuncUnitReq(dataWidth), numStages)
  // request
  imul.io.req.valid    := io.req.valid
  imul.io.req.payload.fn  := io.req.payload.uop.fcn_op
  imul.io.req.payload.dw  := io.req.payload.uop.fcn_dw.asUInt
  imul.io.req.payload.in1 := io.req.payload.rs1_data
  imul.io.req.payload.in2 := io.req.payload.rs2_data
  imul.io.req.payload.tag.assignDontCare()

  connectPipe(pipe)
  // response
  io.resp.valid        := pipe.io.resp(numStages-1).valid
  io.resp.payload.uop  := pipe.io.resp(numStages-1).payload.uop
  io.resp.payload.data := imul.io.resp.payload.data
  io.resp.payload.predicated := False
}
