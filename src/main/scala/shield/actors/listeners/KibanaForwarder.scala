package shield.actors.listeners

import akka.actor.{Actor, ActorLogging}
import com.amazonaws.auth.{AWSCredentials, DefaultAWSCredentialsProviderChain}
import com.typesafe.config.Config
import shield.actors.RestartLogging
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTimeZone, DateTime}
import shield.aws.AWSSigningConfig
import shield.metrics.Instrumented
import spray.client.pipelining._
import spray.http.HttpResponse
import shield.aws.AWSImplicits._
import spray.json.DefaultJsonProtocol._
import spray.json._

// todo: ensure useful mapping on the index
class KibanaForwarder(id: String, host: String, indexPrefix: String, ttype: String, maxOutstanding: Int, signingParams: AWSSigningConfig) extends Actor with ActorLogging with RestartLogging with Instrumented {
  implicit val ctx = context.dispatcher

  // todo: timeout?
  val awsSigningConfig = signingParams
  val pipeline = sendReceive
  val dayFormat = DateTimeFormat.forPattern("yyyy.MM.dd")
  val outstandingCounter = metrics.counter("outstandingPosts", id)
  val droppedMeter = metrics.meter("droppedAccessLogs", id)
  val postTimer = timing("postToKibana", id)

  def receive = {
    case LogsFlushed =>
      outstandingCounter -= 1

    case AccessLogs(buffer) =>
      if (buffer.nonEmpty) {
        if (outstandingCounter.count >= maxOutstanding) {
          droppedMeter.mark(buffer.length)
        } else postTimer {
          outstandingCounter += 1

          val date = DateTimeFormat.forPattern("yyyy.MM.dd").print(DateTime.now(DateTimeZone.UTC))
          // todo: CompactPrint is 1% cpu under load tests.  Faster serialization library?
          val orderedCommands = buffer.flatMap { doc =>
            List(
              JsObject(
                "index" -> JsObject(
                  "_index" -> JsString(s"$indexPrefix-$date"),
                  "_type" -> JsString(ttype)
                )
              ).toJson.compactPrint,
              doc.toJson.compactPrint
            )
          }
          val req = Post(s"$host/_bulk", orderedCommands.mkString("\n") + "\n").withAWSSigning(awsSigningConfig)
          pipeline(req) andThen LogCollector.handleResults(self, droppedMeter, log, buffer.length)
        }
      }
  }
}
