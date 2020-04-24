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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.MacAddress;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FermaSwitchPropertiesRepositoryTest extends InMemoryGraphBasedTest {
    static final SwitchId TEST_SWITCH_ID = new SwitchId(1);
    private static final Integer SERVER_42_PORT = 10;
    private static final MacAddress SERVER_42_MAC_ADDRESS = new MacAddress("42:42:42:42:42:42");

    SwitchRepository switchRepository;
    SwitchPropertiesRepository switchPropertiesRepository;

    @Before
    public void setUp() {
        switchRepository = repositoryFactory.createSwitchRepository();
        switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
    }

    @Test
    public void shouldCreateSwitchPropertiesWithRelation() {
        Switch origSwitch = Switch.builder()
                .switchId(TEST_SWITCH_ID)
                .description("Some description")
                .build();
        switchRepository.add(origSwitch);

        SwitchProperties switchProperties = SwitchProperties.builder()
                .switchObj(origSwitch)
                .supportedTransitEncapsulation(SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES)
                .build();
        switchPropertiesRepository.add(switchProperties);

        List<SwitchProperties> switchPropertiesResult = new ArrayList<>(switchPropertiesRepository.findAll());
        assertEquals(1, switchPropertiesResult.size());
        assertNotNull(switchPropertiesResult.get(0).getSwitchObj());
    }

    @Test
    public void shouldFindSwitchPropertiesBySwitchId() {
        Switch origSwitch = Switch.builder()
                .switchId(TEST_SWITCH_ID)
                .description("Some description")
                .build();
        switchRepository.add(origSwitch);

        SwitchProperties switchProperties = SwitchProperties.builder()
                .switchObj(origSwitch)
                .supportedTransitEncapsulation(SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES)
                .build();
        switchPropertiesRepository.add(switchProperties);

        Optional<SwitchProperties> switchPropertiesOptional = switchPropertiesRepository.findBySwitchId(TEST_SWITCH_ID);
        assertTrue(switchPropertiesOptional.isPresent());
    }

    @Test
    public void shouldCreatePropertiesWithServer42Props() {
        Switch origSwitch = Switch.builder().switchId(TEST_SWITCH_ID)
                .description("Some description").build();
        switchRepository.add(origSwitch);

        SwitchProperties switchProperties = SwitchProperties.builder()
                .switchObj(origSwitch)
                .server42FlowRtt(true)
                .server42Port(SERVER_42_PORT)
                .server42MacAddress(SERVER_42_MAC_ADDRESS)
                .supportedTransitEncapsulation(SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES)
                .build();
        switchPropertiesRepository.add(switchProperties);

        Optional<SwitchProperties> switchPropertiesOptional = switchPropertiesRepository.findBySwitchId(TEST_SWITCH_ID);
        assertTrue(switchPropertiesOptional.isPresent());
        assertTrue(switchPropertiesOptional.get().isServer42FlowRtt());
        assertEquals(SERVER_42_PORT, switchPropertiesOptional.get().getServer42Port());
        assertEquals(SERVER_42_MAC_ADDRESS, switchPropertiesOptional.get().getServer42MacAddress());
    }

    @Test
    public void shouldCreatePropertiesWithNullServer42Props() {
        Switch origSwitch = Switch.builder().switchId(TEST_SWITCH_ID)
                .description("Some description").build();
        switchRepository.add(origSwitch);

        SwitchProperties switchProperties = SwitchProperties.builder()
                .switchObj(origSwitch)
                .server42Port(null)
                .server42MacAddress(null)
                .supportedTransitEncapsulation(SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES).build();
        switchPropertiesRepository.add(switchProperties);

        Optional<SwitchProperties> switchPropertiesOptional = switchPropertiesRepository.findBySwitchId(TEST_SWITCH_ID);
        assertTrue(switchPropertiesOptional.isPresent());
        assertFalse(switchPropertiesOptional.get().isServer42FlowRtt());
        assertNull(switchPropertiesOptional.get().getServer42Port());
        assertNull(switchPropertiesOptional.get().getServer42MacAddress());
    }
}
