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

package org.openkilda.persistence.inmemory;

import org.openkilda.config.provider.ConfigurationProvider;
import org.openkilda.config.provider.PropertiesBasedConfigurationProvider;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.tx.TransactionManager;

import org.junit.Before;
import org.junit.BeforeClass;
import org.mockito.Mockito;

public abstract class InMemoryGraphBasedTest {

    protected static ConfigurationProvider configurationProvider;
    protected static InMemoryGraphPersistenceManager inMemoryGraphPersistenceManager;
    protected static PersistenceManager persistenceManager;
    protected static TransactionManager transactionManager;
    protected static RepositoryFactory repositoryFactory;

    @BeforeClass
    public static void initPersistenceManager() {
        configurationProvider = new PropertiesBasedConfigurationProvider();
        NetworkConfig networkConfig = configurationProvider.getConfiguration(NetworkConfig.class);
        inMemoryGraphPersistenceManager = new InMemoryGraphPersistenceManager(networkConfig);
        persistenceManager = Mockito.spy(inMemoryGraphPersistenceManager);
        transactionManager = persistenceManager.getTransactionManager();
        repositoryFactory = Mockito.spy(persistenceManager.getRepositoryFactory());
        Mockito.when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);
    }

    @Before
    public void cleanTinkerGraph() {
        inMemoryGraphPersistenceManager.purgeData();
    }

    protected Switch createTestSwitch(long switchId) {
        Switch sw = Switch.builder()
                .switchId(new SwitchId(switchId))
                .description("test_description")
                //.socketAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.1"), 30070))
                .controller("test_ctrl")
                .hostname("test_host_" + switchId)
                .status(SwitchStatus.ACTIVE)
                .build();
        repositoryFactory.createSwitchRepository().add(sw);
        return sw;
    }
}
