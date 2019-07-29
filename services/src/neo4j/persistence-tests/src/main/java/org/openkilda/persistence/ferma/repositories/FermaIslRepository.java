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

import org.openkilda.model.SwitchId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FermaGraphFactory;
import org.openkilda.persistence.ferma.model.Isl;
import org.openkilda.persistence.ferma.repositories.frames.FlowFrame;
import org.openkilda.persistence.ferma.repositories.frames.IslFrame;
import org.openkilda.persistence.ferma.repositories.frames.SwitchFrame;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Ferma implementation of {@link IslRepository}.
 */
public class FermaIslRepository extends FermaGenericRepository implements IslRepository {
    public FermaIslRepository(FermaGraphFactory txFactory, TransactionManager transactionManager) {
        super(txFactory, transactionManager);
    }

    @Override
    public Collection<Isl> findByEndpoint(SwitchId switchId, int port) {
        return transactionManager.doInTransaction(() -> {
            SwitchFrame switchFrame = SwitchFrame.load(getFramedGraph(), switchId);
            if (switchFrame == null) {
                throw new IllegalArgumentException("Unable to locate the switch " + switchId);
            }
            List<Isl> result = new ArrayList<>();
            switchFrame.traverse(v -> v.outE(IslFrame.FRAME_LABEL).has(FlowFrame.SRC_PORT_PROPERTY, port))
                    .frameExplicit(IslFrame.class)
                    .forEachRemaining(result::add);
            switchFrame.traverse(v -> v.inE(IslFrame.FRAME_LABEL).has(FlowFrame.DST_PORT_PROPERTY, port))
                    .frameExplicit(IslFrame.class)
                    .forEachRemaining(result::add);
            return unmodifiableList(result);
        });
    }

    @Override
    public Collection<Isl> findBySrcEndpoint(SwitchId srcSwitchId, int srcPort) {
        return transactionManager.doInTransaction(() -> {
            SwitchFrame switchFrame = SwitchFrame.load(getFramedGraph(), srcSwitchId);
            if (switchFrame == null) {
                throw new IllegalArgumentException("Unable to locate the switch " + srcSwitchId);
            }
            return unmodifiableList(
                    switchFrame.traverse(v -> v.outE(IslFrame.FRAME_LABEL).has(FlowFrame.SRC_PORT_PROPERTY, srcPort))
                            .toListExplicit(IslFrame.class));
        });
    }

    @Override
    public Collection<Isl> findByDestEndpoint(SwitchId dstSwitchId, int dstPort) {
        return transactionManager.doInTransaction(() -> {
            SwitchFrame switchFrame = SwitchFrame.load(getFramedGraph(), dstSwitchId);
            if (switchFrame == null) {
                throw new IllegalArgumentException("Unable to locate the switch " + dstSwitchId);
            }
            return unmodifiableList(
                    switchFrame.traverse(v -> v.inE(IslFrame.FRAME_LABEL).has(FlowFrame.DST_PORT_PROPERTY, dstPort))
                            .toListExplicit(IslFrame.class));
        });
    }

    @Override
    public Collection<Isl> findBySrcSwitch(SwitchId switchId) {
        return transactionManager.doInTransaction(() -> {
            SwitchFrame switchFrame = SwitchFrame.load(getFramedGraph(), switchId);
            if (switchFrame == null) {
                throw new IllegalArgumentException("Unable to locate the switch " + switchId);
            }
            return unmodifiableList(switchFrame.traverse(v -> v.outE(IslFrame.FRAME_LABEL))
                    .toListExplicit(IslFrame.class));
        });
    }

    @Override
    public Collection<Isl> findByDestSwitch(SwitchId switchId) {
        return transactionManager.doInTransaction(() -> {
            SwitchFrame switchFrame = SwitchFrame.load(getFramedGraph(), switchId);
            if (switchFrame == null) {
                throw new IllegalArgumentException("Unable to locate the switch " + switchId);
            }
            return unmodifiableList(switchFrame.traverse(v -> v.inE(IslFrame.FRAME_LABEL))
                    .toListExplicit(IslFrame.class));
        });
    }

    @Override
    public Optional<Isl> findByEndpoints(SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort) {
        return Optional.ofNullable(IslFrame.load(getFramedGraph(), srcSwitchId, srcPort, dstSwitchId, dstPort));
    }

    @Override
    public IslFrame create(Isl isl) {
        if (isl.getTimeCreate() == null) {
            isl.setTimeCreate(Instant.now());
        }
        isl.setTimeModify(isl.getTimeCreate());

        return transactionManager.doInTransaction(() -> IslFrame.addNew(getFramedGraph(), isl));
    }

    @Override
    public void delete(Isl isl) {
        transactionManager.doInTransaction(() -> IslFrame.delete(getFramedGraph(), isl));
    }
}
