//******************************************************************************
// Copyright (c) 2011 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Processor Constants
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.common.constants

import spinal.core._
import rocketchip.config.Parameters
import rocketchip.rocket.RVCExpander
import rocketchip.util._

/**
 * Mixin for scalar operation constants
 */
trait ScalarOpConstants {
  val X = BitPat("b?")
  val Y = BitPat("b1")
  val N = BitPat("b0")

  //************************************
  // Extra Constants

  // Which branch predictor predicted us
  val BSRC_SZ = 3
  val BSRC_1 = BitPat(0, BSRC_SZ) // 1-cycle branch pred
  val BSRC_2 = BitPat(1, BSRC_SZ) // 2-cycle branch pred
  val BSRC_3 = BitPat(2, BSRC_SZ) // 3-cycle branch pred
  val BSRC_4 = BitPat(3, BSRC_SZ) // 4-cycle branch pred
  val BSRC_C = BitPat(4, BSRC_SZ) // core branch resolution

  //************************************
  // Control Signals

  // CFI types
  val CFI_SZ   = 3
  val CFI_X    = BitPat(0, CFI_SZ) // Not a CFI instruction
  val CFI_BR   = BitPat(1, CFI_SZ) // Branch
  val CFI_JAL  = BitPat(2, CFI_SZ) // JAL
  val CFI_JALR = BitPat(3, CFI_SZ) // JALR

  // PC Select Signal
  val PC_PLUS4 = BitPat(0, 2)  // PC + 4
  val PC_BRJMP = BitPat(1, 2)  // brjmp_target
  val PC_JALR  = BitPat(2, 2)  // jump_reg_target

  // Branch Type
  val B_N   = BitPat(0, 4)  // Next
  val B_NE  = BitPat(1, 4)  // Branch on NotEqual
  val B_EQ  = BitPat(2, 4)  // Branch on Equal
  val B_GE  = BitPat(3, 4)  // Branch on Greater/Equal
  val B_GEU = BitPat(4, 4)  // Branch on Greater/Equal Unsigned
  val B_LT  = BitPat(5, 4)  // Branch on Less Than
  val B_LTU = BitPat(6, 4)  // Branch on Less Than Unsigned
  val B_J   = BitPat(7, 4)  // Jump
  val B_JR  = BitPat(8, 4)  // Jump Register

  // RS1 Operand Select Signal
  val OP1_RS1 = BitPat(0, 2) // Register Source #1
  val OP1_ZERO= BitPat(1, 2)
  val OP1_PC  = BitPat(2, 2)
  val OP1_RS1SHL = BitPat(3, 2)
  val OP1_X   = BitPat("b??")

  // RS2 Operand Select Signal
  val OP2_RS2 = BitPat(0, 3) // Register Source #2
  val OP2_IMM = BitPat(1, 3) // immediate
  val OP2_ZERO= BitPat(2, 3) // constant 0
  val OP2_NEXT= BitPat(3, 3) // constant 2/4 (for PC+2/4)
  val OP2_IMMC= BitPat(4, 3) // for CSR imm found in RS1
  val OP2_RS2OH = BitPat(5, 3)
  val OP2_IMMOH = BitPat(6, 3)
  val OP2_X   = BitPat("b???")

  // Register File Write Enable Signal
  val REN_0   = BitPat("b0")
  val REN_1   = BitPat("b1")

  // Is 32b Word or 64b Doubldword?
  val SZ_DW = 1
  val DW_X   = BitPat("b1") // Bool(xLen==64)
  val DW_32  = BitPat("b0")
  val DW_64  = BitPat("b1")
  val DW_XPR = BitPat("b1") // Bool(xLen==64)

  // Memory Enable Signal
  val MEN_0   = BitPat("b0")
  val MEN_1   = BitPat("b1")
  val MEN_X   = BitPat("b0")

  // Immediate Extend Select
  val IS_I   = BitPat(0, 3)  // I-Type  (LD,ALU)
  val IS_S   = BitPat(1, 3)  // S-Type  (ST)
  val IS_B   = BitPat(2, 3)  // SB-Type (BR)
  val IS_U   = BitPat(3, 3)  // U-Type  (LUI/AUIPC)
  val IS_J   = BitPat(4, 3)  // UJ-Type (J/JAL)
  val IS_SH  = BitPat(5, 3)  // short-type (sign extend from pimm to get imm)
  val IS_N   = BitPat(6, 3)  // No immediate (zeros immediate)
  val IS_F3  = BitPat(7, 3)  // funct3

