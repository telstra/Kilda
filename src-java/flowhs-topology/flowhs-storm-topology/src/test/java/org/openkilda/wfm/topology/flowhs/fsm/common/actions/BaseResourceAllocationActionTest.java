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

package org.openkilda.wfm.topology.flowhs.fsm.common.actions;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.pce.PathComputer;
import org.openkilda.persistence.inmemory.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.ResourceAllocationException;
import org.openkilda.wfm.share.logger.FlowOperationsDashboardLogger;

import com.google.common.base.Suppliers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class BaseResourceAllocationActionTest extends InMemoryGraphBasedTest {
    private IslRepository islRepositorySpy;

    @Mock
    PathComputer pathComputer;

    @Mock
    FlowResourcesManager resourcesManager;

    @Mock
    FlowOperationsDashboardLogger dashboardLogger;

    @Test(expected = ResourceAllocationException.class)
    public void updateAvailableBandwidthFailsOnOverProvisionTest() throws ResourceAllocationException {
        islRepositorySpy = spy(persistenceManager.getRepositoryFactory().createIslRepository());
        when(repositoryFactory.createIslRepository()).thenReturn(islRepositorySpy);

        doReturn(-1L).when(islRepositorySpy).updateAvailableBandwidth(any(), anyInt(), any(), anyInt());

        BaseResourceAllocationAction action = mock(BaseResourceAllocationAction.class,
                Mockito.withSettings()
                        .useConstructor(persistenceManager, 3, 3, 3, pathComputer, resourcesManager, dashboardLogger)
                        .defaultAnswer(Mockito.CALLS_REAL_METHODS));

        PathSegment segment = PathSegment.builder()
                .pathId(new PathId(""))
                .srcSwitch(Switch.builder().switchId(new SwitchId(1)).build())
                .srcPort(1)
                .destSwitch(Switch.builder().switchId(new SwitchId(2)).build())
                .destPort(2)
                .build();

        action.createPathSegments(singletonList(segment), Suppliers.ofInstance(emptyMap()));
    }

    @Test()
    public void updateAvailableBandwidthNoOverProvisionTest() throws ResourceAllocationException {
        islRepositorySpy = spy(persistenceManager.getRepositoryFactory().createIslRepository());
        when(repositoryFactory.createIslRepository()).thenReturn(islRepositorySpy);

        doReturn(1L).when(islRepositorySpy).updateAvailableBandwidth(any(), anyInt(), any(), anyInt());

        BaseResourceAllocationAction action = mock(BaseResourceAllocationAction.class,
                Mockito.withSettings()
                        .useConstructor(persistenceManager, 3, 3, 3, pathComputer, resourcesManager, dashboardLogger)
                        .defaultAnswer(Mockito.CALLS_REAL_METHODS));

        PathSegment segment = PathSegment.builder()
                .pathId(new PathId(""))
                .srcSwitch(Switch.builder().switchId(new SwitchId(1)).build())
                .srcPort(1)
                .destSwitch(Switch.builder().switchId(new SwitchId(2)).build())
                .destPort(2)
                .build();

        action.createPathSegments(singletonList(segment), Suppliers.ofInstance(emptyMap()));
    }
}
