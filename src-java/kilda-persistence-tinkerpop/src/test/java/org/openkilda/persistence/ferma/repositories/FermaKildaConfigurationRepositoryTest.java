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

package org.openkilda.persistence.ferma.repositories;

import static org.junit.Assert.assertEquals;

import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.KildaConfiguration;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.KildaConfigurationRepository;

import org.junit.Before;
import org.junit.Test;

public class FermaKildaConfigurationRepositoryTest extends InMemoryGraphBasedTest {
    static KildaConfigurationRepository kildaConfigurationRepository;

    @Before
    public void setUp() {
        kildaConfigurationRepository = repositoryFactory.createKildaConfigurationRepository();
    }

    @Test
    public void shouldCreateAndUpdateKildaConfiguration() {
        KildaConfiguration kildaConfiguration = kildaConfigurationRepository.getOrDefault();
        assertEquals(KildaConfiguration.DEFAULTS, kildaConfiguration);

        KildaConfiguration emptyKildaConfiguration = KildaConfiguration.builder().build();
        kildaConfigurationRepository.add(emptyKildaConfiguration);

        KildaConfiguration foundKildaConfiguration = kildaConfigurationRepository.getOrDefault();
        assertEquals(KildaConfiguration.DEFAULTS.getFlowEncapsulationType(),
                foundKildaConfiguration.getFlowEncapsulationType());
        assertEquals(KildaConfiguration.DEFAULTS.getPathComputationStrategy(),
                foundKildaConfiguration.getPathComputationStrategy());

        kildaConfiguration = kildaConfigurationRepository.find().orElse(null);
        kildaConfiguration.setFlowEncapsulationType(FlowEncapsulationType.VXLAN);
        kildaConfiguration.setUseMultiTable(false);
        kildaConfiguration.setPathComputationStrategy(PathComputationStrategy.LATENCY);

        KildaConfiguration updatedKildaConfiguration = kildaConfigurationRepository.find().orElse(null);
        assertEquals(kildaConfiguration, updatedKildaConfiguration);

        updatedKildaConfiguration.setFlowEncapsulationType(FlowEncapsulationType.TRANSIT_VLAN);
        updatedKildaConfiguration.setUseMultiTable(false);
        updatedKildaConfiguration.setPathComputationStrategy(PathComputationStrategy.COST);
    }
}
