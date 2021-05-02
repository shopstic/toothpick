package dev.toothpick.runner.intellij

object TpIntellijServiceMessages {
  object Names {
    val TEST_SUITE_STARTED = "testSuiteStarted"
    val TEST_SUITE_FINISHED = "testSuiteFinished"
    val TEST_STARTED = "testStarted"
    val TEST_FINISHED = "testFinished"
    val TEST_STD_OUT = "testStdOut"
    val TEST_STD_ERR = "testStdErr"
    val TEST_FAILED = "testFailed"
    val MESSAGE = "message"
    val TEST_COUNT = "testCount"
  }

  object Attrs {
    val NAME = "name"
    val MESSAGE = "message"
    val DETAILS = "details"
    val TEXT = "text"
    val ERROR_DETAILS = "errorDetails"
    val STATUS = "status"
    val CAPTURE_STANDARD_OUTPUT = "captureStandardOutput"
    val NODE_ID = "nodeId"
    val PARENT_NODE_ID = "parentNodeId"
    val LOCATION_HINT = "locationHint"
    val OUT = "out"
    val ERROR = "error"
    val DURATION = "duration"
    val COUNT = "count"
  }
}
