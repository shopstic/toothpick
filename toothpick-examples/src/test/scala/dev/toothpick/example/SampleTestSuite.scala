package dev.toothpick.example

import org.scalatest.Succeeded
import org.scalatest.matchers.must.Matchers._
import org.scalatest.wordspec.AsyncWordSpecLike

import java.time.Instant
import java.util.UUID
import scala.concurrent.{blocking, Future}

final class SampleTestSuite extends AsyncWordSpecLike {
  "basic addition" when {
    "1 + 1" should {
      "equal 2" in {
        Future {
          println("Some output from this test")
          (1 + 1) mustEqual 2
        }
      }
      "not equal 3" in {
        Future {
          println("Some output from another test")
          (1 + 1) must not equal 3
        }
      }
    }
  }

  "side-effecting to create artifacts" in {
    Future {
      import better.files.Dsl._
      println("Current working directory:")
      cwd.listRecursively.foreach(f => println(s"- $f"))
      (cwd / UUID.randomUUID().toString).writeText(Instant.now.toString)
      Succeeded
    }
  }

  "print environment" in {
    Future {
      sys.env.foreach { case (k, v) =>
        println(s"ENV $k = $v")
      }
      Succeeded
    }
  }

  "some long tests" when {
    "delay for a while" should {
      "be interrupted if the configured timeout reaches" in {
        Future {
          blocking {
            for (i <- 1 to 30) {
              println(s"Going to sleep, round $i")
              Thread.sleep(1000)
            }
            true mustBe true
          }
        }
      }
    }
  }
}
