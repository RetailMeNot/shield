package shield.actors.config.listener

import akka.actor.{Actor, ActorLogging, Props}
import akka.routing.SmallestMailboxPool
import shield.actors.RestartLogging
import shield.actors.config.ConfigWatcherMsgs
import shield.actors.listeners.AlternateUpstream
import shield.aws.S3DiffUploader
import shield.config.DomainSettings

class AlternateUpstreamBuilder(id: String, domain: DomainSettings) extends Actor with ActorLogging with ListenerBuilder with RestartLogging {
  val c = domain.ConfigForListener(id)

  val hostUri = c.getString("serviceLocation")
  val hostType = c.getString("serviceType")
  val freq = c.getInt("freq")
  val bucket = c.getString("bucket")
  val folder = if (c.hasPath("folder")) c.getString("folder") else "/"

  // since the s3 upload is synchronous, we want a pool of workers
  val uploader = context.actorOf(SmallestMailboxPool(5).props(S3DiffUploader.props(bucket, folder)), "s3UploadRouter")

  context.parent ! ConfigWatcherMsgs.ListenerUpdated(id, context.actorOf(Props(new AlternateUpstream(id, settings.DefaultServiceLocation, hostUri, hostType, freq, uploader))))

  def receive = {
    case _ =>
  }
}
