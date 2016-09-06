package shield.implicits

import scala.language.implicitConversions

class ImplicitString(string: String) {
  def toIntOpt : Option[Int] = try {
    Some(string.toInt)
  } catch {
    case e:Exception => None
  }
  def toLongOpt : Option[Long] = try {
    Some(string.toLong)
  } catch {
    case e:Exception => None
  }

  def mustStartWith(prefix : String) = {
    if(string.startsWith(prefix)) {
      string
    } else {
      prefix.concat(string)
    }
  }
}

object StringImplicits {
  implicit def betterString(string: String): ImplicitString = new ImplicitString(string)
}
