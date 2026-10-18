// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package rocketchip.tilelink

import spinal.core._
import rocketchip.util._
import rocketchip.rocket.constants.MemoryOpConstants

/** TileLink permission encodings (identical to spinal.lib.bus.tilelink.Param). */
object TLPermissions {
  val aWidth = 2
  val bdWidth = 2
  val cWidth = 3

  // Cap types (Grant = new permissions, Probe = permisions <= target)
  def toT = BitPat(0, bdWidth)
  def toB = BitPat(1, bdWidth)
  def toN = BitPat(2, bdWidth)
  def isCap(x: UInt): Bool = x <= toN.U

  // Grow types (Acquire = permissions >= target)
  def NtoB = BitPat(0, aWidth)
  def NtoT = BitPat(1, aWidth)
  def BtoT = BitPat(2, aWidth)
  def isGrow(x: UInt): Bool = x <= BtoT.U

  // Shrink types (ProbeAck, Release)
  def TtoB = BitPat(0, cWidth)
  def TtoN = BitPat(1, cWidth)
  def BtoN = BitPat(2, cWidth)
  def isShrink(x: UInt): Bool = x <= BtoN.U

  // Report types (ProbeAck, Release)
  def TtoT = BitPat(3, cWidth)
  def BtoB = BitPat(4, cWidth)
  def NtoN = BitPat(5, cWidth)
  def isReport(x: UInt): Bool = x <= NtoN.U
}

object ClientStates {
  val width = 2

  def Nothing = BitPat(0, width)
  def Branch  = BitPat(1, width)
  def Trunk   = BitPat(2, width)
  def Dirty   = BitPat(3, width)

  def hasReadPermission(state: UInt): Bool = state > Nothing.U
  def hasWritePermission(state: UInt): Bool = state > Branch.U
}

object MemoryOpCategories extends MemoryOpConstants {
  def wr = BitPat("b11")   // Op actually writes
  def wi = BitPat("b01")   // Future op will write
  def rd = BitPat("b00")   // Op only reads

  def categorize(cmd: UInt): UInt = {
    val cat = Cat(isWrite(cmd), isWriteIntent(cmd)).asUInt
    //assert(cat.isOneOf(wr,wi,rd), "Could not categorize command.")
    cat
  }
}

/** Stores the client-side coherence information,
  * such as permissions on the data and whether the data is dirty.
  * Its API can be used to make TileLink messages in response to
  * memory operations, cache control oeprations, or Probe messages.
  */
class ClientMetadata extends Bundle {
  /** Actual state information stored in this bundle */
  val state = UInt(ClientStates.width bits)

  /** Metadata equality */
  def ===(rhs: UInt): Bool = state === rhs
  def ===(rhs: ClientMetadata): Bool = state === rhs.state
  def =/=(rhs: ClientMetadata): Bool = !this.===(rhs)

  /** Is the block's data present in this cache */
  def isValid(dummy: Int = 0): Bool = state > ClientStates.Nothing.U

  private def lookup2(key: UInt, default: (Bool, UInt), table: Seq[(BitPat, (Bool, UInt))]): (Bool, UInt) = {
    val k = table.map(t => t._1.U)
    (MuxLookup(key, default._1, k.zip(table.map(_._2._1))), MuxLookup(key, default._2, k.zip(table.map(_._2._2))))
  }

