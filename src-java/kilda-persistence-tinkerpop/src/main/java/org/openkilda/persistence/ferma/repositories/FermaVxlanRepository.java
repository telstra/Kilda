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

import org.openkilda.model.PathId;
import org.openkilda.model.Vxlan;
import org.openkilda.model.Vxlan.VxlanData;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.VxlanFrame;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.repositories.VxlanRepository;
import org.openkilda.persistence.tx.TransactionManager;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link VxlanRepository}.
 */
public class FermaVxlanRepository extends FermaGenericRepository<Vxlan, VxlanData, VxlanFrame>
        implements VxlanRepository {
    public FermaVxlanRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Collection<Vxlan> findAll() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(VxlanFrame.FRAME_LABEL))
                .toListExplicit(VxlanFrame.class).stream()
                .map(Vxlan::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<Vxlan> findByPathId(PathId pathId, PathId oppositePathId) {
        List<? extends VxlanFrame> frames = framedGraph().traverse(g -> g.V()
                .hasLabel(VxlanFrame.FRAME_LABEL)
                .has(VxlanFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                .toListExplicit(VxlanFrame.class);
        if (frames.isEmpty() && oppositePathId != null) {
            frames = framedGraph().traverse(g -> g.V()
                    .hasLabel(VxlanFrame.FRAME_LABEL)
                    .has(VxlanFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(oppositePathId)))
                    .toListExplicit(VxlanFrame.class);
        }
        return frames.stream()
                .map(Vxlan::new)
                .collect(Collectors.toList());
    }

    @Override
    public boolean exists(int vxlan) {
        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(VxlanFrame.FRAME_LABEL)
                .has(VxlanFrame.VNI_PROPERTY, vxlan))
                .getRawTraversal()) {
            return traversal.hasNext();
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }
    }

    @Override
    public Optional<Integer> findFirstUnassignedVxlan(int lowestVxlan, int highestVxlan) {
        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(VxlanFrame.FRAME_LABEL)
                .has(VxlanFrame.VNI_PROPERTY, P.gte(lowestVxlan))
                .has(VxlanFrame.VNI_PROPERTY, P.lt(highestVxlan))
                .values(VxlanFrame.VNI_PROPERTY)
                .order().math("_ + 1").as("a")
                .where(__.not(__.V().hasLabel(VxlanFrame.FRAME_LABEL)
                        .values(VxlanFrame.VNI_PROPERTY)
                        .where(P.eq("a"))))
                .select("a")
                .limit(1))
                .getRawTraversal()) {
            if (traversal.hasNext()) {
                return traversal.tryNext()
                        .map(l -> ((Double) l).intValue());
            }
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }

        try (GraphTraversal<?, ?> traversal = framedGraph().traverse(g -> g.V()
                .hasLabel(VxlanFrame.FRAME_LABEL)
                .has(VxlanFrame.VNI_PROPERTY, lowestVxlan))
                .getRawTraversal()) {
            if (!traversal.hasNext()) {
                return Optional.of(lowestVxlan);
            }
        } catch (Exception e) {
            throw new PersistenceException("Failed to traverse", e);
        }

        return Optional.empty();
    }

    @Override
    protected VxlanFrame doAdd(VxlanData data) {
        VxlanFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(), VxlanFrame.FRAME_LABEL,
                VxlanFrame.class);
        Vxlan.VxlanCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(VxlanFrame frame) {
        frame.remove();
    }

    @Override
    protected VxlanData doDetach(Vxlan entity, VxlanFrame frame) {
        return Vxlan.VxlanCloner.INSTANCE.deepCopy(frame);
    }
}
