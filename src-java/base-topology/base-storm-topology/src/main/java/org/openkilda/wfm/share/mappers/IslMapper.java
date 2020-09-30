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

package org.openkilda.wfm.share.mappers;

import org.openkilda.messaging.info.event.IslChangeType;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.BfdSession;
import org.openkilda.model.BfdSessionStatus;
import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;
import org.openkilda.model.Switch;

import com.google.common.base.Strings;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Optional;

/**
 * Convert {@link Isl} to {@link IslInfoData} and back.
 */
@Mapper
public abstract class IslMapper {

    public static final IslMapper INSTANCE = Mappers.getMapper(IslMapper.class);

    /**
     * Convert {@link Isl} to {@link IslInfoData}.
     */
    public IslInfoData map(Isl isl) {
        if (isl == null) {
            return null;
        }

        PathNode src = new PathNode();
        src.setSwitchId(isl.getSrcSwitch().getSwitchId());
        src.setPortNo(isl.getSrcPort());
        src.setSegLatency(isl.getLatency());
        src.setSeqId(0);

        PathNode dst = new PathNode();
        dst.setSwitchId(isl.getDestSwitch().getSwitchId());
        dst.setPortNo(isl.getDestPort());
        dst.setSegLatency(isl.getLatency());
        dst.setSeqId(1);

        Long timeCreateMillis = Optional.ofNullable(isl.getTimeCreate()).map(Instant::toEpochMilli).orElse(null);
        Long timeModifyMillis = Optional.ofNullable(isl.getTimeModify()).map(Instant::toEpochMilli).orElse(null);
        BfdProperties bfdProperties = readBfdProperties(isl);
        return new IslInfoData(isl.getLatency(), src, dst, isl.getSpeed(), isl.getAvailableBandwidth(),
                isl.getMaxBandwidth(), isl.getDefaultMaxBandwidth(), map(isl.getStatus()), map(isl.getActualStatus()),
                isl.getCost(), timeCreateMillis, timeModifyMillis, isl.isUnderMaintenance(),
                bfdProperties.isEnabled(), map(isl.getBfdSessionStatus()), null);
    }

    /**
     * Convert {@link IslInfoData} to {@link org.openkilda.model.Isl}.
     */
    public org.openkilda.model.Isl map(IslInfoData islInfoData) {
        if (islInfoData == null) {
            return null;
        }

        Isl isl = new Isl();

        PathNode sourcePathNode = islInfoData.getSource();
        if (sourcePathNode != null) {
            isl.setSrcSwitch(Switch.builder().switchId(sourcePathNode.getSwitchId()).build());
            isl.setSrcPort(sourcePathNode.getPortNo());
        }

        PathNode destinationPathNode = islInfoData.getDestination();
        if (destinationPathNode != null) {
            isl.setDestSwitch(Switch.builder().switchId(destinationPathNode.getSwitchId()).build());
            isl.setDestPort(destinationPathNode.getPortNo());
        }
        isl.setLatency((int) islInfoData.getLatency());
        isl.setSpeed(islInfoData.getSpeed());
        isl.setAvailableBandwidth(islInfoData.getAvailableBandwidth());
        isl.setStatus(map(islInfoData.getState()));
        isl.setCost(islInfoData.getCost());
        isl.setUnderMaintenance(islInfoData.isUnderMaintenance());
        isl.setBfdSessionStatus(map(islInfoData.getBfdSessionStatus()));

        return isl;
    }

    /**
     * Convert {@link IslStatus} to {@link IslChangeType}.
     */
    public IslChangeType map(IslStatus status) {
        if (status == null) {
            return null;
        }

        switch (status) {
            case ACTIVE:
                return IslChangeType.DISCOVERED;
            case INACTIVE:
                return IslChangeType.FAILED;
            case MOVED:
                return IslChangeType.MOVED;
            default:
                throw new IllegalArgumentException("Unsupported ISL status: " + status);

        }
    }

    /**
     * Convert {@link IslChangeType} to {@link IslStatus}.
     */
    public IslStatus map(IslChangeType status) {
        if (status == null) {
            return null;
        }

        switch (status) {
            case DISCOVERED:
            case CACHED:
            case OTHER_UPDATE:
                return IslStatus.ACTIVE;
            case FAILED:
                return IslStatus.INACTIVE;
            case MOVED:
                return IslStatus.MOVED;
            default:
                throw new IllegalArgumentException("Unsupported ISL status: " + status);
        }
    }

    /**
     * Convert string representation of {@link BfdSessionStatus} into enum constant representation.
     */
    public BfdSessionStatus map(String raw) {
        if (Strings.nullToEmpty(raw).trim().isEmpty()) {
            return null;
        }
        return BfdSessionStatus.valueOf(raw.toUpperCase());
    }

    /**
     * Convert {@link BfdSessionStatus} into string representation.
     */
    public String map(BfdSessionStatus status) {
        if (status == null) {
            return null;
        }
        return status.name().toLowerCase();
    }

    @Mapping(target = "interval", source = "bfdInterval")
    @Mapping(target = "multiplier", source = "bfdMultiplier")
    public abstract BfdProperties readBfdProperties(Isl link);

    public abstract BfdProperties readBfdProperties(BfdSession session);
}
