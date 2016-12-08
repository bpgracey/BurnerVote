package info.bpgracey.burnervote.BurnerVote

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Props
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.headers.HttpCredentials
import spray.json._
import akka.http.scaladsl.Http
import scala.concurrent.Future
import akka.http.scaladsl.model.HttpResponse
import akka.event.Logging
import scala.util.Success
import scala.util.Failure

class VotingActor extends Actor with ActorLogging {
  import scala.concurrent.ExecutionContext.Implicits.global
  import VotingActor._
  
  def receive = {
    case SaveFile(uri) =>
      log.info("Save {}", uri)
      // contact DropBox
      DropBoxDAO.saveUrl(uri)
      // if successful, refresh file list
    case Vote(filename) =>
      // send vote
      voteFor(filename)
    case Update =>
  }
}

object VotingActor {
  type MediaName = String
  
  case class SaveFile(uri: Uri)
  case class Vote(filename: MediaName)
  case object Update

  def props: Props = Props(new VotingActor)
  
  val votes = scala.collection.mutable.Map[MediaName, Int]()
  // some test data
  votes.put("cats.jpg", 1)
  votes.put("kittens.png", 97)
  votes.put("wibble.mov", 123)
  votes.put("selfie.jpg", 0)
  
  def voteFor(name: MediaName): Unit = {
    votes.get(name) match {
      case Some(count) => votes.put(name, count + 1)
      case _ =>
    }
  }
  
  def result = BurnerVotes(votes.map((a) => BurnerVoteCount(a._1, a._2)).toSeq)
}

case class DropBoxSaveUrl(path: String, url: String)

trait DropBoxJsonSupport extends DefaultJsonProtocol {
  implicit val dropBoxSaveUrlFormat = jsonFormat2(DropBoxSaveUrl)
}

object DropBoxDAO extends DropBoxJsonSupport {
  import AkkaSystem._
  val log = Logging(system, this.getClass)
  
  val TOKEN = "mHbYdNuD7bAAAAAAAAAACtKr8D3ehwbF_w_GVa86Dy0Uxv-vWEWcCGQYUd7__df5"
  val PATH = "/Burner/"
  val saveUri = Uri("https://api.dropboxapi.com/2/files/save_url");
  lazy val token = OAuth2BearerToken(TOKEN)
  lazy val headers = List(Authorization(token.asInstanceOf[HttpCredentials]))
  
  def request(uri: Uri) = HttpRequest(
      method = HttpMethods.POST,
      headers = headers,
      uri = uri
  )
  
  lazy val saveUrlRequest = request(saveUri)
  
  def fileName(uri: Uri): String = PATH + uri.path.reverse.head.toString()
  
  def saveUrlEntity(url: Uri) = HttpEntity(ContentTypes.`application/json`, DropBoxSaveUrl(fileName(url), url.toString).toString())
  
  def saveUrl(uri: Uri) = {
    val request = saveUrlRequest.withEntity(saveUrlEntity(uri))
    log.debug("Save Uri Request: {}", request)
    val response: Future[HttpResponse] = Http().singleRequest(request)
    response.onComplete {
      case Success(resp) => log.info("Success: {}", resp)
      case Failure(e) => log.error("Failure: {}", e)
    }
  }
}

