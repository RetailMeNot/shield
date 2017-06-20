package shield.swagger

import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import io.swagger.models.{Operation, Path}
import io.swagger.parser.SwaggerParser
import shield.config.ServiceLocation
import shield.routing._
import spray.client.pipelining.SendReceive
import spray.http.parser.HttpParser
import spray.http._

import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.util.Try

class Swagger2Fetcher(docPath: String, pipeline: SendReceive)(implicit executor: ExecutionContext) extends SwaggerFetcher with LazyLogging {
  val parser = new SwaggerParser
  implicit val timeout = Timeout(30.seconds)

  def getDisabledMiddleware(host: ServiceLocation, ops: Operation) : Set[String] = {
    getVendorExtension(host, ops, "x-disabled-middleware")
  }

  def getDisabledListeners(host: ServiceLocation, ops: Operation) : Set[String] = {
    getVendorExtension(host, ops, "x-disabled-listeners")
  }

  def getVendorExtension(host: ServiceLocation, ops: Operation, extension: String) : Set[String] = {
    var excludes = Set[String]()
    for ((key, value) <- ops.getVendorExtensions.asScala) {
      if (key.toLowerCase == extension.toLowerCase) {
        val parsed = Try { value.asInstanceOf[java.util.List[String]].asScala.map(_.toLowerCase()).toSet }
        if (parsed.isFailure) {
          logger.warn(s"Could not read " + extension.toLowerCase + " (list[str]) value from $host - $parsed")
        }
        excludes = excludes | parsed.getOrElse(Set())
      }
    }
    excludes
  }

  def buildOpList(path: Path) : List[(String, Operation)] = {
    var ops = List[(String, Operation)]()

    if (path.getDelete != null) {
      ops = ("delete", path.getDelete) :: ops
    }
    if (path.getGet != null) {
      ops = ("get", path.getGet) :: ops
    }
    if (path.getOptions != null) {
      ops = ("options", path.getOptions) :: ops
    }
    if (path.getPatch != null) {
      ops = ("patch", path.getPatch) :: ops
    }
    if (path.getPost != null) {
      ops = ("post", path.getPost) :: ops
    }
    if (path.getPut != null) {
      ops = ("put", path.getPut) :: ops
    }

    ops
  }

  def translateParams(op: Operation) : List[Param] = {
    op.getParameters.asScala.map { param =>
      param.getIn.toLowerCase match {
        case "body" => BodyParam(param.getName)
        case "formdata" => FormParam(param.getName)
        case "header" => HeaderParam(param.getName.toLowerCase)
        case "path" => PathParam(param.getName)
        case "query" => QueryParam(param.getName)
      }
    }.toList
  }

  def getConsumes(op: Operation) : List[String] = {
    val consumes = op.getConsumes
    if (consumes == null) {
      List()
    } else {
      consumes.asScala.toList
    }
  }

  def getProduces(op: Operation) : List[String] = {
    val produces = op.getProduces
    if (produces == null) {
      List()
    } else {
      produces.asScala.toList
    }
  }

  def translateMediaType(mt: String) : Option[MediaType] = {
    // todo: handle invalid content types
    HttpParser.parse(HttpParser.ContentTypeHeaderValue, mt).fold(e => None, ct => Some(ct.mediaType))
  }


  def fetch(host: ServiceLocation) = {
    logger.info(s"Fetching swagger2 specs from $host")

    // don't use swagger parser's .read() here, because if it fails we get a AbstractMethodError that kills the entire system (it bypasses try catch, and escapes past actor isolation)
    pipeline(HttpRequest(HttpMethods.GET, Uri(docPath))).map { response =>
    assert(response.status == StatusCodes.OK, s"Request for swagger doc resulted in ${response.status}, not 200 OK")
    // todo: collect inherited parameters
      val parsed = parser.parse(response.entity.data.asString)
      val endpoints = (for {
        (path, ops) <- parsed.getPaths.asScala
        (method, op) <- buildOpList(ops)
      } yield (
        EndpointTemplate(
          HttpMethods.getForKey(method.toUpperCase).get,
          shield.routing.Path(path)
        ),
        EndpointDetails(
          translateParams(op).toSet,
          getConsumes(op).flatMap(translateMediaType).toSet,
          getProduces(op).flatMap(translateMediaType).toSet,
          getDisabledMiddleware(host, op),
          getDisabledListeners(host,op)
        )
      )).toMap

      val info = Option(parsed.getInfo)
      SwaggerDetails(info.flatMap(i => Option(i.getTitle)).getOrElse("(not specified)"), info.flatMap(i => Option(i.getVersion)).getOrElse("(not specified)"), endpoints)
    }
  }
}
