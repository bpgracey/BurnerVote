package info.bpgracey.burnervote.BurnerVote

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.DefaultJsonProtocol
import spray.json.RootJsonFormat
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.DeserializationException
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.server.Directives
import scala.concurrent.Future
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.Http
import scala.io.StdIn
import akka.http.scaladsl.model.headers.HttpCredentials
import akka.event.Logging
import akka.event.Logging.LogLevel
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import scala.concurrent.ExecutionContext
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.directives.LogEntry
import akka.http.scaladsl.server.directives.DebuggingDirectives
import akka.http.scaladsl.server.directives.LoggingMagnet
import akka.http.scaladsl.model.StatusCodes
import akka.pattern.ask
import akka.util.Timeout



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
  import AkkaSystem._
  import VotingActor._
  val route =
    post {
      path("event") {
        entity(as[BurnerMessage]) { message =>
          message match {
            case BurnerMessage("inboundMedia", mediaUrl, _, _, _, _) =>
              votingActor ! SaveFile(mediaUrl)
            case BurnerMessage("inboundText", fileName, _, _, _, _) =>
              votingActor ! Vote(fileName)
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
    }
}

object AkkaSystem {
  implicit val system = ActorSystem("burner-system")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  
  import scala.concurrent.duration._
  implicit val timeout = Timeout(5 seconds)
  
  // just 1 actor for now...
  val votingActor = system.actorOf(VotingActor.props, "Booth")
}

object WebServer extends App with JsonService {
  import AkkaSystem._
  
  val log = Logging(system, this.getClass)
  val loggedRoute = requestMethodAndResponseStatusAsInfo(Logging.InfoLevel, route)
  val bindingFuture: Future[ServerBinding] = Http().bindAndHandle(loggedRoute, "localhost", 8080)
  
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
