package boom.sim

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Paths}

/** Minimal ELF64 (little-endian) reader: loadable segments and symbols. */
class Elf(path: String) {
  private val bytes = Files.readAllBytes(Paths.get(path))
  private val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  require(bytes(0) == 0x7f && bytes(1) == 'E' && bytes(2) == 'L' && bytes(3) == 'F', s"$path is not an ELF file")
  require(bytes(4) == 2, "only ELF64 is supported")

  private def u16(o: Long) = bb.getShort(o.toInt) & 0xffff
  private def u32(o: Long) = bb.getInt(o.toInt).toLong & 0xffffffffL
  private def u64(o: Long) = bb.getLong(o.toInt)

  val entry: Long = u64(0x18)
  private val phoff = u64(0x20)
  private val shoff = u64(0x28)
  private val phentsize = u16(0x36)
  private val phnum = u16(0x38)
  private val shentsize = u16(0x3a)
  private val shnum = u16(0x3c)

  /** (physical address, file bytes, memory size) of each PT_LOAD segment. */
  val segments: Seq[(Long, Array[Byte], Long)] = (0 until phnum).flatMap { i =>
    val h = phoff + i * phentsize
    if (u32(h) != 1) None
    else {
      val off = u64(h + 0x08)
      val paddr = u64(h + 0x18)
      val filesz = u64(h + 0x20)
      val memsz = u64(h + 0x28)
      Some((paddr, java.util.Arrays.copyOfRange(bytes, off.toInt, (off + filesz).toInt), memsz))
    }
  }

  /** Symbol name -> value, from every SHT_SYMTAB section. */
  val symbols: Map[String, Long] = {
    val sections = (0 until shnum).map(i => shoff + i * shentsize)
    sections.filter(s => u32(s + 4) == 2).flatMap { s =>
      val off = u64(s + 0x18)
      val size = u64(s + 0x20)
      val link = u32(s + 0x28)
      val entsize = u64(s + 0x38)
      val strOff = u64(sections(link.toInt) + 0x18)
      (0L until size / entsize).map { k =>
        val e = off + k * entsize
        val nameOff = u32(e)
        var end = (strOff + nameOff).toInt
        while (bytes(end) != 0) end += 1
        new String(bytes, (strOff + nameOff).toInt, end - (strOff + nameOff).toInt) -> u64(e + 8)
      }
    }.toMap
  }

  def load(write: (Long, Array[Byte]) => Unit): Unit =
    for ((addr, data, memsz) <- segments) {
      write(addr, data)
      if (memsz > data.length) write(addr + data.length, new Array[Byte]((memsz - data.length).toInt))
    }
}
