/* Copyright 2019 Telstra Open Source
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

package org.openkilda.wfm.topology.nbworker.services;

import org.openkilda.model.KildaConfiguration;
import org.openkilda.model.KildaConfiguration.KildaConfigurationCloner;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.KildaConfigurationRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class KildaConfigurationService {

    private KildaConfigurationRepository kildaConfigurationRepository;
    private TransactionManager transactionManager;

    public KildaConfigurationService(RepositoryFactory repositoryFactory, TransactionManager transactionManager) {
        this.kildaConfigurationRepository = repositoryFactory.createKildaConfigurationRepository();
        this.transactionManager = transactionManager;
    }

    /**
     * Get kilda configuration.
     * @return kilda configuration.
     */
    public KildaConfiguration getKildaConfiguration() {
        return kildaConfigurationRepository.getOrDefault();
    }

    /**
     * Update kilda configuration.
     * @param kildaConfiguration kilda configuration.
     * @return updated kilda configuration.
     */
    public KildaConfiguration updateKildaConfiguration(KildaConfiguration kildaConfiguration) {
        log.info("Process kilda config update - config: {}", kildaConfiguration);
        return transactionManager.doInTransaction(() -> {
            Optional<KildaConfiguration> currentKildaConfiguration = kildaConfigurationRepository.find();
            if (currentKildaConfiguration.isPresent()) {
                KildaConfigurationCloner.INSTANCE.copyNonNull(kildaConfiguration,
                        currentKildaConfiguration.get());
                return currentKildaConfiguration.get();
            } else {
                kildaConfigurationRepository.add(kildaConfiguration);
                return kildaConfiguration;
            }
        });
    }
}
