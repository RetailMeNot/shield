package shield.swagger

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.typesafe.scalalogging.LazyLogging
import io.swagger.models.apideclaration.{ApiDeclaration, Parameter}
import io.swagger.models.resourcelisting.{ApiListingReference, ResourceListing}
import io.swagger.parser.SwaggerLegacyParser
import io.swagger.reader.{SwaggerReaderConfiguration, SwaggerReaderFactory}
import io.swagger.transform.migrate.{V11ApiDeclarationMigrator, V11ResourceListingMigrator}
import io.swagger.validate.{ApiDeclarationSchemaValidator, ResourceListingSchemaValidator}
import shield.config.ServiceLocation
import shield.routing._
import shield.transports.HttpTransport.SendReceive
import spray.http.parser.HttpParser
import spray.http._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

object Swagger1Helper {
  private val mapper = new ObjectMapper().enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING)
  private val reader = new SwaggerReaderFactory(new SwaggerReaderConfiguration()).newReader()
  private val listingMigrator = new V11ResourceListingMigrator()
  private val listingValidator = new ResourceListingSchemaValidator()
  private val apiMigrator = new V11ApiDeclarationMigrator()
  private val apiValidator = new ApiDeclarationSchemaValidator()

  def parseResourceListing(response: HttpResponse) : ResourceListing = {
    val node = mapper.readTree(response.entity.data.toByteArray)
    val migratedNode = listingMigrator.migrate(node)

    //lovely java exception throwing code :(
    listingValidator.validate(migratedNode)

    mapper.readValue(migratedNode.traverse(), classOf[ResourceListing])
  }

  def parseApiDeclaration(response: HttpResponse) : ApiDeclaration = {
    val node = mapper.readTree(response.entity.data.toByteArray)
    val migratedNode = apiMigrator.migrate(node)

    //lovely java exception throwing code :(
    apiValidator.validate(migratedNode)

    mapper.readValue(migratedNode.traverse(), classOf[ApiDeclaration])
  }
}

class Swagger1Fetcher(basePath: String, pipeline: SendReceive)(implicit executor: ExecutionContext) extends SwaggerFetcher with LazyLogging {
  val parser = new SwaggerLegacyParser
  def translate(apiReference: ApiListingReference) = {
    pipeline(HttpRequest(HttpMethods.GET, basePath + apiReference.getPath))
      .map(Swagger1Helper.parseApiDeclaration)
      .map { declaration =>
        for {
          api <- declaration.getApis.asScala
          op <- api.getOperations.asScala
        } yield {
          (
            EndpointTemplate(
              HttpMethods.getForKey(op.getMethod.toValue.toUpperCase).get,
              Path(api.getPath)
            ),
            EndpointDetails(
              op.getParameters.asScala.map(translateParam).toSet,
              op.getConsumes.asScala.flatMap(translateMediaType).toSet,
              op.getProduces.asScala.flatMap(translateMediaType).toSet,
              Set(),
              Set()
            )
          )
        }
      }
  }

  def translateParam(param: Parameter) : Param = {
    param.getParamType.toValue.toLowerCase match {
      case "body" => BodyParam(param.getName)
      case "form" => FormParam(param.getName)
      case "header" => HeaderParam(param.getName.toLowerCase)
      case "path" => PathParam(param.getName)
      case "query" => QueryParam(param.getName)
    }
  }

  def translateMediaType(mt: String) : Option[MediaType] = {
    // todo: handle invalid content types
    HttpParser.parse(HttpParser.ContentTypeHeaderValue, mt).fold(e => None, ct => Some(ct.mediaType))
  }

  def fetch(host: ServiceLocation) : Future[SwaggerDetails] = {
    logger.info(s"Fetching swagger1 api-docs from $host")
    val rootDocFuture = pipeline(HttpRequest(HttpMethods.GET, Uri(basePath))).map(Swagger1Helper.parseResourceListing)

    val apiDocsFuture = rootDocFuture.flatMap(root => Future.sequence(root.getApis.asScala.map(translate)))

    for {
      rootDoc <- rootDocFuture
      apiDocs <- apiDocsFuture
    } yield SwaggerDetails(
      Option(rootDoc.getInfo).flatMap(i => Option(i.getTitle)).getOrElse("(not specified)"),
      Option(rootDoc.getApiVersion).getOrElse("(not specified)"),
      apiDocs.flatten.toMap
    )
  }
}
