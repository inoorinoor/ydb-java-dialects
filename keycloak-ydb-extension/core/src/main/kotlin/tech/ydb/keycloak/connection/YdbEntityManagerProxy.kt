package tech.ydb.keycloak.connection

import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import org.keycloak.common.util.Retry
import tech.ydb.keycloak.utils.isYdbRetryable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

// TODO: add unit test
class YdbEntityManagerProxy(private val delegate: EntityManager) {

  private fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
    if (method.name in NO_RETRY) {
      return if (args == null) {
        method.invoke(delegate)
      } else {
        method.invoke(delegate, *args)
      }
    }

    var attempt = 0
    val maxRetries = 5

    while (true) {
      try {

        val result = if (args == null) {
          method.invoke(delegate)
        } else {
          method.invoke(delegate, *args)
        }

        // проксируем Query
        if (result is Query) {
          return YdbQueryProxy.create(result)
        }

        // ВАЖНО: вернуть результат
        return result

      } catch (e: InvocationTargetException) {

        val cause = e.targetException

        if (!isYdbRetryable(cause) || attempt++ >= maxRetries) {
          throw cause
        }

        LOG.warn("YDB retry EntityManager method=${method.name} attempt=$attempt")

        sleepBackoff(attempt)
      }
    }
  }

  private fun ydbRetryableResponse(cause: Throwable) = WebApplicationException(
    cause.message,
    cause,
    Response.status(Response.Status.SERVICE_UNAVAILABLE)
      .entity("""{"error":"ydb_retryable","error_description":"Transaction aborted due to contention, please retry"}""")
      .header("Retry-After", "1")
      .type(MediaType.APPLICATION_JSON_TYPE)
      .build()
  )

  private fun sleepBackoff(attempt: Int) {
    try {
      Thread.sleep((5L shl attempt)) // 5ms,10ms,20ms,40ms...
    } catch (_: InterruptedException) {
    }
  }

  companion object {
    private val LOG: Logger = Logger.getLogger(YdbEntityManagerProxy::class.java)

    fun create(em: EntityManager): EntityManager {
      val proxy = YdbEntityManagerProxy(em)
      return Proxy.newProxyInstance(
        EntityManager::class.java.classLoader,
        arrayOf(EntityManager::class.java),
        proxy::invoke
      ) as EntityManager
    }

    private val NO_RETRY = setOf(
      "getTransaction",
      "close",
      "isOpen",
      "unwrap",
      "getDelegate"
    )
  }
}
