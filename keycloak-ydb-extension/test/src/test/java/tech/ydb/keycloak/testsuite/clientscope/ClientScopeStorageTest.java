package tech.ydb.keycloak.testsuite.clientscope;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.*;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.clientscope.ClientScopeStorageProvider;
import org.keycloak.storage.clientscope.ClientScopeStorageProviderModel;
import tech.ydb.keycloak.testsuite.KeycloakModelTest;
import tech.ydb.keycloak.testsuite.RequireProvider;

@RequireProvider(RealmProvider.class)
@RequireProvider(ClientScopeStorageProvider.class)
public class ClientScopeStorageTest extends KeycloakModelTest {

    private String realmId;
    private String clientScopeFederationId;

    @Override
    public void createEnvironment(KeycloakSession s) {
        RealmModel realm = createRealm(s, "realm");
        s.getContext().setRealm(realm);
        realm.setDefaultRole(s.roles().addRealmRole(realm, Constants.DEFAULT_ROLES_ROLE_PREFIX + "-" + realm.getName()));
        this.realmId = realm.getId();
    }

    @Override
    public void cleanEnvironment(KeycloakSession s) {
        RealmModel realm = s.realms().getRealm(realmId);
        s.getContext().setRealm(realm);
        s.realms().removeRealm(realmId);
    }

    @Test
    public void testGetClientScopeById() {
        getParameters(ClientScopeStorageProviderModel.class).forEach(fs -> inComittedTransaction(fs, (session, federatedStorage) -> {
            Assume.assumeThat("Cannot handle more than 1 client scope federation provider", clientScopeFederationId, Matchers.nullValue());
            RealmModel realm = session.realms().getRealm(realmId);
            federatedStorage.setParentId(realmId);
            federatedStorage.setEnabled(true);
            federatedStorage.getConfig().putSingle(HardcodedClientScopeStorageProviderFactory.SCOPE_NAME, HardcodedClientScopeStorageProviderFactory.SCOPE_NAME);
            ComponentModel res = realm.addComponentModel(federatedStorage);
            clientScopeFederationId = res.getId();
            log.infof("Added %s client scope federation provider: %s", federatedStorage.getName(), clientScopeFederationId);
            return null;
        }));

        inComittedTransaction(1, (session, i) -> {
            final RealmModel realm = session.realms().getRealm(realmId);
            StorageId storageId = new StorageId(clientScopeFederationId, "scope_name");
            ClientScopeModel hardcoded = session.clientScopes().getClientScopeById(realm, storageId.getId());
            Assert.assertNotNull(hardcoded);
            return null;
        });
    }
}
