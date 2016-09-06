package shield.implicits


import akka.pattern.CircuitBreakerOpenException
import com.typesafe.scalalogging.LazyLogging
import shield.metrics.Instrumented

import scala.util.{Failure, Try}

object FutureUtil extends LazyLogging with Instrumented {
  def logFailure[A](identifier: String, handlers: PartialFunction[Throwable, Unit] = PartialFunction.empty) : PartialFunction[Try[A], Unit] = {
    val h : PartialFunction[Throwable, Unit] = handlers orElse {
      case _: CircuitBreakerOpenException => metrics.meter("CircuitOpenException", identifier).mark()
      case throwable => logger.error(s"Failed future '$identifier'", throwable)
    }

    {
      case Failure(r) => h(r)
    }
  }
}
