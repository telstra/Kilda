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

package org.openkilda.wfm.share.mappers;

import org.openkilda.messaging.model.FlowDto;
import org.openkilda.messaging.model.FlowPairDto;
import org.openkilda.messaging.model.SwapFlowDto;
import org.openkilda.messaging.payload.flow.FlowState;
import org.openkilda.messaging.payload.flow.FlowStatusDetails;
import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.KildaConfiguration;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Convert {@link Flow} to {@link FlowDto} and back.
 */
@Mapper(uses = {FlowPathMapper.class, DetectConnectedDevicesMapper.class}, imports = {FlowStatusDetails.class})
public abstract class FlowMapper {

    public static final FlowMapper INSTANCE = Mappers.getMapper(FlowMapper.class);

    @Mapping(source = "srcPort", target = "sourcePort")
    @Mapping(source = "srcVlan", target = "sourceVlan")
    @Mapping(source = "destPort", target = "destinationPort")
    @Mapping(source = "destVlan", target = "destinationVlan")
    @Mapping(target = "sourceSwitch", expression = "java(flow.getSrcSwitchId())")
    @Mapping(target = "destinationSwitch", expression = "java(flow.getDestSwitchId())")
    @Mapping(source = "status", target = "state")
    @Mapping(source = "timeModify", target = "lastUpdated")
    @Mapping(source = "timeCreate", target = "createdTime")
    @Mapping(target = "flowStatusDetails",
            expression = "java(flow.isAllocateProtectedPath() ? "
                    + "new FlowStatusDetails(flow.getMainFlowPrioritizedPathsStatus(), "
                    + "flow.getProtectedFlowPrioritizedPathsStatus()) : null)")
    @Mapping(target = "cookie", ignore = true)
    @Mapping(target = "meterId", ignore = true)
    @Mapping(target = "transitEncapsulationId", ignore = true)
    @Mapping(target = "diverseWith", ignore = true)
    public abstract FlowDto map(Flow flow);

    /**
     * Convert {@link Flow} to {@link FlowDto} with diverse flow ids.
     */
    public FlowDto map(Flow flow, Set<String> diverseWith) {
        FlowDto flowDto = map(flow);
        flowDto.setDiverseWith(diverseWith);
        return flowDto;
    }

    /**
     * Convert {@link FlowPairDto} to {@link Flow}.
     * If encapsulation type and/or path computation strategy is not provided then values from KildaConfiguration
     * will be used.
     */
    public Flow map(FlowPairDto<FlowDto, FlowDto> flowPair, Supplier<KildaConfiguration> kildaConfiguration) {
        if (flowPair == null) {
            return null;
        }

        Flow flow = map(flowPair.getLeft(), kildaConfiguration);

        FlowPath forwardPath = buildPath(flowPair.getLeft());
        FlowPath reversePath = buildPath(flowPair.getRight());

        flow.setForwardPath(forwardPath);
        flow.setReversePath(reversePath);
        return flow;
    }

    /**
     * Convert {@link FlowDto} to {@link Flow}.
     * If encapsulation type and/or path computation strategy is not provided then values from KildaConfiguration
     * will be used.
     */
    public Flow map(FlowDto flow, Supplier<KildaConfiguration> kildaConfiguration) {
        Switch srcSwitch = Switch.builder().switchId(flow.getSourceSwitch()).build();
        Switch destSwitch = Switch.builder().switchId(flow.getDestinationSwitch()).build();

        return Flow.builder()
                .flowId(flow.getFlowId())
                .srcSwitch(srcSwitch)
                .destSwitch(destSwitch)
                .srcPort(flow.getSourcePort())
                .destPort(flow.getDestinationPort())
                .srcVlan(flow.getSourceVlan())
                .destVlan(flow.getDestinationVlan())
                .status(map(flow.getState()))
                .description(flow.getDescription())
                .bandwidth(flow.getBandwidth())
                .ignoreBandwidth(flow.isIgnoreBandwidth())
                .periodicPings(Boolean.TRUE.equals(flow.getPeriodicPings()))
                .allocateProtectedPath(flow.isAllocateProtectedPath())
                .encapsulationType(Optional.ofNullable(flow.getEncapsulationType())
                        .map(encapsulationType -> FlowEncapsulationType.valueOf(encapsulationType.name()))
                        .orElse(kildaConfiguration.get().getFlowEncapsulationType()))
                .pathComputationStrategy(Optional.ofNullable(flow.getPathComputationStrategy())
                        .map(pathComputationStrategy -> PathComputationStrategy.valueOf(pathComputationStrategy.name()))
                        .orElse(kildaConfiguration.get().getPathComputationStrategy()))
                .maxLatency(flow.getMaxLatency())
                .priority(flow.getPriority())
                .pinned(flow.isPinned())
                .detectConnectedDevices(DetectConnectedDevicesMapper.INSTANCE.map(flow.getDetectConnectedDevices()))
                .build();
    }

