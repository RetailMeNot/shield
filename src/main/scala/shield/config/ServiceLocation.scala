package shield.config

import akka.actor.ActorRefFactory
import shield.transports.{HttpTransport, LambdaTransport}
import shield.transports.HttpTransport.SendReceive
import spray.http.Uri
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


sealed trait ServiceLocation{
  def locationName : String
}

case class HttpServiceLocation(baseUrl: Uri) extends ServiceLocation {
  // nb: spray.http.Uri does not provide default port numbers.  If one is not given, baseUrl.authority.port == 0
  require(baseUrl.isAbsolute, "baseUrl must be absolute")
  require(baseUrl.scheme == "http" || baseUrl.scheme == "https", "baseUrl must use either HTTP or HTTPS")
  require(baseUrl.authority.userinfo == "", "baseUrl cannot have auth information")
  require(baseUrl.path == Uri.Path.Empty, "baseUrl cannot have a path")
  require(baseUrl.query == Uri.Query.Empty, "baseUrl cannot have a query")
  require(baseUrl.fragment.isEmpty, "baseUrl cannot have a fragment")

  override def locationName : String = baseUrl.toString()
}

case class LambdaServiceLocation(arn : String) extends ServiceLocation {
  override def locationName : String = arn
}

object TransportFactory {
  def apply(location: ServiceLocation)(implicit factory: ActorRefFactory, executor: ExecutionContext) : SendReceive = location match {
    case HttpServiceLocation(uri) => HttpTransport.httpTransport(uri, 5.seconds)
    case LambdaServiceLocation(arn) => LambdaTransport.lambdaTransport(arn)
  }
}

