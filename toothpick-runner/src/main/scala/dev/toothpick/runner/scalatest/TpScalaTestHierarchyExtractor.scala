package dev.toothpick.runner.scalatest

import dev.toothpick.runner.TpRunnerModels.ScalaTestHierarchy

object TpScalaTestHierarchyExtractor {
  def apply(loader: ClassLoader): TpScalaTestHierarchyExtractor = new TpScalaTestHierarchyExtractor(loader)
}

final class TpScalaTestHierarchyExtractor private (loader: ClassLoader) {
  val createAnyWordSpecLikeExtractor = new TpScalaTestHierarchyExtractorImpl(
    loader = loader,
    traitName = "org.scalatest.wordspec.AnyWordSpecLike",
    engineClassName = "org.scalatest.SuperEngine"
  )

  val createAsyncWordSpecLikeExtractor = new TpScalaTestHierarchyExtractorImpl(
    loader = loader,
    traitName = "org.scalatest.wordspec.AsyncWordSpecLike",
    engineClassName = "org.scalatest.AsyncSuperEngine"
  )

  val createAnyFlatSpecLikeExtractor = new TpScalaTestHierarchyExtractorImpl(
    loader = loader,
    traitName = "org.scalatest.flatspec.AnyFlatSpecLike",
    engineClassName = "org.scalatest.SuperEngine"
  )

  def apply(
    suite: Any,
    suiteClassName: String,
    startId: Int,
    testNameFilters: Seq[String]
  ): ScalaTestHierarchy = {
    val anyWordSpecLike = createAnyWordSpecLikeExtractor(suiteClassName, startId, testNameFilters)
    val asyncWordSpecLike = createAsyncWordSpecLikeExtractor(suiteClassName, startId, testNameFilters)
    val anyFlatSpecLike = createAnyFlatSpecLikeExtractor(suiteClassName, startId, testNameFilters)

    suite match {
      case anyWordSpecLike(result) => result
      case asyncWordSpecLike(result) => result
      case anyFlatSpecLike(result) => result
      case _ => throw new IllegalArgumentException(s"No extractor supports: $suite")
    }
  }
}
