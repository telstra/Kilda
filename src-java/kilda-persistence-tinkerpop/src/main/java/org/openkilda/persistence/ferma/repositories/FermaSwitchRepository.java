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

import static java.util.Collections.emptyList;

import org.openkilda.model.Switch;
import org.openkilda.model.Switch.SwitchData;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowPathFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.SwitchFrame;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchStatusConverter;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.tx.TransactionManager;
import org.openkilda.persistence.tx.TransactionRequired;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Direction;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ferma (Tinkerpop) implementation of {@link SwitchRepository}.
 */
public class FermaSwitchRepository extends FermaGenericRepository<Switch, SwitchData, SwitchFrame>
        implements SwitchRepository {
    public FermaSwitchRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Collection<Switch> findAll() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(SwitchFrame.FRAME_LABEL))
                .toListExplicit(SwitchFrame.class).stream()
                .map(Switch::new)
                .collect(Collectors.toList());
    }

    @Override
    public boolean exists(SwitchId switchId) {
        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .has(SwitchFrame.SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .getRawTraversal()) {
            return traversal.hasNext();
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }
    }

    @Override
    public Collection<Switch> findActive() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(SwitchFrame.FRAME_LABEL)
                .has(SwitchFrame.STATUS_PROPERTY, SwitchStatusConverter.INSTANCE.toGraphProperty(SwitchStatus.ACTIVE)))
                .toListExplicit(SwitchFrame.class).stream()
                .map(Switch::new)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Switch> findById(SwitchId switchId) {
        return SwitchFrame.load(framedGraph(), SwitchIdConverter.INSTANCE.toGraphProperty(switchId)).map(Switch::new);
    }

    @Override
    public Collection<Switch> findSwitchesInFlowPathByFlowId(String flowId) {
        List<? extends FlowPathFrame> flowPathFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(FlowPathFrame.FRAME_LABEL)
                .has(FlowPathFrame.FLOW_ID_PROPERTY, flowId))
                .toListExplicit(FlowPathFrame.class);
        if (flowPathFrames.isEmpty()) {
            return emptyList();
        }
        Map<SwitchId, Switch> result = new HashMap<>();
        flowPathFrames.forEach(flowPath -> {
            Stream.of(flowPath.getSrcSwitch(), flowPath.getDestSwitch())
                    .forEach(sw -> result.put(sw.getSwitchId(), sw));
            flowPath.getSegments().forEach(pathSegment -> {
                Stream.of(pathSegment.getSrcSwitchId(), pathSegment.getDestSwitchId())
                        .map(this::findById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .forEach(sw -> result.put(sw.getSwitchId(), sw));
            });
        });
        return result.values();
    }

    @Override
    @TransactionRequired
    public boolean removeIfNoDependant(Switch entity) {
        SwitchData data = entity.getData();
        if (data instanceof SwitchFrame) {
            if (!((SwitchFrame) data).getElement().edges(Direction.BOTH).hasNext()) {
                ((SwitchFrame) data).remove();
                return true;
            }
            return false;
        } else {
            throw new IllegalArgumentException("Can't delete object " + entity + " which is not framed graph element");
        }
    }

    @Override
    protected SwitchFrame doAdd(SwitchData data) {
        SwitchFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(), SwitchFrame.FRAME_LABEL,
                SwitchFrame.class);
        Switch.SwitchCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(SwitchFrame frame) {
        frame.remove();
    }

    @Override
    protected SwitchData doDetach(Switch entity, SwitchFrame frame) {
        return Switch.SwitchCloner.INSTANCE.deepCopy(frame);
    }
}
