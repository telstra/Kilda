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
import static org.junit.Assert.assertTrue;

import org.openkilda.model.FeatureToggles;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;

import org.junit.Before;
import org.junit.Test;

public class FermaFeatureTogglesRepositoryTest extends InMemoryGraphBasedTest {
    FeatureTogglesRepository featureTogglesRepository;

    @Before
    public void setUp() {
        featureTogglesRepository = repositoryFactory.createFeatureTogglesRepository();
    }

    @Test
    public void shouldCreateAndUpdateFeatureToggles() {
        FeatureToggles featureTogglesA = FeatureToggles.builder()
                .flowsRerouteOnIslDiscoveryEnabled(false)
                .createFlowEnabled(false)
                .updateFlowEnabled(false)
                .deleteFlowEnabled(false)
                .pushFlowEnabled(false)
                .unpushFlowEnabled(false)
                .useBfdForIslIntegrityCheck(false)
                .floodlightRoutePeriodicSync(false)
                .flowsRerouteViaFlowHs(false)
                .flowsRerouteUsingDefaultEncapType(false)
                .build();
        featureTogglesRepository.add(featureTogglesA);

        FeatureToggles foundFeatureToggles = featureTogglesRepository.find().get();
        assertEquals(featureTogglesA, foundFeatureToggles);

        foundFeatureToggles.setUpdateFlowEnabled(true);
        foundFeatureToggles.setPushFlowEnabled(true);

        FeatureToggles updatedFeatureToggles = featureTogglesRepository.find().get();
        assertTrue(updatedFeatureToggles.getUpdateFlowEnabled());
        assertTrue(updatedFeatureToggles.getPushFlowEnabled());
    }
}
