package slack.rtm

import org.apache.pekko.Done
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, ActorSystem, Props}
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import org.apache.pekko.http.scaladsl.settings.ClientConnectionSettings
import org.apache.pekko.http.scaladsl.{ClientTransport, Http}
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl._
import com.typesafe.config.ConfigFactory
import slack.rtm.WebSocketClientActor._

import java.net.{InetSocketAddress, URI}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

private[rtm] object WebSocketClientActor {
  case class SendWSMessage(message: Message)
  case class RegisterWebsocketListener(listener: ActorRef)
  case class DeregisterWebsocketListener(listener: ActorRef)

  case object WebSocketClientConnected
  case object WebSocketClientDisconnected
  case object WebSocketClientConnectFailed

  case class WebSocketConnectSuccess(queue: SourceQueueWithComplete[Message], closed: Future[Done])
  case object WebSocketConnectFailure
  case object WebSocketDisconnected

  private[WebSocketClientActor] val config   = ConfigFactory.load()
  private[WebSocketClientActor] val useProxy: Boolean = config.getBoolean("slack-scala-client.http.useproxy")

  private[WebSocketClientActor] val maybeSettings: Option[ClientConnectionSettings] = if (useProxy) {
    val proxyHost = config.getString("slack-scala-client.http.proxyHost")
    val proxyPort = config.getString("slack-scala-client.http.proxyPort").toInt

    val httpsProxyTransport = ClientTransport.httpsProxy(InetSocketAddress.createUnresolved(proxyHost, proxyPort))

    Some(ClientConnectionSettings(config)
        .withTransport(httpsProxyTransport))
  } else {
    None
  }

  def apply(url: String)(implicit arf: ActorRefFactory): ActorRef = {
    arf.actorOf(Props(new WebSocketClientActor(url)))
  }
}

private[rtm] class WebSocketClientActor(url: String) extends Actor with ActorLogging {
  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val system: ActorSystem = context.system

  val uri = new URI(url)
  var outboundMessageQueue: Option[SourceQueueWithComplete[Message]] = None

  override def receive = {
    case m: TextMessage =>
      log.debug("[WebSocketClientActor] Received Text Message: {}", m)
      context.parent ! m
    case m: Message =>
      log.debug("[WebsocketClientActor] Received Message: {}", m)
    case SendWSMessage(m) =>
      outboundMessageQueue.map(_.offer(m))
    case WebSocketConnectSuccess(queue, closed) =>
      outboundMessageQueue = Some(queue)
      closed.onComplete(_ => self ! WebSocketDisconnected)
      context.parent ! WebSocketClientConnected
    case WebSocketDisconnected =>
      log.info("[WebSocketClientActor] WebSocket disconnected.")
      context.stop(self)
    case _ =>
  }

  def connectWebSocket(): Unit = {
    val messageSink: Sink[Message, Future[Done]] = {
      Sink.foreach {
        case message => self ! message
      }
    }

    val queueSource: Source[Message, SourceQueueWithComplete[Message]] = {
      Source.queue[Message](1000, OverflowStrategy.dropHead)
    }

    val flow: Flow[Message, Message, (Future[Done], SourceQueueWithComplete[Message])] =
      Flow.fromSinkAndSourceMat(messageSink, queueSource)(Keep.both)

    val (upgradeResponse, (closed, messageSourceQueue)) =
      Http().singleWebSocketRequest(request = WebSocketRequest(url),
        clientFlow = flow,
        settings = maybeSettings.getOrElse(ClientConnectionSettings(system)))

    upgradeResponse.onComplete {
      case Success(upgrade) if upgrade.response.status == StatusCodes.SwitchingProtocols =>
        log.info("[WebSocketClientActor] Web socket connection success")
        self ! WebSocketConnectSuccess(messageSourceQueue, closed)
      case Success(upgrade) =>
        log.info("[WebSocketClientActor] Web socket connection failed: {}", upgrade.response)
        context.parent ! WebSocketClientConnectFailed
        context.stop(self)
      case Failure(err) =>
        log.info("[WebSocketClientActor] Web socket connection failed with error: {}", err.getMessage)
        context.parent ! WebSocketClientConnectFailed
        context.stop(self)
    }
  }

  override def preStart(): Unit = {
    log.info("WebSocketClientActor] Connecting to RTM: {}", url)
    connectWebSocket()
  }

  override def postStop(): Unit = {
    outboundMessageQueue.foreach(_.complete())
    context.parent ! WebSocketClientDisconnected
  }
}
