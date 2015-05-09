package com.github.iwaiawi.play2.rproxy

import play.api.{Logger, Play}
import play.api.http.{Writeable, HttpProtocol}
import play.api.mvc.HttpConnection._
import play.api.mvc.{Request, Result}
import play.api.libs.iteratee.{Iteratee, Enumerator}
import play.api.libs.ws.{WSRequestHolder, WSResponseHeaders, WS}
import play.api.http.HeaderNames._
import play.api.mvc._

import scala.concurrent.Future

trait ProxyInterpreter[A] extends (Request[A] => Future[Either[Result, Result]])

object ProxyInterpreter extends Writeables { interpreter =>

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  def apply[A <: AnyContent](f: Request[A] => Future[Result]): ProxyInterpreter[A] = new ProxyInterpreter[A] {
    override def apply(req: Request[A]): Future[Either[Result, Result]] = {
      try {
        f(req).map(Right(_))
      } catch {
        case e: Exception => {
          Logger.error( """Input request "%s" caught Exception. Headers are %s.""".format(req, req.headers.toSimpleMap), e)
          Future.successful(Left(Results.InternalServerError))
        }
      }
    }
  }

  def connection(headers: SimpleHeaders, default: HttpConnection.Value = KeepAlive): HttpConnection.Value = {
    import HttpConnection._
    headers.foldLeft(default) { (c, header) =>
      c match {
        case Close => c
        case KeepAlive =>
          header match {
            case (k, v) if CONNECTION.equalsIgnoreCase(k) && "Close".equalsIgnoreCase(v) => Close
            case _ => c
          }
      }
    }
  }

  def transferEncode(enum: Enumerator[Array[Byte]], headers: SimpleHeaders): Enumerator[Array[Byte]] = {
    import HttpProtocol.CHUNKED
    if (headers.exists(t => TRANSFER_ENCODING.equalsIgnoreCase(t._1) && CHUNKED.equalsIgnoreCase(t._2))) {
      import play.api.mvc.Results._
      enum &> chunk
    } else {
      enum
    }
  }

  object PlayWs {

    def apply[A <: AnyContent]: ProxyInterpreter[A] = interpreter.apply {
      wsStream[A](_) {
        _ |>>> Iteratee.fold(Array.empty[Byte]) { (merged, bytes) =>
          Array.concat(merged, bytes)
        } map {
          Enumerator(_)
        }
      }
    }

    def byChunked[A <: AnyContent]: ProxyInterpreter[A] = interpreter.apply {
      wsStream[A](_)(Future.successful(_))
    }

    private def wsStream[A <: AnyContent](req: Request[A])(f: Enumerator[Array[Byte]] => Future[Enumerator[Array[Byte]]]): Future[Result] = {
      wsReq(req).stream().flatMap {
        case (wsHeader: WSResponseHeaders, enum) => {
          val header = ResponseHeader(wsHeader.status, wsHeader.headers.toSimple)
          f(enum) map { body =>
            Result(
              header,
              transferEncode(body, header.headers),
              connection(header.headers)
            )
          }
        }
      }
    }

    private def wsReq[A <: AnyContent : Writeable](req: Request[A]): WSRequestHolder = {
      import Play.current
      WS.url(req.path)
        .withFollowRedirects(false)
        .withMethod(req.method)
        .withQueryString(req.queryString.toMap.mapValues(_.headOption.getOrElse("")).toSeq: _*)
        .withHeaders(req.headers.toSimpleMap.toSeq: _*)
        .withBody(implicitly[Writeable[A]].transform(req.body))
    }    
  }
  
}

trait Writeables {

  implicit def writeableOf_AnyContentAsJson(implicit codec: Codec): Writeable[AnyContentAsJson] =
    Writeable.writeableOf_JsValue.map(c => c.json)

  implicit def writeableOf_AnyContentAsXml(implicit codec: Codec): Writeable[AnyContentAsXml] =
    Writeable.writeableOf_NodeSeq.map(c => c.xml)

  implicit def writeableOf_AnyContentAsFormUrlEncoded(implicit code: Codec): Writeable[AnyContentAsFormUrlEncoded] =
    Writeable.writeableOf_urlEncodedForm.map(c => c.data)

  implicit def writeableOf_AnyContentAsRaw: Writeable[AnyContentAsRaw] =
    Writeable.wBytes.map(c => c.raw.initialData)

  implicit def writeableOf_AnyContentAsText(implicit code: Codec): Writeable[AnyContentAsText] =
    Writeable.wString.map(c => c.txt)

  implicit def writeableOf_AnyContentAsEmpty(implicit code: Codec): Writeable[AnyContentAsEmpty.type] =
    Writeable(_ => Array.empty[Byte], None)

  implicit def writeableOf_AnyContent[A <: AnyContent](implicit code: Codec): Writeable[A] = Writeable(
    content => content match {
      case c: AnyContentAsRaw => writeableOf_AnyContentAsRaw.transform(c)
      case c: AnyContentAsJson => writeableOf_AnyContentAsJson.transform(c)
      case c: AnyContentAsXml => writeableOf_AnyContentAsXml.transform(c)
      case c: AnyContentAsFormUrlEncoded => writeableOf_AnyContentAsFormUrlEncoded.transform(c)
      case c: AnyContentAsText => writeableOf_AnyContentAsText.transform(c)
      case _ => Array.empty
    },
    None
  )

}

