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

package org.openkilda.persistence.orientdb.repositories;

import static java.lang.String.format;

import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.IslConfig;
import org.openkilda.model.IslStatus;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.ferma.frames.IslFrame;
import org.openkilda.persistence.ferma.frames.PathSegmentFrame;
import org.openkilda.persistence.ferma.frames.SwitchFrame;
import org.openkilda.persistence.ferma.frames.SwitchPropertiesFrame;
import org.openkilda.persistence.ferma.frames.converters.FlowEncapsulationTypeConverter;
import org.openkilda.persistence.ferma.frames.converters.InstantStringConverter;
import org.openkilda.persistence.ferma.frames.converters.IslStatusConverter;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchStatusConverter;
import org.openkilda.persistence.ferma.repositories.FermaFlowPathRepository;
import org.openkilda.persistence.ferma.repositories.FermaIslRepository;
import org.openkilda.persistence.orientdb.OrientDbGraphFactory;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.tx.TransactionManager;

import lombok.extern.slf4j.Slf4j;
import org.apache.tinkerpop.gremlin.orientdb.OrientGraph;
import org.apache.tinkerpop.gremlin.orientdb.executor.OGremlinResult;
import org.apache.tinkerpop.gremlin.orientdb.executor.OGremlinResultSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OrientDB implementation of {@link IslRepository}.
 */
@Slf4j
public class OrientDbIslRepository extends FermaIslRepository {
    private static final String QUERY_FETCH_SEGMENTS_BY_PATH =
            format("SELECT %s, %s, %s, %s, %s FROM %s WHERE %s = ?",
                    PathSegmentFrame.SRC_SWITCH_ID_PROPERTY, PathSegmentFrame.SRC_PORT_PROPERTY,
                    PathSegmentFrame.DST_SWITCH_ID_PROPERTY, PathSegmentFrame.DST_PORT_PROPERTY,
                    PathSegmentFrame.BANDWIDTH_PROPERTY,
                    PathSegmentFrame.FRAME_LABEL, PathSegmentFrame.PATH_ID_PROPERTY);

    private static final String ISL_VIEW_FIELDS = String.join(", ",
            IslFrame.SRC_SWITCH_ID_PROPERTY, IslFrame.SRC_PORT_PROPERTY,
            IslFrame.DST_SWITCH_ID_PROPERTY, IslFrame.DST_PORT_PROPERTY,
            IslFrame.LATENCY_PROPERTY, IslFrame.COST_PROPERTY,
            IslFrame.AVAILABLE_BANDWIDTH_PROPERTY,
            IslFrame.UNDER_MAINTENANCE_PROPERTY,
            IslFrame.TIME_UNSTABLE_PROPERTY);

    private static final String QUERY_FETCH_ISLS_BY_ENDPOINTS_AND_BANDWIDTH =
            format("SELECT %s FROM %s WHERE %s = ? AND %s = ? AND %s = ? AND %s = ? AND %s = ? AND %s >= ?",
                    ISL_VIEW_FIELDS, IslFrame.FRAME_LABEL,
                    IslFrame.SRC_SWITCH_ID_PROPERTY, IslFrame.SRC_PORT_PROPERTY,
                    IslFrame.DST_SWITCH_ID_PROPERTY, IslFrame.DST_PORT_PROPERTY,
                    IslFrame.STATUS_PROPERTY, IslFrame.AVAILABLE_BANDWIDTH_PROPERTY);

    private static final String QUERY_FETCH_ISLS_BY_STATUS =
            format("SELECT %s FROM %s WHERE %s = ?",
                    ISL_VIEW_FIELDS, IslFrame.FRAME_LABEL, IslFrame.STATUS_PROPERTY);

    private static final String QUERY_FETCH_ISLS_BY_STATUS_AND_BANDWIDTH =
            format("SELECT %s FROM %s WHERE %s = ? AND %s >= ?",
                    ISL_VIEW_FIELDS, IslFrame.FRAME_LABEL,
                    IslFrame.STATUS_PROPERTY, IslFrame.AVAILABLE_BANDWIDTH_PROPERTY);

    private static final String QUERY_FETCH_SWITCHES_BY_STATUS =
            format("SELECT %s, %s FROM %s WHERE %s = ?",
                    SwitchFrame.SWITCH_ID_PROPERTY, SwitchFrame.POP_PROPERTY,
                    SwitchFrame.FRAME_LABEL, SwitchFrame.STATUS_PROPERTY);

    private static final String QUERY_FETCH_SWITCHES_BY_STATUS_AND_ENCAPSULATION =
            format("SELECT %s, %s FROM %s WHERE %s = ? AND out('%s').%s CONTAINS ?",
                    SwitchFrame.SWITCH_ID_PROPERTY, SwitchFrame.POP_PROPERTY,
                    SwitchFrame.FRAME_LABEL, SwitchFrame.STATUS_PROPERTY,
                    SwitchPropertiesFrame.HAS_BY_EDGE,
                    SwitchPropertiesFrame.SUPPORTED_TRANSIT_ENCAPSULATION_PROPERTY);

