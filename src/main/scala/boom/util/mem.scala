package boom.util

import spinal.core._
import spinal.lib._
import rocketchip.util._

/**
 * Chisel's `SyncReadMem(depth, Vec(n, T))` with per-element write masks,
 * mapped onto a Spinal `Mem[Bits]` with symbol write masks. Element i lives at
 * bits [i*w, (i+1)*w). Addresses are truncated/extended to the memory's
 * address width, as a Chisel memory port does. Reads follow Chisel's simulated
 * SyncReadMem behaviour (registered address, write-first): see `readSyncChisel`.
 */
class SyncReadVecMem[T <: Data](val depth: Int, val n: Int, gen: HardType[T]) {
  val w: Int = gen.getBitsWidth
  val mem = Mem(Bits(n * w bits), depth)

  def read(addr: UInt, en: Bool = True): Vec[T] = {
    val d = mem.readSyncChisel(addr, en)
    Vec((0 until n).map { i => val e = gen(); e.assignFromBits(d(i * w, w bits)); e })
  }

  def readAsync(addr: UInt): Vec[T] = {
    val d = mem.readAsync(addr.resized)
    Vec((0 until n).map { i => val e = gen(); e.assignFromBits(d(i * w, w bits)); e })
  }

  def write(addr: UInt, data: Seq[T], mask: Seq[Bool], en: Bool = True): Unit = {
    require(data.size == n && mask.size == n)
    mem.write(addr.resized, Cat(data.map(_.asBits).reverse.toList: _*), en, mask.asUInt.asBits)
  }

  def write(addr: UInt, data: Seq[T]): Unit = write(addr, data, Seq.fill(n)(True))

  def setName(name: String): this.type = { mem.setName(name); this }
}

/** Chisel's `SyncReadMem(depth, T)` (whole-word writes). */
class SyncReadMemC[T <: Data](val depth: Int, gen: HardType[T]) {
  val mem = Mem(gen, depth)
  def read(addr: UInt, en: Bool = True): T = mem.readSyncChisel(addr, en)
  def readAsync(addr: UInt): T = mem.readAsync(addr.resized)
  def write(addr: UInt, data: T, en: Bool = True): Unit = mem.write(addr.resized, data, en)
  def setName(name: String): this.type = { mem.setName(name); this }
}
