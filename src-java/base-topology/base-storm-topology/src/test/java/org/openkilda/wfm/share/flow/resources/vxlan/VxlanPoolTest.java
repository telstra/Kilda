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

package org.openkilda.wfm.share.flow.resources.vxlan;

import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.Flow;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.Vxlan;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.VxlanRepository;
import org.openkilda.wfm.share.flow.resources.ResourceNotAvailableException;

import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class VxlanPoolTest extends InMemoryGraphBasedTest {
    private static final Switch SWITCH_A = Switch.builder().switchId(new SwitchId("ff:00")).build();
    private static final Switch SWITCH_B = Switch.builder().switchId(new SwitchId("ff:01")).build();
    private static final Flow FLOW_1 = Flow.builder().flowId("flow_1").srcSwitch(SWITCH_A).destSwitch(SWITCH_B).build();
    private static final Flow FLOW_2 = Flow.builder().flowId("flow_2").srcSwitch(SWITCH_A).destSwitch(SWITCH_B).build();
    private static final Flow FLOW_3 = Flow.builder().flowId("flow_3").srcSwitch(SWITCH_A).destSwitch(SWITCH_B).build();
    private static final PathId PATH_ID_1 = new PathId("path_1");
    private static final PathId PATH_ID_2 = new PathId("path_2");
    private static final PathId PATH_ID_3 = new PathId("path_3");
    private static final int MIN_VXLAN = 100;
    private static final int MAX_VXLAN = 110;

    private VxlanPool vxlanPool;
    private VxlanRepository vxlanRepository;

    @Before
    public void setUp() {
        vxlanPool = new VxlanPool(persistenceManager, MIN_VXLAN, MAX_VXLAN);
        vxlanRepository = persistenceManager.getRepositoryFactory().createVxlanRepository();
    }

    @Test
    public void vxlanIdPool() {
        Set<Integer> vxlans = new HashSet<>();
        for (int i = MIN_VXLAN; i <= MAX_VXLAN; i++) {
            Flow flow = Flow.builder().flowId(format("flow_%d", i)).srcSwitch(SWITCH_A).destSwitch(SWITCH_B).build();
            vxlans.add(vxlanPool.allocate(
                    flow,
                    new PathId(format("path_%d", i)),
                    new PathId(format("opposite_dummy_%d", i))).getVxlan().getVni());
        }
        assertEquals(MAX_VXLAN - MIN_VXLAN + 1, vxlans.size());
        vxlans.forEach(vni -> assertTrue(vni >= MIN_VXLAN && vni <= MAX_VXLAN));
    }


    @Test(expected = ResourceNotAvailableException.class)
    public void vxlanPoolFullTest() {
        for (int i = MIN_VXLAN; i <= MAX_VXLAN + 1; i++) {
            Flow flow = Flow.builder().flowId(format("flow_%d", i)).srcSwitch(SWITCH_A).destSwitch(SWITCH_B).build();
            assertTrue(vxlanPool.allocate(flow, new PathId(format("path_%d", i)),
                    new PathId(format("op_path_%d", i))).getVxlan().getVni() > 0);
        }
    }

    @Test
    public void deallocateVxlanTest() {
        vxlanPool.allocate(FLOW_1, PATH_ID_1, PATH_ID_2);
        vxlanPool.allocate(FLOW_2, PATH_ID_2, PATH_ID_1);
        int vni = vxlanPool.allocate(FLOW_3, PATH_ID_3, PATH_ID_3).getVxlan().getVni();
        assertEquals(2, vxlanRepository.findAll().size());

        vxlanPool.deallocate(PATH_ID_1);
        Collection<Vxlan> transitVlans = vxlanRepository.findAll();
        assertEquals(1, transitVlans.size());
        assertEquals(vni, transitVlans.iterator().next().getVni());
    }
}
