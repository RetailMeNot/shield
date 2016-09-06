package shield.routing

import org.specs2.mutable.Specification
import spray.http.Uri

class PathParsingSpec extends Specification {
  "PathParsing" should {
    "handle empty paths" in {
      val emptyPath = Path("")

      emptyPath.segments must be equalTo Nil
    }

    "handle root paths" in {
      val rootPath = Path("/")

      rootPath.segments must be equalTo Nil
    }

    "handle static segments" in {
      val foo = Path("/foo")

      foo.segments must be equalTo List(StaticSegment("foo"))
    }

    "handle nested static segments" in {
      val foobar = Path("/foo/bar")

      foobar.segments must be equalTo List(StaticSegment("foo"), SlashSegment, StaticSegment("bar"))
    }

    "handle wildcard segments" in {
      val wild = Path("/foo/{bar}")
      wild.segments must be equalTo List(StaticSegment("foo"), SlashSegment, WildcardSegment)
    }

    "handle path segments" in {
      val path = Path("/foo/{+bar}")
      path.segments must be equalTo List(StaticSegment("foo"), SlashSegment, PathSegment)
    }

    "handles wildcards adjacent to static" in {
      val path = Path("/foo{.media}")
      path.segments must be equalTo List(StaticSegment("foo"), ExtensionSegment)
    }
  }
}
