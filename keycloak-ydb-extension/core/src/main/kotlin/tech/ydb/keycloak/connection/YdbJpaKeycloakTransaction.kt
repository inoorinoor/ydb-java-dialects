
import jakarta.persistence.EntityManager
import org.jboss.logging.Logger
import org.keycloak.connections.jpa.JpaKeycloakTransaction
import java.sql.SQLRecoverableException

class YdbJpaKeycloakTransaction(em: EntityManager?) : JpaKeycloakTransaction(em) {
  override fun commit() {
    var attempt = 0

    while (true) {
      try {
        super.commit()
        return
      } catch (e: RuntimeException) {
        if (!isRetryable(e) || attempt++ >= 10) {
          throw e
        }

        LOG.warn("YDB transaction aborted, retrying attempt " + attempt)

        safeRollback()
        super.begin()
      }
    }
  }

  private fun isRetryable(e: Throwable?): Boolean {
    var t = e

    while (t != null) {
      if (t is SQLRecoverableException) {
        return true
      }

      if (t.javaClass.getName().contains("YdbRetryableException")) {
        return true
      }

      t = t.cause
    }

    return false
  }

  private fun safeRollback() {
    try {
      if (isActive()) {
        rollback()
      }
    } catch (ignored: Exception) {
    }
  }

  companion object {
    private val LOG: Logger = Logger.getLogger(YdbJpaKeycloakTransaction::class.java)
  }
}