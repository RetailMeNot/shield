package shield.actors
//
//import shield.config._
//import shield.swagger.SwaggerFetcher
//import shield.transports.HttpTransport
//import shield.transports.LambdaTransport
//import scala.concurrent.duration._
//import scala.concurrent.ExecutionContext.Implicits.global
//
//trait Swagger1Builder extends HostProxy{
//  def buildFetcher() : SwaggerFetcher = Swagger1ServiceType.fetcher(context.system, Settings(context.system))
//}
//
//trait Swagger2HttpBuilder extends HostProxy{
//  def buildFetcher() : SwaggerFetcher = Swagger2ServiceType.fetcher(context.system, Settings(context.system))
//}
//
//trait Swagger2LambdaBuilder extends HostProxy{
//  def buildFetcher() : SwaggerFetcher = LambdaServiceType.fetcher(context.system, Settings(context.system))
//}
//
//trait HttpPipeline extends HostProxy{
//  def pipelineBuilder = HttpTransport.httpTransport(serviceLocation.asInstanceOf[HttpServiceLocation].baseUrl, 5.seconds)
//}
//
//trait LambdaPipeline extends HostProxy {
//  def pipelineBuilder = LambdaTransport.lambdaTransport(serviceLocation.asInstanceOf[LambdaServiceLocation].arn)
//}
//
//class Swagger1HostProxy(location: ServiceLocation, localServiceLocation : ServiceLocation) extends HostProxy(location, Swagger1ServiceType, localServiceLocation) with Swagger1Builder with HttpPipeline
//class Swagger2HttpHostProxy(location: ServiceLocation, localServiceLocation : ServiceLocation) extends HostProxy(location, Swagger2ServiceType, localServiceLocation) with Swagger2HttpBuilder with HttpPipeline
//class Swagger2LambdaHostProxy(location: ServiceLocation, localServiceLocation : ServiceLocation) extends HostProxy(location, LambdaServiceType, localServiceLocation) with Swagger2LambdaBuilder with LambdaPipeline