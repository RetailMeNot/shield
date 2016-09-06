package shield.consul

import shield.config.{HttpServiceLocation, ServiceLocation}
import spray.http.Uri

case class ConsulServiceNode(
  Node: String,
  Address: String,
  ServiceID: String,
  ServiceName: String,
  ServiceTags: Option[List[String]],
  ServiceAddress: String,
  ServicePort: Int
) {
  def effectiveAddress = if (ServiceAddress.nonEmpty) ServiceAddress else Address
  // todo: allow consul services to register http vs https
  def toHost = HttpServiceLocation(Uri(scheme="http", authority=Uri.Authority(Uri.Host(effectiveAddress), ServicePort)))
  def url(scheme: String) = s"$scheme://$effectiveAddress:$ServicePort"
}


