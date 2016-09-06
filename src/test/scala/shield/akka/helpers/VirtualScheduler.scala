package shield.akka.helpers

import java.util.concurrent.ThreadFactory

import akka.actor.{Cancellable, Scheduler}
import akka.event.LoggingAdapter
import com.miguno.akka.testing.VirtualTime
import com.typesafe.config.Config

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

class VirtualScheduler(cfg: Config, adapter: LoggingAdapter, factory: ThreadFactory) extends Scheduler {

  var virtualTime = new VirtualTime

  def reset() = virtualTime = new VirtualTime

  override def schedule(initialDelay: FiniteDuration, interval: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
    virtualTime.scheduler.schedule(initialDelay, interval, runnable)(executor)

  override def maxFrequency: Double = virtualTime.scheduler.maxFrequency

  override def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
    virtualTime.scheduler.scheduleOnce(delay, runnable)(executor)
}
