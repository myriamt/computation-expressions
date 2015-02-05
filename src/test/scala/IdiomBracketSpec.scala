package com.github.jedesah

import java.util.concurrent.TimeUnit

import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary._
import org.specs2.ScalaCheck
import org.specs2.mutable._
import IdiomBracket.extract

import scala.concurrent.Await
import scalaz.{Monad, Applicative}
import scalaz.std.option._
import scalaz.std.list._

class IdiomBracketSpec extends Specification with ScalaCheck {

  def doThing(e: String, f: String) = e + f

  implicit def FutureArbitrary[A: Arbitrary]: Arbitrary[scala.concurrent.Future[A]] =
    Arbitrary(arbitrary[A] map ((x: A) => scala.concurrent.Future.successful(x)))

  "IdiomBracket" should {
    "simple function application" in {
      "2 params" ! prop { (a: Option[String], b: Option[String], doThing: (String, String) => String) =>
        val f = IdiomBracket[Option, String](doThing(extract[Option, String](a), extract[Option, String](b)))
        f ==== Applicative[Option].apply2(a, b)(doThing)
      }
      "3 params" in {
        "all extracts" ! prop { (a: Option[String], b: Option[String], c: Option[String], doThing: (String, String, String) => String) =>
          val f = IdiomBracket[Option, String](doThing(extract(a), extract(b), extract(c)))
          f ==== Applicative[Option].apply3(a, b, c)(doThing)
        }
        "some extracts, some not " ! prop { (a: String, b: Option[String], c: Option[String], doThing: (String, String, String) => String) =>
          val f = IdiomBracket[Option, String](doThing(a, extract(b), extract(c)))
          f ==== Applicative[Option].apply3(Some(a),b,c)(doThing)
        }
      }
    }
    "method invocation" in {
      "no extract in LHS" ! prop { (a: String, b: Option[Int], c: Option[Int]) =>
        val f = IdiomBracket[Option, Int](a.indexOf(extract(b), extract(c)))
        f ==== Applicative[Option].apply2(b, c)(a.indexOf(_, _))
      }
      "extract in LHS" ! prop { (a: Option[String], b: Int, c: Option[Int]) =>
        val f = IdiomBracket[Option, Int](extract(a).indexOf(b, extract(c)))
        f ==== Applicative[Option].apply2(a,c)(_.indexOf(b, _))
      }
      "complex method invocation" in {
        "1" ! prop { (a: Option[String], b: Int, c: Option[Int], doThing: (String, String) => String) =>
          val f = IdiomBracket[Option, Int](doThing(extract(a), extract(c).toString).indexOf(b, extract(c)))
          f ==== Applicative[Option].apply2(a, c)((aa, cc) => doThing(aa, cc.toString).indexOf(b, cc))
        }
        "2" ! prop { (a: Option[String], b: Int, c: Option[Int], d: Option[String]) =>
          val f = IdiomBracket[Option, Int](doThing(extract(a), extract(d)).indexOf(b, extract(c)))
          f ==== Applicative[Option].apply3(a, c, d)((aa, cc, dd) => doThing(aa, dd).indexOf(b, cc))
        }
      }
    }
    "extract buried" in {
      "deep" ! prop { (a: Option[String], b: Option[String], c: Option[String], doThing: (String, String, String) => String, otherThing: String => String) =>
        val f = IdiomBracket[Option, String](doThing(otherThing(extract(a)), extract(b), extract(c)))
        f ==== Applicative[Option].apply3(a, b, c)((aa, bb, cc) => doThing(otherThing(aa), bb, cc))
      }
      "deeper" ! prop { (a: Option[String], b: Option[String], c: Option[String], doThing: (String, String, String) => String, otherThing: String => String, firstThis: String => String) =>
        val f = IdiomBracket[Option, String](doThing(otherThing(firstThis(extract(a))), extract(b), extract(c)))
        f ==== Applicative[Option].apply3(a,b,c)((aa,bb,cc) => doThing(otherThing(firstThis(aa)), bb,cc))
      }
    }
    "monadic" in {
      "double nested extract within argument" in {
        "simple enough" ! prop { (a: Option[String], b: Option[String], c: Option[String], d: Option[Int], doThing: (String, String, String) => String, firstThis: String => Option[String]) =>
          val f = IdiomBracket.monad[Option, String](doThing(extract(firstThis(extract(a))), extract(b), extract(c)))
          f ==== Applicative[Option].apply3(Monad[Option].bind(a)(firstThis), b, c)(doThing)
        }
        "nested a little deeper" ! prop { (a: Option[String], b: Option[String], c: Option[String], d: Option[Int], doThing: (String, String, String) => String, firstThis: String => Option[String], other: String => String) =>
          val f = IdiomBracket.monad[Option, String](doThing(other(extract(firstThis(extract(a)))),extract(b), extract(c)))
          f ==== Applicative[Option].apply3(Applicative[Option].map(Monad[Option].bind(a)(firstThis))(other),b,c)(doThing)
        }
        "with 2 monads inside first extract" ! prop { (a: Option[String], b: Option[String], c: Option[String], d: Option[Int], doThing: (String, String) => String, firstThis: (String, String) => Option[String], other: String => String) =>
          val f = IdiomBracket.monad[Option, String](doThing(other(extract(firstThis(extract(a), extract(b)))), extract(c)))
          f == Applicative[Option].apply2(Applicative[Option].map(Monad[Option].bind2(a,b)(firstThis))(other),c)(doThing)
        }
        "tricky function that takes a monad and extracts itself. Want to make sure we are not to eager to lift things" ! prop { (a: Option[String], b: Option[String], c: Option[String], d: Option[Int], doThing: (String, String, String) => String, firstThis: String => String, other: String => String) =>
          val f = IdiomBracket.monad[Option, String](doThing(other(firstThis(extract(a))),extract(b), extract(c)))
          f ==== Applicative[Option].apply3(Applicative[Option].map(Applicative[Option].map(a)(firstThis))(other),b,c)(doThing)
        }
        "if that is like a monadic function" ! prop { (a: Option[String], b: Option[String], c: Option[String], d: Option[Int], doThing: (String, String, String) => String, other: String => String) =>
          val f = IdiomBracket.monad[Option, String]{doThing(other(extract(if (extract(a) == "") Some("a") else None)),extract(b), extract(c))}
          f ==== Applicative[Option].apply3(Applicative[Option].map(Monad[Option].bind(a)(f => if (f == "") Some("a") else None))(other),b,c)(doThing)
        }
        "interpolated String" ! prop {(a: Option[String]) =>
          val f = IdiomBracket.monad[Option, String] {s"It is ${extract(a)}!"}
          f ==== Applicative[Option].map(a)(aa => s"It is $aa!")
        }
        "is lazy with if/else" in {
          import scala.concurrent.Future
          import scala.concurrent.Promise
          import scala.concurrent.ExecutionContext.Implicits.global
          import scala.concurrent.duration._

          val aPromise = Promise[Boolean]()
          val a = aPromise.future

          val bPromise = Promise[String]()
          val b = bPromise.future

          val cPromise = Promise[String]()
          val c = cPromise.future

          implicit val monad: Monad[Future] = scalaz.std.scalaFuture.futureInstance

          val f = IdiomBracket.monad[Future, String](if(extract(a)) extract(b) else extract(c))

          f.value ==== None

          aPromise.success(true)

          f.value ==== None

          bPromise.success("hello")

          Await.result(f, FiniteDuration(1, TimeUnit.SECONDS)) ==== "hello"
        }
      }
    }
    "block" in {
      "simple" ! prop { (a: Option[String]) =>
        def otherThing(ff: String) = ff * 3
        val f = IdiomBracket[Option, String] {
          otherThing(extract(a))
        }
        f ==== Applicative[Option].map(a)(otherThing)
      }
      "slighly more complex is a useless way you would think" ! prop { (a: Option[String]) =>
        def otherThing(ff: String) = ff * 3
        val f = IdiomBracket[Option, String] {
          otherThing(extract(a))
          otherThing(extract(a))
        }
        f ==== Applicative[Option].map(a)(otherThing)
      }
      "pointless val" ! prop { (a: Option[String]) =>
        def otherThing(ff: String) = ff * 3
        val f = IdiomBracket[Option, String] {
          val aa = otherThing(extract(a))
          aa
        }
        f ==== Applicative[Option].map(a)(otherThing)
      }
      "slighly less simple and somewhat usefull" ! prop { (a: Option[String]) =>
        def otherThing(ff: String) = ff * 3
        val f = IdiomBracket[Option, String] {
          val aa = otherThing(extract(a))
          otherThing(aa)
        }
        f ==== Applicative[Option].map(a)(aa => otherThing(otherThing(aa)))
      }
    }
    "match" in {
      "with extract in LHS" ! prop { (a: Option[String]) =>
        val f = IdiomBracket[Option, String] {
          extract(a) match {
            case "hello" => "h"
            case _ => "e"
          }
        }
        if (a.isDefined)
          f == Some(a.get match {
            case "hello" => "h"
            case _ => "e"
          })
        else
          f == None
      }
      "with stable identifier in case statement" ! prop { (a: Option[String], b: Option[String]) =>
        import IdiomBracket.extract
        val f = IdiomBracket[Option, String] {
          val bb = extract(b)
          extract(a) match {
            case `bb` => "h"
            case _ => "e"
          }
        }
        val expected = Applicative[Option].apply2(a,b)((a,b) =>
          a match {
            case `b` => "h"
            case _ => "e"
          }
        )
        f == expected
      }
    }
    "if statement" in {
      "extract in condition expression" ! prop { (a: Option[String]) =>
        val f = IdiomBracket[Option, Int] {
          if (extract(a).length == 5) 10 else 20
        }
        f ==== Applicative[Option].map(a)(aa => if (aa.length == 5) 10 else 20)
      }
    }
    tag("funky")
    "renamed import" ! prop { (a: Option[String], b: Option[String]) =>
      import IdiomBracket.{extract => extractt}
      def doThing(e: String, f: String) = e + f
      val f = IdiomBracket[Option, String](doThing(extractt(a),extractt(b)))
      f == Applicative[Option].apply2(a,b)(doThing)
    }
    tag("funky")
    "implicit extract" ! prop { (a: Option[String], b: Option[String]) =>
      import IdiomBracket.auto.extract
      def doThing(e: String, f: String) = e + f
      val f = IdiomBracket[Option, String](doThing(a,b))
      f == Applicative[Option].apply2(a,b)(doThing)
    }
    /*"SIP-22 example" ! prop { (optionDOY: Option[String]) =>
      val date = """(\d+)/(\d+)""".r
      case class Ok(message: String)
      case class NotFound(message: String)
      def nameOfMonth(num: Int): Option[String] = None

      val f = IdiomBracket {
        extract(optionDOY) match {
          case date(month, day) =>
            Ok(s"It’s ${extract(nameOfMonth(month.toInt))}!")
          case _ =>
            NotFound("Not a date, mate!")
        }
      }
    }*/
    "asc reverse core site" in {
      "without val pattern match" ! prop { (phone: Option[String], hitCounter: Option[String], locById: Option[String]) =>
        def test(a: String, b: String): Option[(String, String)] = Some((a, b))
        def otherTest(a: String, b: String, c: String): Option[String] = Some(a)

        val result = IdiomBracket.monad[Option, String] {
          val tuple: (String, String) = extract(test(extract(phone), extract(hitCounter)))
          extract(otherTest(tuple._2, tuple._1, extract(locById)))
        }
        val first = Monad[Option].bind2(phone, hitCounter)(test)
        val expected = Monad[Option].bind2(first, locById)((first1, locById1) => otherTest(first1._2, first1._1, locById1))
        result == expected
      }
      // I don't think this is easy to support for now cuz of issues with unapply in match statement
      // reminder: value pattern match is transformed into a pattern match
      /*"with original val pattern match" ! prop { (phone: Option[String], hitCounter: Option[String], locById: Option[String]) =>
        def test(a: String, b: String): Option[(String, String)] = Some((a, b))
        def otherTest(a: String, b: String, c: String): Option[String] = Some(a)

        val result = IdiomBracket.monad[Option, String] {
          val (dict, res) = extract(test(extract(phone), extract(hitCounter)))
          extract(otherTest(dict, res, extract(locById)))
        }
        val first = Monad[Option].bind2(phone, hitCounter)(test)
        val expected = Monad[Option].bind2(first, locById)((first1, locById1) => otherTest(first1._2, first1._1, locById1))
        result == expected
      }*/
    }
    "with interpolated string" in {
      "simple" ! prop {(a: Option[String]) =>
        val f = IdiomBracket[Option, String] {s"It’s ${extract(a)}!"}
        f ==== Applicative[Option].map(a)(aa => s"It’s $aa!")
      }
      "less simple" ! prop {(a: Option[String]) =>

        case class Ok(message: String)
        def nameOfMonth(num: Int): Option[String] = a
        val month = 5

        val f = IdiomBracket[Option, Ok]{
          Ok(s"It’s ${extract(nameOfMonth(month.toInt))}!")
        }

        if (a.isDefined)
          f == Some(Ok(s"It’s ${nameOfMonth(month.toInt).get}!"))
        else
          f == None
      }
    }
    "with currying" in {
      "2 currys with one param" ! prop { (a: Option[String]) =>
        def test(a: String)(b: String) = a + b
        val f = IdiomBracket[Option, String](test(extract(a))("foo"))
        f == Applicative[Option].map(a)(test(_)("foo"))
      }
      "2 currys with one two params" ! prop { (a: Option[String], b: Option[String]) =>
        def test(a: String, b: String)(c: String) = a + b + c
        val f = IdiomBracket[Option, String](test(extract(a), extract(b))("foo"))
        f == Applicative[Option].apply2(a, b)(test(_,_)("foo"))
      }
      "2 currys with two two params" ! prop { (a: Option[String], b: Option[String]) =>
        def test(a: String, b: String)(c: String, d: String) = a + b + c + d
        val f = IdiomBracket[Option, String](test(extract(a), extract(b))("foo", "bar"))
        f == Applicative[Option].apply2(a, b)(test(_,_)("foo", "bar"))
      }
    }
    "with List" ! prop {(a: List[String], b: List[String]) =>
      val f = IdiomBracket[List, String](extract(a) + extract(b))
      f == Applicative[List].apply2(a,b)(_ + _)
    }
    "with Future" ! prop {(a: scala.concurrent.Future[String], b: scala.concurrent.Future[String]) =>
      import scala.concurrent.Future
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration._

      implicit val applicative: Applicative[Future] = scalaz.std.scalaFuture.futureInstance

      val f = IdiomBracket[Future, String](extract(a) + extract(b))

      Await.result(f, FiniteDuration(100, TimeUnit.MILLISECONDS)) ==== Await.result(Applicative[Future].apply2(a,b)(_ + _), FiniteDuration(100, TimeUnit.MILLISECONDS))
    }
  }
}
