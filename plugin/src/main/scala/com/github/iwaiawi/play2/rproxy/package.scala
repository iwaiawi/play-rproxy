package com.github.iwaiawi.play2

import play.api.mvc.{Request, Result}

import scala.concurrent.Future

package object rproxy {

  private type Headers[+A] = Map[String, A]
  type SimpleHeaders = Headers[String]
  type MultiValueHeaders = Headers[Seq[String]]

  implicit class HeadersWrapper[A](h: Headers[A]) {
    def getIgnoreCase(key: String): Option[A] = h.foldLeft[Option[A]](None) {
      case (Some(found), _) => Some(found)
      case (_, (k, v)) if k.equalsIgnoreCase(key) => Option(v)
      case _ => None
    }
  }

  implicit class SimpleHeadersWrapper(simple: SimpleHeaders) {
    def toMultiValue: MultiValueHeaders = simple.mapValues(Seq(_))
  }

  implicit class MultiValueHeadersWrapper(multi: MultiValueHeaders) {
    def toSimple: SimpleHeaders = multi.mapValues(_.headOption.getOrElse(""))
  }

  implicit class FutureEitherWrapper[E, A](futureEither: Future[Either[E, A]]) {
    import play.api.libs.concurrent.Execution.Implicits.defaultContext
    def flatMapE[B](f: A => Future[Either[E, B]]): Future[Either[E, B]] = futureEither.flatMap {
      case Right(a) => f(a)
      case Left(e) => Future.successful(Left(e))
    }
  }

}
