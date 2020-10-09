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

package org.openkilda.wfm.share.mappers;

import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEndpoint;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper(imports = {FlowEndpoint.class})
public abstract class RequestedFlowMapper {

    public static final RequestedFlowMapper INSTANCE = Mappers.getMapper(RequestedFlowMapper.class);

    /**
     * Convert {@link Flow} to {@link FlowRequest}.
     */
    @Mapping(target = "flowId", source = "flowId")
    @Mapping(target = "source", expression = "java(new FlowEndpoint(flow.getSrcSwitchId(), "
            + "flow.getSrcPort(), flow.getSrcVlan()))")
    @Mapping(target = "destination", expression = "java(new FlowEndpoint(flow.getDestSwitchId(), "
            + "flow.getDestPort(), flow.getDestVlan()))")
    @Mapping(target = "encapsulationType", source = "encapsulationType")
    @Mapping(target = "pathComputationStrategy",
            expression = "java(java.util.Optional.ofNullable(flow.getPathComputationStrategy())"
                    + ".map(pcs -> pcs.toString().toLowerCase())"
                    + ".orElse(null))")
    @Mapping(target = "bandwidth", source = "bandwidth")
    @Mapping(target = "ignoreBandwidth", source = "ignoreBandwidth")
    @Mapping(target = "periodicPings", source = "periodicPings")
    @Mapping(target = "allocateProtectedPath", source = "allocateProtectedPath")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "maxLatency", source = "maxLatency")
    @Mapping(target = "priority", source = "priority")
    @Mapping(target = "pinned", source = "pinned")
    @Mapping(target = "detectConnectedDevices", source = "detectConnectedDevices")
    @Mapping(target = "transitEncapsulationId", ignore = true)
    @Mapping(target = "diverseFlowId", ignore = true)
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "bulkUpdateFlowIds", ignore = true)
    @Mapping(target = "doNotRevert", ignore = true)
    @Mapping(target = "time", ignore = true)
    @Mapping(target = "creationTime", ignore = true)
    public abstract FlowRequest toFlowRequest(Flow flow);
}