  /** Determine whether this cmd misses, and the new state (on hit) or param to be sent (on miss) */
  private def growStarter(cmd: UInt): (Bool, UInt) = {
    import MemoryOpCategories._
    import TLPermissions._
    import ClientStates._
    val c = categorize(cmd)
    lookup2(Cat(c, state).asUInt, (False, U(0, 2 bits)), Seq(
      //(effect, am now) -> (was a hit,   next)
      (rd ## Dirty)   -> (True,  Dirty.U),
      (rd ## Trunk)   -> (True,  Trunk.U),
      (rd ## Branch)  -> (True,  Branch.U),
      (wi ## Dirty)   -> (True,  Dirty.U),
      (wi ## Trunk)   -> (True,  Trunk.U),
      (wr ## Dirty)   -> (True,  Dirty.U),
      (wr ## Trunk)   -> (True,  Dirty.U),
      //(effect, am now) -> (was a miss,  param)
      (rd ## Nothing) -> (False, NtoB.U),
      (wi ## Branch)  -> (False, BtoT.U),
      (wi ## Nothing) -> (False, NtoT.U),
      (wr ## Branch)  -> (False, BtoT.U),
      (wr ## Nothing) -> (False, NtoT.U)))
  }

  /** Determine what state to go to after miss based on Grant param
    * For now, doesn't depend on state (which may have been Probed).
    */
  private def growFinisher(cmd: UInt, param: UInt): UInt = {
    import MemoryOpCategories._
    import TLPermissions._
    import ClientStates._
    val c = categorize(cmd)
    //assert(c === rd || param === toT, "Client was expecting trunk permissions.")
    MuxLookup(Cat(c, param).asUInt, Nothing.U, Seq(
      //(effect param) -> (next)
      (rd ## toB).U -> Branch.U,
      (rd ## toT).U -> Trunk.U,
      (wi ## toT).U -> Trunk.U,
      (wr ## toT).U -> Dirty.U))
  }

  /** Does this cache have permissions on this block sufficient to perform op,
    * and what to do next (Acquire message param or updated metadata). */
  def onAccess(cmd: UInt): (Bool, UInt, ClientMetadata) = {
    val r = growStarter(cmd)
    (r._1, r._2, ClientMetadata(r._2))
  }

  /** Does a secondary miss on the block require another Acquire message */
  def onSecondaryAccess(first_cmd: UInt, second_cmd: UInt): (Bool, Bool, UInt, ClientMetadata, UInt) = {
    import MemoryOpCategories._
    val r1 = growStarter(first_cmd)
    val r2 = growStarter(second_cmd)
    val needs_second_acq = isWriteIntent(second_cmd) && !isWriteIntent(first_cmd)
    val hit_again = r1._1 && r2._1
    val dirties = categorize(second_cmd) === wr.U
    val biggest_grow_param = Mux(dirties, r2._2, r1._2)
    val dirtiest_state = ClientMetadata(biggest_grow_param)
    val dirtiest_cmd = Mux(dirties, second_cmd, first_cmd)
    (needs_second_acq, hit_again, biggest_grow_param, dirtiest_state, dirtiest_cmd)
  }

  /** Metadata change on a returned Grant */
  def onGrant(cmd: UInt, param: UInt): ClientMetadata = ClientMetadata(growFinisher(cmd, param))

  /** Determine what state to go to based on Probe param */
  private def shrinkHelper(param: UInt): (Bool, UInt, UInt) = {
    import ClientStates._
    import TLPermissions._
    val table = Seq(
      //(wanted, am now)  -> (hasDirtyData resp, next)
      (toT ## Dirty)   -> (True,  TtoT, Trunk),
      (toT ## Trunk)   -> (False, TtoT, Trunk),
      (toT ## Branch)  -> (False, BtoB, Branch),
      (toT ## Nothing) -> (False, NtoN, Nothing),
      (toB ## Dirty)   -> (True,  TtoB, Branch),
      (toB ## Trunk)   -> (False, TtoB, Branch),  // Policy: Don't notify on clean downgrade
      (toB ## Branch)  -> (False, BtoB, Branch),
      (toB ## Nothing) -> (False, NtoN, Nothing),
      (toN ## Dirty)   -> (True,  TtoN, Nothing),
      (toN ## Trunk)   -> (False, TtoN, Nothing), // Policy: Don't notify on clean downgrade
      (toN ## Branch)  -> (False, BtoN, Nothing), // Policy: Don't notify on clean downgrade
      (toN ## Nothing) -> (False, NtoN, Nothing))
    val key = Cat(param, state).asUInt
    val keys = table.map(_._1.U)
    (MuxLookup(key, False, keys.zip(table.map(_._2._1))),
      MuxLookup(key, U(0, cWidth bits), keys.zip(table.map(_._2._2.U))),
      MuxLookup(key, U(0, ClientStates.width bits), keys.zip(table.map(_._2._3.U))))
  }

  /** Translate cache control cmds into Probe param */
  private def cmdToPermCap(cmd: UInt): UInt = {
    import MemoryOpCategories._
    import TLPermissions._
    MuxLookup(cmd, toN.U, Seq(
      M_FLUSH.U   -> toN.U,
      M_PRODUCE.U -> toB.U,
      M_CLEAN.U   -> toT.U))
  }

  def onCacheControl(cmd: UInt): (Bool, UInt, ClientMetadata) = {
    val r = shrinkHelper(cmdToPermCap(cmd))
    (r._1, r._2, ClientMetadata(r._3))
  }

  def onProbe(param: UInt): (Bool, UInt, ClientMetadata) = {
    val r = shrinkHelper(param)
    (r._1, r._2, ClientMetadata(r._3))
  }
}

/** Factories for ClientMetadata, including on reset */
object ClientMetadata {
  def apply(perm: UInt): ClientMetadata = {
    val meta = new ClientMetadata
    meta.state := perm.resized
    meta
  }
  def onReset: ClientMetadata = ClientMetadata(ClientStates.Nothing.U)
  def maximum: ClientMetadata = ClientMetadata(ClientStates.Dirty.U)
}
