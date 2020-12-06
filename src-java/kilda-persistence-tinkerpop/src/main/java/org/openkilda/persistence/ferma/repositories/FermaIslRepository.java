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

package org.openkilda.persistence.ferma.repositories;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableCollection;

import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.Isl;
import org.openkilda.model.Isl.IslData;
import org.openkilda.model.IslConfig;
import org.openkilda.model.IslStatus;
import org.openkilda.model.PathId;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.IslFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseEdgeFrame;
import org.openkilda.persistence.ferma.frames.PathSegmentFrame;
import org.openkilda.persistence.ferma.frames.SwitchFrame;
import org.openkilda.persistence.ferma.frames.SwitchPropertiesFrame;
import org.openkilda.persistence.ferma.frames.converters.FlowEncapsulationTypeConverter;
import org.openkilda.persistence.ferma.frames.converters.IslStatusConverter;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchStatusConverter;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.tx.TransactionManager;
import org.openkilda.persistence.tx.TransactionRequired;

import com.syncleus.ferma.FramedGraph;
import lombok.extern.slf4j.Slf4j;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Edge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ferma implementation of {@link IslRepository}.
 */
@Slf4j
public class FermaIslRepository extends FermaGenericRepository<Isl, IslData, IslFrame> implements IslRepository {
    protected final FermaFlowPathRepository flowPathRepository;
    protected final IslConfig islConfig;

    public FermaIslRepository(FramedGraphFactory<?> graphFactory,
                              TransactionManager transactionManager, FermaFlowPathRepository flowPathRepository,
                              IslConfig islConfig) {
        super(graphFactory, transactionManager);
        this.flowPathRepository = flowPathRepository;
        this.islConfig = islConfig;
    }

