package shield.actors.config.upstream

import akka.actor.Status.Failure
import akka.actor._
import akka.pattern.pipe
import akka.util.Timeout
import com.typesafe.config.Config
import shield.actors.RestartLogging
import shield.actors.config.{ServiceDetails, UpstreamAggregatorMsgs}
import shield.config.{ServiceLocation, Swagger1ServiceType, Swagger2ServiceType}
import shield.transports.HttpTransport._
import spray.httpx.UnsuccessfulResponseException
import spray.httpx.unmarshalling.{FromResponseUnmarshaller, Unmarshaller}
import spray.json._
import shield.consul.ConsulJsonProtocol._
import shield.consul.{ConsulKVP, ConsulServiceNode, ConsulVersionedResponse}

import spray.can.client.{ClientConnectionSettings, HostConnectorSettings}
import spray.http.Uri
import spray.httpx.SprayJsonSupport._
import spray.httpx.UnsuccessfulResponseException
import spray.httpx.unmarshalling.FromResponseUnmarshaller
import spray.json._

import scala.concurrent.duration._

sealed trait SwaggerVersion
case object Swagger1 extends SwaggerVersion
case object Swagger2 extends SwaggerVersion

case class ConsulServiceNames(version: SwaggerVersion, services: Set[String])

case class ConsulServiceNodes(version: SwaggerVersion, service: String, nodes: Set[ConsulServiceNode])

class ConsulUpstreamWatcher(domainConfig: Config) extends Actor with ActorLogging with UpstreamWatcher with RestartLogging {

  //todo: actor names based of service
  val config = domainConfig.getConfig("consul-watcher")
  val consulHost = Uri(config.getString("host"))
  val swagger1Service = context.actorOf(ConsulSwaggerServiceListWatcher.props(Swagger1, consulHost, config.getString("swagger1-key"), ConsulVersionedResponse.kvpUnmarshaller))
  var swagger1Watchers = Map[String, ActorRef]()
  var swagger1Nodes = Map[String, Set[ServiceLocation]]()
  val swagger2Service = context.actorOf(ConsulSwaggerServiceListWatcher.props(Swagger2, consulHost, config.getString("swagger2-key"), ConsulVersionedResponse.kvpUnmarshaller))
  var swagger2Watchers = Map[String, ActorRef]()
  var swagger2Nodes = Map[String, Set[ServiceLocation]]()

  def nodeWatcher(service: String, version: SwaggerVersion) : ActorRef = context.actorOf(ConsulSwaggerServiceNodeWatcher.props(version, consulHost, service, ConsulVersionedResponse.serviceUnmarshaller))

  def receive = {
    case serviceNames: ConsulServiceNames => if (serviceNames.version == Swagger1) {
      val toRemove = swagger1Watchers.keySet.diff(serviceNames.services)
      val toAdd = serviceNames.services.diff(swagger1Watchers.keySet)

      for (key <- toRemove) {
        context.stop(swagger1Watchers(key))
      }
      swagger1Watchers = swagger1Watchers.filter{case (k, _) => !toRemove.contains(k)} ++ toAdd.map(service => service -> nodeWatcher(service, Swagger1)).toMap
      swagger1Nodes = swagger1Nodes.filter{case (k, _) => !toRemove.contains(k)}
    } else {
      val toRemove = swagger2Watchers.keySet.diff(serviceNames.services)
      val toAdd = serviceNames.services.diff(swagger2Watchers.keySet)

      for (key <- toRemove) {
        context.stop(swagger2Watchers(key))
      }
      swagger2Watchers = swagger2Watchers.filter{case (k, _) => !toRemove.contains(k)} ++ toAdd.map(service => service -> nodeWatcher(service, Swagger2)).toMap
      swagger2Nodes = swagger2Nodes.filter{case (k, _) => !toRemove.contains(k)}
    }

    case serviceNodes: ConsulServiceNodes => if (serviceNodes.version == Swagger1) {
        swagger1Nodes += (serviceNodes.service -> serviceNodes.nodes.map(_.toHost))
      } else {
        swagger2Nodes += (serviceNodes.service -> serviceNodes.nodes.map(_.toHost))
      }
      context.parent ! UpstreamAggregatorMsgs.DiscoveredUpstreams((swagger1Nodes.values.flatten.toList.map(_ -> ServiceDetails(Swagger1ServiceType, 1)) ++ swagger2Nodes.values.flatten.toList.map(_ -> ServiceDetails(Swagger2ServiceType, 1))).toMap)
  }
}

