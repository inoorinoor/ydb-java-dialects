package tech.ydb.keycloak.testsuite.clientscope;

import java.util.List;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.storage.clientscope.ClientScopeStorageProviderFactory;
import org.keycloak.storage.clientscope.ClientScopeStorageProviderModel;

public class HardcodedClientScopeStorageProviderFactory implements ClientScopeStorageProviderFactory<HardcodedClientScopeStorageProvider> {

    public static final String PROVIDER_ID = "hardcoded-clientscope";
    public static final String SCOPE_NAME = "scope_name";
    protected static final List<ProviderConfigProperty> CONFIG_PROPERTIES;

    @Override
    public HardcodedClientScopeStorageProvider create(KeycloakSession session, ComponentModel model) {
        return new HardcodedClientScopeStorageProvider(session, new ClientScopeStorageProviderModel(model));
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
  
    static {
        CONFIG_PROPERTIES = ProviderConfigurationBuilder.create()
                .property().name(SCOPE_NAME)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Hardcoded Scope Name")
                .helpText("Only this scope name is available for lookup")
                .defaultValue("hardcoded-clientscope")
                .add()
                .build();
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }
}
