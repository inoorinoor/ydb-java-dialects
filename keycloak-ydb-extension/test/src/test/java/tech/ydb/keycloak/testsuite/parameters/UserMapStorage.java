/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tech.ydb.keycloak.testsuite.parameters;

import com.google.common.collect.ImmutableSet;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderModel;
import tech.ydb.keycloak.testsuite.KeycloakModelParameters;
import tech.ydb.keycloak.testsuite.user.UserMapStorageFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 *
 * @author hmlnarik
 */
public class UserMapStorage extends KeycloakModelParameters {

    static final Set<Class<? extends Spi>> ALLOWED_SPIS = ImmutableSet.<Class<? extends Spi>>builder()
      .build();

    static final Set<Class<? extends ProviderFactory>> ALLOWED_FACTORIES = ImmutableSet.<Class<? extends ProviderFactory>>builder()
      .add(UserMapStorageFactory.class)
      .build();

    private final AtomicInteger counter = new AtomicInteger();

    public UserMapStorage() {
        super(ALLOWED_SPIS, ALLOWED_FACTORIES);
    }

    @Override
    public <T> Stream<T> getParameters(Class<T> clazz) {
        if (UserStorageProviderModel.class.isAssignableFrom(clazz)) {
            UserStorageProviderModel federatedStorage = new UserStorageProviderModel();
            federatedStorage.setName(UserMapStorageFactory.PROVIDER_ID + ":" + counter.getAndIncrement());
            federatedStorage.setProviderId(UserMapStorageFactory.PROVIDER_ID);
            federatedStorage.setProviderType(UserStorageProvider.class.getName());
            return Stream.of((T) federatedStorage);
        } else {
            return super.getParameters(clazz);
        }
    }
}
