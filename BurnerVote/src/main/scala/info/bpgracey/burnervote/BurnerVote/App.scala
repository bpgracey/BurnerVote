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

/**
 * @author Bancroft Gracey
 */

case class BurnerMessage(msgType: String, payload: String, fromNumber: String, toNumber: String, userid: String, burnerId: String)
case class BurnerVoteCount(image: String, votes: Int)
case class BurnerVotes(votes: List[BurnerVoteCount])

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val burnerMessageFormat = jsonFormat6(BurnerMessage)
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
