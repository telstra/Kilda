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

package org.openkilda.persistence.ferma.repositories;

import static java.util.Collections.unmodifiableList;

import org.openkilda.model.FlowStatus;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FermaGraphFactory;
import org.openkilda.persistence.ferma.model.Flow;
import org.openkilda.persistence.ferma.repositories.frames.FlowFrame;
import org.openkilda.persistence.ferma.repositories.frames.SwitchFrame;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Ferma implementation of {@link FlowRepository}.
 */
@Slf4j
public class FermaFlowRepository extends FermaGenericRepository implements FlowRepository {
    public FermaFlowRepository(FermaGraphFactory txFactory, TransactionManager transactionManager) {
        super(txFactory, transactionManager);
    }

    @Override
    public Collection<Flow> findAll() {
        return transactionManager.doInTransaction(() -> unmodifiableList(
                getFramedGraph().traverse(input -> input.V().hasLabel(FlowFrame.FRAME_LABEL))
                        .toListExplicit(FlowFrame.class)));
    }

    @Override
    public long countFlows() {
        return transactionManager.doInTransaction(() ->
                (Long) getFramedGraph().traverse(input -> input.V().hasLabel(FlowFrame.FRAME_LABEL).count())
                        .getRawTraversal().next());
    }

    @Override
    public boolean exists(String flowId) {
        return transactionManager.doInTransaction(() ->
                (Long) getFramedGraph().traverse(input -> input.V().hasLabel(FlowFrame.FRAME_LABEL)
                        .has(FlowFrame.FLOW_ID_PROPERTY, flowId).count())
                        .getRawTraversal().next() > 0);
    }

    @Override
    public Optional<Flow> findById(String flowId) {
        return transactionManager.doInTransaction(() -> Optional.ofNullable(FlowFrame.load(getFramedGraph(), flowId)));
    }

    @Override
    public Collection<Flow> findByGroupId(String flowGroupId) {
        return transactionManager.doInTransaction(() -> unmodifiableList(
                getFramedGraph().traverse(input -> input.V().hasLabel(FlowFrame.FRAME_LABEL)
                        .has(FlowFrame.GROUP_ID_PROPERTY, flowGroupId)).toListExplicit(FlowFrame.class)));
    }

    @Override
    public Collection<String> findFlowsIdByGroupId(String flowGroupId) {
        return transactionManager.doInTransaction(() -> {
            List<String> result =
                    (List<String>) getFramedGraph().traverse(input -> input.V().hasLabel(FlowFrame.FRAME_LABEL)
                            .has(FlowFrame.GROUP_ID_PROPERTY, flowGroupId)
                            .values(FlowFrame.FLOW_ID_PROPERTY)).getRawTraversal().toList();
            return unmodifiableList(result);
        });
    }

    @Override
    public Collection<Flow> findWithPeriodicPingsEnabled() {
        return transactionManager.doInTransaction(() -> unmodifiableList(
                getFramedGraph().traverse(input -> input.V().hasLabel(FlowFrame.FRAME_LABEL)
                        .has(FlowFrame.PERIODIC_PINGS_PROPERTY, true)).toListExplicit(FlowFrame.class)));
    }

    @Override
    public Collection<Flow> findByEndpoint(SwitchId switchId, int port) {
        return transactionManager.doInTransaction(() -> {
            SwitchFrame switchFrame = SwitchFrame.load(getFramedGraph(), switchId);
            if (switchFrame == null) {
                throw new IllegalArgumentException("Unable to locate the switch " + switchId);
            }
            List<Flow> result = new ArrayList<>();
            switchFrame.traverse(v -> v.out(FlowFrame.SOURCE_EDGE).has(FlowFrame.SRC_PORT_PROPERTY, port))
                    .frameExplicit(FlowFrame.class)
                    .forEachRemaining(result::add);
            switchFrame.traverse(v -> v.out(FlowFrame.DESTINATION_EDGE).has(FlowFrame.DST_PORT_PROPERTY, port))
                    .frameExplicit(FlowFrame.class)
                    .forEachRemaining(result::add);
            return unmodifiableList(result);
        });
    }

    @Override
    public Collection<Flow> findByEndpointSwitch(SwitchId switchId) {
        return transactionManager.doInTransaction(() -> {
            SwitchFrame switchFrame = SwitchFrame.load(getFramedGraph(), switchId);
            if (switchFrame == null) {
                throw new IllegalArgumentException("Unable to locate the switch " + switchId);
            }
            List<Flow> result = new ArrayList<>();
            switchFrame.traverse(v -> v.out(FlowFrame.SOURCE_EDGE))
                    .frameExplicit(FlowFrame.class)
                    .forEachRemaining(result::add);
            switchFrame.traverse(v -> v.out(FlowFrame.DESTINATION_EDGE))
                    .frameExplicit(FlowFrame.class)
                    .forEachRemaining(result::add);
            return unmodifiableList(result);
        });
    }

    @Override
    public Collection<Flow> findDownFlows() {
        return transactionManager.doInTransaction(() -> {
            return unmodifiableList(
                    getFramedGraph().traverse(input -> input.V().hasLabel(FlowFrame.FRAME_LABEL)
                            .has(FlowFrame.STATUS_PROPERTY, FlowStatus.DOWN.name().toLowerCase()))
                            .toListExplicit(FlowFrame.class));
        });
    }

    @Override
    public Flow create(Flow flow) {
        if (flow.getTimeCreate() == null) {
            flow.setTimeCreate(Instant.now());
        }
        flow.setTimeModify(flow.getTimeCreate());

        return transactionManager.doInTransaction(() -> FlowFrame.addNew(getFramedGraph(), flow));
    }

    @Override
    public void delete(Flow flow) {
        transactionManager.doInTransaction(() -> FlowFrame.delete(getFramedGraph(), flow));
    }

    @Override
    public void updateStatus(@NonNull String flowId, @NonNull FlowStatus flowStatus) {
        transactionManager.doInTransaction(() ->
                getFramedGraph().traverse(input -> input.V().hasLabel(FlowFrame.FRAME_LABEL)
                        .has(FlowFrame.FLOW_ID_PROPERTY, flowId)
                        .property(FlowFrame.STATUS_PROPERTY, flowStatus.name().toLowerCase())));
    }
}
