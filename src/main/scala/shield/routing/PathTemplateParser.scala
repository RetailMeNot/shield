package shield.routing

import spray.http.HttpMethods

import scala.annotation.tailrec

object PathTemplateParser {

  // /foo/bar/{var_name: re(gex)?}
  private val regex = """\{[a-zA-Z0-9_]+:((?:[^\}\\]|\\.)*)\}(.*)""".r

  // /foo/bar/{+var_name}
  // (to understand the prefix, look at section 3.2 of https://tools.ietf.org/html/rfc6570
  private val wildcard = """\{([\+\?\.#/;&]?)[^\}]*\}(.*)""".r

  // anything up to a {, /, or }
  private val static = """([^\{/}]+)(.*)""".r

  /** *
    * Strips the leading and trailing slashes from a path
    * @param path
    * @return
    */
  def normalizePath(path: String) = path.stripPrefix("/").stripSuffix("/")

  def pathToSegments(path : String) : List[Segment] = {
    val normalized = normalizePath(path)

    def parse(path: String) : List[Segment] = path match {
      case "" => Nil
      case matched if matched.startsWith("/") => SlashSegment :: parse(matched.substring(1))
      case regex(expr, remainder) => RegexSegment(expr.trim) :: parse(remainder)
      case wildcard(wildcard_type, remainder) => (if (wildcard_type == "+") PathSegment else if (wildcard_type == ".") ExtensionSegment else  WildcardSegment) :: parse(remainder)
      case static(static_string, remainder) => StaticSegment(static_string) :: parse(remainder)
    }

    parse(normalized)
  }
}
