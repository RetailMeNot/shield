package shield.metrics

import nl.grons.metrics.scala.{Timer, FutureMetrics, InstrumentedBuilder}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class CurriedTiming(timer: Timer)(implicit context: ExecutionContext) {
  def apply[A](future: => Future[A]) = {
    val ctx = timer.timerContext
    val f = future
    f.onComplete(_ => ctx.stop())
    f
  }
}
trait Instrumented extends InstrumentedBuilder with FutureMetrics {
  val metricRegistry = Registry.metricRegistry

  /**
    * Like timing, but with scope
   */
  def timing[A](metricName: String, scope: String)(implicit context: ExecutionContext): CurriedTiming = new CurriedTiming(metrics.timer(metricName, scope))

  /**
    * Like timed, but with scope
    */
  def timed[A](metricName: String, scope: String)(action: => A)(implicit context: ExecutionContext): Future[A] = {
    val timer = metrics.timer(metricName, scope)
    Future(timer.time(action))
  }
}
