package shield.proxying

import akka.actor.{ActorContext, ActorRef, ActorRefFactory, PoisonPill}
import akka.pattern.ask
import akka.routing.RoundRobinGroup
import akka.util.Timeout
import shield.actors.Middleware
import shield.actors.config.{ProxyState, WeightedProxyState}
import shield.config.ServiceLocation
import shield.routing.{EndpointTemplate, Param}
import spray.http.{HttpRequest, HttpResponse}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

case class ProxyRequest(template: EndpointTemplate, request: HttpRequest)
case class ProxiedResponse(upstreamService: ServiceLocation, serviceName: String, template: EndpointTemplate, cacheParams: Set[Param], response: HttpResponse)


trait ProxyBalancer {
  def proxy(template: EndpointTemplate, request: HttpRequest) : Future[ProxiedResponse]
}

object FailBalancer extends ProxyBalancer {
  def proxy(template: EndpointTemplate, request: HttpRequest): Future[ProxiedResponse] =
    Future.failed(new NotImplementedError())
}

class AkkaBalancer(val balancer: ActorRef) extends ProxyBalancer {
  // todo: use global timeout config
  implicit val timeout = Timeout(60.seconds)
  def proxy(template: EndpointTemplate, request: HttpRequest) = (balancer ? ProxyRequest(template, request)).mapTo[ProxiedResponse]
}

trait ProxyBalancerBuilder[T <: ProxyBalancer] {
  val allMiddleware : List[Middleware]
  def build(actors: Set[ActorRef]) : ProxyBalancer
  def teardown() : Unit
}

object EmptyBalancerBuilder extends ProxyBalancerBuilder[ProxyBalancer] {
  val allMiddleware : List[Middleware] = Nil
  def build(actors: Set[ActorRef]) : ProxyBalancer = {
    FailBalancer
  }
  def teardown() : Unit = {}
}

// todo: weighted service instances (and dynamic weighting)
// todo: retry safe gets (config option to enable) via something like http://doc.akka.io/docs/akka/snapshot/scala/routing.html#TailChoppingPool_and_TailChoppingGroup
class RoundRobinBalancerBuilder(val allMiddleware: List[Middleware], factory: ActorRefFactory, hostProxies: Map[ActorRef, WeightedProxyState])(implicit execContext: ExecutionContext) extends ProxyBalancerBuilder[AkkaBalancer] {
  var balancers : List[ActorRef] = Nil

  // https://en.wikipedia.org/wiki/Euclidean_algorithm#Implementations
  // nb: gcd is associative, so we're safe to `reduce` the results
  @tailrec
  private def gcd(a: Int, b: Int) : Int =
    if (b == 0) {
      a
    } else {
      gcd(b, a % b)
    }


  def build(actors: Set[ActorRef]) : AkkaBalancer = {
    // todo: refactor this out to somewhere common when we have other balancer types
    val actorWeight = actors.map(hostProxies(_).weight)
    val totalWeight = actorWeight.sum
    val group = if (totalWeight == 0) {
      actors.toList
    } else {
      val actorGCD = actorWeight.filter(_ != 0).reduceLeftOption(gcd).getOrElse(1)
      Random.shuffle(actors.toList.flatMap(a => List.fill(hostProxies(a).weight / actorGCD)(a)))
    }
    val balancer = factory.actorOf(RoundRobinGroup(group.map(_.path.toString)).props())
    balancers = balancer :: balancers

    new AkkaBalancer(balancer)
  }

  def teardown() = {
    for (balancer <- balancers) {
      // just stop the router, not the host proxy behind them
      balancer ! PoisonPill
    }
    balancers = Nil
  }
}
