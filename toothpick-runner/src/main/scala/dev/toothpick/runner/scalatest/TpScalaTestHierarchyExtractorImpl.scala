package dev.toothpick.runner.scalatest

import dev.toothpick.proto.api.{TpTest, TpTestClassLocation, TpTestGroup, TpTestSuite}
import dev.toothpick.runner.TpRunnerUtils.{ROOT_NODE_ID, ScalaTestHierarchy, TestNode}

import java.util.concurrent.atomic.AtomicReference

final class TpScalaTestHierarchyExtractorImpl(loader: ClassLoader, traitName: String, engineClassName: String) {
  import dev.toothpick.runner.TpRunnerUtils.TestNodeOps

  private val traitClass = loader.loadClass(traitName)
  private val engineMethod = traitClass.getDeclaredMethod(
    traitName.replace(".", "$") + "$$engine"
  )
  private val bundleTestMapsMethod = loader.loadClass(s"$engineClassName$$Bundle").getDeclaredMethod("testsMap")
  private val bundleTestNamesListMethod =
    loader.loadClass(s"$engineClassName$$Bundle").getDeclaredMethod("testNamesList")
  private val atomicMethod = loader.loadClass(engineClassName).getDeclaredMethod("atomic")
  private val testLeafClass = loader.loadClass(s"$engineClassName$$TestLeaf")
  private val descriptionBranchClass = loader.loadClass(s"$engineClassName$$DescriptionBranch")

  private val testLeafParentMethod = testLeafClass.getDeclaredMethod("parent")
  private val testLeafTestNameMethod = testLeafClass.getDeclaredMethod("testName")
  private val testLeafTestTextMethod = testLeafClass.getDeclaredMethod("testText")
  private val testLeafPosMethod = testLeafClass.getDeclaredMethod("pos")

  private val descriptionBranchParentMethod = descriptionBranchClass.getDeclaredMethod("parent")
  private val descriptionBranchTextMethod = descriptionBranchClass.getDeclaredMethod("descriptionText")
  private val descriptionBranchChildPrefixMethod = descriptionBranchClass.getDeclaredMethod("childPrefix")
  private val descriptionBranchLocationMethod = descriptionBranchClass.getDeclaredMethod("location")

  private val sourcePositionClass = loader.loadClass("org.scalactic.source.Position")
  private val fileNameMethod = sourcePositionClass.getDeclaredMethod("fileName")
  private val lineNumberMethod = sourcePositionClass.getDeclaredMethod("lineNumber")
  private val lineInFileLocationClass = loader.loadClass("org.scalatest.events.LineInFile")
  private val lineInFileLineNumberMethod = lineInFileLocationClass.getDeclaredMethod("lineNumber")
  private val lineInFileFileNameMethod = lineInFileLocationClass.getDeclaredMethod("fileName")

  final class Extractor(suiteClassName: String, seedId: Int, testNameFilters: Seq[String]) {
    def unapply(suite: Any): Option[ScalaTestHierarchy] = {
      if (traitClass.isInstance(suite)) {
        val engine = engineMethod.invoke(suite)
        val bundle = atomicMethod.invoke(engine).asInstanceOf[AtomicReference[Any]].get()
        val testNamesList = bundleTestNamesListMethod.invoke(bundle).asInstanceOf[List[String]]
        val testsMap = bundleTestMapsMethod.invoke(bundle).asInstanceOf[Map[String, Any]]

        def traverse(
          node: Any,
          map: Map[Any, TestNode],
          nextId: Int
        ): (TestNode, Map[Any, TestNode], Int) = {
          map.get(node) match {
            case Some(existing) => (existing, map, nextId)
            case None =>
              if (testLeafClass.isInstance(node)) {
                val parent = testLeafParentMethod.invoke(node)
                val (parentNode, newMap, id) = traverse(parent, map, nextId)

                val testName = testLeafTestNameMethod.invoke(node).asInstanceOf[String]
                val testText = testLeafTestTextMethod.invoke(node).asInstanceOf[String]
                val location = testLeafPosMethod.invoke(node).asInstanceOf[Option[Any]].map { pos =>
                  TpTestClassLocation(
                    className = suiteClassName,
                    fileName = fileNameMethod.invoke(pos).asInstanceOf[String],
                    lineNumber = lineNumberMethod.invoke(pos).asInstanceOf[Int]
                  )
                }

                val leaf = TpTest(
                  fullName = testName,
                  name = testText,
                  id = id,
                  parentId = parentNode.id,
                  className = suiteClassName,
                  location = location
                )

                (leaf, newMap.updated(node, leaf), id + 1)
              }
              else if (descriptionBranchClass.isInstance(node)) {
                val parent = descriptionBranchParentMethod.invoke(node)
                val (parentNode, newMap, id) = traverse(parent, map, nextId)

                val text = descriptionBranchTextMethod.invoke(node).asInstanceOf[String]
                val childPrefix = descriptionBranchChildPrefixMethod.invoke(node).asInstanceOf[Option[String]]
                val location = descriptionBranchLocationMethod.invoke(node).asInstanceOf[Option[Any]].flatMap { loc =>
                  if (lineInFileLocationClass.isInstance(loc)) {
                    Some(TpTestClassLocation(
                      className = suiteClassName,
                      fileName = lineInFileFileNameMethod.invoke(loc).asInstanceOf[String],
                      lineNumber = lineInFileLineNumberMethod.invoke(loc).asInstanceOf[Int]
                    ))
                  }
                  else {
                    None
                  }
                }

                val group = TpTestGroup(
                  name = text,
                  id = id,
                  childNamePrefix = childPrefix,
                  parentId = parentNode.id,
                  location = location
                )

                (group, newMap.updated(node, group), id + 1)
              }
              else {
                val suite = TpTestSuite(
                  name = suiteClassName,
                  id = nextId,
                  parentId = ROOT_NODE_ID,
                  duplicateSeq = 0,
                  hasFilters = testNameFilters.nonEmpty
                )

                (suite, map.updated(node, suite), nextId + 1)
              }
          }
        }

        val (hierarchy, lastId) = testNamesList
          .foldLeft((Map.empty[Any, TestNode], seedId)) { case ((map, nextId), testName) =>
            if (testNameFilters.isEmpty || testNameFilters.exists(f => testName.contains(f))) {
              val leaf = testsMap(testName)
              val (_, newMap, newNextId) = traverse(leaf, map, nextId)
              newMap -> newNextId
            }
            else {
              map -> nextId
            }
          }

        val result = hierarchy
          .map { case (_, value) =>
            value.id -> value
          }

        Some(ScalaTestHierarchy(result, lastId))
      }
      else None
    }
  }

  def apply(suiteClassName: String, seedId: Int, testNameFilters: Seq[String]): Extractor = {
    new Extractor(suiteClassName, seedId, testNameFilters)
  }
}
