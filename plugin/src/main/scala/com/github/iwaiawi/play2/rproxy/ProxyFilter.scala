package com.github.iwaiawi.play2.rproxy

import java.net.URL

import play.api.Application
import play.api.cache.Cache
import play.api.http.{Writeable, HeaderNames}
import play.api.http.HeaderNames._
import play.api.libs.iteratee.{Iteratee, Enumerator}
import play.api.mvc._

import scala.concurrent.Future

trait ProxyFilter[A, B] {
  val request: RequestFilter[A, B]
  val response: ResponseFilter

  def ~>[C](other: ProxyFilter[B, C]): ProxyFilter[A, C] =
    ProxyFilter(request ~> other.request, response <~ other.response)

  def ~>[C](other: RequestFilter[B, C]): ProxyFilter[A, C] =
    ProxyFilter(request ~> other, response)

  def ~>(other: ResponseFilter): ProxyFilter[A, B] =
    ProxyFilter(request, response <~ other)
}

case class RequestFilter[A, B](run: (Request[A] => Future[Either[Result, Request[B]]])) {
  def apply(r: Request[A]): Future[Either[Result, Request[B]]] = run(r)

  def ~>[C](other: RequestFilter[B, C]) = RequestFilter(run andThen {
    _.flatMapE(other.run)
  })

  def <~[C](other: RequestFilter[C, A]) = RequestFilter(other.run andThen {
    _.flatMapE(run)
  })

  def ~>[C](other: ProxyFilter[B, C]): ProxyFilter[A, C] = ProxyFilter(this ~> other.request, other.response)

  def <~[C](other: ProxyFilter[C, A]): ProxyFilter[C, B] = ProxyFilter(this <~ other.request, other.response)
}

case class ResponseFilter(run: (Result => Future[Either[Result, Result]])) {
  def apply(r: Result): Future[Either[Result, Result]] = run(r)

  def ~>(other: ResponseFilter) = ResponseFilter(run andThen {
    _.flatMapE(other.run)
  })

  def <~(other: ResponseFilter) = ResponseFilter(other.run andThen {
    _.flatMapE(run)
  })

  def ~>[B, C](other: ProxyFilter[B, C]): ProxyFilter[B, C] = ProxyFilter(other.request, this <~ other.response)

  def <~[B, C](other: ProxyFilter[B, C]): ProxyFilter[B, C] = ProxyFilter(other.request, this ~> other.response)
}


object ProxyFilter {

  def apply[A, B](req: RequestFilter[A, B], resp: ResponseFilter): ProxyFilter[A, B] = new ProxyFilter[A, B] {
    val request = req
    val response = resp
  }

  def nop[A](): ProxyFilter[A, A] = new ProxyFilter[A, A] {
    val request = RequestFilter.nop[A]
    val response = ResponseFilter.nop
  }

  def apply[A](backendHost: String, proxyHost: String): ProxyFilter[A, A] = passThrough[A](backendHost, proxyHost)

  def passThrough[A](backendHost: String, proxyHost: String): ProxyFilter[A, A] = ProxyFilter[A, A](
    RequestFilter.passThrough[A](backendHost, proxyHost),
    ResponseFilter.passThrough(backendHost, proxyHost)
  )

  def contentModify[A, C](f: String => C)(implicit writeable: Writeable[C], codec: Codec): ProxyFilter[A, A] = ProxyFilter[A, A](
    RequestFilter.disableGzip[A],
    ResponseFilter.dechunkIfChunked ~> ResponseFilter.contentMap[C](codec.decode andThen f)
  )

  def cache[A](key: Request[A] => String, duration: Int)(implicit app: Application): ProxyFilter[A, A] = new ProxyFilter[A, A] {
    private var cacheKey: Option[String] = None

    val request = RequestFilter.sync[A, A] { req: Request[A] =>
      cacheKey = Option(key(req))

      cacheKey.flatMap(Cache.getAs[Result](_)) match {
        case Some(result) => Left(result.withHeaders("X-Cache-Key" -> cacheKey.getOrElse("no")))
        case _ => Right(req)
      }
    }

    val response = ResponseFilter.simple { result: Result =>
      cacheKey.foreach { k =>
        if (result.header.status == 200) {
          Cache.set(k, result, duration)
        }
      }
      result
    }
  }

  object Implicit {

    implicit class ActionWrapper[A](val action: Action[A]) {

      import play.api.libs.concurrent.Execution.Implicits.defaultContext

      def ~>(filter: ProxyFilter[A, A]): Action[A] = {
        new Action[A] {
          def parser = action.parser

          def apply(req: Request[A]) = filter.request.run(req).flatMapE {
            action(_).map(Right(_))
          } flatMapE {
            filter.response.run
          } map {
            case Right(r) => r
            case Left(r) => r
          }
        }
      }

      def ~>(filter: RequestFilter[A, A]): Action[A] = {
        new Action[A] {
          def parser = action.parser

          def apply(req: Request[A]) = filter.run(req).flatMapE {
            action(_).map(Right(_))
          } map {
            case Right(r) => r
            case Left(r) => r
          }
        }
      }

      def ~>(filter: ResponseFilter): Action[A] = {
        new Action[A] {
          def parser = action.parser

          def apply(req: Request[A]) = action(req).map(Right[Result, Result](_)).flatMapE {
            filter.run
          } map {
            case Right(r) => r
            case Left(r) => r
          }
        }
      }

    }

    implicit def requestFilter2ProxyFilter[A, B](req: RequestFilter[A, B]): ProxyFilter[A, B] = ProxyFilter(req, ResponseFilter.nop)

