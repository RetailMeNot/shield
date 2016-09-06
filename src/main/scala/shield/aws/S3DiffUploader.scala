package shield.aws

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets

import akka.actor.{Actor, ActorLogging, Props}
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import shield.actors.listeners.ComparisonDiffFile

object S3DiffUploader{
  def props(bucket: String, folder: String) : Props = Props(new S3DiffUploader(bucket, folder))
}

class S3DiffUploader(bucket: String, folder: String)  extends Actor with ActorLogging {
  val s3Client = new AmazonS3Client()
  val charset = StandardCharsets.UTF_8
  val stripped = folder.stripPrefix("/").stripSuffix("/")
  val prefix = if (stripped.isEmpty) {
    stripped
  } else {
    stripped + "/"
  }

  def receive = {
    case file: ComparisonDiffFile =>
      val metadata = new ObjectMetadata()
      metadata.setContentLength(file.contents.length)
      s3Client.putObject(bucket, s"$prefix${file.fileName}", new ByteArrayInputStream(file.contents), metadata)
  }
}