    @Override
    public Collection<Isl> findAll() {
        return framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    @Override
    public boolean existsByEndpoint(SwitchId switchId, int port) {
        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(IslFrame.SRC_PORT_PROPERTY, port))
                .getRawTraversal()) {
            if (traversal.hasNext()) {
                return true;
            }
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }
        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(IslFrame.DST_PORT_PROPERTY, port))
                .getRawTraversal()) {
            return traversal.hasNext();
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }
    }

    @Override
    public Collection<Isl> findByEndpoint(SwitchId switchId, int port) {
        List<Isl> result = new ArrayList<>();
        framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(IslFrame.SRC_PORT_PROPERTY, port))
                .frameExplicit(IslFrame.class)
                .forEachRemaining(frame -> result.add(addIslConfigToIsl(new Isl(frame))));
        framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(IslFrame.DST_PORT_PROPERTY, port))
                .frameExplicit(IslFrame.class)
                .forEachRemaining(frame -> result.add(addIslConfigToIsl(new Isl(frame))));
        return result;
    }

    @Override
    public Collection<Isl> findBySrcEndpoint(SwitchId srcSwitchId, int srcPort) {
        return framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(srcSwitchId))
                .has(IslFrame.SRC_PORT_PROPERTY, srcPort))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Isl> findByDestEndpoint(SwitchId dstSwitchId, int dstPort) {
        return framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(dstSwitchId))
                .has(IslFrame.DST_PORT_PROPERTY, dstPort))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Isl> findBySrcSwitch(SwitchId switchId) {
        return framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.SRC_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Isl> findByDestSwitch(SwitchId switchId) {
        return framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.DST_SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Isl> findByEndpoints(SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort) {
        return findIsl(srcSwitchId, srcPort, dstSwitchId, dstPort)
                .map(Isl::new)
                .map(this::addIslConfigToIsl);
    }

    @Override
    public Collection<Isl> findByPathIds(List<PathId> pathIds) {
        List<String> pathIdAsStr = pathIds.stream()
                .map(PathIdConverter.INSTANCE::toGraphProperty)
                .collect(Collectors.toList());
        List<? extends PathSegmentFrame> segmentFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.PATH_ID_PROPERTY, P.within(pathIdAsStr)))
                .toListExplicit(PathSegmentFrame.class);
        if (segmentFrames.isEmpty()) {
            return emptyList();
        }

        List<Isl> result = new ArrayList<>();
        segmentFrames.forEach(segmentFrame -> {
            framedGraph().traverse(g -> g.E()
                    .hasLabel(IslFrame.FRAME_LABEL)
                    .has(IslFrame.SRC_SWITCH_ID_PROPERTY,
                            SwitchIdConverter.INSTANCE.toGraphProperty(segmentFrame.getSrcSwitchId()))
                    .has(IslFrame.DST_SWITCH_ID_PROPERTY,
                            SwitchIdConverter.INSTANCE.toGraphProperty(segmentFrame.getDestSwitchId()))
                    .has(IslFrame.SRC_PORT_PROPERTY, segmentFrame.getSrcPort())
                    .has(IslFrame.DST_PORT_PROPERTY, segmentFrame.getDestPort()))
                    .frameExplicit(IslFrame.class)
                    .forEachRemaining(frame -> result.add(addIslConfigToIsl(new Isl(frame))));
        });
        return result;


    }

    private Optional<IslFrame> findIsl(SwitchId srcSwitchId, long srcPort, SwitchId dstSwitchId, long dstPort) {
        return findIsl(framedGraph(), SwitchIdConverter.INSTANCE.toGraphProperty(srcSwitchId), srcPort,
                SwitchIdConverter.INSTANCE.toGraphProperty(dstSwitchId), dstPort);
    }

    private Optional<IslFrame> findIsl(FramedGraph framedGraph,
                                       String srcSwitchId, long srcPort, String dstSwitchId, long dstPort) {
        List<? extends IslFrame> islFrames = framedGraph.traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.SRC_SWITCH_ID_PROPERTY, srcSwitchId)
                .has(IslFrame.DST_SWITCH_ID_PROPERTY, dstSwitchId)
                .has(IslFrame.SRC_PORT_PROPERTY, srcPort)
                .has(IslFrame.DST_PORT_PROPERTY, dstPort))
                .toListExplicit(IslFrame.class);
        return islFrames.isEmpty() ? Optional.empty() : Optional.of(islFrames.get(0));
    }

    @Override
    public Collection<Isl> findByPartialEndpoints(SwitchId srcSwitchId, Integer srcPort,
                                                  SwitchId dstSwitchId, Integer dstPort) {
        List<Isl> result = new ArrayList<>();
        framedGraph().traverse(g -> {
            GraphTraversal<Edge, Edge> traversal = g.E()
                    .hasLabel(IslFrame.FRAME_LABEL);
            if (srcSwitchId != null) {
                traversal = traversal.has(IslFrame.SRC_SWITCH_ID_PROPERTY,
                        SwitchIdConverter.INSTANCE.toGraphProperty(srcSwitchId));
            }
            if (dstSwitchId != null) {
                traversal = traversal.has(IslFrame.DST_SWITCH_ID_PROPERTY,
                        SwitchIdConverter.INSTANCE.toGraphProperty(dstSwitchId));
            }
            if (srcPort != null) {
                traversal = traversal.has(IslFrame.SRC_PORT_PROPERTY, srcPort);
            }
            if (dstPort != null) {
                traversal = traversal.has(IslFrame.DST_PORT_PROPERTY, dstPort);
            }
            return traversal;
        }).frameExplicit(IslFrame.class)
                .forEachRemaining(frame -> result.add(addIslConfigToIsl(new Isl(frame))));

        return unmodifiableCollection(result);
    }

    @Override
    public Collection<IslImmutableView> findActiveByPathAndBandwidthAndEncapsulationType(
            PathId pathId, long requiredBandwidth, FlowEncapsulationType flowEncapsulationType) {
        String pathIdAsStr = PathIdConverter.INSTANCE.toGraphProperty(pathId);
        String activeIslStatusAsStr = IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE);

        Set<String> activeSwitches = findActiveSwitchesWithSupportEncapsulationType(flowEncapsulationType);
        List<IslImmutableView> result = new ArrayList<>();
        framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.PATH_ID_PROPERTY, pathIdAsStr))
                .frameExplicit(PathSegmentFrame.class)
                .forEachRemaining(segmentFrame -> {
                    String srcSwitch = segmentFrame.getProperty(PathSegmentFrame.SRC_SWITCH_ID_PROPERTY);
                    String dstSwitch = segmentFrame.getProperty(PathSegmentFrame.DST_SWITCH_ID_PROPERTY);
                    if (activeSwitches.contains(srcSwitch) && activeSwitches.contains(dstSwitch)) {
                        framedGraph().traverse(g -> g.E()
                                .hasLabel(IslFrame.FRAME_LABEL)
                                .has(IslFrame.SRC_SWITCH_ID_PROPERTY, srcSwitch)
                                .has(IslFrame.DST_SWITCH_ID_PROPERTY, dstSwitch)
                                .has(IslFrame.SRC_PORT_PROPERTY, segmentFrame.getSrcPort())
                                .has(IslFrame.DST_PORT_PROPERTY, segmentFrame.getDestPort())
                                .has(IslFrame.STATUS_PROPERTY, activeIslStatusAsStr)
                                .has(IslFrame.AVAILABLE_BANDWIDTH_PROPERTY,
                                        P.gte(requiredBandwidth - segmentFrame.getBandwidth())))
                                .frameExplicit(IslFrame.class)
                                .forEachRemaining(frame -> result.add(new IslViewImpl(frame, islConfig)));
                    }
                });
        return result;
    }

    @Override
    public Collection<IslImmutableView> findAllActive() {
        Set<String> activeSwitches = findActiveSwitches();
        return framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.STATUS_PROPERTY, IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE)))
                .toListExplicit(IslFrame.class).stream()
                .filter(frame -> {
                    String srcSwitch = frame.getProperty(IslFrame.SRC_SWITCH_ID_PROPERTY);
                    String dstSwitch = frame.getProperty(IslFrame.DST_SWITCH_ID_PROPERTY);
                    return activeSwitches.contains(srcSwitch) && activeSwitches.contains(dstSwitch);
                })
                .map(frame -> new IslViewImpl(frame, islConfig))
                .collect(Collectors.toList());
    }

    @Override
    public Collection<IslImmutableView> findActiveByEncapsulationType(FlowEncapsulationType flowEncapsulationType) {
        Set<String> activeSwitches = findActiveSwitchesWithSupportEncapsulationType(flowEncapsulationType);
        return framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.STATUS_PROPERTY, IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE)))
                .toListExplicit(IslFrame.class).stream()
                .filter(frame -> {
                    String srcSwitch = frame.getProperty(IslFrame.SRC_SWITCH_ID_PROPERTY);
                    String dstSwitch = frame.getProperty(IslFrame.DST_SWITCH_ID_PROPERTY);
                    return activeSwitches.contains(srcSwitch) && activeSwitches.contains(dstSwitch);
                })
                .map(frame -> new IslViewImpl(frame, islConfig))
                .collect(Collectors.toList());
    }

    @Override
    public Collection<IslImmutableView> findActiveByBandwidthAndEncapsulationType(long requiredBandwidth,
                                                                          FlowEncapsulationType flowEncapsulationType) {
        return findActiveByAvailableBandwidthAndEncapsulationType(requiredBandwidth, flowEncapsulationType).values();
    }

    @Override
    public Collection<IslImmutableView> findSymmetricActiveByBandwidthAndEncapsulationType(long requiredBandwidth,
                                                                          FlowEncapsulationType flowEncapsulationType) {
        Map<IslEndpoints, IslImmutableView> foundIsls =
                findActiveByAvailableBandwidthAndEncapsulationType(requiredBandwidth, flowEncapsulationType);

        List<IslImmutableView> result = new ArrayList<>();
        foundIsls.forEach((key, isl) -> {
            if (foundIsls.containsKey(new IslEndpoints(
                    key.getDestSwitch(), key.getDestPort(),
                    key.getSrcSwitch(), key.getSrcPort()))) {
                result.add(isl);
            }
        });

        return result;
    }

    protected Map<IslEndpoints, IslImmutableView> findActiveByAvailableBandwidthAndEncapsulationType(
            long requiredBandwidth, FlowEncapsulationType flowEncapsulationType) {
        Set<String> activeSwitches = findActiveSwitchesWithSupportEncapsulationType(flowEncapsulationType);
        Map<IslEndpoints, IslImmutableView> result = new HashMap<>();
        framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.STATUS_PROPERTY, IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE))
                .has(IslFrame.AVAILABLE_BANDWIDTH_PROPERTY, P.gte(requiredBandwidth)))
                .frameExplicit(IslFrame.class)
                .forEachRemaining(frame -> {
                    String srcSwitch = frame.getProperty(IslFrame.SRC_SWITCH_ID_PROPERTY);
                    String dstSwitch = frame.getProperty(IslFrame.DST_SWITCH_ID_PROPERTY);
                    if (activeSwitches.contains(srcSwitch) && activeSwitches.contains(dstSwitch)) {
                        result.put(new IslEndpoints(srcSwitch, frame.getSrcPort(), dstSwitch, frame.getDestPort()),
                                new IslViewImpl(frame, islConfig));
                    }
                });
        return result;
    }

    protected Set<String> findActiveSwitches() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .has(SwitchFrame.STATUS_PROPERTY, SwitchStatusConverter.INSTANCE.toGraphProperty(SwitchStatus.ACTIVE))
                .values(SwitchFrame.SWITCH_ID_PROPERTY))
                .getRawTraversal().toStream()
                .map(s -> (String) s)
                .collect(Collectors.toSet());
    }

    protected Set<String> findActiveSwitchesWithSupportEncapsulationType(FlowEncapsulationType flowEncapsulationType) {
        String flowEncapType = FlowEncapsulationTypeConverter.INSTANCE.toGraphProperty(flowEncapsulationType);

        return framedGraph().traverse(g -> g.V()
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .has(SwitchFrame.STATUS_PROPERTY, SwitchStatusConverter.INSTANCE.toGraphProperty(SwitchStatus.ACTIVE))
                .where(__.out(SwitchPropertiesFrame.HAS_BY_EDGE)
                        .hasLabel(SwitchPropertiesFrame.FRAME_LABEL)
                        .values(SwitchPropertiesFrame.SUPPORTED_TRANSIT_ENCAPSULATION_PROPERTY).unfold()
                        .is(flowEncapType))
                .values(SwitchFrame.SWITCH_ID_PROPERTY))
                .getRawTraversal().toStream()
                .map(s -> (String) s)
                .collect(Collectors.toSet());
    }

    private Isl addIslConfigToIsl(Isl isl) {
        isl.setIslConfig(islConfig);
        return isl;
    }

    @TransactionRequired
    @Override
    public long updateAvailableBandwidth(SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort) {
        FramedGraph framedGraph = framedGraph();
        String srcSwitchIdAsStr = SwitchIdConverter.INSTANCE.toGraphProperty(srcSwitchId);
        String dstSwitchIdAsStr = SwitchIdConverter.INSTANCE.toGraphProperty(dstSwitchId);

        long usedBandwidth = flowPathRepository.getUsedBandwidthBetweenEndpoints(framedGraph,
                srcSwitchIdAsStr, srcPort, dstSwitchIdAsStr, dstPort);
        return updateAvailableBandwidth(framedGraph,
                srcSwitchIdAsStr, srcPort, dstSwitchIdAsStr, dstPort, usedBandwidth);
    }

    private long updateAvailableBandwidth(FramedGraph framedGraph, String srcSwitchId, int srcPort,
                                            String dstSwitchId, int dstPort, long usedBandwidth) {
        IslFrame isl = findIsl(framedGraph, srcSwitchId, srcPort, dstSwitchId, dstPort)
                .orElseThrow(() -> new PersistenceException(format("ISL %s_%d - %s_%d not found to be updated",
                        srcSwitchId, srcPort, dstSwitchId, dstPort)));
        long updatedAvailableBandwidth = isl.getMaxBandwidth() - usedBandwidth;
        isl.setAvailableBandwidth(updatedAvailableBandwidth);
        return updatedAvailableBandwidth;
    }

    @Override
    public Map<IslEndpoints, Long> updateAvailableBandwidthOnIslsOccupiedByPath(PathId pathId) {
        FramedGraph framedGraph = framedGraph();

        Set<IslEndpoints> segmentEndpoints = new HashSet<>();
        framedGraph.traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                .frameExplicit(PathSegmentFrame.class)
                .forEachRemaining(frame -> {
                    String srcSwitch = frame.getProperty(PathSegmentFrame.SRC_SWITCH_ID_PROPERTY);
                    String dstSwitch = frame.getProperty(PathSegmentFrame.DST_SWITCH_ID_PROPERTY);
                    segmentEndpoints.add(new IslEndpoints(srcSwitch, frame.getSrcPort(),
                            dstSwitch, frame.getDestPort()));
                });

        Map<IslEndpoints, Long> updatedEndpoints = new HashMap<>();
        segmentEndpoints.forEach(endpoint -> {
            long usedBandwidth = flowPathRepository.getUsedBandwidthBetweenEndpoints(framedGraph,
                    endpoint.getSrcSwitch(), endpoint.getSrcPort(), endpoint.getDestSwitch(), endpoint.getDestPort());
            long updatedAvailableBandwidth = updateAvailableBandwidth(framedGraph,
                    endpoint.getSrcSwitch(), endpoint.getSrcPort(), endpoint.getDestSwitch(), endpoint.getDestPort(),
                    usedBandwidth);
            updatedEndpoints.put(endpoint, updatedAvailableBandwidth);
        });
        return updatedEndpoints;
    }

    /**
     * An implementation of IslImmutableView which is based on Isl entity.
     */
    protected static class IslViewImpl implements IslImmutableView {
        private Isl isl;

        protected IslViewImpl(IslFrame frame, IslConfig islConfig) {
            isl = new Isl(frame);
            isl.setIslConfig(islConfig);
        }

        public IslViewImpl(SwitchId srcSwitchId, int srcPort, String srcPop,
                           SwitchId destSwitchId, int destPort, String destPop,
                           long latency, int cost, long availableBandwidth,
                           boolean underMaintenance, Instant timeUnstable, IslConfig islConfig) {
            isl = Isl.builder()
                    .srcSwitch(Switch.builder().switchId(srcSwitchId).pop(srcPop).build())
                    .destSwitch(Switch.builder().switchId(destSwitchId).pop(destPop).build())
                    .srcPort(srcPort).destPort(destPort).latency(latency).cost(cost)
                    .availableBandwidth(availableBandwidth).underMaintenance(underMaintenance)
                    .timeUnstable(timeUnstable).build();
            isl.setIslConfig(islConfig);
        }

        @Override
        public SwitchId getSrcSwitchId() {
            return isl.getSrcSwitchId();
        }

        @Override
        public int getSrcPort() {
            return isl.getSrcPort();
        }

        @Override
        public String getSrcPop() {
            return isl.getSrcSwitch().getPop();
        }

        @Override
        public String getDestPop() {
            return isl.getDestSwitch().getPop();
        }

        @Override
        public SwitchId getDestSwitchId() {
            return isl.getDestSwitchId();
        }

        @Override
        public int getDestPort() {
            return isl.getDestPort();
        }

        @Override
        public long getLatency() {
            return isl.getLatency();
        }

        @Override
        public int getCost() {
            return isl.getCost();
        }

        @Override
        public long getAvailableBandwidth() {
            return isl.getAvailableBandwidth();
        }

        @Override
        public boolean isUnderMaintenance() {
            return isl.isUnderMaintenance();
        }

        @Override
        public boolean isUnstable() {
            return isl.isUnstable();
        }
    }

    @Override
    public void add(Isl entity) {
        super.add(entity);
        addIslConfigToIsl(entity);
    }

    @Override
    protected IslFrame doAdd(IslData data) {
        String srcSwitchId = SwitchIdConverter.INSTANCE.toGraphProperty(data.getSrcSwitchId());
        SwitchFrame source = SwitchFrame.load(framedGraph(), srcSwitchId)
                .orElseThrow(() -> new IllegalArgumentException("Unable to locate the switch " + srcSwitchId));
        String dstSwitchId = SwitchIdConverter.INSTANCE.toGraphProperty(data.getDestSwitchId());
        SwitchFrame destination = SwitchFrame.load(framedGraph(), dstSwitchId)
                .orElseThrow(() -> new IllegalArgumentException("Unable to locate the switch " + dstSwitchId));
        IslFrame frame = KildaBaseEdgeFrame.addNewFramedEdge(framedGraph(), source, destination,
                IslFrame.FRAME_LABEL, IslFrame.class);
        frame.setProperty(IslFrame.SRC_SWITCH_ID_PROPERTY, srcSwitchId);
        frame.setProperty(IslFrame.DST_SWITCH_ID_PROPERTY, dstSwitchId);
        Isl.IslCloner.INSTANCE.copyWithoutSwitches(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(IslFrame frame) {
        frame.remove();
    }

    @Override
    protected IslData doDetach(Isl entity, IslFrame frame) {
        return Isl.IslCloner.INSTANCE.deepCopy(frame);
    }
}
