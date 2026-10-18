import spinal.core._

package object hardfloat {
  // Aliases for port directions, for bundles that have fields named `in`/`out`
  // (which would otherwise shadow spinal.core.in/out inside the bundle body).
  val IN: spinal.core.in.type = spinal.core.in
  val OUT: spinal.core.out.type = spinal.core.out
}
