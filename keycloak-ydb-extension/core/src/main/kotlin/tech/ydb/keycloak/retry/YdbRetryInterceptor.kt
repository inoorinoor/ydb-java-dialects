package tech.ydb.keycloak.retry

import net.bytebuddy.implementation.bind.annotation.RuntimeType
import net.bytebuddy.implementation.bind.annotation.SuperCall
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException
import java.util.concurrent.Callable
import java.util.concurrent.ThreadLocalRandom
import java.util.logging.Level
import java.util.logging.Logger

object YdbRetryInterceptor {

    private val LOG = Logger.getLogger(YdbRetryInterceptor::class.java.name)

    private val MAX_RETRIES: Int by lazy {
        System.getProperty("ydb.retry.maxAttempts", "20").toIntOrNull() ?: 20
    }

    private const val BASE_DELAY_MS = 50L
    private const val MAX_DELAY_MS = 2000L

    @JvmStatic
    @RuntimeType
    fun intercept(@SuperCall zuper: Callable<Any?>): Any? {
        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            try {
                return zuper.call()
            } catch (e: Exception) {
                if (!isYdbRetryable(e)) throw e
                lastException = e
                LOG.log(
                    Level.WARNING,
                    "YDB transaction ABORTED, retry attempt ${attempt + 1}/$MAX_RETRIES",
                    e
                )
                backoff(attempt)
            }
        }

        throw lastException!!
    }

    private fun backoff(attempt: Int) {
        val expDelay = (BASE_DELAY_MS shl attempt).coerceAtMost(MAX_DELAY_MS)
        val jitter = ThreadLocalRandom.current().nextLong(expDelay)
        Thread.sleep(jitter)
    }

    fun isYdbRetryable(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is SQLRecoverableException || cause is SQLTransientException) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
}