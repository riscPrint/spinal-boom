package rocketchip.config

import spinal.core._
import rocketchip.tile.TileParams
import rocketchip.util._

/**
 * Replacement for rocket-chip's CDE `Parameters` + diplomacy-derived values.
 *
 * In rocket-chip, a tile learns its physical address width, block size,
 * bus width and the memory map (PMAs) through diplomatic negotiation. Here
 * those are static elaboration parameters; the SoC that instantiates the
 * tile is responsible for making its TileLink slaves agree with `memoryMap`.
 */
case class Parameters(tile: TileParams, system: SystemParams)

/** An aligned power-of-two address set (rocket diplomacy AddressSet). */
case class AddressSet(base: BigInt, mask: BigInt) {
  require((base & mask) == 0, s"AddressSet base $base not aligned to mask $mask")
  require(((mask + 1) & mask) == 0, s"AddressSet mask $mask is not contiguous")
  def size: BigInt = mask + 1
  def max: BigInt = base | mask
  def contains(x: BigInt): Boolean = ((x ^ base) & ~mask) == 0
  def contains(x: UInt): Bool = {
    val w = x.getWidth
    val all = (BigInt(1) << w) - 1
    if (base > all) False
    else ((x ^ U(base, w bits)) & U(~mask & all, w bits)) === 0
  }
  def overlaps(x: AddressSet): Boolean = ((base ^ x.base) & ~(mask | x.mask)) == 0
  def contains(x: AddressSet): Boolean = ((x.base & ~mask) == base) && ((x.mask & ~mask) == 0) // x within this
  /** rocket-chip AddressSet.alignment: the size of the aligned block. */
  def alignment: BigInt = (mask + 1) & ~mask
}

object AddressSet {
  /** rocket-chip AddressSet.unify: merge sets that differ in exactly one base bit. */
  def unify(seq: Seq[AddressSet]): Seq[AddressSet] = {
    val n = seq.size
    val array = seq.toArray
    val filter = Array.fill(n) { false }
    for (i <- 0 until n - 1) {
      if (!filter(i)) {
        for (j <- i + 1 until n) {
          if (!filter(j)) {
            val a = array(i)
            val b = array(j)
            val x = a.base ^ b.base
            if (a.mask == b.mask && x > 0 && (x & (x - 1)) == 0) {
              val c_base = a.base & ~x
              val c_mask = a.mask | x
              filter.update(j, true)
              array.update(i, AddressSet(c_base, c_mask))
            }
          }
        }
      }
    }
    val out = (array zip filter) flatMap { case (a, f) => if (f) None else Some(a) }
    if (out.size != n) unify(out.toSeq) else out.toList
  }

  /** Cover [base, base+size) with aligned power-of-two sets. */
  def misaligned(base: BigInt, size: BigInt): Seq[AddressSet] = {
    if (size == 0) Nil
    else {
      val maxBaseAlignment = base & (-base) // 0 for base=0
      val maxSizeAlignment = BigInt(1) << (size.bitLength - 1)
      val step =
        if (maxBaseAlignment == 0 || maxBaseAlignment > maxSizeAlignment) maxSizeAlignment
        else maxBaseAlignment
      AddressSet(base, step - 1) +: misaligned(base + step, size - step)
    }
  }
}

object RegionType extends Enumeration {
  // Same ordering as rocket-chip: lower is "more cacheable"
  val CACHED, TRACKED, UNCACHED, IDEMPOTENT, VOLATILE, PUT_EFFECTS, GET_EFFECTS = Value
}

/**
 * Static description of a TileLink manager reachable from the tile (the
 * subset of TLSlaveParameters that rocket/BOOM consult).
 */
case class MemoryRegion(
  name: String,
  address: Seq[AddressSet],
  regionType: RegionType.Value,
  executable: Boolean,
  supportsAcquireT: Boolean = false,
  supportsAcquireB: Boolean = false,
  supportsGet: Boolean = true,
  supportsPutFull: Boolean = true,
  supportsPutPartial: Boolean = true,
  supportsArithmetic: Boolean = false,
  supportsLogical: Boolean = false,
  supportsHint: Boolean = false
) {
  def contains(x: UInt): Bool = address.map(_.contains(x)).orR
}

/** The subset of rocket-chip's DebugModuleParams that the core consults. */
case class DebugModuleParams(
  address: AddressSet = AddressSet(0, 0xfff),
  nDscratch: Int = 1,
  debugEntry: BigInt = 0x800,
  debugException: BigInt = 0x808
)

/** System-level parameters a tile would normally receive via diplomacy / CDE keys. */
case class SystemParams(
  paddrBits: Int = 32,
  cacheBlockBytes: Int = 64,      // CacheBlockBytes
  beatBytes: Int = 8,             // SystemBusKey.beatBytes
  maxHartIdBits: Int = 1,         // MaxHartIdBits
  asIdBits: Int = 0,              // ASIdBits
  vmIdBits: Int = 0,              // VMIdBits
  nRoCC: Int = 0,                 // BuildRoCC.size
  debugModule: Option[DebugModuleParams] = None,  // DebugModuleKey
  memoryMap: Seq[MemoryRegion] = Nil
) {
  def managers: Seq[MemoryRegion] = memoryMap

  def findSafe(x: UInt): Bool = memoryMap.map(_.contains(x)).orR

  /** Property of the region containing `x` (only meaningful when findSafe(x)). */
  def fastProperty(x: UInt, member: MemoryRegion => Boolean): Bool =
    memoryMap.filter(member).map(_.contains(x)).orR

  def anySupportLogical: Boolean = memoryMap.exists(_.supportsLogical)
  def anySupportArithmetic: Boolean = memoryMap.exists(_.supportsArithmetic)
  def anySupportAcquireB: Boolean = memoryMap.exists(_.supportsAcquireB)
}
