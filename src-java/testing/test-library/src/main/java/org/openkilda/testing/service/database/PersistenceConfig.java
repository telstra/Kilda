/* Copyright 2018 Telstra Open Source
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

package org.openkilda.testing.service.database;

import org.openkilda.config.provider.PropertiesBasedConfigurationProvider;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.spi.PersistenceProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

@Configuration
public class PersistenceConfig {
    @Bean
    public PersistenceManager persistenceManager(
            @Value("${neo.uri}") String neoUri,
            @Value("${neo.user}") String neoUser,
            @Value("${neo.password}") String neoPassword,
            @Value("${orientdb.url}") String orientdbUrl,
            @Value("${orientdb.user}") String orientdbUser,
            @Value("${orientdb.password}") String orientdbPassword) {
        Properties configProps = new Properties();
        configProps.setProperty("neo4j.uri", neoUri);
        configProps.setProperty("neo4j.user", neoUser);
        configProps.setProperty("neo4j.password", neoPassword);
        configProps.setProperty("orientdb.url", orientdbUrl);
        configProps.setProperty("orientdb.user", orientdbUser);
        configProps.setProperty("orientdb.password", orientdbPassword);

        PropertiesBasedConfigurationProvider configurationProvider =
                new PropertiesBasedConfigurationProvider(configProps);

        return PersistenceProvider.getInstance().getPersistenceManager(configurationProvider);
    }

    @Bean
    public TransactionManager transactionManager(PersistenceManager persistenceManager) {
        return persistenceManager.getTransactionManager();
    }

    @Bean
    public RepositoryFactory repositoryFactory(PersistenceManager persistenceManager) {
        return persistenceManager.getRepositoryFactory();
    }
}