    implicit def responseFilter2ProxyFilter[A](resp: ResponseFilter): ProxyFilter[A, A] = ProxyFilter(RequestFilter.nop[A], resp)

  }

}

object RequestFilter {

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  def nop[A](): RequestFilter[A, A] = simple[A](identity)

  def sync[A, B](f: (Request[A] => Either[Result, Request[B]])): RequestFilter[A, B] = RequestFilter { r: Request[A] =>
    Future.successful(f(r))
  }

  def simple[A](f: (Request[A] => Request[A])): RequestFilter[A, A] = RequestFilter { r: Request[A] =>
    Future.successful(Right(f(r)))
  }

  def apply[A](backendHost: String, proxyHost: String): RequestFilter[A, A] = passThrough[A](backendHost, proxyHost)

  def passThrough[A](backendHost: String, proxyHost: String): RequestFilter[A, A] = {

    implicit class URLWrapper(val url: URL) {
      def hostAndPort: String = url.getHost +
        (url.getPort match {
          case 80 => ""
          case n => ":" + n.toString
        })
    }

    path[A](backendHost + _.path) ~> headersModify[A] {
      case (k, v) if HOST.equalsIgnoreCase(k) => (k, v.replace(new URL(proxyHost).hostAndPort, new URL(backendHost).hostAndPort))
      case (k, v) if REFERER.equalsIgnoreCase(k) || ORIGIN.equalsIgnoreCase(k) => (k, v.replace(proxyHost, backendHost))
      case t => t
    }
  }

  def disableGzip[A] = headersFilter[A](!_._1.equalsIgnoreCase(HeaderNames.ACCEPT_ENCODING))

  def path[A](f: Request[A] => String) = RequestFilter.simple[A] { orig: Request[A] =>
    Request(orig.copy(path = f(orig)), orig.body)
  }

  def headersModify[A](f: ((String, String)) => (String, String)) = headers[A](_.map(f))

  def headersFilter[A](f: ((String, String)) => Boolean) = headers[A](_.filter(f))

  def headers[A](f: SimpleHeaders => SimpleHeaders) = RequestFilter.simple[A] { orig: Request[A] =>
    Request(
      orig.copy(
        headers = new Headers {
          val data = f(orig.headers.toSimpleMap).toMultiValue.toSeq
        }
      ),
      orig.body)
  }
}

object ResponseFilter {

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  val nop: ResponseFilter = simple(identity)

  def sync(f: (Result => Either[Result, Result])): ResponseFilter = ResponseFilter { r: Result =>
    Future.successful(f(r))
  }

  def simple(f: (Result => Result)): ResponseFilter = ResponseFilter { r: Result =>
    Future.successful(Right(f(r)))
  }

  def apply(backendHost: String, proxyHost: String): ResponseFilter = passThrough(backendHost, proxyHost)

  def passThrough(backendHost: String, proxyHost: String): ResponseFilter = {
    headersModify {
      case (k, v) if LOCATION.equalsIgnoreCase(k) => (k, v.replace(backendHost, proxyHost))
      case t => t
    } <~ unsecureCookie
  }

  lazy val unsecureCookie: ResponseFilter = headersModify {
    case (k, v) if SET_COOKIE.equalsIgnoreCase(k) => (k, v.replaceAllLiterally( """; secure; HttpOnly""", ""))
    case t => t
  }

  lazy val dechunkIfChunked: ResponseFilter =
    calcContentLength() <~
      headersFilter(!_._1.equalsIgnoreCase(TRANSFER_ENCODING)) <~
      ResponseFilter.simple { orig: Result =>
        if (orig.header.headers.keys.exists(_.equalsIgnoreCase(TRANSFER_ENCODING))) {
          orig.copy(body = orig.body &> Results.dechunk)
        } else {
          orig
        }
      }

  def calcContentLength(isForced: Boolean = false): ResponseFilter = ResponseFilter { orig: Result =>
    if (isForced ||
      (
        !orig.header.headers.keys.exists(_.equalsIgnoreCase(CONTENT_LENGTH))
          && !orig.header.headers.keys.exists(_.equalsIgnoreCase(TRANSFER_ENCODING))
        )
    ) {
      orig.body |>>> Iteratee.fold(Array.empty[Byte]) { (merged, bytes) =>
        Array.concat(merged, bytes)
      } map { entire =>
        orig.copy(
          header = orig.header.copy(
            headers = orig.header.headers.filterKeys(!_.equalsIgnoreCase(CONTENT_LENGTH))
              + (CONTENT_LENGTH -> entire.length.toString)
          ),
          body = Enumerator(entire)
        )
      } map (Right(_))
    } else {
      Future.successful(Right(orig))
    }
  }

  def contentMap[C](f: Array[Byte] => C)(implicit writeable: Writeable[C]): ResponseFilter = ResponseFilter { orig: Result =>
    orig.body |>>> Iteratee.fold(Array.empty[Byte]) { (merged, bytes) =>
      Array.concat(merged, bytes)
    } map { entire =>
      Right(orig.copy(body = Enumerator(writeable.transform(f(entire)))))
    }
  } ~> ResponseFilter.calcContentLength(isForced = true)

  def headers(f: SimpleHeaders => SimpleHeaders): ResponseFilter = ResponseFilter.simple { orig: Result =>
    orig.copy(header = orig.header.copy(headers = f(orig.header.headers)))
  }

  def headersModify(f: ((String, String)) => (String, String)) = headers(_.map(f))

  def headersFilter(f: ((String, String)) => Boolean) = headers(_.filter(f))
}





