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

import org.openkilda.model.SwitchId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FermaGraphFactory;
import org.openkilda.persistence.ferma.model.Switch;
import org.openkilda.persistence.ferma.repositories.frames.SwitchFrame;

import com.syncleus.ferma.FramedGraph;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

/**
 * Ferma implementation of {@link SwitchRepository}.
 */
public class FermaSwitchRepository extends FermaGenericRepository implements SwitchRepository {
    public FermaSwitchRepository(FermaGraphFactory txFactory, TransactionManager transactionManager) {
        super(txFactory, transactionManager);
    }

    @Override
    public Collection<Switch> findAll() {
        return transactionManager.doInTransaction(() -> Collections.unmodifiableList(
                getFramedGraph().traverse(input -> input.V().hasLabel(SwitchFrame.FRAME_LABEL))
                        .toListExplicit(SwitchFrame.class)));
    }

    @Override
    public boolean exists(SwitchId switchId) {
        return transactionManager.doInTransaction(() -> {
            FramedGraph framedGraph = getFramedGraph();
            return (Long) framedGraph.traverse(input -> input.V().hasLabel(SwitchFrame.FRAME_LABEL)
                    .has(SwitchFrame.SWITCH_ID_PROPERTY, switchId.toString()).count())
                    .getRawTraversal().next() > 0;
        });
    }

    @Override
    public Optional<Switch> findById(SwitchId switchId) {
        return transactionManager.doInTransaction(() ->
                Optional.ofNullable(SwitchFrame.load(getFramedGraph(), switchId)));
    }

    @Override
    public SwitchFrame create(Switch sw) {
        if (sw.getTimeCreate() == null) {
            sw.setTimeCreate(Instant.now());
        }
        sw.setTimeModify(sw.getTimeCreate());

        return transactionManager.doInTransaction(() -> SwitchFrame.addNew(getFramedGraph(), sw));
    }

    @Override
    public void delete(Switch sw) {
        transactionManager.doInTransaction(() -> SwitchFrame.delete(getFramedGraph(), sw));
    }
}