  // Decode Stage Control Signals
  val RT_FIX   = BitPat(0, 2)
  val RT_FLT   = BitPat(1, 2)
  val RT_X     = BitPat(2, 2) // not-a-register (prs1 = lrs1 special case)
  val RT_ZERO  = BitPat(3, 2)

  // IQT type
  val IQ_SZ  = 4
  val IQ_MEM = 0
  val IQ_UNQ = 1
  val IQ_ALU = 2
  val IQ_FP  = 3

  // Functional unit select
  // bit mask, since a given execution pipeline may support multiple functional units
  val FC_SZ  = 10

  val FC_ALU  = 0
  val FC_AGEN = 1
  val FC_DGEN = 2
  val FC_MUL  = 3
  val FC_DIV  = 4
  val FC_CSR  = 5
  val FC_FPU  = 6
  val FC_FDV  = 7
  val FC_I2F  = 8
  val FC_F2I  = 9

  def NullMicroOp(implicit p: Parameters): boom.common.MicroOp = new boom.common.MicroOp().getZero
}

/**
 * Mixin for RISCV constants
 */
trait RISCVConstants {
  // abstract out instruction decode magic numbers
  val RD_MSB  = 11
  val RD_LSB  = 7
  val RS1_MSB = 19
  val RS1_LSB = 15
  val RS2_MSB = 24
  val RS2_LSB = 20
  val RS3_MSB = 31
  val RS3_LSB = 27

  val CSR_ADDR_MSB = 31
  val CSR_ADDR_LSB = 20
  val CSR_ADDR_SZ = 12

  // location of the fifth bit in the shamt (for checking for illegal ops for SRAIW,etc.)
  val SHAMT_5_BIT = 25
  val LONGEST_IMM_SZ = 20
  val X0 = BitPat.lit(0)
  val RA = BitPat.lit(1) // return address register

  // memory consistency model
  // The C/C++ atomics MCM requires that two loads to the same address maintain program order.
  // The Cortex A9 does NOT enforce load/load ordering (which leads to buggy behavior).
  val MCM_ORDER_DEPENDENT_LOADS = true

  val jal_opc = BitPat(0x6f, 7)
  val jalr_opc = BitPat(0x67, 7)

  def GetUop(inst: UInt): UInt = inst(6 downto 0)
  def GetRd (inst: UInt): UInt = inst(RD_MSB downto RD_LSB)
  def GetRs1(inst: UInt): UInt = inst(RS1_MSB downto RS1_LSB)

  def ExpandRVC(inst: UInt)(implicit p: Parameters): UInt = {
    val rvc_exp = new RVCExpander
    rvc_exp.io.in := inst
    Mux(rvc_exp.io.rvc, rvc_exp.io.out.bits, inst)
  }

  // Note: Accepts only EXPANDED rvc instructions
  def ComputeBranchTarget(pc: UInt, inst: UInt, xlen: Int)(implicit p: Parameters): UInt = {
    val b_imm32 = Cat(Fill(20, inst(31)), inst(7), inst(30 downto 25), inst(11 downto 8), U(0, 1 bits)).asSInt
    val w = pc.getWidth max b_imm32.getWidth
    ((pc.asSInt.resize(w) + b_imm32.resize(w)) & S(-2, w bits)).asUInt
  }

  // Note: Accepts only EXPANDED rvc instructions
  def ComputeJALTarget(pc: UInt, inst: UInt, xlen: Int)(implicit p: Parameters): UInt = {
    val j_imm32 = Cat(Fill(12, inst(31)), inst(19 downto 12), inst(20), inst(30 downto 25), inst(24 downto 21), U(0, 1 bits)).asSInt
    val w = pc.getWidth max j_imm32.getWidth
    ((pc.asSInt.resize(w) + j_imm32.resize(w)) & S(-2, w bits)).asUInt
  }

  // Note: Accepts only EXPANDED rvc instructions
  def GetCfiType(inst: UInt)(implicit p: Parameters): UInt = {
    val bdecode = new boom.exu.BranchDecode
    bdecode.io.inst := inst
    bdecode.io.pc := 0
    bdecode.io.out.cfi_type
  }
}

/**
 * Mixin for exception cause constants
 */
trait ExcCauseConstants {
  // a memory disambigious misspeculation occurred
  val MINI_EXCEPTION_MEM_ORDERING = BitPat.lit(16)
  val MINI_EXCEPTION_CSR_REPLAY = BitPat.lit(17)

  require(!rocketchip.rocket.Causes.all.contains(16))
  require(!rocketchip.rocket.Causes.all.contains(17))
}
