//******************************************************************************
// Copyright (c) 2018 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package boom.common

import spinal.core._
import rocketchip.config.Parameters
import rocketchip.util.ParameterizedBundle

/**
 * BOOM module that is used to add parameters to the module
 */
abstract class BoomModule(implicit val p: Parameters) extends Component
  with HasBoomCoreParameters

/**
 * BOOM bundle used to add parameters to the object/class/trait/etc
 */
class BoomBundle(implicit p: Parameters) extends ParameterizedBundle()(p)
  with HasBoomCoreParameters
