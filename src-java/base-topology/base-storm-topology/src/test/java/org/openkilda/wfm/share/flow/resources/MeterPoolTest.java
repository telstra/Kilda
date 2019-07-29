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

package org.openkilda.wfm.share.flow.resources;

import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.FlowMeter;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ConstraintViolationException;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.FlowMeterRepository;
import org.openkilda.persistence.repositories.SwitchRepository;

import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class MeterPoolTest extends InMemoryGraphBasedTest {
    private static final SwitchId SWITCH_ID = new SwitchId("ff:00");
    private static final String FLOW_1 = "flow_1";
    private static final String FLOW_2 = "flow_2";
    private static final String FLOW_3 = "flow_3";
    private static final PathId PATH_ID_1 = new PathId("path_1");
    private static final PathId PATH_ID_2 = new PathId("path_2");
    private static final PathId PATH_ID_3 = new PathId("path_3");
    private static final MeterId MIN_METER_ID = new MeterId(31L);
    private static final MeterId MAX_METER_ID = new MeterId(40L);

    private MeterPool meterPool;
    private FlowMeterRepository flowMeterRepository;

    @Before
    public void setUp() {
        meterPool = new MeterPool(persistenceManager, MIN_METER_ID, MAX_METER_ID);

        SwitchRepository switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();
        switchRepository.add(Switch.builder().switchId(SWITCH_ID).build());
        flowMeterRepository = persistenceManager.getRepositoryFactory().createFlowMeterRepository();
    }

    @Test
    public void meterPoolTest() {
        long minMeterId = MIN_METER_ID.getValue();
        long maxMeterId = MAX_METER_ID.getValue();
        Set<MeterId> meterIds = new HashSet<>();
        for (long i = minMeterId; i <= maxMeterId; i++) {
            meterIds.add(meterPool.allocate(SWITCH_ID, format("flow_%d", i), new PathId(format("path_%d", i))));
        }
        assertEquals(maxMeterId - minMeterId + 1, meterIds.size());
        meterIds.forEach(meterId -> assertTrue(meterId.getValue() >= minMeterId && meterId.getValue() <= maxMeterId));
    }

    @Test(expected = ResourceNotAvailableException.class)
    public void meterPoolFullTest() {
        for (long i = MIN_METER_ID.getValue(); i <= MAX_METER_ID.getValue() + 1; i++) {
            meterPool.allocate(SWITCH_ID, format("flow_%d", i), new PathId(format("path_%d", i)));
        }
    }

    @Test(expected = ConstraintViolationException.class)
    public void createTwoMeterForOnePathTest() {
        long first = meterPool.allocate(SWITCH_ID, FLOW_1, PATH_ID_1).getValue();
        long second = meterPool.allocate(SWITCH_ID, FLOW_1, PATH_ID_1).getValue();
    }

    @Test
    public void deallocateMetersByPathTest() {
        meterPool.allocate(SWITCH_ID, FLOW_1, PATH_ID_1);
        meterPool.allocate(SWITCH_ID, FLOW_2, PATH_ID_2);
        long meterId = meterPool.allocate(SWITCH_ID, FLOW_3, PATH_ID_3).getValue();
        assertEquals(3, flowMeterRepository.findAll().size());

        meterPool.deallocate(PATH_ID_1, PATH_ID_2);
        Collection<FlowMeter> flowMeters = flowMeterRepository.findAll();
        assertEquals(1, flowMeters.size());
        assertEquals(meterId, flowMeters.iterator().next().getMeterId().getValue());
    }
}
