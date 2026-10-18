// See LICENSE.Berkeley for license details.

package rocketchip.rocket.constants

import spinal.core._
import rocketchip.util._

trait ScalarOpConstants {
  val SZ_BR = 3
  def BR_X    = BitPat("b???")
  def BR_EQ   = BitPat(0, 3)
  def BR_NE   = BitPat(1, 3)
  def BR_J    = BitPat(2, 3)
  def BR_N    = BitPat(3, 3)
  def BR_LT   = BitPat(4, 3)
  def BR_GE   = BitPat(5, 3)
  def BR_LTU  = BitPat(6, 3)
  def BR_GEU  = BitPat(7, 3)

  def A1_X    = BitPat("b??")
  def A1_ZERO = BitPat(0, 2)
  def A1_RS1  = BitPat(1, 2)
  def A1_PC   = BitPat(2, 2)
  def A1_RS1SHL = BitPat(3, 2)

  def IMM_X  = BitPat("b???")
  def IMM_S  = BitPat(0, 3)
  def IMM_SB = BitPat(1, 3)
  def IMM_U  = BitPat(2, 3)
  def IMM_UJ = BitPat(3, 3)
  def IMM_I  = BitPat(4, 3)
  def IMM_Z  = BitPat(5, 3)

  def A2_X      = BitPat("b???")
  def A2_ZERO   = BitPat(0, 3)
  def A2_SIZE   = BitPat(1, 3)
  def A2_RS2    = BitPat(2, 3)
  def A2_IMM    = BitPat(3, 3)
  def A2_RS2OH  = BitPat(4, 3)
  def A2_IMMOH  = BitPat(5, 3)

  def X = BitPat("b?")
  def N = BitPat("b0")
  def Y = BitPat("b1")

  val SZ_DW = 1
  def DW_X  = X
  def DW_32 = BitPat("b0")
  def DW_64 = BitPat("b1")
  def DW_XPR = DW_64
}
object ScalarOpConstants extends ScalarOpConstants

trait MemoryOpConstants {
  val NUM_XA_OPS = 9
  val M_SZ      = 5
  def M_X       = BitPat("b?????")
  def M_XRD     = BitPat("b00000") // int load
  def M_XWR     = BitPat("b00001") // int store
  def M_PFR     = BitPat("b00010") // prefetch with intent to read
  def M_PFW     = BitPat("b00011") // prefetch with intent to write
  def M_XA_SWAP = BitPat("b00100")
  def M_FLUSH_ALL = BitPat("b00101")  // flush all lines
  def M_XLR     = BitPat("b00110")
  def M_XSC     = BitPat("b00111")
  def M_XA_ADD  = BitPat("b01000")
  def M_XA_XOR  = BitPat("b01001")
  def M_XA_OR   = BitPat("b01010")
  def M_XA_AND  = BitPat("b01011")
  def M_XA_MIN  = BitPat("b01100")
  def M_XA_MAX  = BitPat("b01101")
  def M_XA_MINU = BitPat("b01110")
  def M_XA_MAXU = BitPat("b01111")
  def M_FLUSH   = BitPat("b10000") // write back dirty data and cede R/W permissions
  def M_PWR     = BitPat("b10001") // partial (masked) store
  def M_PRODUCE = BitPat("b10010") // write back dirty data and cede W permissions
  def M_CLEAN   = BitPat("b10011") // write back dirty data and retain R/W permissions
  def M_SFENCE  = BitPat("b10100") // SFENCE.VMA
  def M_HFENCEV = BitPat("b10101") // HFENCE.VVMA
  def M_HFENCEG = BitPat("b10110") // HFENCE.GVMA
  def M_WOK     = BitPat("b10111") // check write permissions but don't perform a write
  def M_HLVX    = BitPat("b10000") // HLVX instruction

  def isAMOLogical(cmd: UInt): Bool = cmd.isOneOf(M_XA_SWAP, M_XA_XOR, M_XA_OR, M_XA_AND)
  def isAMOArithmetic(cmd: UInt): Bool = cmd.isOneOf(M_XA_ADD, M_XA_MIN, M_XA_MAX, M_XA_MINU, M_XA_MAXU)
  def isAMO(cmd: UInt): Bool = isAMOLogical(cmd) || isAMOArithmetic(cmd)
  def isPrefetch(cmd: UInt): Bool = cmd === M_PFR || cmd === M_PFW
  def isRead(cmd: UInt): Bool = cmd.isOneOf(M_XRD, M_HLVX, M_XLR, M_XSC) || isAMO(cmd)
  def isWrite(cmd: UInt): Bool = cmd === M_XWR || cmd === M_PWR || cmd === M_XSC || isAMO(cmd)
  def isWriteIntent(cmd: UInt): Bool = isWrite(cmd) || cmd === M_PFW || cmd === M_XLR
}
object MemoryOpConstants extends MemoryOpConstants
