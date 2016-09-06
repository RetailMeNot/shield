package shield.metrics

import java.util.concurrent.TimeUnit

import com.codahale.metrics.json.MetricsModule
import com.codahale.metrics.jvm.{ThreadStatesGaugeSet, MemoryUsageGaugeSet, GarbageCollectorMetricSet}
import com.codahale.metrics.{JvmAttributeGaugeSet, MetricRegistry}
import com.fasterxml.jackson.databind.ObjectMapper
import spray.http.{ContentTypes, HttpEntity, StatusCodes, HttpResponse}

// todo: Separate metrics per domain
object Registry {
  val metricRegistry = new MetricRegistry()
  metricRegistry.register("jvm.attribute", new JvmAttributeGaugeSet())
  metricRegistry.register("jvm.gc", new GarbageCollectorMetricSet())
  metricRegistry.register("jvm.memory", new MemoryUsageGaugeSet())
  metricRegistry.register("jvm.threads", new ThreadStatesGaugeSet())

  private val mapper = new ObjectMapper()
  mapper.registerModule(new MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, false))
  private val writer = mapper.writerWithDefaultPrettyPrinter()

  def metricsResponse : HttpResponse = HttpResponse(StatusCodes.OK, HttpEntity(ContentTypes.`application/json`,
    writer.writeValueAsBytes(metricRegistry)
  ))
}

