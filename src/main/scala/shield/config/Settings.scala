package shield.config

import akka.actor._
import com.typesafe.config.Config
import shield.actors.config.domain.DomainWatcher
import shield.routing.{EndpointTemplate, Path}
import spray.http.{HttpMethods, Uri}


class SettingsImpl(val config: Config, _system: ActorSystem) extends Extension {
  val LocalServiceName = config.getString("shield.service-name")
  val Port = config.getInt("shield.port")
  val Host = if (config.hasPath("shield.hostname")) config.getString("shield.hostname") else if (System.getenv().containsKey("HOSTNAME")) System.getenv("HOSTNAME") else java.net.InetAddress.getLocalHost.getHostName
  val DefaultServiceLocation = HttpServiceLocation(Uri(if (Port == 80) s"http://$Host" else s"http://$Host:$Port"))

  val DomainWatcher = Settings.getClass(config.getString("shield.domain-watcher"), "shield.actors.config.domain", classOf[DomainWatcher])

  val HealthcheckTemplate = EndpointTemplate(HttpMethods.GET, Path(config.getString("shield.paths.healthcheck")))
  val MetricsTemplate = EndpointTemplate(HttpMethods.GET, Path(config.getString("shield.paths.metrics")))
  val SwaggerTemplate = EndpointTemplate(HttpMethods.GET, Path(config.getString("shield.paths.swagger2")))
  val Swagger1Path = config.getString("shield.paths.swagger1")
  val Swagger2Path = config.getString("shield.paths.swagger2")

  //Can't have a negative value here because it used when accessing a list
  val trustProxies= Math.max( 0, config.getInt("shield.trust-proxies"))
  val trustXForwardedProto=config.getBoolean("shield.trust-x-forwarded-proto")
}

object Settings extends ExtensionId[SettingsImpl] with ExtensionIdProvider {

  override def lookup = Settings

  override def createExtension(system: ExtendedActorSystem) =
    new SettingsImpl(system.settings.config, system)

  /**
   * Java API: retrieve the Settings extension for the given system.
   */
  override def get(system: ActorSystem): SettingsImpl = super.get(system)

  def getClass[T](name: String, defaultPackage: String, parent: Class[T]) : Class[_ <: T] = {
    val fqcn = if (name.contains(".")) name else s"$defaultPackage.$name"
    Class.forName(fqcn).asSubclass(parent)
  }
}

