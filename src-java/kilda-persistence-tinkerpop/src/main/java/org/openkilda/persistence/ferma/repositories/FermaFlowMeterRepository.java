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

import org.openkilda.model.FlowMeter;
import org.openkilda.model.FlowMeter.FlowMeterData;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowMeterFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.converters.MeterIdConverter;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.FlowMeterRepository;
import org.openkilda.persistence.tx.TransactionManager;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link FlowMeterRepository}.
 */
public class FermaFlowMeterRepository extends FermaGenericRepository<FlowMeter, FlowMeterData, FlowMeterFrame>
        implements FlowMeterRepository {
    public FermaFlowMeterRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
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
        List<? extends FlowMeterFrame> flowMeterFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowMeterFrame.FRAME_LABEL)
                .has(FlowMeterFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                .toListExplicit(FlowMeterFrame.class);
        return flowMeterFrames.isEmpty() ? Optional.empty() : Optional.of(flowMeterFrames.get(0))
                .map(FlowMeter::new);
    }

    @Override
    public boolean exists(SwitchId switchId, MeterId meterId) {
        String switchIdAsStr = SwitchIdConverter.INSTANCE.toGraphProperty(switchId);
        Long meterIdAsLong = MeterIdConverter.INSTANCE.toGraphProperty(meterId);

        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowMeterFrame.FRAME_LABEL)
                .has(FlowMeterFrame.METER_ID_PROPERTY, meterIdAsLong)
                .has(FlowMeterFrame.SWITCH_PROPERTY, switchIdAsStr))
                .getRawTraversal()) {
            return traversal.hasNext();
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }
    }


    @Override
    public Optional<MeterId> findFirstUnassignedMeter(SwitchId switchId, MeterId lowestMeterId,
                                                      MeterId highestMeterId) {
        String switchIdAsStr = SwitchIdConverter.INSTANCE.toGraphProperty(switchId);
        Long lowestMeterIdAsLong = MeterIdConverter.INSTANCE.toGraphProperty(lowestMeterId);
        Long highestMeterIdAsLong = MeterIdConverter.INSTANCE.toGraphProperty(highestMeterId);

        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowMeterFrame.FRAME_LABEL)
                .has(FlowMeterFrame.METER_ID_PROPERTY, P.gte(lowestMeterIdAsLong))
                .has(FlowMeterFrame.METER_ID_PROPERTY, P.lt(highestMeterIdAsLong))
                .has(FlowMeterFrame.SWITCH_PROPERTY, switchIdAsStr)
                .values(FlowMeterFrame.METER_ID_PROPERTY)
                .order().math("_ + 1").as("a")
                .where(__.not(__.V().hasLabel(FlowMeterFrame.FRAME_LABEL)
                        .has(FlowMeterFrame.SWITCH_PROPERTY, switchIdAsStr)
                        .values(FlowMeterFrame.METER_ID_PROPERTY)
                        .where(P.eq("a"))))
                .select("a")
                .limit(1))
                .getRawTraversal()) {
            if (traversal.hasNext()) {
                return traversal.tryNext()
                        .map(l -> ((Double) l).longValue())
                        .map(MeterIdConverter.INSTANCE::toEntityAttribute);
            }
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }

        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowMeterFrame.FRAME_LABEL)
                .has(FlowMeterFrame.METER_ID_PROPERTY, lowestMeterIdAsLong)
                .has(FlowMeterFrame.SWITCH_PROPERTY, switchIdAsStr))
                .getRawTraversal()) {
            if (!traversal.hasNext()) {
                return Optional.of(lowestMeterId);
            }
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }

        return Optional.empty();
    }

    @Override
    protected FlowMeterFrame doAdd(FlowMeterData data) {
        FlowMeterFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                FlowMeterFrame.FRAME_LABEL, FlowMeterFrame.class);
        FlowMeter.FlowMeterCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(FlowMeterFrame frame) {
        frame.remove();
    }

    @Override
    protected FlowMeterData doDetach(FlowMeter entity, FlowMeterFrame frame) {
        return FlowMeter.FlowMeterCloner.INSTANCE.deepCopy(frame);
    }
}
