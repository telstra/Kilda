/* Copyright 2020 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.persistence.spi;

import org.openkilda.config.provider.ConfigurationProvider;
import org.openkilda.persistence.InMemoryGraphPersistenceManager;
import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.context.PersistenceContextManager;

/**
 * In-memory implementation of the provider for persistence manager(s).
 * Built on top of Tinkerpop / Ferma implementation.
 */
public class InMemoryPersistenceProvider implements PersistenceProvider {
    @Override
    public PersistenceManager getPersistenceManager(ConfigurationProvider configurationProvider) {
        NetworkConfig networkConfig = configurationProvider.getConfiguration(NetworkConfig.class);
        return new InMemoryGraphPersistenceManager(networkConfig);
    }

    @Override
    public PersistenceContextManager getPersistenceContextManager() {
        return new InMemoryPersistenceContextManager();
    }

    /**
     * In-memory implementation of {@link PersistenceContextManager}. It ignores context events.
     */
    public static class InMemoryPersistenceContextManager implements PersistenceContextManager {
        @Override
        public void initContext() {
        }

        @Override
        public boolean isContextInitialized() {
            return true;
        }

        @Override
        public void closeContext() {
        }
    }
}
