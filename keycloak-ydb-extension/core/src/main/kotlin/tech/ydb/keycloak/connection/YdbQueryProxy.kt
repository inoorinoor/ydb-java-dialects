package tech.ydb.keycloak.connection

import jakarta.persistence.Query
import tech.ydb.keycloak.utils.isYdbRetryable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class YdbQueryProxy private constructor(
    private val delegate: Query
) : InvocationHandler {

    companion object {
      fun create(query: Query): Query {

        val interfaces = query.javaClass.interfaces

        return Proxy.newProxyInstance(
          query.javaClass.classLoader,
          interfaces,
          YdbQueryProxy(query)
        ) as Query
      }
    }

  override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
    var attempt = 0

    while (true) {
      try {
        val result = if (args == null) {
          method.invoke(delegate)
        } else {
          method.invoke(delegate, *args)
        }

        // fluent API fix
        if (result === delegate) {
          return proxy
        }

        return result

      } catch (e: InvocationTargetException) {

        val cause = e.targetException

        if (!isYdbRetryable(cause) || attempt++ >= 5) {
          throw cause
        }

        Thread.sleep(5L shl attempt)
      }
    }
  }

}
