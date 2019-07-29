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

package org.openkilda.testing.service.database;

import org.openkilda.messaging.info.event.PathInfoData;
import org.openkilda.model.Flow;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.model.TransitVlan;
import org.openkilda.testing.model.topology.TopologyDefinition.Isl;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface Database {

    // ISLs

    boolean updateIslMaxBandwidth(Isl isl, long value);

    boolean updateIslAvailableBandwidth(Isl isl, long value);

    boolean resetIslBandwidth(Isl isl);

    int getIslCost(Isl isl);

    boolean updateIslCost(Isl isl, int value);

    boolean updateIslLatency(Isl isl, long latency);

    boolean resetCosts();

    boolean updateIslTimeUnstable(Isl isl, Instant newTimeUnstable);

    Instant getIslTimeUnstable(Isl isl);

    // Switches

    Switch getSwitch(SwitchId switchId);

    void setSwitchStatus(SwitchId switchId, SwitchStatus swStatus);

    List<PathInfoData> getPaths(SwitchId src, SwitchId dst);

    void removeConnectedDevices(SwitchId sw);

    boolean removeInactiveSwitches();

    void setSwitchPop(SwitchId switchId, String swPop);

    // Flows

    int countFlows();

    Flow getFlow(String flowId);

    Collection<TransitVlan> getTransitVlans(PathId forwardPathId, PathId reversePathId);

    Optional<TransitVlan> getTransitVlan(PathId pathId);

    void updateFlowBandwidth(String flowId, long newBw);

    void updateFlowMeterId(String flowId, MeterId newMeterId);

    //misc

    List<Object> dumpAllNodes();

    List<Object> dumpAllRelations();

    List<Object> dumpAllSwitches();

    List<Object> dumpAllIsls();
}
