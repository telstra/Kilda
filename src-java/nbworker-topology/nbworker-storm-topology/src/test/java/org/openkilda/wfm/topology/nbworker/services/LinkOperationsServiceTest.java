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

package org.openkilda.wfm.topology.nbworker.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.Isl;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.IslNotFoundException;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.List;

public class LinkOperationsServiceTest extends InMemoryGraphBasedTest {
    private static IslRepository islRepository;
    private static SwitchRepository switchRepository;
    private static LinkOperationsService linkOperationsService;

    private static final SwitchId TEST_SWITCH_A_ID = new SwitchId(1);
    private static final int TEST_SWITCH_A_PORT = 1;
    private static final SwitchId TEST_SWITCH_B_ID = new SwitchId(2);
    private static final int TEST_SWITCH_B_PORT = 1;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @BeforeClass
    public static void setUpOnce() {
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        islRepository = repositoryFactory.createIslRepository();
        switchRepository = repositoryFactory.createSwitchRepository();

        ILinkOperationsServiceCarrier carrier = new ILinkOperationsServiceCarrier() {
            @Override
            public void islBfdFlagChanged(Isl isl) {
            }
        };

        linkOperationsService = new LinkOperationsService(carrier, persistenceManager.getRepositoryFactory(),
                persistenceManager.getTransactionManager());
    }

    @Test
    public void shouldUpdateLinkUnderMaintenanceFlag() throws IslNotFoundException {
        createIsl();

        for (int i = 0; i < 2; i++) {
            List<Isl> link = linkOperationsService
                    .updateLinkUnderMaintenanceFlag(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT,
                            TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT, true);
            assertEquals(2, link.size());
            assertTrue(link.get(0).isUnderMaintenance());
            assertTrue(link.get(1).isUnderMaintenance());
        }

        for (int i = 0; i < 2; i++) {
            List<Isl> link = linkOperationsService
                    .updateLinkUnderMaintenanceFlag(TEST_SWITCH_A_ID, TEST_SWITCH_A_PORT,
                            TEST_SWITCH_B_ID, TEST_SWITCH_B_PORT, false);
            assertEquals(2, link.size());
            assertFalse(link.get(0).isUnderMaintenance());
            assertFalse(link.get(1).isUnderMaintenance());
        }

    }

    private void createIsl() {
        Isl isl = Isl.builder()
                .srcSwitch(createSwitchIfNotExist(TEST_SWITCH_A_ID))
                .srcPort(TEST_SWITCH_A_PORT)
                .destSwitch(createSwitchIfNotExist(TEST_SWITCH_B_ID))
                .destPort(TEST_SWITCH_B_PORT)
                .cost(0)
                .build();

        islRepository.add(isl);

        isl = Isl.builder()
                .srcSwitch(createSwitchIfNotExist(TEST_SWITCH_B_ID))
                .srcPort(TEST_SWITCH_B_PORT)
                .destSwitch(createSwitchIfNotExist(TEST_SWITCH_A_ID))
                .destPort(TEST_SWITCH_A_PORT)
                .cost(0)
                .build();

        islRepository.add(isl);
    }

    private Switch createSwitchIfNotExist(SwitchId switchId) {
        return switchRepository.findById(switchId).orElseGet(() -> {
            Switch sw = Switch.builder().switchId(switchId).status(SwitchStatus.ACTIVE).build();
            switchRepository.add(sw);
            return sw;
        });
    }
}
