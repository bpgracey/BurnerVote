package info.bpgracey.burnervote.BurnerVote

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Props
import akka.event.Logging
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.pattern.ask
import akka.pattern.pipe
import spray.json._
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.util.ByteString
import scala.util.Try

class VotingActor extends Actor with ActorLogging {
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
      // get list of files, from start
      DropBoxDAO.startList()
    case Continue =>
      // get list of updates/continuations
      DropBoxDAO.continueList()
  }
}

object VotingActor {
  type MediaName = String
  
  case class SaveFile(uri: Uri)
  case class Vote(filename: MediaName)
  case object Update
  case object Continue

  def props: Props = Props(new VotingActor)
  
  private val votes = scala.collection.mutable.Map[MediaName, Int]() // the database!
  
  def voteFor(name: MediaName): Unit = {
    votes.get(name) match {
      case Some(count) => votes.put(name, count + 1)
      case _ => // do nothing
    }
  }
  
  def add(name: MediaName): Unit = {
    if (!votes.contains(name)) votes.put(name, 0)
  }
  
  def result = BurnerVotes(votes.map((a) => BurnerVoteCount(a._1, a._2)).toSeq)
}

class DropBoxActor extends Actor with ActorLogging with DropBoxJsonSupport {
  import DropBoxActor._
  import VotingActor.add
  
  import context.dispatcher
  
  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  
  val http = Http(context.system)
  
  def receive = {
    // trigger a request, send response to this actor
    case DropBoxRequest(request) => http.singleRequest(request).pipeTo(self)
    
    // response is OK
    case HttpResponse(StatusCodes.OK, headers, entity, _) =>
      log.debug("Request succeeded: {}", entity)
      val body = entity.dataBytes.runFold(ByteString(""))(_ ++ _)
      body.onSuccess {
        case str: ByteString if !str.isEmpty =>
          val payload = str.utf8String
          log.info("Received: {}", payload)
          Try(payload.parseJson.asJsObject) match {
            case Success(json) if json.fields.contains("entries") =>
              log.info("Processing list_folder message")
              processJson(dropBoxFileListFormat.read(json))
            case _ => log.info("Not a list_folder message")
          }
        case _ => log.info("Entity not recognised") 
      }
      
    // response is not OK (note: this will include other 200-series codes!)
    case HttpResponse(code, _, body, _) => log.warning("Request failed, code {}: {}", code, body)
  }
  
  def processJson(str: DropBoxFileList): Unit = {
    DropBoxDAO.cursor = str.cursor
    str.entries.filter(_.tag == "file").map(_.fileName).foreach(add(_))
    if (str.has_more) self ! DropBoxRequest(DropBoxDAO.listFilesContinueRequest)
  }
}

object DropBoxActor {
  case class DropBoxRequest(request: HttpRequest)
  
  def props: Props = Props(new DropBoxActor)
}

case class DropBoxEntry(tag: String, fileName: String)
case class DropBoxFileList(entries: List[DropBoxEntry], cursor: String, has_more: Boolean)
case class DropBoxSaveUrl(path: String, url: String)
case class DropBoxCursor(cursor: String)

trait DropBoxJsonSupport extends DefaultJsonProtocol {
  implicit val dropBoxSaveUrlFormat = jsonFormat2(DropBoxSaveUrl)
  
  implicit object DropBoxEntryFormat extends RootJsonFormat[DropBoxEntry] {
    def write(entry: DropBoxEntry) = JsObject(".tag" -> JsString(entry.tag), "name" -> JsString(entry.fileName))
    def read(value: JsValue) = {
      value.asJsObject.getFields(".tag", "name") match {
        case Seq(JsString(tag), JsString(name)) => new DropBoxEntry(tag, name)
        case _ => throw new DeserializationException("DropBoxEntry expected")
      }
    }
  }
  
  implicit val dropBoxFileListFormat = jsonFormat3(DropBoxFileList)
  implicit val dropBoxCursorFormat = jsonFormat1(DropBoxCursor)
}

object DropBoxDAO extends DropBoxJsonSupport {
  import AkkaSystem._
  implicit val log = Logging(system, this.getClass)
  
  val dropBoxActor = system.actorOf(DropBoxActor.props, "dropbox")
  
  val TOKEN = Settings.DBTOKEN
  val PATH = Settings.DBPATH
  log.info("Dropbox path: {}", PATH)
  
  var cursor: String = "" // won't know this value yet...
  
  lazy val saveUri = Uri("https://api.dropboxapi.com/2/files/save_url");
  lazy val listUri = Uri("https://api.dropbox.com/2/files/list_folder")
  lazy val listContinueUri = Uri("https://api.dropbox.com/2/files/list_folder/continue")
  lazy val token = OAuth2BearerToken(TOKEN)
  lazy val headers = List(Authorization(token.asInstanceOf[HttpCredentials])) // These 2 lines took 2 hours!
  
  def request(uri: Uri) = HttpRequest(
      method = HttpMethods.POST,
      headers = headers,
      uri = uri
  )
  
  lazy val saveUrlRequest = request(saveUri)
  lazy val listFilesRequest = request(listUri).withEntity(listFilesEntity)
  lazy val listFilesContinueRequest = request(listContinueUri).withEntity(listFilesCursorEntity)
  
  def fileName(uri: Uri): String = PATH + "/" + uri.path.reverse.head.toString()
  
  def saveUrlEntity(url: Uri) = HttpEntity(ContentTypes.`application/json`, DropBoxSaveUrl(fileName(url), url.toString).toJson.toString())
  lazy val listFilesEntityBody = JsObject("path" -> JsString(PATH), "recursive" -> JsFalse, "include_media_info" -> JsFalse, "include_deleted" -> JsFalse, "include_has_explicit_shared_members" -> JsFalse)
  lazy val listFilesEntity = HttpEntity(ContentTypes.`application/json`, listFilesEntityBody.toString())
  def listFilesCursorEntity = if (cursor isEmpty) throw new Exception("Too soon!") else HttpEntity(ContentTypes.`application/json`, DropBoxCursor(cursor).toJson.toString())
 
  def makeRequest(request: HttpRequest) {
    log.debug("Request: {}", request)
    dropBoxActor ! DropBoxActor.DropBoxRequest(request)
  }
  
  def saveUrl(uri: Uri) {makeRequest(saveUrlRequest.withEntity(saveUrlEntity(uri)))}
  
  def startList() {makeRequest(listFilesRequest)}
  
  def continueList() {makeRequest(listFilesContinueRequest)}
}

