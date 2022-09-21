package dev.toothpick.artifact

import eu.timepit.refined.types.numeric.PosInt

final case class TpArtifactAggregatorConfig(extractionParallelism: PosInt)
