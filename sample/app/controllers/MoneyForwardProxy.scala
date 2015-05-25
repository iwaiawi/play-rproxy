package controllers

import play.api.Logger
import play.api.cache.Cached
import play.api.mvc._
import play.api.cache.Cache

import com.github.iwaiawi.play2.rproxy._
import com.github.iwaiawi.play2.rproxy.ProxyFilter.Implicit._

object MoneyForwardProxy extends ReverseProxy(
  backendHost = "https://moneyforward.com",
  proxyHost = "http://localhost:9000"
) {

  import play.api.Play.current

  implicit val defaultBuilder = play.api.mvc.Action

  // GET https://moneyforward.com/cf
  def cf() = Cached.status(r => Seq(r.method, r.path, r.queryString).toString(), 200, 5 * 60) {
    contentModify(
      MfSessionCache.redirectSelfWithSessionIfNotHave[AnyContent] ~>
        MfSessionCache() ~>
        MfSessionCache.withSession
    ) {
      def addPagingLink4Kimono(content: String): String = {
        content.replaceAllLiterally(
          """<div class='mf-col-custom-body clearfix'>""",
          """<div class='mf-col-custom-body clearfix'><a class="fc-button fc-button-prev kimono-paging-prev">◄◄◄</a>"""
        )
      }
      content => addPagingLink4Kimono(content)
    }
  }

  // POST https://moneyforward.com/cf/fetch
  def fetch() = super.cacheableThrough(
    ProxyFilter.cache[AnyContent]({ r =>
      Seq(r.method, r.path, r.body.asFormUrlEncoded.flatMap(_.get("from"))).toString()
    }, 5 * 60)
  )

  // POST https://moneyforward.com/session
  def session() = headersModify[AnyContent](MfSessionCache()) {
    case (k, v) if LOCATION.equalsIgnoreCase(k) && v == proxyHost + "/" => (LOCATION, proxyHost + "/cf")
    case t => t
  }

  // POST https://moneyforward.com/users/sign_in
  def sign_in() = headersModify[AnyContent](MfSessionCache()) {
    case (k, v) if LOCATION.equalsIgnoreCase(k) && v == proxyHost + "/" => (LOCATION, proxyHost + "/cf")
    case t => t
  }

  // Redirect https://moneyforward.com/assets/*
  def assets(path: String = "/") = Action(req => {
    Found(backendHost + req.path)
  })

  def cachedPassThrough(path: String = "/") = Cached.status({ req =>
    Seq(req.method, req.path, req.queryString).toString()
  }, 200, 10 * 60) {
    super.cacheableThrough(MfSessionCache())
  }

  def passThrough(path: String = "/") = super.passThrough(MfSessionCache())

  // clear MfSessionCache for admin
  def clear = Action(req => {
    MfSessionCache.clear()
    Ok(s"Clear session cache.")
  })

  object MfSessionCache {

    private val name = "_moneybook_session"
    private val cacheKey = (proxyHost, name).toString

    def apply() = ResponseFilter.simple { result: Result =>
      if (result.header.headers.getIgnoreCase(LOCATION).forall(!_.endsWith("/users/sign_in"))) {
        // if auth succeeded
        Cookies(result.header.headers.getIgnoreCase(SET_COOKIE)).get(name).foreach { c =>
          Cache.set(cacheKey, c)
          Logger.debug(s"Update session cache $c")
        }
      }
      result
    }

    def clear(): Unit = {
      Cache.getAs[Cookie](cacheKey).foreach { c =>
        Cache.remove(cacheKey)
        Logger.debug(s"Clear session cache $c")
      }
    }

    val withSession = ResponseFilter.simple { result: Result =>
      val orig = Cookies(result.header.headers.getIgnoreCase(SET_COOKIE)).get(name)
      val cached = Cache.getAs[Cookie](cacheKey)
      (orig, cached) match {
        case (None, Some(c)) => result.withCookies(c)
        case _ => result
      }
    }

    def redirectSelfWithSessionIfNotHave[A] = RequestFilter.sync[A, A] { req: Request[A] =>
      val orig = Cookies(req.headers.toSimpleMap.getIgnoreCase(COOKIE)).get(name)
      val cached = Cache.getAs[Cookie](cacheKey)
      (orig, cached) match {
        case (None, Some(c)) => Left(Found(req.uri).withCookies(c))
        case _ => Right(req)
      }
    }
  }

}