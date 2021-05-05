package dev.toothpick.reporter

import dev.toothpick.runner.TpRunnerUtils.{ROOT_NODE_ID, TestNode}

import scala.collection.immutable.Queue

final case class TpTestHierarchy(
  nodeMap: Map[Int, TestNode],
  topDownNodeQueue: Queue[TestNode],
  topDownNodeMap: Map[Int, Set[Int]]
)

object TpTestHierarchy {
  import dev.toothpick.runner.TpRunnerUtils.TestNodeOps

  def create(nodeMap: Map[Int, TestNode]): TpTestHierarchy = {
    val topDownMap = nodeMap
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
        if (id == ROOT_NODE_ID) {
          queue
        }
        else {
          queue.enqueue(nodeMap(id))
        }

      topDownMap(id).toVector.sorted.foldLeft(nextQueue) { (q, cid) =>
        buildTopDownQueue(q, cid)
      }
    }

    val topDownQueue = buildTopDownQueue(Queue.empty, 0)

    TpTestHierarchy(nodeMap = nodeMap, topDownNodeQueue = topDownQueue, topDownNodeMap = topDownMap)
  }

  def trimTopDownNodeMap(
    state: TpTestHierarchy,
    id: Int,
    maybeChildId: Option[Int] = None
  ): (TpTestHierarchy, List[TestNode]) = {
    (state.topDownNodeMap.get(id), maybeChildId) match {
      case (Some(set), Some(childId)) if set.contains(childId) =>
        val newSet = set - childId

        if (newSet.isEmpty) {
          if (id == ROOT_NODE_ID) {
            state.copy(topDownNodeMap = Map.empty) -> Nil
          }
          else {
            val node = state.nodeMap(id)
            val parentId = node.parentId

            val (newState, emitList) =
              trimTopDownNodeMap(state.copy(topDownNodeMap = state.topDownNodeMap - id), parentId, Some(id))
            (newState, node :: emitList)
          }
        }
        else {
          state.copy(topDownNodeMap = state.topDownNodeMap.updated(id, newSet)) -> Nil
        }

      case (Some(set), None) if set.isEmpty =>
        val node = state.nodeMap(id)
        val parentId = node.parentId
        val (newState, emitList) =
          trimTopDownNodeMap(state.copy(topDownNodeMap = state.topDownNodeMap - id), parentId, Some(id))
        (newState, node :: emitList)

      case _ =>
        throw new IllegalStateException(s"Invalid state: $state $id $maybeChildId")
    }
  }
}