    private final OrientDbGraphFactory orientDbGraphFactory;

    public OrientDbIslRepository(OrientDbGraphFactory orientDbGraphFactory,
                                 TransactionManager transactionManager, FermaFlowPathRepository flowPathRepository,
                                 IslConfig islConfig) {
        super(orientDbGraphFactory, transactionManager, flowPathRepository, islConfig);
        this.orientDbGraphFactory = orientDbGraphFactory;
    }

    @Override
    public boolean existsByEndpoint(SwitchId switchId, int port) {
        String switchIdAsStr = SwitchIdConverter.INSTANCE.toGraphProperty(switchId);
        try (OGremlinResultSet results = orientDbGraphFactory.getOrientGraph().querySql(
                format("SELECT @rid FROM %s WHERE (%s = ? AND %s = ?) OR (%s = ? AND %s = ?) LIMIT 1",
                        IslFrame.FRAME_LABEL, IslFrame.SRC_SWITCH_ID_PROPERTY, IslFrame.SRC_PORT_PROPERTY,
                        IslFrame.DST_SWITCH_ID_PROPERTY, IslFrame.DST_PORT_PROPERTY),
                switchIdAsStr, port, switchIdAsStr, port)) {
            return results.iterator().hasNext();
        }
    }

    @Override
    public Collection<IslImmutableView> findActiveByPathAndBandwidthAndEncapsulationType(
            PathId pathId, long requiredBandwidth, FlowEncapsulationType flowEncapsulationType) {
        Map<String, String> switches = findActiveSwitchesAndPopByEncapsulationType(flowEncapsulationType);

        Map<IslEndpoints, Long> segments = new HashMap<>();
        try (OGremlinResultSet results = orientDbGraphFactory.getOrientGraph().querySql(
                QUERY_FETCH_SEGMENTS_BY_PATH,
                PathIdConverter.INSTANCE.toGraphProperty(pathId))) {
            results.forEach(gs -> {
                String srcSwitch = gs.getProperty(PathSegmentFrame.SRC_SWITCH_ID_PROPERTY);
                String dstSwitch = gs.getProperty(PathSegmentFrame.DST_SWITCH_ID_PROPERTY);
                if (switches.containsKey(srcSwitch) && switches.containsKey(dstSwitch)) {
                    segments.put(new IslEndpoints(srcSwitch, gs.getProperty(PathSegmentFrame.SRC_PORT_PROPERTY),
                                    dstSwitch, gs.getProperty(PathSegmentFrame.DST_PORT_PROPERTY)),
                            gs.getProperty(PathSegmentFrame.BANDWIDTH_PROPERTY));
                }
            });
        }

        String islStatusAsStr = IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE);
        OrientGraph orientGraph = orientDbGraphFactory.getOrientGraph();

