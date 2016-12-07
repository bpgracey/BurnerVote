package info.bpgracey.burnervote.BurnerVote

import scala.concurrent.Future
import scala.io.StdIn

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directive.addByNameNullaryApply
import akka.http.scaladsl.server.Directive.addDirectiveApply
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.RouteResult.route2HandlerFlow
import akka.stream.ActorMaterializer
import spray.json.DefaultJsonProtocol
import spray.json.RootJsonFormat
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.DeserializationException

/**
 * @author Bancroft Gracey
 */

case class BurnerMessage(msgType: String, payload: String, fromNumber: String, toNumber: String, userid: String, burnerId: String)
case class BurnerVoteCount(image: String, votes: Int)
case class BurnerVotes(votes: List[BurnerVoteCount])

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
  val route =
    post {
      path("event") {
        entity(as[BurnerMessage]) { message =>
          val typ = message.msgType
          val pay = message.payload
          complete(s"Received $typ: $pay")
        }
      }
    } ~
    get {
      path("report") {
        val votes = BurnerVotes(List(BurnerVoteCount("cat.jpg", 1), BurnerVoteCount("kittens.png", 123)))
        complete(votes.votes)
      }
    } ~
    get {
      path("status") {
        complete("Hello!")
      }
    }
}

object WebServer extends App with JsonService {
  
  implicit val system = ActorSystem("burner-system")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  
  val bindingFuture: Future[ServerBinding] = Http().bindAndHandle(route, "localhost", 8080)
  println("Running...")
  StdIn.readLine() // user pressed return
  bindingFuture.flatMap(_.unbind()).onComplete(_ => system.terminate())
  println("Stopped!")
}
