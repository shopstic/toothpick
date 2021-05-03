package dev.toothpick.runner.scalatest

import better.files.File
import com.linkedin.cytodynamics.nucleus.{DelegateRelationshipBuilder, IsolationLevel, LoaderBuilder, OriginRestriction}
import dev.toothpick.runner.TpRunnerUtils.{ScalaTestHierarchy, TestNode}
import dev.toothpick.runner.intellij.TpIntellijTestRunArgsParser.{TpRunnerContext, TpRunnerSuiteFilter}
import zio.Task

import java.util.regex.Pattern

object TpScalaTestDiscovery {
  final private class ObjectPrivateMethodCaller[R](
    loader: ClassLoader,
    packageName: String,
    objectName: String,
    methodName: String
  ) {
    private val javaClassName = s"$packageName.${objectName.replace('.', '$')}$$"
    private val clazz = loader.loadClass(javaClassName)
    private val method = clazz.getDeclaredMethods.find(_.getName == methodName).get
    method.setAccessible(true)
    private val field = clazz.getField("MODULE$")
    private val instance = field.get(null) // null because field is static

    def invoke(args: AnyRef*): R = {
      method.invoke(instance, args: _*).asInstanceOf[R]
    }
  }

  def discover(config: TpRunnerContext, startingNodeId: Int = 1): Task[Map[Int, TestNode]] = {
    Task {
      import scala.jdk.CollectionConverters._

      val classpath = config.environment.classpath
      val filters = config.filters
      val classpathFiles = classpath.map(File(_))
      val testClasses = classpathFiles.filter(_.name == "test-classes").map(_.pathAsString)
      val loader = LoaderBuilder
        .anIsolatingLoader
        .withClasspath(
          classpathFiles
            .filterNot(f => f.name.startsWith("scala-library-"))
            .map(_.uri)
            .asJava
        )
        .withParentRelationship(
          DelegateRelationshipBuilder
            .builder
            .withIsolationLevel(IsolationLevel.FULL)
            .addWhitelistedClassPredicate(s => s.startsWith("jdk.") || s.startsWith("java.") || s.startsWith("scala."))
            .build
        )
        .withOriginRestriction(OriginRestriction.allowByDefault())
        .build

      val suiteFilters =
        if (filters.isEmpty) {
          val suiteClassNames = new ObjectPrivateMethodCaller[Set[String]](
            loader,
            "org.scalatest.tools",
            "SuiteDiscoveryHelper",
            "discoverSuiteNames"
          ).invoke(
            testClasses,
            loader,
            Option.empty[Pattern]
          )

          suiteClassNames.map(name => TpRunnerSuiteFilter(name))
        }
        else {
          filters
        }

      val getSuiteInstance = new ObjectPrivateMethodCaller[Any](
        loader,
        "org.scalatest.tools",
        "DiscoverySuite",
        "getSuiteInstance"
      )

      val extractHierarchy = TpScalaTestHierarchyExtractor(loader)

      val ScalaTestHierarchy(combinedMap, _) = suiteFilters
        .foldLeft(ScalaTestHierarchy(Map.empty[Int, TestNode], startingNodeId)) {
          case (ScalaTestHierarchy(map, startId), suiteFilter) =>
            val suiteClassName = suiteFilter.suiteClassName
            val testNameFilters = suiteFilter.filterTestNames

            val suite = getSuiteInstance.invoke(suiteClassName, loader)

            val ScalaTestHierarchy(hierarchy, nextId) = extractHierarchy(
              suite = suite,
              suiteClassName = suiteClassName,
              startId = startId,
              testNameFilters = testNameFilters
            )

            ScalaTestHierarchy(map ++ hierarchy, nextId)
        }

      combinedMap
    }
  }
}
