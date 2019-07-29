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

package org.openkilda.wfm.share.flow.resources.transitvlan;

import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.Flow;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.TransitVlan;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.wfm.share.flow.resources.ResourceNotAvailableException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class TransitVlanPoolTest extends InMemoryGraphBasedTest {
    private static final Switch SWITCH_A = Switch.builder().switchId(new SwitchId("ff:00")).build();
    private static final Switch SWITCH_B = Switch.builder().switchId(new SwitchId("ff:01")).build();
    private static final Flow FLOW_1 = Flow.builder().flowId("flow_1").srcSwitch(SWITCH_A).destSwitch(SWITCH_B).build();
    private static final Flow FLOW_2 = Flow.builder().flowId("flow_2").srcSwitch(SWITCH_A).destSwitch(SWITCH_B).build();
    private static final Flow FLOW_3 = Flow.builder().flowId("flow_3").srcSwitch(SWITCH_A).destSwitch(SWITCH_B).build();
    private static final PathId PATH_ID_1 = new PathId("path_1");
    private static final PathId PATH_ID_2 = new PathId("path_2");
    private static final PathId PATH_ID_3 = new PathId("path_3");
    private static final int MIN_TRANSIT_VLAN = 100;
    private static final int MAX_TRANSIT_VLAN = 110;

    private TransitVlanPool transitVlanPool;
    private TransitVlanRepository transitVlanRepository;

    @Before
    public void setUp() {
        transitVlanPool = new TransitVlanPool(persistenceManager, MIN_TRANSIT_VLAN, MAX_TRANSIT_VLAN);
        transitVlanRepository = persistenceManager.getRepositoryFactory().createTransitVlanRepository();
    }

    @Test
    public void vlanPoolTest() {
        Set<Integer> transitVlans = new HashSet<>();
        for (int i = MIN_TRANSIT_VLAN; i <= MAX_TRANSIT_VLAN; i++) {
            Flow flow = Flow.builder().flowId(format("flow_%d", i)).srcSwitch(SWITCH_A).destSwitch(SWITCH_B).build();
            transitVlans.add(transitVlanPool.allocate(
                    flow,
                    new PathId(format("path_%d", i)),
                    new PathId(format("opposite_dummy_%d", i))).getTransitVlan().getVlan());
        }
        assertEquals(MAX_TRANSIT_VLAN - MIN_TRANSIT_VLAN + 1, transitVlans.size());
        transitVlans.forEach(vlan -> assertTrue(vlan >= MIN_TRANSIT_VLAN && vlan <= MAX_TRANSIT_VLAN));
    }

    @Test(expected = ResourceNotAvailableException.class)
    public void vlanPoolFullTest() {
        for (int i = MIN_TRANSIT_VLAN; i <= MAX_TRANSIT_VLAN + 1; i++) {
            Flow flow = Flow.builder().flowId(format("flow_%d", i)).srcSwitch(SWITCH_A).destSwitch(SWITCH_B).build();
            assertTrue(transitVlanPool.allocate(
                    flow,
                    new PathId(format("path_%d", i)),
                    new PathId(format("opposite_dummy_%d", i))).getTransitVlan().getVlan() > 0);
        }
    }

    @Test
    public void gotSameVlanForOppositePath() {
        Flow flow = Flow.builder().flowId("flow_1").srcSwitch(SWITCH_A).destSwitch(SWITCH_B).build();

        final PathId forwardPathId = new PathId("forward");
        final PathId reversePathId = new PathId("reverse");
        TransitVlan forward = transitVlanPool.allocate(flow, forwardPathId, reversePathId)
                .getTransitVlan();

        TransitVlan reverse = transitVlanPool.allocate(flow, reversePathId, forwardPathId)
                .getTransitVlan();

        Assert.assertEquals(forward, reverse);
    }

    @Test
    public void deallocateTransitVlanTest() {
        transitVlanPool.allocate(FLOW_1, PATH_ID_1, PATH_ID_2);
        transitVlanPool.allocate(FLOW_2, PATH_ID_2, PATH_ID_1);
        int vlan = transitVlanPool.allocate(FLOW_3, PATH_ID_3, PATH_ID_3).getTransitVlan().getVlan();
        assertEquals(2, transitVlanRepository.findAll().size());

        transitVlanPool.deallocate(PATH_ID_1);
        Collection<TransitVlan> transitVlans = transitVlanRepository.findAll();
        assertEquals(1, transitVlans.size());
        assertEquals(vlan, transitVlans.iterator().next().getVlan());
    }
}
