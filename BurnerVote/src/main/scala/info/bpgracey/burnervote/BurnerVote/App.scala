package info.bpgracey.burnervote.BurnerVote

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.DefaultJsonProtocol
import akka.http.scaladsl.server.Directives

/**
 * @author Bancroft Gracey
 */

case class BurnerMessage(msgType: String, payload: String, fromNumber: String, toNumber: String, userid: String, burnerId: String)

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val burnerMessageFormat = jsonFormat6(BurnerMessage)
}

class JsonService extends Directives with JsonSupport {
  val route =
    post {
      path("/event") {
        entity(as[BurnerMessage]) { message =>
          complete("Received")
        }
      } ~
     get {
        path("/report") {
          complete("Reporting")
        }
      }
  }
}

object App {
  
  def foo(x : Array[String]) = x.foldLeft("")((a,b) => a + b)
  
  def main(args : Array[String]) {
    println( "Hello World!" )
    println("concat arguments = " + foo(args))
  }

}
