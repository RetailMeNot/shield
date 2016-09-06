package shield.routing

import org.specs2.mutable.Specification

class RouteSortingSpec extends Specification {
  "RouteSorting" should {
    "prefer more specific routes" in {
      val static = Path("/foo")
      val wild = Path("/{}")
      val path = Path("/{+}")

      static must beLessThan(wild)
      wild must beLessThan(path)
      static must beLessThan(path)
    }

    "give higher precedence to earlier static segments" in {
      val high = Path("/foo/{}")
      val low = Path("/{}/bar")

      high must beLessThan(low)
    }

    "use later segments as tie breakers" in {
      val fbf = Path("/foo/bar/fizz")
      val fb_ = Path("/foo/bar/{}")
      val f_f = Path("/foo/{}/fizz")
      val f__ = Path("/foo/{}/{}")

      fbf must beLessThan(fb_)
      fbf must beLessThan(f_f)
      fbf must beLessThan(f__)

      fb_ must beLessThan(f_f)
      fb_ must beLessThan(f__)

      f_f must beLessThan(f__)
    }

    "use Nil segments as lowest priority" in {
      // this may seem counter-intuitive at first glance.  However, if there exists a segment
      // after a regex or path segment, that is more specific than if the regex or path segment
      // consumed that later segment.
      val root = Path("/")
      val foo = Path("/foo")
      val foobar = Path("/foo/bar")
      val nested = Path("/foo/{}")

      root must beGreaterThan(foo)

      foo must beGreaterThan(foobar)
      foo must beGreaterThan(nested)
    }

    "treat path segments as lower priority than any number of static or wild segments" in {
      val wilds = Path("/foo/{}/{]/{}/{}")
      val nestedPath = Path("/foo/{+}")
      val pathSegment = Path("/{+}")

      wilds must beLessThan(nestedPath)
      wilds must beLessThan(pathSegment)

      nestedPath must beLessThan(pathSegment)
    }

    "prioritize path segments with trailing segments" in {
      val trailingStatic = Path("/{+}/foo")
      val trailingWild = Path("/{+}/{}")
      val path = Path("/{+}")

      trailingStatic must beLessThan(trailingWild)
      trailingStatic must beLessThan(path)

      trailingWild must beLessThan(path)
    }

    "handle edge cases" in {
      val root = Path("/")
      val empty = Path("")

      root must beEqualTo(empty)
    }
  }
}
