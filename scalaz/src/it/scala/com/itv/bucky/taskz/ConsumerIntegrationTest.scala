package com.itv.bucky.taskz

import com.itv.bucky.Unmarshaller._
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.FunSuite
import org.scalatest.concurrent.{Eventually, ScalaFutures}

import scala.concurrent.duration._
import IntegrationUtils._
import com.itv.bucky.UnmarshalResult.Success
import com.itv.bucky._

import scalaz.concurrent.Task
import org.scalatest.Matchers._
import org.scalatest.Inside._

import scalaz.\/-

class ConsumerIntegrationTest extends FunSuite with ScalaFutures with StrictLogging with Eventually {

  implicit val consumerPatienceConfig: Eventually.PatienceConfig = Eventually.PatienceConfig(timeout = 90.seconds)

  case class Message(value: String)

  val messageUnmarshaller = StringPayloadUnmarshaller map Message

  private val success = \/-(())

  test(s"Can consume messages from a (pre-existing) queue") {
    val handler = new StubConsumeHandler[Task, Message]()
    withPublisherAndConsumer(requeueStrategy = NoneRequeue(AmqpClient.deliveryHandlerOf(handler, toDeliveryUnmarshaller(messageUnmarshaller)))) { app =>
      handler.receivedMessages shouldBe 'empty

      val expectedMessage = randomString()
      app.publisher(PublishCommand(app.exchangeName, app.routingKey, MessageProperties.persistentBasic, Payload.from(expectedMessage))).unsafePerformSyncAttempt should ===(success)

      eventually {
        logger.info(s"Waiting Can consume messages from a (pre-existing) queue")
        handler.receivedMessages should have size 1

        handler.receivedMessages.head shouldBe Message(expectedMessage)
      }
    }
  }

  test("Can extract headers from consumed message") {
    import com.itv.bucky.UnmarshalResult._

    case class Bar(value: String)
    case class Baz(value: String)
    case class Foo(bar: Bar, baz: Baz)

    val barUnmarshaller: Unmarshaller[Delivery, Bar] =
      Unmarshaller liftResult { delivery =>
        if (delivery.properties.headers.contains("bar"))
          Bar(delivery.properties.headers("bar").toString).unmarshalSuccess
        else
          "delivery did not contain bar header".unmarshalFailure
      }

    val bazUnmarshaller: Unmarshaller[Delivery, Baz] =
      toDeliveryUnmarshaller(Unmarshaller liftResult (_.unmarshal[String].map(Baz)))

    val fooUnmarshaller: Unmarshaller[Delivery, Foo] =
      (barUnmarshaller zip bazUnmarshaller) map { case (bar, baz) => Foo(bar, baz) }

    val handler = new StubConsumeHandler[Task, Foo]

    withPublisherAndConsumer(requeueStrategy = NoneRequeue(AmqpClient.deliveryHandlerOf(handler, fooUnmarshaller))) { app =>
      handler.receivedMessages shouldBe 'empty
      val expected = Foo(Bar("bar"), Baz("baz"))

      val publishCommand = PublishCommand(ExchangeName(""),
        app.routingKey,
        MessageProperties.persistentBasic.withHeader("bar" -> expected.bar.value),
        Payload.from(expected.baz.value))

      app.publisher(publishCommand).unsafePerformSyncAttempt should ===(success)

      eventually {
        handler.receivedMessages should have size 1
        handler.receivedMessages.head shouldBe expected
      }
    }
  }

  test(s"DeadLetter upon exception from handler") {
    val handler = new StubConsumeHandler[Task, Delivery]()
    withPublisherAndConsumer(requeueStrategy = SimpleRequeue(handler)) { app =>
      app.dlqHandler.get.receivedMessages shouldBe 'empty
      handler.nextException = Some(new RuntimeException("Hello, world"))
      val expectedMessage = "Message to dlq"
      app.publisher(PublishCommand(app.exchangeName, app.routingKey, MessageProperties.persistentBasic, Payload.from(expectedMessage))).unsafePerformSyncAttempt should ===(success)

      eventually {
        handler.receivedMessages should have size 1
      }
      eventually {
        app.dlqHandler.get.receivedMessages should have size 1
      }
    }
  }

  test("Can consume messages from a (pre-existing) queue with the raw consumer") {
    val handler = new StubConsumeHandler[Task, Delivery]()
    withPublisherAndConsumer(requeueStrategy = NoneRequeue(handler)) { app =>
      handler.receivedMessages shouldBe 'empty

      val expectedMessage = "Hello World!"
      app.publish(Payload.from(expectedMessage), MessageProperties.textPlain).unsafePerformSyncAttempt should ===(success)


      eventually {
        handler.receivedMessages should have size 1
        inside(handler.receivedMessages.head) {
          case Delivery(body, _, _, _) => Payload(body.value).unmarshal[String] shouldBe Success(expectedMessage)
        }
      }
    }
  }

  test("Message headers are exposed to (raw) consumers") {
    val handler = new StubConsumeHandler[Task, Delivery]()
    withPublisherAndConsumer(requeueStrategy = SimpleRequeue(handler)) { app =>
      handler.receivedMessages shouldBe 'empty

      val expectedMessage = "Hello World!"

      val messageProperties = MessageProperties.textPlain.withHeader("hello" -> "world")

      app.publish(Payload.from(expectedMessage), messageProperties).unsafePerformSyncAttempt should ===(success)

      eventually {
        handler.receivedMessages should have size 1
        inside(handler.receivedMessages.head) {
          case Delivery(body, _, _, properties) => properties.headers.get("hello").map(_.toString) shouldBe Some("world")
        }
      }
    }
  }
}
