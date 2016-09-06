package shield.routing

import java.util.regex.Pattern

sealed trait Segment {
  def regexPiece : String
  private[routing] def priority: Int
}

case object SlashSegment extends Segment {
  override def regexPiece: String = "/"
  override private[routing] val priority: Int = 0
  override def toString = "/"
}

case class StaticSegment(segment: String) extends Segment {
  override def regexPiece: String = Pattern.quote(segment)
  override private[routing] val priority: Int = 1
  override def toString = segment
}

case object ExtensionSegment extends Segment {
  override def regexPiece: String = "(\\.[^/]*)?"
  override private[routing] val priority: Int = 2
  override def toString = "(.extension)?"
}

case object WildcardSegment extends Segment {
  override def regexPiece: String = "[^/]*"
  override private[routing] val priority: Int = 3
  override def toString = "{}"
}

case class RegexSegment(expr: String) extends Segment {
  override def regexPiece: String = expr
  override private[routing] val priority: Int = 4
  override def toString = s"{regex: $expr}"
}

case object PathSegment extends Segment {
  override def regexPiece: String = ".*"
  override private[routing] val priority: Int = 5
  override def toString = "(.*)"
}

