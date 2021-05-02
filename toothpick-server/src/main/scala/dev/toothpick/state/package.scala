package dev.toothpick

import zio.Has

package object state {
  type TpState = Has[TpState.Service]
}
