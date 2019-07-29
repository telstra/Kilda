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

package org.openkilda.persistence.ferma.model;

import static java.lang.String.format;

import org.openkilda.model.Cookie;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Represents a bi-directional flow. This includes the source and destination, flow status,
 * bandwidth and description, associated paths, encapsulation type.
 */
@Data
@EqualsAndHashCode(exclude = {"paths"})
@ToString(exclude = {"path"})
public class FlowImpl implements Flow {
    private String flowId;
    private int srcPort;
    private int srcVlan;
    private int destPort;
    private int destVlan;
    private PathId forwardPathId;
    private PathId reversePathId;
    private boolean allocateProtectedPath;
    private PathId protectedForwardPathId;
    private PathId protectedReversePathId;
    private String groupId;
    private long bandwidth;
    private boolean ignoreBandwidth;
    private String description;
    private boolean periodicPings;
    private FlowEncapsulationType encapsulationType;
    private FlowStatus status;
    private Integer maxLatency;
    private Integer priority;
    private Instant timeCreate;
    private Instant timeModify;
    private boolean pinned;
    private Switch srcSwitch;
    private Switch destSwitch;
    private Set<FlowPath> paths = new HashSet<>();

    @Builder(toBuilder = true)
    public FlowImpl(@NonNull String flowId, @NonNull Switch srcSwitch, @NonNull Switch destSwitch,
                    int srcPort, int srcVlan, int destPort, int destVlan,
                    String groupId, long bandwidth, boolean ignoreBandwidth, String description, boolean periodicPings,
                    boolean allocateProtectedPath, FlowEncapsulationType encapsulationType, FlowStatus status,
                    Integer maxLatency, Integer priority,
                    Instant timeCreate, Instant timeModify, boolean pinned) {
        this.flowId = flowId;
        this.srcSwitch = srcSwitch;
        this.destSwitch = destSwitch;
        this.srcPort = srcPort;
        this.srcVlan = srcVlan;
        this.destPort = destPort;
        this.destVlan = destVlan;
        this.groupId = groupId;
        this.bandwidth = bandwidth;
        this.ignoreBandwidth = ignoreBandwidth;
        this.description = description;
        this.periodicPings = periodicPings;
        this.allocateProtectedPath = allocateProtectedPath;
        this.encapsulationType = encapsulationType;
        this.status = status;
        this.maxLatency = maxLatency;
        this.priority = priority;
        this.timeCreate = timeCreate;
        this.timeModify = timeModify;
        this.pinned = pinned;
    }

    public static FlowImplBuilder clone(Flow flow) {
        if (flow instanceof FlowImpl) {
            return ((FlowImpl) flow).toBuilder();
        } else {
            return FlowImpl.builder()
                    .flowId(flow.getFlowId())
                    .srcSwitch(flow.getSrcSwitch())
                    .destSwitch(flow.getDestSwitch())
                    .srcPort(flow.getSrcPort())
                    .srcVlan(flow.getSrcVlan())
                    .destPort(flow.getDestPort())
                    .destVlan(flow.getDestVlan())
                    .groupId(flow.getGroupId())
                    .bandwidth(flow.getBandwidth())
                    .ignoreBandwidth(flow.isIgnoreBandwidth())
                    .description(flow.getDescription())
                    .periodicPings(flow.isPeriodicPings())
                    .allocateProtectedPath(flow.isAllocateProtectedPath())
                    .encapsulationType(flow.getEncapsulationType())
                    .status(flow.getStatus())
                    .maxLatency(flow.getMaxLatency())
                    .priority(flow.getPriority())
                    .timeCreate(flow.getTimeCreate())
                    .timeModify(flow.getTimeModify())
                    .pinned(flow.isPinned());
        }
    }

    @Override
    public Set<FlowPath> getPaths() {
        return Collections.unmodifiableSet(paths);
    }

    @Override
    public Set<PathId> getPathIds() {
        return paths.stream().map(FlowPath::getPathId).collect(Collectors.toSet());
    }

    /**
     * Get an associated path by id.
     */
    @Override
    public final Optional<FlowPath> getPath(PathId pathId) {
        return paths.stream()
                .filter(path -> path.getPathId().equals(pathId))
                .findAny();
    }

