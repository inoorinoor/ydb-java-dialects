package tech.ydb.keycloak.role

import org.jboss.logging.Logger
import org.keycloak.Config
import org.keycloak.models.KeycloakSession
import org.keycloak.models.jpa.JpaRoleProviderFactory
import org.keycloak.provider.EnvironmentDependentProviderFactory
import tech.ydb.keycloak.config.ProviderPriority.PROVIDER_PRIORITY
import tech.ydb.keycloak.config.YdbProfile.IS_YDB_PROFILE_ENABLED
import tech.ydb.keycloak.connection.YdbConnectionProvider
import tech.ydb.keycloak.realm.YdbRealmProvider

// TODO: test deletion of roles after adding tables
//  GROUP_ROLE_MAPPING, KEYCLOAK_GROUP
//  CLIENT_SCOPE_ROLE_MAPPING, CLIENT_SCOPE and so on...
class YdbRoleProviderFactory : JpaRoleProviderFactory(), EnvironmentDependentProviderFactory {
  private val logger = Logger.getLogger(YdbRoleProviderFactory::class.java)

  override fun create(session: KeycloakSession): YdbRealmProvider {
    val provider = session.getProvider(YdbConnectionProvider::class.java)?.let {
      YdbRealmProvider(session, it.entityManager)
    } ?: error("YdbConnectionProvider is not configured")

    logger.info("YdbRoleProvider successfully created")

    return provider
  }

  override fun getId(): String = ID

  override fun isSupported(scope: Config.Scope): Boolean = IS_YDB_PROFILE_ENABLED

  override fun order(): Int = PROVIDER_PRIORITY + 1

  private companion object {
    private const val ID = "ydb-role-provider-factory"
  }
}