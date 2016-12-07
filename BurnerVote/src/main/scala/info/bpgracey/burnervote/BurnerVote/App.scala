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

object DropBoxDAO {
  val TOKEN = "mHbYdNuD7bAAAAAAAAAACtKr8D3ehwbF_w_GVa86Dy0Uxv-vWEWcCGQYUd7__df5"
  val uri = "";
  val token = OAuth2BearerToken(TOKEN)
  
  val request = HttpRequest(
      method = HttpMethods.POST,
      headers = List(Authorization(token.asInstanceOf[HttpCredentials]))
  )
}

trait JsonService extends Directives with JsonSupport {
  val route =
    post {
      path("event") {
        entity(as[BurnerMessage]) { message =>
          message match {
            case BurnerMessage("inboundMedia", mediaUrl, _, _, _, _) => 
            case BurnerMessage("inboundText", fileName, _, _, _, _) =>
          }
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
    } ~
    get {
      path("stop") {
        WebServer.stop()
        complete(WebServer.stop())
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
  stop()
  
  def stop(): String = {
    bindingFuture.flatMap(_.unbind()).onComplete(_ => system.terminate())
    println("Stopped!")
    "Stopped"
  }
}
