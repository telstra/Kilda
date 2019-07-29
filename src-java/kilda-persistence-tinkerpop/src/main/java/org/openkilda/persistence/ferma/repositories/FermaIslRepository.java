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
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowPathFrame;
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

import lombok.extern.slf4j.Slf4j;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma implementation of {@link IslRepository}.
 */
@Slf4j
public class FermaIslRepository extends FermaGenericRepository<Isl, IslData, IslFrame> implements IslRepository {
    private final IslConfig islConfig;

    public FermaIslRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager,
                              IslConfig islConfig) {
        super(graphFactory, transactionManager);
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
    public Collection<Isl> findByEndpoint(SwitchId switchId, int port) {
        List<Isl> result = new ArrayList<>();
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .outE(IslFrame.FRAME_LABEL)
                .has(IslFrame.SRC_PORT_PROPERTY, port))
                .frameExplicit(IslFrame.class)
                .forEachRemaining(frame -> result.add(addIslConfigToIsl(new Isl(frame))));
        framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .inE(IslFrame.FRAME_LABEL)
                .has(IslFrame.DST_PORT_PROPERTY, port))
                .frameExplicit(IslFrame.class)
                .forEachRemaining(frame -> result.add(addIslConfigToIsl(new Isl(frame))));
        return result;
    }

    @Override
    public Collection<Isl> findBySrcEndpoint(SwitchId srcSwitchId, int srcPort) {
        return framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, srcSwitchId)
                .outE(IslFrame.FRAME_LABEL)
                .has(IslFrame.SRC_PORT_PROPERTY, srcPort))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Isl> findByDestEndpoint(SwitchId dstSwitchId, int dstPort) {
        return framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, dstSwitchId)
                .inE(IslFrame.FRAME_LABEL)
                .has(IslFrame.DST_PORT_PROPERTY, dstPort))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Isl> findBySrcSwitch(SwitchId switchId) {
        return framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .outE(IslFrame.FRAME_LABEL))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Isl> findByDestSwitch(SwitchId switchId) {
        return framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, switchId)
                .inE(IslFrame.FRAME_LABEL))
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

    private Optional<IslFrame> findIsl(SwitchId srcSwitchId, long srcPort, SwitchId dstSwitchId, long dstPort) {
        return Optional.ofNullable(
                framedGraph().traverse(g -> FermaSwitchRepository.getTraverseForSwitch(g, srcSwitchId)
                        .outE(IslFrame.FRAME_LABEL)
                        .has(IslFrame.SRC_PORT_PROPERTY, srcPort)
                        .has(IslFrame.DST_PORT_PROPERTY, dstPort)
                        .where(__.inV().hasLabel(SwitchFrame.FRAME_LABEL)
                                .has(SwitchFrame.SWITCH_ID_PROPERTY,
                                        SwitchIdConverter.INSTANCE.toGraphProperty(dstSwitchId))))
                        .nextOrDefaultExplicit(IslFrame.class, null));
    }

    @Override
    public Collection<Isl> findByPartialEndpoints(SwitchId srcSwitchId, Integer srcPort,
                                                  SwitchId dstSwitchId, Integer dstPort) {
        List<Isl> result = new ArrayList<>();
        if (srcSwitchId != null) {
            framedGraph().traverse(g -> {
                GraphTraversal<Vertex, Edge> traversal = FermaSwitchRepository.getTraverseForSwitch(g, srcSwitchId)
                        .outE(IslFrame.FRAME_LABEL);
                if (srcPort != null) {
                    traversal = traversal.has(IslFrame.SRC_PORT_PROPERTY, srcPort);
                }
                if (dstPort != null) {
                    traversal = traversal.has(IslFrame.DST_PORT_PROPERTY, dstPort);
                }
                if (dstSwitchId != null) {
                    traversal = traversal.where(__.inV().hasLabel(SwitchFrame.FRAME_LABEL)
                            .has(SwitchFrame.SWITCH_ID_PROPERTY,
                                    SwitchIdConverter.INSTANCE.toGraphProperty(dstSwitchId)));
                }
                return traversal;
            }).frameExplicit(IslFrame.class)
                    .forEachRemaining(frame -> result.add(addIslConfigToIsl(new Isl(frame))));
        } else if (dstSwitchId != null) {
            framedGraph().traverse(g -> {
                GraphTraversal<Vertex, Edge> traversal = FermaSwitchRepository.getTraverseForSwitch(g, dstSwitchId)
                        .inE(IslFrame.FRAME_LABEL);
                if (srcPort != null) {
                    traversal = traversal.has(IslFrame.SRC_PORT_PROPERTY, srcPort);
                }
                if (dstPort != null) {
                    traversal = traversal.has(IslFrame.DST_PORT_PROPERTY, dstPort);
                }
                return traversal;
            }).frameExplicit(IslFrame.class)
                    .forEachRemaining(frame -> result.add(addIslConfigToIsl(new Isl(frame))));
        } else {
            return findAll();
        }

        return unmodifiableCollection(result);
    }

    @Override
    public Collection<Isl> findActiveAndOccupiedByFlowPathWithAvailableBandwidth(
            PathId pathId, long requiredBandwidth, FlowEncapsulationType flowEncapsulationType) {
        FlowPathFrame flowPath = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                .nextOrDefaultExplicit(FlowPathFrame.class, null);
        if (flowPath == null) {
            return emptyList();
        }

        String activeSwitchStatus = SwitchStatusConverter.INSTANCE.toGraphProperty(SwitchStatus.ACTIVE);
        String flowEncapType = FlowEncapsulationTypeConverter.INSTANCE.toGraphProperty(flowEncapsulationType);
        return framedGraph().traverse(g -> g.V()
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .has(SwitchFrame.STATUS_PROPERTY, activeSwitchStatus)
                .where(__.out(SwitchPropertiesFrame.HAS_BY_EDGE)
                        .hasLabel(SwitchPropertiesFrame.FRAME_LABEL)
                        .has(SwitchPropertiesFrame.SUPPORTED_TRANSIT_ENCAPSULATION_PROPERTY, flowEncapType))
                .as("source")
                .outE(IslFrame.FRAME_LABEL)
                .has(IslFrame.STATUS_PROPERTY, IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE))
                .has(IslFrame.AVAILABLE_BANDWIDTH_PROPERTY, P.gte(requiredBandwidth - flowPath.getBandwidth()))
                .as("isl")
                .inV()
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .has(SwitchFrame.STATUS_PROPERTY, activeSwitchStatus)
                .where(__.out(SwitchPropertiesFrame.HAS_BY_EDGE)
                        .hasLabel(SwitchPropertiesFrame.FRAME_LABEL)
                        .has(SwitchPropertiesFrame.SUPPORTED_TRANSIT_ENCAPSULATION_PROPERTY, flowEncapType))
                .as("destination")
                .in(PathSegmentFrame.DESTINATION_EDGE)
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .where(P.eq("isl")).by(IslFrame.SRC_PORT_PROPERTY)
                .where(P.eq("isl")).by(IslFrame.DST_PORT_PROPERTY)
                .where(__.out(PathSegmentFrame.SOURCE_EDGE).where(P.eq("source")))
                .in(FlowPathFrame.OWNS_SEGMENTS_EDGE).is(flowPath.getElement())
                .select("isl"))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Isl> findAllActive() {
        String activeSwitchStatus = SwitchStatusConverter.INSTANCE.toGraphProperty(SwitchStatus.ACTIVE);
        return framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.STATUS_PROPERTY, IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE))
                .where(__.outV().hasLabel(SwitchFrame.FRAME_LABEL)
                        .has(SwitchFrame.STATUS_PROPERTY, activeSwitchStatus))
                .where(__.inV().hasLabel(SwitchFrame.FRAME_LABEL)
                        .has(SwitchFrame.STATUS_PROPERTY, activeSwitchStatus)))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Isl> findAllActiveByEncapsulationType(FlowEncapsulationType flowEncapsulationType) {
        String activeSwitchStatus = SwitchStatusConverter.INSTANCE.toGraphProperty(SwitchStatus.ACTIVE);
        String flowEncapType = FlowEncapsulationTypeConverter.INSTANCE.toGraphProperty(flowEncapsulationType);
        return framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.STATUS_PROPERTY, IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE))
                .as("forward")
                .outV()
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .has(SwitchFrame.STATUS_PROPERTY, activeSwitchStatus)
                .where(__.out(SwitchPropertiesFrame.HAS_BY_EDGE)
                        .hasLabel(SwitchPropertiesFrame.FRAME_LABEL)
                        .has(SwitchPropertiesFrame.SUPPORTED_TRANSIT_ENCAPSULATION_PROPERTY, flowEncapType))
                .select("forward")
                .inV()
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .has(SwitchFrame.STATUS_PROPERTY, activeSwitchStatus)
                .where(__.out(SwitchPropertiesFrame.HAS_BY_EDGE)
                        .hasLabel(SwitchPropertiesFrame.FRAME_LABEL)
                        .has(SwitchPropertiesFrame.SUPPORTED_TRANSIT_ENCAPSULATION_PROPERTY, flowEncapType))
                .select("forward"))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Isl> findActiveWithAvailableBandwidth(long requiredBandwidth,
                                                            FlowEncapsulationType flowEncapsulationType) {
        String activeSwitchStatus = SwitchStatusConverter.INSTANCE.toGraphProperty(SwitchStatus.ACTIVE);
        String flowEncapType = FlowEncapsulationTypeConverter.INSTANCE.toGraphProperty(flowEncapsulationType);
        return framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.STATUS_PROPERTY, IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE))
                .has(IslFrame.AVAILABLE_BANDWIDTH_PROPERTY, P.gte(requiredBandwidth))
                .as("forward")
                .outV()
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .has(SwitchFrame.STATUS_PROPERTY, activeSwitchStatus)
                .where(__.out(SwitchPropertiesFrame.HAS_BY_EDGE)
                        .hasLabel(SwitchPropertiesFrame.FRAME_LABEL)
                        .has(SwitchPropertiesFrame.SUPPORTED_TRANSIT_ENCAPSULATION_PROPERTY, flowEncapType))
                .select("forward")
                .inV()
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .has(SwitchFrame.STATUS_PROPERTY, activeSwitchStatus)
                .where(__.out(SwitchPropertiesFrame.HAS_BY_EDGE)
                        .hasLabel(SwitchPropertiesFrame.FRAME_LABEL)
                        .has(SwitchPropertiesFrame.SUPPORTED_TRANSIT_ENCAPSULATION_PROPERTY, flowEncapType))
                .select("forward"))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Isl> findSymmetricActiveWithAvailableBandwidth(long requiredBandwidth,
                                                                     FlowEncapsulationType flowEncapsulationType) {
        String activeSwitchStatus = SwitchStatusConverter.INSTANCE.toGraphProperty(SwitchStatus.ACTIVE);
        String activeIslStatus = IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE);
        String flowEncapType = FlowEncapsulationTypeConverter.INSTANCE.toGraphProperty(flowEncapsulationType);
        return framedGraph().traverse(g -> g.E()
                .hasLabel(IslFrame.FRAME_LABEL)
                .has(IslFrame.STATUS_PROPERTY, activeIslStatus)
                .has(IslFrame.AVAILABLE_BANDWIDTH_PROPERTY, P.gte(requiredBandwidth))
                .as("forward")
                .outV()
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .has(SwitchFrame.STATUS_PROPERTY, activeSwitchStatus)
                .where(__.out(SwitchPropertiesFrame.HAS_BY_EDGE)
                        .hasLabel(SwitchPropertiesFrame.FRAME_LABEL)
                        .has(SwitchPropertiesFrame.SUPPORTED_TRANSIT_ENCAPSULATION_PROPERTY, flowEncapType))
                .as("source")
                .select("forward")
                .inV()
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .has(SwitchFrame.STATUS_PROPERTY, activeSwitchStatus)
                .where(__.out(SwitchPropertiesFrame.HAS_BY_EDGE)
                        .hasLabel(SwitchPropertiesFrame.FRAME_LABEL)
                        .has(SwitchPropertiesFrame.SUPPORTED_TRANSIT_ENCAPSULATION_PROPERTY, flowEncapType))
                .outE(IslFrame.FRAME_LABEL)
                .has(IslFrame.STATUS_PROPERTY, activeIslStatus)
                .has(IslFrame.AVAILABLE_BANDWIDTH_PROPERTY, P.gte(requiredBandwidth))
                .where(__.values(IslFrame.SRC_PORT_PROPERTY).as("rsp")
                        .select("forward").values(IslFrame.DST_PORT_PROPERTY).where(P.eq("rsp")))
                .where(__.values(IslFrame.DST_PORT_PROPERTY).as("rdp")
                        .select("forward").values(IslFrame.SRC_PORT_PROPERTY).where(P.eq("rdp")))
                .inV()
                .where(P.eq("source"))
                .select("forward"))
                .toListExplicit(IslFrame.class).stream()
                .map(Isl::new)
                .map(this::addIslConfigToIsl)
                .collect(Collectors.toList());
    }

    private Isl addIslConfigToIsl(Isl isl) {
        isl.setIslConfig(islConfig);
        return isl;
    }

    @Override
    public long updateAvailableBandwidth(SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort,
                                         long usedBandwidth) {
        return transactionManager.doInTransaction(() -> {
            IslFrame isl = findIsl(srcSwitchId, srcPort, dstSwitchId, dstPort)
                    .orElseThrow(() -> new PersistenceException(format("ISL %s_%d - %s_%d not found to be updated",
                            srcSwitchId, srcPort, dstSwitchId, dstPort)));

            long updatedAvailableBandwidth = isl.getMaxBandwidth() - usedBandwidth;
            isl.setAvailableBandwidth(updatedAvailableBandwidth);
            return updatedAvailableBandwidth;
        });
    }

    @Override
    public void add(Isl entity) {
        super.add(entity);
        addIslConfigToIsl(entity);
    }

    @Override
    protected IslFrame doAdd(IslData data) {
        SwitchFrame source = SwitchFrame.load(framedGraph(), data.getSrcSwitchId())
                .orElseThrow(() -> new IllegalArgumentException("Unable to locate the switch "
                        + data.getSrcSwitchId()));
        SwitchFrame destination = SwitchFrame.load(framedGraph(), data.getDestSwitchId())
                .orElseThrow(() -> new IllegalArgumentException("Unable to locate the switch "
                        + data.getDestSwitchId()));
        IslFrame frame = KildaBaseEdgeFrame.addNewFramedEdge(framedGraph(), source, destination,
                IslFrame.FRAME_LABEL, IslFrame.class);
        Isl.IslCloner.INSTANCE.copyWithoutSwitches(data, frame);
        return frame;
    }

    @Override
    protected IslData doRemove(Isl entity, IslFrame frame) {
        IslData data = Isl.IslCloner.INSTANCE.copy(frame);
        frame.remove();
        return data;
    }
}