        List<IslImmutableView> isls = new ArrayList<>(segments.size());
        segments.keySet().forEach(endpoint -> {
            try (OGremlinResultSet results = orientGraph.querySql(
                    QUERY_FETCH_ISLS_BY_ENDPOINTS_AND_BANDWIDTH,
                    endpoint.getSrcSwitch(), endpoint.getSrcPort(), endpoint.getDestSwitch(), endpoint.getDestPort(),
                    islStatusAsStr, requiredBandwidth - segments.get(endpoint))) {
                results.forEach(gs -> isls.add(mapToIslView(gs,
                        switches.get(endpoint.getSrcSwitch()), switches.get(endpoint.getDestSwitch()))));
            }
        });
        return isls;
    }

    @Override
    public Collection<IslImmutableView> findAllActive() {
        Map<String, String> switches = findActiveSwitchesAndPop();

        List<IslImmutableView> isls = new ArrayList<>();
        try (OGremlinResultSet results = orientDbGraphFactory.getOrientGraph().querySql(
                QUERY_FETCH_ISLS_BY_STATUS,
                IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE))) {
            results.forEach(gs -> {
                String srcSwitch = gs.getProperty(IslFrame.SRC_SWITCH_ID_PROPERTY);
                String dstSwitch = gs.getProperty(IslFrame.DST_SWITCH_ID_PROPERTY);
                if (switches.containsKey(srcSwitch) && switches.containsKey(dstSwitch)) {
                    isls.add(mapToIslView(gs, switches.get(srcSwitch), switches.get(dstSwitch)));
                }
            });
        }
        return isls;
    }

    @Override
    public Collection<IslImmutableView> findActiveByEncapsulationType(FlowEncapsulationType flowEncapsulationType) {
        Map<String, String> switches = findActiveSwitchesAndPopByEncapsulationType(flowEncapsulationType);

        List<IslImmutableView> isls = new ArrayList<>();
        try (OGremlinResultSet results = orientDbGraphFactory.getOrientGraph().querySql(
                QUERY_FETCH_ISLS_BY_STATUS,
                IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE))) {
            results.forEach(gs -> {
                String srcSwitch = gs.getProperty(IslFrame.SRC_SWITCH_ID_PROPERTY);
                String dstSwitch = gs.getProperty(IslFrame.DST_SWITCH_ID_PROPERTY);
                if (switches.containsKey(srcSwitch) && switches.containsKey(dstSwitch)) {
                    isls.add(mapToIslView(gs, switches.get(srcSwitch), switches.get(dstSwitch)));
                }
            });
        }
        return isls;
    }

    @Override
    protected Map<IslEndpoints, IslImmutableView> findActiveByAvailableBandwidthAndEncapsulationType(
            long requiredBandwidth, FlowEncapsulationType flowEncapsulationType) {
        Map<String, String> switches = findActiveSwitchesAndPopByEncapsulationType(flowEncapsulationType);

        String islStatusAsStr = IslStatusConverter.INSTANCE.toGraphProperty(IslStatus.ACTIVE);

        Map<IslEndpoints, IslImmutableView> isls = new HashMap<>();
        try (OGremlinResultSet results = orientDbGraphFactory.getOrientGraph().querySql(
                QUERY_FETCH_ISLS_BY_STATUS_AND_BANDWIDTH,
                islStatusAsStr, requiredBandwidth)) {
            results.forEach(gs -> {
                String srcSwitch = gs.getProperty(IslFrame.SRC_SWITCH_ID_PROPERTY);
                String dstSwitch = gs.getProperty(IslFrame.DST_SWITCH_ID_PROPERTY);
                if (switches.containsKey(srcSwitch) && switches.containsKey(dstSwitch)) {
                    IslEndpoints key = new IslEndpoints(srcSwitch, gs.getProperty(IslFrame.SRC_PORT_PROPERTY),
                            dstSwitch, gs.getProperty(IslFrame.DST_PORT_PROPERTY));
                    isls.put(key, mapToIslView(gs, switches.get(srcSwitch), switches.get(dstSwitch)));
                }
            });
        }
        return isls;
    }

    private IslImmutableView mapToIslView(OGremlinResult gs, String srcPop, String dstPop) {
        return new IslViewImpl(
                SwitchIdConverter.INSTANCE.toEntityAttribute(gs.getProperty(IslFrame.SRC_SWITCH_ID_PROPERTY)),
                gs.getProperty(IslFrame.SRC_PORT_PROPERTY),
                srcPop,
                SwitchIdConverter.INSTANCE.toEntityAttribute(gs.getProperty(IslFrame.DST_SWITCH_ID_PROPERTY)),
                gs.getProperty(IslFrame.DST_PORT_PROPERTY),
                dstPop,
                gs.getProperty(IslFrame.LATENCY_PROPERTY),
                gs.getProperty(IslFrame.COST_PROPERTY),
                gs.getProperty(IslFrame.AVAILABLE_BANDWIDTH_PROPERTY),
                gs.getProperty(IslFrame.UNDER_MAINTENANCE_PROPERTY),
                InstantStringConverter.INSTANCE.toEntityAttribute(gs.getProperty(IslFrame.TIME_UNSTABLE_PROPERTY)),
                islConfig);
    }

    private Map<String, String> findActiveSwitchesAndPop() {
        String switchStatusAsStr = SwitchStatusConverter.INSTANCE.toGraphProperty(SwitchStatus.ACTIVE);

        Map<String, String> switches = new HashMap<>();
        try (OGremlinResultSet results = orientDbGraphFactory.getOrientGraph().querySql(
                QUERY_FETCH_SWITCHES_BY_STATUS,
                switchStatusAsStr)) {
            results.forEach(gs ->
                    switches.put(gs.getProperty(SwitchFrame.SWITCH_ID_PROPERTY),
                            gs.getProperty(SwitchFrame.POP_PROPERTY)));
        }
        return switches;
    }

    private Map<String, String> findActiveSwitchesAndPopByEncapsulationType(
            FlowEncapsulationType flowEncapsulationType) {
        String switchStatusAsStr = SwitchStatusConverter.INSTANCE.toGraphProperty(SwitchStatus.ACTIVE);
        String flowEncapType = FlowEncapsulationTypeConverter.INSTANCE.toGraphProperty(flowEncapsulationType);

        Map<String, String> switches = new HashMap<>();
        try (OGremlinResultSet results = orientDbGraphFactory.getOrientGraph().querySql(
                QUERY_FETCH_SWITCHES_BY_STATUS_AND_ENCAPSULATION,
                switchStatusAsStr, flowEncapType)) {
            results.forEach(gs ->
                    switches.put(gs.getProperty(SwitchFrame.SWITCH_ID_PROPERTY),
                            gs.getProperty(SwitchFrame.POP_PROPERTY)));
        }
        return switches;
    }
}
