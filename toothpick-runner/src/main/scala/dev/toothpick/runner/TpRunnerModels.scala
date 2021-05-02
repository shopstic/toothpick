package dev.toothpick.runner

import dev.toothpick.proto.api.{TpTest, TpTestGroup, TpTestNode, TpTestSuite}

object TpRunnerModels {
  val ROOT_NODE_ID = 0

  type TestNode = TpTestNode.NonEmpty

  final case class ScalaTestHierarchy(map: Map[Int, TestNode], nextId: Int)

  implicit class TestNodeOps(node: TpTestNode.NonEmpty) {
    def id: Int = node match {
      case test: TpTest => test.id
      case group: TpTestGroup => group.id
      case suite: TpTestSuite => suite.id
    }

    def parentId: Int = node match {
      case test: TpTest => test.parentId
      case group: TpTestGroup => group.parentId
      case suite: TpTestSuite => suite.parentId
    }
  }
}
