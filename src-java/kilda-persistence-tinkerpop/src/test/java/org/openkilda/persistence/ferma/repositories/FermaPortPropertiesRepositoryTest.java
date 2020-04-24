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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.PortProperties;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.PortPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;

import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.Optional;

public class FermaPortPropertiesRepositoryTest extends InMemoryGraphBasedTest {
    static final SwitchId TEST_SWITCH_ID = new SwitchId(1);

    SwitchRepository switchRepository;
    PortPropertiesRepository portPropertiesRepository;

    @Before
    public void setUp() {
        switchRepository = repositoryFactory.createSwitchRepository();
        portPropertiesRepository = repositoryFactory.createPortPropertiesRepository();
    }

    @Test
    public void shouldCreatePortPropertiesWithRelation() {
        Switch origSwitch = createTestSwitch(TEST_SWITCH_ID.getId());

        PortProperties portProperties = PortProperties.builder()
                .switchObj(origSwitch)
                .discoveryEnabled(false)
                .build();
        portPropertiesRepository.add(portProperties);

        Collection<PortProperties> portPropertiesResult = portPropertiesRepository.findAll();
        assertEquals(1, portPropertiesResult.size());
        assertNotNull(portPropertiesResult.iterator().next().getSwitchObj());
    }

    @Test
    public void shouldGetPortPropertiesBySwitchIdAndPort() {
        Switch origSwitch = createTestSwitch(TEST_SWITCH_ID.getId());

        int port = 7;
        PortProperties portProperties = PortProperties.builder()
                .switchObj(origSwitch)
                .port(port)
                .discoveryEnabled(false)
                .build();

        portPropertiesRepository.add(portProperties);
        Optional<PortProperties> portPropertiesResult =
                portPropertiesRepository.getBySwitchIdAndPort(origSwitch.getSwitchId(), port);
        assertTrue(portPropertiesResult.isPresent());
        assertEquals(origSwitch.getSwitchId(), portPropertiesResult.get().getSwitchObj().getSwitchId());
        assertEquals(port, portPropertiesResult.get().getPort());
        assertFalse(portPropertiesResult.get().isDiscoveryEnabled());
    }
}
