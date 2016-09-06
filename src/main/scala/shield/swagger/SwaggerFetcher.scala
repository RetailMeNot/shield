package shield.swagger

import akka.actor.ActorRef
import io.swagger.models.parameters._
import io.swagger.models.{Operation, Swagger}
import shield.config.ServiceLocation

import scala.collection.JavaConverters._
import shield.routing._

import scala.concurrent.{ExecutionContext, Future}

object SwaggerFetcher {
  def swaggerSpec(endpoints: Iterable[(EndpointTemplate, EndpointDetails)]) : Swagger = {
    val operationsByPath = endpoints
      .groupBy(_._1.path.toString)
      .map { case (path, pathHandlers) =>
        val t = pathHandlers.groupBy(_._1.method)
          .map { case (method, handlers) =>
            val op = new Operation()
              .consumes(handlers.flatMap(_._2.canConsume.map(_.toString())).toSet.toList.asJava)
              .produces(handlers.flatMap(_._2.canProduce.map(_.toString())).toSet.toList.asJava)
            handlers.flatMap(_._2.params).map { p => p.location match {
              case BodyParam => new BodyParameter().name(p.name)
              case FormParam => new FormParameter().name(p.name)
              case HeaderParam => new HeaderParameter().name(p.name)
              case PathParam => new PathParameter().name(p.name)
              case QueryParam => new QueryParameter().name(p.name)
            }
            }.foreach { p =>
              op.addParameter(p)
            }
            method -> op
          }
        val p = new io.swagger.models.Path()
        t.foreach { kvp =>
          p.set(kvp._1.toString().toLowerCase, kvp._2)
        }
        path -> p
      }
    new Swagger()
      .host("localhost")
      .basePath("/")
      .paths(operationsByPath.asJava)
  }
}

case class SwaggerDetails(service: String, version: String, endpoints: Map[EndpointTemplate, EndpointDetails])

trait SwaggerFetcher {
  def fetch(host: ServiceLocation) : Future[SwaggerDetails]
}