object ConsulSwaggerServiceListWatcher {
  def props(version: SwaggerVersion, host: Uri, key: String, unmarshaller: FromResponseUnmarshaller[ConsulVersionedResponse[List[ConsulKVP]]]) : Props = Props(new ConsulSwaggerServiceListWatcher(version, host, key, unmarshaller))
}
class ConsulSwaggerServiceListWatcher(version: SwaggerVersion, host: Uri, key: String, unmarshaller: FromResponseUnmarshaller[ConsulVersionedResponse[List[ConsulKVP]]]) extends Actor with ActorLogging with RestartLogging {
  val watcher = context.actorOf(Props(
    classOf[ConsulResponseWatcher[List[ConsulKVP]]],
    Uri(host.scheme, host.authority, Uri.Path(s"/v1/kv$key")),
    unmarshaller
  ))

  def receive = {
    case kvp: List[ConsulKVP] => context.parent ! ConsulServiceNames(version, kvp.head.StringValue.parseJson.convertTo[List[String]].toSet)
  }
}

object ConsulSwaggerServiceNodeWatcher {
  def props(version: SwaggerVersion, host: Uri, service: String, unmarshaller: FromResponseUnmarshaller[ConsulVersionedResponse[List[ConsulServiceNode]]]) : Props = Props(new ConsulSwaggerServiceNodeWatcher(version, host, service, unmarshaller))
}
class ConsulSwaggerServiceNodeWatcher(version: SwaggerVersion, host: Uri, service: String, unmarshaller: FromResponseUnmarshaller[ConsulVersionedResponse[List[ConsulServiceNode]]]) extends Actor with ActorLogging with RestartLogging {
  val watcher = context.actorOf(Props(
    classOf[ConsulResponseWatcher[List[ConsulServiceNode]]],
    Uri(host.scheme, host.authority, Uri.Path(s"/v1/catalog/service/$service")),
    unmarshaller
  ))

  def receive = {
    case nodes: List[ConsulServiceNode] => context.parent ! ConsulServiceNodes(version, service, nodes.toSet)
  }
}

class ConsulResponseWatcher[T](path: Uri, unmarshaller: FromResponseUnmarshaller[ConsulVersionedResponse[T]]) extends Actor with ActorLogging with RestartLogging {
  implicit def actorRefFactory: ActorContext = context
  import context.dispatcher
  implicit val requestTimeout = Timeout(15.minutes)

  context.setReceiveTimeout(15.minutes)

  val connectionSettings = ClientConnectionSettings(context.system).copy(requestTimeout = 15.minutes, idleTimeout = 15.minutes)
  val consulHostSettings = HostConnectorSettings(context.system).copy(connectionSettings = connectionSettings)

  val consulPipeline = httpTransport(path, consulHostSettings) ~> unmarshal[ConsulVersionedResponse[T]](unmarshaller)

  // kick off the first request to consul
  consulPipeline(Get(path)) pipeTo self

  def receive : Receive = {
    case response: ConsulVersionedResponse[T] => {
      log.info(s"Response for $path: $response")
      context.parent ! response.body

      //
      consulPipeline(Get(path.withQuery(path.query.+:(("wait", "10m")).+:(("index", response.consulIndex.toString))))) pipeTo self
    }
    case ReceiveTimeout => {
      // todo: verify that this can't cause a large pileup of pending requests
      log.warning("Have not heard from Consul in some time.  Trying again.")
      consulPipeline(Get(path)) pipeTo self
    }
    case fail : Failure =>
      log.info(s"fail: $fail")
      fail.cause match {
        case t: UnsuccessfulResponseException =>
          if (t.response.status.intValue == 404) {
            // todo: handle missing default for service nodes
            consulPipeline(Put(path, "[]")) andThen {
              case _ => consulPipeline(Get(path)) pipeTo self
            }
          }
        case _ =>
      }
    case f =>
      log.info(s"Uncaught: $f")
  }
}
