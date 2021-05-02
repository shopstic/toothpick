package dev.toothpick

import zio.Has

package object pipeline {
  type TpExecutionPipeline = Has[TpExecutionPipeline.Service]
  type TpDistributionPipeline = Has[TpDistributionPipeline.Service]
}
