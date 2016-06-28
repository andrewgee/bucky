package itv.bucky.decl

import itv.bucky._
import itv.contentdelivery.lifecycle.Lifecycle
import itv.httpyroraptor.{UriBuilder, GET}
import itv.utils.{Blob, BlobMarshaller}
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.Matchers._
import itv.contentdelivery.testutilities.SameThreadExecutionContext.implicitly
import PublishCommandBuilder._
import org.scalatest.concurrent.Eventually
import Eventually._
import scala.concurrent.duration._

import scala.util.Random

class DeclarationTest extends FunSuite with ScalaFutures {

  val declarationPatienceConfig: Eventually.PatienceConfig = Eventually.PatienceConfig(timeout = 5.second, interval = 100.millis)

  test("Should be able to declare a queue") {
    val queueName = "queue.declare" + Random.nextInt()

    val (amqpConfig, _, rmqAdminHttp) = IntegrationUtils.configAndHttp

    rmqAdminHttp.handle(GET(UriBuilder / "api" / "queues" / "/" / queueName)).statusCode shouldBe 404

    Lifecycle.using(amqpConfig) { amqpClient =>
      Queue(QueueName(queueName),
        isDurable = true,
        isExclusive = true,
        shouldAutoDelete = false,
        Map.empty).applyTo(amqpClient).futureValue
      rmqAdminHttp.handle(GET(UriBuilder / "api" / "queues" / "/" / queueName)).statusCode shouldBe 200

      val handler = new StubConsumeHandler[Delivery]()
      val lifecycle =
        for {
          _ <- amqpClient.consumer(QueueName(queueName), handler)
          marshaller = new BlobMarshaller[String](Blob.from)
          serializer = publishCommandBuilder[String] using RoutingKey(queueName) using ExchangeName("")
          publisher <- amqpClient.publisher().map(AmqpClient.publisherOf(serializer))
        }
          yield publisher

      Lifecycle.using(lifecycle) { publisher =>
        handler.receivedMessages.size shouldBe 0
        publisher("Hello")
        eventually {
          handler.receivedMessages.size shouldBe 1
        }(declarationPatienceConfig)
      }
    }
  }

  test("Should be able to declare an exchange") {
    val exchangeName = "exchange.declare" + Random.nextInt()

    val (amqpConfig, _, rmqAdminHttp) = IntegrationUtils.configAndHttp

    rmqAdminHttp.handle(GET(UriBuilder / "api" / "exchanges" / "/" / exchangeName)).statusCode shouldBe 404

    Lifecycle.using(amqpConfig) { amqpClient =>
      Exchange(ExchangeName(exchangeName),
        isDurable = false,
        shouldAutoDelete = true,
        isInternal = false,
        arguments = Map.empty).applyTo(amqpClient).futureValue

      eventually {
        rmqAdminHttp.handle(GET(UriBuilder / "api" / "exchanges" / "/" / exchangeName)).statusCode shouldBe 200
      }(declarationPatienceConfig)
    }
  }

  test("Should be able to declare bindings") {
    val exchangeName = ExchangeName("bindingex-" + Random.nextInt())
    val queueName = QueueName("bindingq" + Random.nextInt())
    val routingKey = RoutingKey("bindingr" + Random.nextInt())

    val (amqpConfig, _, _) = IntegrationUtils.configAndHttp

    Lifecycle.using(amqpConfig) { amqpClient =>
      Exchange(exchangeName,
        isDurable = false,
        shouldAutoDelete = true,
        isInternal = false,
        arguments = Map.empty).applyTo(amqpClient).futureValue

      Queue(queueName,
        isDurable = false,
        isExclusive = true,
        shouldAutoDelete = false,
        Map.empty).applyTo(amqpClient).futureValue

      Binding(exchangeName,
        queueName,
        routingKey,
        Map.empty).applyTo(amqpClient).futureValue

      val handler = new StubConsumeHandler[Delivery]()
      val lifecycle =
        for {
          _ <- amqpClient.consumer(queueName, handler)
          marshaller = new BlobMarshaller[String](Blob.from)
          serializer = publishCommandBuilder[String] using routingKey using exchangeName
          publisher <- amqpClient.publisher().map(AmqpClient.publisherOf(serializer))
        }
          yield publisher

      Lifecycle.using(lifecycle) { publisher =>
        handler.receivedMessages.size shouldBe 0
        publisher("Hello")
        eventually {
          handler.receivedMessages.size shouldBe 1
        }(declarationPatienceConfig)
      }
    }

  }

}