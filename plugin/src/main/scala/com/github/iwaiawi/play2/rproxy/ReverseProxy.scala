package com.github.iwaiawi.play2.rproxy

import play.api._
import play.api.http.Writeable
import play.api.libs.concurrent.Execution.Implicits
import play.api.mvc._

import scala.concurrent.Future

abstract class ReverseProxy(val backendHost: String, val proxyHost: String) extends Controller {

  def passThrough[B <: AnyContent](filter: ProxyFilter[AnyContent, B])(implicit builder: ActionBuilder[Request]): Action[AnyContent] =
    proxy[B](filter ~> ProxyFilter[B](backendHost, proxyHost))

  def passThrough()(implicit builder: ActionBuilder[Request]): Action[AnyContent] = passThrough(ProxyFilter.nop[AnyContent])

  def cacheableThrough[B <: AnyContent](filter: ProxyFilter[AnyContent, B])(implicit builder: ActionBuilder[Request]): Action[AnyContent] =
    cacheable(filter ~> ProxyFilter[B](backendHost, proxyHost))

  def cacheableThrough()(implicit builder: ActionBuilder[Request]): Action[AnyContent] = cacheableThrough(ProxyFilter.nop[AnyContent])

  def contentModify[B <: AnyContent, C](filter: ProxyFilter[AnyContent, B])(f: String => C)(implicit builder: ActionBuilder[Request], writer: Writeable[C]): Action[AnyContent] =
    cacheable(
      filter ~>
        ProxyFilter.contentModify[B, C](f) ~>
        ProxyFilter[B](backendHost, proxyHost)
    )

  def contentModify[C](f: String => C)(implicit builder: ActionBuilder[Request], writer: Writeable[C]): Action[AnyContent] =
    contentModify(ProxyFilter.nop[AnyContent])(f)

  def headersModify[B <: AnyContent](filter: ProxyFilter[AnyContent, B])(f: ((String, String)) => (String, String))(implicit builder: ActionBuilder[Request]): Action[AnyContent] =
    cacheable(
      filter ~>
        ResponseFilter.headersModify(f) ~>
        ProxyFilter[B](backendHost, proxyHost)
    )

  def headersModify(f: ((String, String)) => (String, String))(implicit builder: ActionBuilder[Request]): Action[AnyContent] =
    headersModify(ProxyFilter.nop[AnyContent])(f)

  def cacheable[B <: AnyContent](filter: ProxyFilter[AnyContent, B])(implicit builder: ActionBuilder[Request]): Action[AnyContent] = proxy(filter, ProxyInterpreter.PlayWs[B])

  def proxy[B <: AnyContent](filter: ProxyFilter[AnyContent, B])(implicit builder: ActionBuilder[Request]): Action[AnyContent] = proxy(filter, ProxyInterpreter.PlayWs.byChunked[B])

  def proxy[B](filter: ProxyFilter[AnyContent, B], interpreter: ProxyInterpreter[B])(implicit builder: ActionBuilder[Request]): Action[AnyContent] = proxy(parse.anyContent, filter, interpreter)

  def proxy[A, B](bodyParser: BodyParser[A], filter: ProxyFilter[A, B], interpreter: ProxyInterpreter[B])(implicit builder: ActionBuilder[Request]): Action[A] = builder.async(bodyParser) { request: Request[A] =>
    import play.api.libs.concurrent.Execution.Implicits.defaultContext

    val proxyRequest: Future[Either[Result, Request[B]]] = filter.request(request)
    val interpreterResponse: Future[Either[Result, Result]] = proxyRequest.flatMapE(interpreter)
    val proxyResponse: Future[Either[Result, Result]] = interpreterResponse.flatMapE(filter.response.apply)

    logging(request, proxyRequest, interpreterResponse, proxyResponse)

    proxyResponse map {
      case Right(result) => result
      case Left(result) => result
    }
  }

  def logging[A, B](
                     originalRequest: Request[A],
                     proxyRequest: Future[Either[Result, Request[B]]],
                     interpreterResponse: Future[Either[Result, Result]],
                     proxyResponse: Future[Either[Result, Result]]
                     ) {
    def headerRequest[A](e: Either[Result, Request[A]]): SimpleHeaders = e match {
      case Right(req) => req.headers.toSimpleMap
      case Left(result) => result.header.headers
    }

    def headerResponse(e: Either[Result, Result]): SimpleHeaders = e match {
      case Right(result) => result.header.headers
      case Left(result) => result.header.headers
    }

    import Implicits.defaultContext

    for {
      pReq <- proxyRequest
      iResp <- interpreterResponse
      pResp <- proxyResponse
    } yield {
      Logger.debug(originalRequest.toString() + " => " +
        Map(
          "originalRequest" -> originalRequest.headers.toSimpleMap.mkString("\n\t\t", "\n\t\t", ""),
          "proxyRequest" -> headerRequest(pReq).mkString("\n\t\t", "\n\t\t", ""),
          "originalResponse" -> headerResponse(iResp).mkString("\n\t\t", "\n\t\t", ""),
          "proxyResponse" -> headerResponse(pResp).mkString("\n\t\t", "\n\t\t", "")
        ).mkString("\n\t", "\n\t", "") //.toString()
      )
    }
  }
}
