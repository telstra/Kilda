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

import org.openkilda.model.FlowMeter;
import org.openkilda.model.FlowMeter.FlowMeterData;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ConstraintViolationException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowMeterFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.converters.MeterIdConverter;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.FlowMeterRepository;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link FlowMeterRepository}.
 */
class FermaFlowMeterRepository extends FermaGenericRepository<FlowMeter, FlowMeterData, FlowMeterFrame>
        implements FlowMeterRepository {
    FermaFlowMeterRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Collection<FlowMeter> findAll() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowMeterFrame.FRAME_LABEL))
                .toListExplicit(FlowMeterFrame.class).stream()
                .map(FlowMeter::new)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<FlowMeter> findByPathId(PathId pathId) {
        return Optional.ofNullable(framedGraph().traverse(g -> g.V()
                .hasLabel(FlowMeterFrame.FRAME_LABEL)
                .has(FlowMeterFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                .nextOrDefaultExplicit(FlowMeterFrame.class, null))
                .map(FlowMeter::new);
    }

    @Override
    public Optional<MeterId> findMaximumAssignedMeter(SwitchId switchId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowMeterFrame.FRAME_LABEL)
                .has(FlowMeterFrame.SWITCH_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .values(FlowMeterFrame.METER_ID_PROPERTY).max())
                .getRawTraversal().tryNext()
                .filter(n -> !(n instanceof Double && ((Double) n).isNaN()))
                .map(l -> MeterIdConverter.INSTANCE.toEntityAttribute((Long) l));
    }

    @Override
    public MeterId findFirstUnassignedMeter(SwitchId switchId, MeterId startMeterId) {
        String switchIdAsStr = SwitchIdConverter.INSTANCE.toGraphProperty(switchId);
        Long startMeterIdAsLong = MeterIdConverter.INSTANCE.toGraphProperty(startMeterId);

        if (!framedGraph().traverse(g -> g.V()
                .hasLabel(FlowMeterFrame.FRAME_LABEL)
                .has(FlowMeterFrame.METER_ID_PROPERTY, startMeterIdAsLong)
                .has(FlowMeterFrame.SWITCH_PROPERTY, switchIdAsStr))
                .getRawTraversal().hasNext()) {
            return startMeterId;
        }

        return framedGraph().traverse(g -> g.V()
                .hasLabel(FlowMeterFrame.FRAME_LABEL)
                .has(FlowMeterFrame.METER_ID_PROPERTY, P.gte(startMeterIdAsLong))
                .has(FlowMeterFrame.SWITCH_PROPERTY, switchIdAsStr)
                .values(FlowMeterFrame.METER_ID_PROPERTY)
                .order().math("_ + 1").as("a")
                .where(__.not(__.V().hasLabel(FlowMeterFrame.FRAME_LABEL)
                        .has(FlowMeterFrame.SWITCH_PROPERTY, switchIdAsStr)
                        .values(FlowMeterFrame.METER_ID_PROPERTY)
                        .where(P.eq("a"))))
                .select("a"))
                .getRawTraversal().tryNext()
                .map(l -> ((Double) l).longValue())
                .map(l -> MeterIdConverter.INSTANCE.toEntityAttribute(l)).orElse(startMeterId);
    }

    @Override
    protected FlowMeterFrame doAdd(FlowMeterData data) {
        if (framedGraph().traverse(input -> input.V()
                .hasLabel(FlowMeterFrame.FRAME_LABEL)
                .or(__.has(FlowMeterFrame.SWITCH_PROPERTY,
                        SwitchIdConverter.INSTANCE.toGraphProperty(data.getSwitchId()))
                                .has(FlowMeterFrame.METER_ID_PROPERTY, data.getMeterId()),
                        __.has(FlowMeterFrame.PATH_ID_PROPERTY,
                                PathIdConverter.INSTANCE.toGraphProperty(data.getPathId()))))
                .getRawTraversal().hasNext()) {
            throw new ConstraintViolationException(
                    format("Unable to create a vertex with duplicated %s (%s) or %s (%s)",
                            FlowMeterFrame.METER_ID_PROPERTY, data.getMeterId(),
                            FlowMeterFrame.PATH_ID_PROPERTY, data.getPathId()));
        }

        FlowMeterFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                FlowMeterFrame.FRAME_LABEL, FlowMeterFrame.class);
        FlowMeter.FlowMeterCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected FlowMeterData doRemove(FlowMeter entity, FlowMeterFrame frame) {
        FlowMeterData data = FlowMeter.FlowMeterCloner.INSTANCE.copy(frame);
        frame.remove();
        return data;
    }
}
