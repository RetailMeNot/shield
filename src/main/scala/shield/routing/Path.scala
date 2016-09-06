package shield.routing

import scala.annotation.tailrec
import scala.util.matching.Regex

object Path {
  def apply(path: String): Path = {
    new Path(PathTemplateParser.pathToSegments(path))
  }
}

case class Path(segments: List[Segment]) extends Ordered[Path] {
  override def compare(that: Path): Int =  {
    @tailrec
    def analyze(segments: (List[Segment], List[Segment])) : Int = {
      segments match {
        case (Nil, Nil) => 0
        case (Nil, _) => 1
        case (_, Nil) => -1
        case (this_head :: this_tail, that_head :: that_tail) => (this_head, that_head) match {
          case (l, r) if l.priority != r.priority => l.priority - r.priority
          case (l, r) => analyze(this_tail, that_tail)
        }
      }
    }

    val comparison = analyze(segments, that.segments)
    if (comparison == 0) {
      segments.toString().compare(that.segments.toString())
    } else {
      comparison
    }
  }

  override lazy val toString : String = {
    (Iterable("/") ++ segments.map(_.toString)).mkString("")
  }

  // todo: safely handle invalid regexs due to bad swagger documentation
  lazy val regex : Regex = {
    (Iterable("^/") ++ segments.map(_.regexPiece) ++ Iterable("/?$")).mkString("").r
  }
}
