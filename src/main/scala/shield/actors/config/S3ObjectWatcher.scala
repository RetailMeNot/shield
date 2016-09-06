package shield.actors.config

import akka.actor.{Actor, ActorLogging}
import com.amazonaws.services.s3.AmazonS3Client

sealed trait S3ObjectWatcherMessage
case object Refresh extends S3ObjectWatcherMessage
case class ChangedContents(contents: String) extends S3ObjectWatcherMessage


class S3ObjectWatcher(bucketName: String, configFilename: String) extends Actor with ActorLogging {
  val s3Client = new AmazonS3Client()
  var lastContents = ""

  def receive = {
    case Refresh =>
      val s3Object = s3Client.getObject(bucketName, configFilename)
      val newContents = scala.io.Source.fromInputStream(s3Object.getObjectContent).mkString

      if (newContents != lastContents) {
        log.info("Detected change in s3 file contents")
        log.debug(s"Fetched from s3: $newContents")
        context.parent ! ChangedContents(newContents)
        lastContents = newContents
      }
  }
}
