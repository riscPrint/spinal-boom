package boom.sim

import rocketchip.config.Parameters

import boom.common._
import boom.common.BoomConfigs._

/**
 * Named core configuration for the simulation specs and the cycle-equivalence gate.
 * Only `giga` is supported.
 */
object SimConfigs {
  val presets = Seq("giga")
  val extras = Seq.empty[String]
  val names = presets ++ extras

  def apply(name: String): BoomTileParams = name match {
    case "giga" => giga()
    case other => throw new IllegalArgumentException(s"unknown core config $other; one of ${names.mkString(", ")}")
  }
}
