package com.itv.bucky.future

import java.util.concurrent.{ScheduledExecutorService, TimeoutException}

import com.itv.bucky.{Any, PublishCommand, Publisher}
import com.itv.lifecycle.{ExecutorLifecycles, Lifecycle}
import com.typesafe.scalalogging.StrictLogging
import org.mockito.Matchers.any
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.FunSuite
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success}

class FutureTimeoutPublisherTest extends FunSuite with ScalaFutures with StrictLogging {
  import Implicits._
  import com.itv.bucky.ext.future._

  test("Returns result of delegate publisher if result occurs before timeout") {
    val command1         = Any.publishCommand()
    val command2         = Any.publishCommand()
    val expectedOutcome1 = Success(())
    val expectedOutcome2 = Failure(new RuntimeException("Bang!"))
    val delegate: Publisher[Future, PublishCommand] = {
      case `command1`                 => Future.fromTry(expectedOutcome1)
      case `command2`                 => Future.fromTry(expectedOutcome2)
      case PublishCommand(_, _, _, _) => fail("Unexpected outcome")
    }
    val service = mock[ScheduledExecutorService]
    when(service.execute(any[Runnable]())).thenAnswer(new Answer[Unit] {
      override def answer(invocation: InvocationOnMock): Unit =
        invocation.getArguments()(0).asInstanceOf[Runnable].run()
    })

    val publisher = new FutureTimeoutPublisher(delegate, 5.seconds)(service)

    publisher(command1).asTry.futureValue shouldBe expectedOutcome1
    publisher(command2).asTry.futureValue shouldBe expectedOutcome2
  }

  test(s"Returns timeout of delegate publisher if result occurs after timeout") {
    Lifecycle.using(ExecutorLifecycles.singleThreadScheduledExecutor) { scheduledExecutor =>
      val delegate: Publisher[Future, PublishCommand] = { _ =>
        Promise[Nothing]().future
      }

      val publisher = new FutureTimeoutPublisher(delegate, 250.millis)(scheduledExecutor)

      val future = publisher(Any.publishCommand()).asTry

      future.value shouldBe None

      val result = Await.result(future, 2.second)
      result shouldBe 'failure
      result.failed.get shouldBe a[TimeoutException]
      result.failed.get.getMessage should include("Timed out").and(include("250 millis"))
    }
  }
}
