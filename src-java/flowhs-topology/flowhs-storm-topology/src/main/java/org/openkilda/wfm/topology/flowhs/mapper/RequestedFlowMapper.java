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

package org.openkilda.wfm.topology.flowhs.mapper;

import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.model.Flow;
import org.openkilda.wfm.topology.flowhs.model.RequestedFlow;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public abstract class RequestedFlowMapper {

    public static final RequestedFlowMapper INSTANCE = Mappers.getMapper(RequestedFlowMapper.class);

    /**
     * Convert {@link Flow} to {@link RequestedFlow}.
     */
    @Mapping(source = "flowId", target = "flowId")
    @Mapping(source = "sourceSwitch", target = "srcSwitch")
    @Mapping(source = "sourcePort", target = "srcPort")
    @Mapping(source = "sourceVlan", target = "srcVlan")
    @Mapping(source = "destinationSwitch", target = "destSwitch")
    @Mapping(source = "destinationPort", target = "destPort")
    @Mapping(source = "destinationVlan", target = "destVlan")
    @Mapping(source = "encapsulationType", target = "flowEncapsulationType")
    @Mapping(target = "pathComputationStrategy",
            expression = "java(java.util.Optional.ofNullable(request.getPathComputationStrategy())"
                    + ".map(pcs -> org.openkilda.model.PathComputationStrategy.valueOf(pcs.toUpperCase()))"
                    + ".orElse(null))")
    @Mapping(target = "srcWithMultiTable", ignore = true)
    @Mapping(target = "destWithMultiTable", ignore = true)
    public abstract RequestedFlow toRequestedFlow(FlowRequest request);

    /**
     * Convert {@link Flow} to {@link RequestedFlow}.
     */
    @Mapping(source = "flowId", target = "flowId")
    @Mapping(target = "srcSwitch", expression = "java(flow.getSrcSwitchId())")
    @Mapping(source = "srcPort", target = "srcPort")
    @Mapping(source = "srcVlan", target = "srcVlan")
    @Mapping(target = "destSwitch", expression = "java(flow.getDestSwitchId())")
    @Mapping(source = "destPort", target = "destPort")
    @Mapping(source = "destVlan", target = "destVlan")
    @Mapping(source = "encapsulationType", target = "flowEncapsulationType")
    @Mapping(target = "diverseFlowId", ignore = true)
    public abstract RequestedFlow toRequestedFlow(Flow flow);

    /**
     * Convert {@link RequestedFlow} to {@link Flow}.
     */
    @Mapping(source = "flowId", target = "flowId")
    @Mapping(target = "srcSwitch",
            expression = "java(org.openkilda.model.Switch.builder().switchId(requestedFlow.getSrcSwitch()).build())")
    @Mapping(source = "srcPort", target = "srcPort")
    @Mapping(source = "srcVlan", target = "srcVlan")
    @Mapping(target = "destSwitch",
            expression = "java(org.openkilda.model.Switch.builder().switchId(requestedFlow.getDestSwitch()).build())")
    @Mapping(source = "destPort", target = "destPort")
    @Mapping(source = "destVlan", target = "destVlan")
    @Mapping(source = "flowEncapsulationType", target = "encapsulationType")
    @Mapping(target = "groupId", ignore = true)
    @Mapping(target = "status", ignore = true)
    public abstract Flow toFlow(RequestedFlow requestedFlow);
}
