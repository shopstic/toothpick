package dev.toothpick.runner

import dev.toothpick.runner.TpRunnerModels.{ROOT_NODE_ID, TestNode}

import scala.collection.immutable.Queue

final case class TpRunnerState(
  hierachy: Map[Int, TestNode],
  topDownQueue: Queue[TestNode],
  pendingMap: Map[Int, Set[Int]]
)

object TpRunnerState {
  import dev.toothpick.runner.TpRunnerModels.TestNodeOps

  def create(hierachy: Map[Int, TestNode]): TpRunnerState = {
    val topDownMap = hierachy
      .values
      .foldLeft(Map.empty[Int, Set[Int]]) { (pendingMap, node) =>
        val id = node.id
        val parentId = node.parentId

        pendingMap
          .updated(id, pendingMap.getOrElse(id, Set.empty))
          .updated(parentId, pendingMap.getOrElse(parentId, Set.empty) + id)
      }

    def buildTopDownQueue(queue: Queue[TestNode], id: Int): Queue[TestNode] = {
      val nextQueue =
        if (id == TpRunnerModels.ROOT_NODE_ID) {
          queue
        }
        else {
          queue.enqueue(hierachy(id))
        }

      topDownMap(id).toVector.sorted.foldLeft(nextQueue) { (q, cid) =>
        buildTopDownQueue(q, cid)
      }
    }

    val topDownQueue = buildTopDownQueue(Queue.empty, 0)

    TpRunnerState(hierachy = hierachy, topDownQueue = topDownQueue, pendingMap = topDownMap)
  }

  def trimPendingMap(
    state: TpRunnerState,
    id: Int,
    maybeChildId: Option[Int] = None
  ): (TpRunnerState, List[TestNode]) = {
    (state.pendingMap.get(id), maybeChildId) match {
      case (Some(set), Some(childId)) if set.contains(childId) =>
        val newSet = set - childId

        if (newSet.isEmpty) {
          if (id == ROOT_NODE_ID) {
            state.copy(pendingMap = Map.empty) -> Nil
          }
          else {
            val node = state.hierachy(id)
            val parentId = node.parentId

            val (newState, emitList) =
              trimPendingMap(state.copy(pendingMap = state.pendingMap - id), parentId, Some(id))
            (newState, node :: emitList)
          }
        }
        else {
          state.copy(pendingMap = state.pendingMap.updated(id, newSet)) -> Nil
        }

      case (Some(set), None) if set.isEmpty =>
        val node = state.hierachy(id)
        val parentId = node.parentId
        val (newState, emitList) = trimPendingMap(state.copy(pendingMap = state.pendingMap - id), parentId, Some(id))
        (newState, node :: emitList)

      case _ =>
        throw new IllegalStateException(s"Invalid state: $state $id $maybeChildId")
    }
  }
}