    /**
     * Return opposite pathId to passed pathId.
     */
    @Override
    public PathId getOppositePathId(PathId pathId) {
        if (pathId.equals(forwardPathId)) {
            return reversePathId;
        } else if (pathId.equals(reversePathId)) {
            return forwardPathId;
        } else if (pathId.equals(protectedForwardPathId)) {
            return protectedReversePathId;
        } else if (pathId.equals(protectedReversePathId)) {
            return protectedForwardPathId;
        } else {
            // Handling the case of non-active paths.
            Optional<Long> requestedPathCookie = paths.stream()
                    .filter(path -> path.getPathId().equals(pathId))
                    .findAny()
                    .map(FlowPath::getCookie)
                    .map(Cookie::getUnmaskedValue);
            if (requestedPathCookie.isPresent()) {
                Optional<PathId> oppositePathId = paths.stream()
                        .filter(path -> !path.getPathId().equals(pathId))
                        .filter(path -> path.getCookie().getUnmaskedValue() == requestedPathCookie.get())
                        .findAny()
                        .map(FlowPath::getPathId);
                if (oppositePathId.isPresent()) {
                    return oppositePathId.get();
                } else {
                    throw new IllegalArgumentException(
                            format("Flow %s does not have reverse path with cookie %s", flowId,
                                    requestedPathCookie.get()));
                }
            } else {
                throw new IllegalArgumentException(format("Flow %s does not have path %s", flowId, pathId));
            }
        }
    }

    /**
     * Add and associate flow path(s) with the flow.
     */
    @Override
    public final void addPaths(FlowPath... paths) {
        for (FlowPath pathToAdd : paths) {
            boolean toBeAdded = true;
            Iterator<FlowPath> it = this.paths.iterator();
            while (it.hasNext()) {
                FlowPath each = it.next();
                if (pathToAdd == each) {
                    toBeAdded = false;
                    break;
                }
                if (pathToAdd.getPathId().equals(each.getPathId())) {
                    it.remove();
                    // Quit as no duplicates expected.
                    break;
                }
            }
            if (toBeAdded) {
                this.paths.add(pathToAdd);
                ((FlowPathImpl) pathToAdd).setFlow(this);
            }
        }
    }

    @Override
    public void removePaths(PathId... pathIds) {
        Set<PathId> pathIdSet = Arrays.stream(pathIds).collect(Collectors.toSet());
        Iterator<FlowPath> it = paths.iterator();
        while (it.hasNext()) {
            FlowPath each = it.next();
            if (pathIdSet.contains(each.getPathId())) {
                it.remove();
            }
        }
    }

    /**
     * Set already associated path as the forward.
     */
    @Override
    public final void setForwardPathId(PathId pathId) {
        if (!getPath(pathId).isPresent()) {
            throw new IllegalArgumentException(format("The path %s is not associated with flow %s", pathId, flowId));
        }

        this.forwardPathId = pathId;
    }

    /**
     * Set already associated path as protected forward.
     */
    @Override
    public final void setProtectedForwardPathId(PathId pathId) {
        if (!getPath(pathId).isPresent()) {
            throw new IllegalArgumentException(format("The path %s is not associated with flow %s", pathId, flowId));
        }

        this.protectedForwardPathId = pathId;
    }

    /**
     * Set already associated path as the reverse.
     */
    @Override
    public final void setReversePathId(PathId pathId) {
        if (!getPath(pathId).isPresent()) {
            throw new IllegalArgumentException(format("The path %s is not associated with flow %s", pathId, flowId));
        }

        this.reversePathId = pathId;
    }

    /**
     * Set already associated path as the protected reverse.
     */
    @Override
    public final void setProtectedReversePathId(PathId pathId) {
        if (!getPath(pathId).isPresent()) {
            throw new IllegalArgumentException(format("The path %s is not associated with flow %s", pathId, flowId));
        }

        this.protectedReversePathId = pathId;
    }

    @Override
    public SwitchId getSrcSwitchId() {
        return Optional.ofNullable(getSrcSwitch()).map(Switch::getSwitchId).orElse(null);
    }

    @Override
    public SwitchId getDestSwitchId() {
        return Optional.ofNullable(getDestSwitch()).map(Switch::getSwitchId).orElse(null);
    }
}
