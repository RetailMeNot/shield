package shield.implicits

import com.typesafe.scalalogging.LazyLogging
import spray.http.HttpHeaders.RawHeader
import spray.http.Uri.Path
import spray.http._

import scala.language.implicitConversions

// todo: Do not like/want.  Do this better
class ImplicitHttpResponse(msg: HttpResponse) extends LazyLogging {
   def withAdditionalHeaders(header: HttpHeader*) : HttpResponse = {
      msg.withHeaders(header.toList ++ msg.headers)
   }

   def withReplacedHeaders(header: HttpHeader*) : HttpResponse = {
      val headerNames = header.map(_.lowercaseName).toSet
      msg.withHeaders(header.toList ++ msg.headers.filterNot(h => headerNames.contains(h.lowercaseName)))
   }

   def withStrippedHeaders(headers: Set[String]) : HttpResponse = {
      msg.withHeaders(msg.headers.filterNot(h => headers.exists(s => s.toLowerCase.equals(h.lowercaseName))))
   }
}

class ImplicitHttpRequest(msg: HttpRequest) extends LazyLogging {
   def withAdditionalHeaders(header: HttpHeader*) : HttpRequest = {
      msg.withHeaders(header.toList ++ msg.headers)
   }

   def withReplacedHeaders(header: HttpHeader*) : HttpRequest = {
      val headerNames = header.map(_.lowercaseName).toSet
      msg.withHeaders(header.toList ++ msg.headers.filterNot(h => headerNames.contains(h.lowercaseName)))
   }

   def withStrippedHeaders(headers: Set[String]) : HttpRequest = {
      msg.withHeaders(msg.headers.filterNot(h => headers.contains(h.lowercaseName)))
   }

   def withTrustXForwardedFor(trustProxies : Int) : HttpRequest = {
      val forwardedList:Array[String] = msg.headers.find(_.lowercaseName == "x-forwarded-for").map(_.value.split(",")).getOrElse(Array())
      val remoteHeader = msg.headers.find(_.lowercaseName == "remote-address").map(_.value).getOrElse("127.0.0.1")
      val combinedList = (forwardedList :+ remoteHeader).reverse //List containing [Remote-Address header, most recent x-forwarded-for, 2nd most recent x-forwarded-for, etc]
      val clientAddress =  RawHeader("client-address", combinedList(if(trustProxies < combinedList.length) trustProxies else combinedList.length-1).trim)
      withReplacedHeaders(clientAddress)
   }

   def withTrustXForwardedProto(trustProto : Boolean) : HttpRequest = {
      if (trustProto) {
         val proto = msg.headers.find(_.lowercaseName == "x-forwarded-proto").map(_.value).getOrElse(msg.uri.scheme)
         try {
            msg.copy(uri = msg.uri.copy(scheme = proto))
         } catch {
            case e: spray.http.IllegalUriException =>
               logger.error("Received invalid protocol \"" + proto + "\" in the 'X-Forwarded-Proto' header, using original request.",e)
               msg
         }
      } else {
         msg
      }
   }

   def withStrippedExtensions(extensions : Set[String]) : HttpRequest = {
      val trimmedPath = getExtension(msg.uri.path.toString) match {
         case (path, Some(extension)) if extensions.contains(extension) => Path(path)
         case (path, _) => msg.uri.path
      }

      msg.copy(uri = msg.uri.copy(path = trimmedPath))
   }

   protected def getExtension(path: String) : (String, Option[String]) = {
      val extensionPos = path.lastIndexOf('.')
      val lastDirSeparator = path.lastIndexOf('/')

      if (lastDirSeparator < extensionPos) {
         val t = path.splitAt(extensionPos)

         t._1 -> Some(t._2.toLowerCase())
      } else {
         path -> None
      }
   }
}

object HttpImplicits {
   implicit def toHttpMethod(s: String) : HttpMethod = HttpMethods.getForKey(s.toUpperCase).get
   implicit def betterResponse(response: HttpResponse) : ImplicitHttpResponse = new ImplicitHttpResponse(response)
   implicit def betterRequest(request: HttpRequest) : ImplicitHttpRequest = new ImplicitHttpRequest(request)
}