    /**
     * Convert {@link String} to {@link Instant}.
     */
    public Instant map(String value) {
        if (value == null) {
            return null;
        }

        return Instant.parse(value);
    }

    /**
     * Convert {@link Instant} to {@link String}.
     */
    public String map(Instant value) {
        if (value == null) {
            return null;
        }

        return value.toString();
    }

    /**
     * Convert {@link FlowStatus} to {@link FlowState}.
     */
    public FlowState map(FlowStatus status) {
        if (status == null) {
            return null;
        }

        switch (status) {
            case IN_PROGRESS:
                return FlowState.IN_PROGRESS;
            case UP:
                return FlowState.UP;
            case DOWN:
                return FlowState.DOWN;
            case DEGRADED:
                return FlowState.DEGRADED;
            default:
                throw new IllegalArgumentException("Unsupported Flow status: " + status);
        }
    }

    /**
     * Convert {@link FlowState} to {@link FlowStatus}.
     */
    public FlowStatus map(FlowState status) {
        if (status == null) {
            return null;
        }

        switch (status) {
            case IN_PROGRESS:
                return FlowStatus.IN_PROGRESS;
            case UP:
                return FlowStatus.UP;
            case DOWN:
                return FlowStatus.DOWN;
            case DEGRADED:
                return FlowStatus.DEGRADED;
            default:
                throw new IllegalArgumentException("Unsupported Flow status: " + status);
        }
    }

    /**
     * Convert {@link FlowEncapsulationType} to {@link org.openkilda.messaging.payload.flow.FlowEncapsulationType}.
     */
    public org.openkilda.messaging.payload.flow.FlowEncapsulationType map(FlowEncapsulationType encapsulationType) {
        return encapsulationType != null ? org.openkilda.messaging.payload.flow.FlowEncapsulationType.valueOf(
                encapsulationType.name()) : null;
    }

    private FlowPath buildPath(FlowDto flowDto) {
        Switch srcSwitch = Switch.builder().switchId(flowDto.getSourceSwitch()).build();
        Switch destSwitch = Switch.builder().switchId(flowDto.getDestinationSwitch()).build();

        return FlowPath.builder()
                .srcSwitch(srcSwitch)
                .destSwitch(destSwitch)
                .cookie(new Cookie(flowDto.getCookie()))
                .bandwidth(flowDto.getBandwidth())
                .ignoreBandwidth(flowDto.isIgnoreBandwidth())
                .pathId(new PathId(UUID.randomUUID().toString()))
                .meterId(flowDto.getMeterId() != null ? new MeterId(flowDto.getMeterId()) : null)
                .build();
    }

    /**
     * Builds a flow from swap flow dto.
     *
     * @param flow a swap flow dto.
     * @return a flow.
     */
    public Flow buildFlow(SwapFlowDto flow) {
        Switch srcSwitch = Switch.builder().switchId(flow.getSourceSwitch()).build();
        Switch dstSwitch = Switch.builder().switchId(flow.getDestinationSwitch()).build();

        return Flow.builder()
                .flowId(flow.getFlowId())
                .srcSwitch(srcSwitch)
                .srcPort(flow.getSourcePort())
                .srcVlan(flow.getSourceVlan())
                .destSwitch(dstSwitch)
                .destPort(flow.getDestinationPort())
                .destVlan(flow.getDestinationVlan())
                .build();
    }
}
