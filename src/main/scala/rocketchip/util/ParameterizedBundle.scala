package rocketchip.util

import spinal.core._
import rocketchip.config.Parameters

/**
 * Base for bundles that take the elaboration `Parameters` implicitly.
 *
 * Spinal clones bundles reflectively and cannot recover an implicit
 * constructor argument that isn't stored as a field. This base re-supplies
 * `p` for every `Parameters`-typed constructor argument. Any other
 * constructor arguments must be declared as `val`s (they are read back from
 * the instance's own fields, in declaration order).
 */
abstract class ParameterizedBundle(implicit val p: Parameters) extends Bundle {
  override def clone: Bundle = {
    if (hardtype != null) return super.clone
    val clazz = getClass
    val ctor = clazz.getConstructors.head
    val types = ctor.getParameterTypes
    val fields = clazz.getDeclaredFields.filterNot { f =>
      f.getType == classOf[Parameters] || f.getName.contains("$") || java.lang.reflect.Modifier.isStatic(f.getModifiers)
    }
    val outer = clazz.getDeclaredFields.find(_.getName == "$outer").map { f => f.setAccessible(true); f.get(this) }
    var k = 0
    val args = types.zipWithIndex.map { case (t, i) =>
      if (t == classOf[Parameters]) p
      else if (i == 0 && outer.isDefined && t.isInstance(outer.get)) outer.get
      else {
        val f = fields(k); k += 1
        f.setAccessible(true)
        f.get(this) match {
          case d: Data => cloneOf(d)
          case o => o
        }
      }
    }
    ctor.newInstance(args.asInstanceOf[Array[AnyRef]]: _*).asInstanceOf[Bundle]
  }
}
