package shield.actors

import akka.actor._
import akka.io.{IO, Tcp}
import shield.config.{DomainSettings, Settings}
import shield.metrics.Instrumented
import shield.routing._
import spray.can.Http
import spray.http._

// todo: verify how Spray handles HEAD requests
// todo: 412 Precondition Failed support
// todo: 417 Expectation Failed support
// todo: does spray automatically handle `505 HTTP Version Not Supported`?
// todo: periodically inspect a response to validate it's compliance with the documented swagger schema
object ShieldActor {
  def props() : Props = Props(new ShieldActor())
}

object ShieldActorMsgs {
  case class DomainsUpdated(domains: Map[String, DomainSettings])
  case class RouterUpdated(domain: String, router: RequestRouter)
  case class ListenersUpdated(domain: String, listeners: collection.Map[String, ActorRef])
}
class ShieldActor() extends Actor with ActorLogging with RestartLogging with Instrumented {
  // todo: integrate spray's service statistics into this
  import context.system
  val settings = Settings(context.system)

  val domainWatcher = context.actorOf(Props(settings.DomainWatcher), "domain-watcher")
  var domains = Map[String, DomainSettings]()

  var defaultRouter = buildDefaultRouter()
  var routers = Map[String, RequestRouter]()
  var listeners = Map[String,  collection.Map[String, ActorRef]]()

  // start a new HTTP server on port 8080 with our service actor as the handler
  IO(Http) ! Http.Bind(self, interface = "0.0.0.0", port = settings.Port)

  val connectionCounter = metrics.counter("open-connections")
  val requestMeter = metrics.meter("requests")
  val connectionOpenMeter = metrics.meter("connection-open")
  val connectionCloseMeter = metrics.meter("connection-close")


  def receive: Receive = {
    // todo: graceful shutdown (save bound's sender for later)
    case Http.Bound(addr) =>
      log.info("Server successfully bound to {}", addr)

    case Http.CommandFailed(cmd) =>
      log.error("Failed to bind the server: cmd={}", cmd)
      system.shutdown()

    case _ : Tcp.Connected =>
      connectionCounter += 1
      connectionOpenMeter.mark()
      sender ! Tcp.Register(self)
    case _ : Tcp.ConnectionClosed =>
      connectionCounter -= 1
      connectionCloseMeter.mark()

    case request: HttpRequest =>
      requestMeter.mark()
      val domain = request.header[HttpHeaders.Host].map(_.host).getOrElse(request.uri.authority.host.address)
      val destination = for {
        location <- Some(settings.DefaultServiceLocation)
        domainSettings <- domains.get(domain)
        router <- routers.get(domain)
        listeners <- listeners.get(domain)
      } yield (location, domainSettings.IgnoreExtensions, router, listeners)
      val (location, ignoredExtensions, router, listenerList) = destination.getOrElse(settings.DefaultServiceLocation, Set[String](), defaultRouter, collection.Map[String, ActorRef]())

      // todo: profiling optimization: destroying these actors is expensive.  Make a resizable pool
      context.actorOf(RequestProcessor.props(router, listenerList, sender(), location, ignoredExtensions)) ! request

    case du: ShieldActorMsgs.DomainsUpdated =>
      val newDomains = du.domains.keySet.diff(domains.keySet)
      for (d <- newDomains) {
        routers += d -> BootstrapRouter.router(settings.DefaultServiceLocation, settings.LocalServiceName, settings.HealthcheckTemplate)
        listeners += d -> collection.Map[String, ActorRef]()
      }
      val removedDomains = domains.keySet.diff(du.domains.keySet)
      for (d <- removedDomains) {
        routers -= d
        listeners -= d
      }
      domains = du.domains
      defaultRouter = buildDefaultRouter()

    case ru: ShieldActorMsgs.RouterUpdated => routers += ru.domain -> ru.router
    case lu: ShieldActorMsgs.ListenersUpdated => listeners += lu.domain -> lu.listeners
  }

  private def buildDefaultRouter() : RequestRouter = {
    BootstrapRouter.defaultRouter(settings.DefaultServiceLocation, settings.LocalServiceName, settings.HealthcheckTemplate, domains.keySet)
  }
}
