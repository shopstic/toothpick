package dev.toothpick.master

import eu.timepit.refined.types.numeric.PosInt

final case class TpMasterConfig(parallelism: PosInt)
