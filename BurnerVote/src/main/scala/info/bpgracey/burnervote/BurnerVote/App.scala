package info.bpgracey.burnervote.BurnerVote

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.io.StdIn

import akka.actor.ActorSystem
import akka.event.Logging
import akka.event.Logging.LogLevel
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.HttpCredentials
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.directives.LoggingMagnet
import akka.http.scaladsl.server.directives.DebuggingDirectives
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.directives.LogEntry
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import spray.json.DefaultJsonProtocol
import spray.json.DeserializationException
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat



/**
 * @author Bancroft Gracey
 */

case class BurnerMessage(msgType: String, payload: String, fromNumber: String, toNumber: String, userid: String, burnerId: String)
case class BurnerVoteCount(image: String, votes: Int)
case class BurnerVotes(votes: Seq[BurnerVoteCount])

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit object BurnerMessageFormat extends RootJsonFormat[BurnerMessage] {
    def write(msg: BurnerMessage) = JsObject(
        "type" -> JsString(msg.msgType),
        "payload" -> JsString(msg.payload),
        "fromNumber" -> JsString(msg.fromNumber),
        "toNumber" -> JsString(msg.toNumber),
        "userId" -> JsString(msg.userid),
        "burnerId" -> JsString(msg.burnerId)
    )
    
    def read(value: JsValue) = {
      value.asJsObject.getFields("type", "payload", "fromNumber", "toNumber", "userId", "burnerId") match {
        case Seq(JsString(msgType), JsString(payload), JsString(fromNumber), JsString(toNumber), JsString(userId), JsString(burnerId)) =>
          BurnerMessage(msgType, payload, fromNumber, toNumber, userId, burnerId)
        case _ => throw DeserializationException("Burner message expected")
      }
    }
  }
  implicit val burnerVoteCountFormat = jsonFormat2(BurnerVoteCount)
  implicit val burnerVotesFormat = jsonFormat1(BurnerVotes)
}

trait JsonService extends Directives with JsonSupport {
  import VotingActor._
  val route =
    post {
      path("event") {
        entity(as[BurnerMessage]) { message =>
          message match {
            case BurnerMessage("inboundMedia", mediaUrl, _, _, _, _) =>
              BurnerService.saveFile(mediaUrl)
            case BurnerMessage("inboundText", fileName, _, _, _, _) =>
              BurnerService.vote(fileName)
          }
          val typ = message.msgType
          val pay = message.payload
          complete(s"Received $typ: $pay")
        }
      }
    } ~
    get {
      path("report") {
        complete(VotingActor.result.votes)
      }
    } ~
    get {
      path("status") {
        complete("Hello!")
      }
    } ~
    get {
      path("stop") {
        WebServer.stop()
        complete(WebServer.stop())
      }
    } ~
    // dropbox webhook - respond to challenge, process updates to file list
    get {
      path("webhook") {
        parameters('challenge) { challenge =>
          complete(challenge)
        }
      }
    } ~
    post {
      path("webhook") {
        BurnerService.continue
        complete("")
      }
    }
}

object Settings {
  val conf = ConfigFactory.load().getConfig("burnervote")
  val HOST = conf.getString("http.host")
  val PORT = conf.getInt("http.port")
  val DBPATH = conf.getString("dropbox.path")
  val DBTOKEN = conf.getString("dropbox.token")
  if (DBTOKEN isEmpty) throw new Exception("Config - token missing")
}

object AkkaSystem {
  implicit val system = ActorSystem("burner-system")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  
  import scala.concurrent.duration._
  implicit val timeout = Timeout(5 seconds)
  
}

object BurnerService {
  import AkkaSystem._

  // just 1 actor for now...
  val votingActor = system.actorOf(VotingActor.props, "Booth")
  
  def update: Unit = votingActor ! VotingActor.Update
  
  def continue: Unit = votingActor ! VotingActor.Continue
  
  def saveFile(mediaUrl: String): Unit = votingActor ! VotingActor.SaveFile(mediaUrl)
  
  def vote(fileName: String): Unit = votingActor ! VotingActor.Vote(fileName)
}

object WebServer extends App with JsonService {
  import AkkaSystem._
  
  val log = Logging(system, this.getClass)
  val loggedRoute = requestMethodAndResponseStatusAsInfo(Logging.InfoLevel, route)
  val host = Settings.HOST
  val port = Settings.PORT
  log.info("Binding to {}:{}", host, port)
  val bindingFuture: Future[ServerBinding] = Http().bindAndHandle(loggedRoute, host, port)
  
  BurnerService.update
  
  log.info("Running...")
  StdIn.readLine() // user pressed return
  stop()
  
  def stop(): String = {
    bindingFuture.flatMap(_.unbind()).onComplete(_ => system.terminate())
    log.info("Stopped!")
    "Stopped"
  }
  
  def requestMethodAndResponseStatusAsInfo(level: LogLevel, route: Route)
                                          (implicit m: Materializer, ex: ExecutionContext) = {

    def akkaResponseTimeLoggingFunction(loggingAdapter: LoggingAdapter, requestTimestamp: Long)(req: HttpRequest)(res: Any): Unit = {
      val entry = res match {
        case Complete(resp) =>
          val responseTimestamp: Long = System.currentTimeMillis()
          val elapsedTime: Long = responseTimestamp - requestTimestamp
          val loggingString = "Logged Request:" + req.method + ":" + req.uri + ":" + resp.status + ":" + elapsedTime
          LogEntry(loggingString, level)
        case anythingElse =>
          LogEntry(s"$anythingElse", level)
      }
      entry.logTo(loggingAdapter)
    }
    
    DebuggingDirectives.logRequestResult(LoggingMagnet(log => {
      val requestTimestamp = System.currentTimeMillis()
      akkaResponseTimeLoggingFunction(log, requestTimestamp)
    }))(route)

  }
}
