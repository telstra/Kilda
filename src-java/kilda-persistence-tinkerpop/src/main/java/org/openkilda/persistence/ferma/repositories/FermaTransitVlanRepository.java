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

import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.TransitVlan;
import org.openkilda.model.TransitVlan.TransitVlanData;
import org.openkilda.persistence.ConstraintViolationException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.TransitVlanFrame;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.repositories.TransitVlanRepository;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link TransitVlanRepository}.
 */
class FermaTransitVlanRepository extends FermaGenericRepository<TransitVlan, TransitVlanData, TransitVlanFrame>
        implements TransitVlanRepository {
    FermaTransitVlanRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Collection<TransitVlan> findAll() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(TransitVlanFrame.FRAME_LABEL))
                .toListExplicit(TransitVlanFrame.class).stream()
                .map(TransitVlan::new)
                .collect(Collectors.toList());
    }

    /**
     * Lookup for {@link FlowPath} object by pathId (or opposite pathId) value.
     *
     * <p>It make lookup by pathId first and if there is no result it make lookup by {@code oppositePathId}. Such
     * weird logic allow to support both kind of flows(first kind - each path have it's own transit vlan, second
     * kind - only one path have transit vlan, but both of them use it).
     */
    @Override
    public Collection<TransitVlan> findByPathId(PathId pathId, PathId oppositePathId) {
        List<? extends TransitVlanFrame> frames =
                framedGraph().traverse(g -> g.V()
                        .hasLabel(TransitVlanFrame.FRAME_LABEL)
                        .has(TransitVlanFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                        .toListExplicit(TransitVlanFrame.class);
        if (frames.isEmpty() && oppositePathId != null) {
            frames = framedGraph().traverse(g -> g.V()
                    .hasLabel(TransitVlanFrame.FRAME_LABEL)
                    .has(TransitVlanFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(oppositePathId)))
                    .toListExplicit(TransitVlanFrame.class);
        }
        return frames.stream()
                .map(TransitVlan::new)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<TransitVlan> findByPathId(PathId pathId) {
        return Optional.ofNullable(framedGraph().traverse(g -> g.V()
                .hasLabel(TransitVlanFrame.FRAME_LABEL)
                .has(TransitVlanFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                .nextOrDefaultExplicit(TransitVlanFrame.class, null))
                .map(TransitVlan::new);
    }

    @Override
    public Optional<TransitVlan> findByVlan(int vlan) {
        return Optional.ofNullable(framedGraph().traverse(g -> g.V()
                .hasLabel(TransitVlanFrame.FRAME_LABEL)
                .has(TransitVlanFrame.VLAN_PROPERTY, vlan))
                .nextOrDefaultExplicit(TransitVlanFrame.class, null))
                .map(TransitVlan::new);
    }

    @Override
    public Optional<Integer> findMaximumAssignedVlan() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(TransitVlanFrame.FRAME_LABEL)
                .values(TransitVlanFrame.VLAN_PROPERTY).max())
                .getRawTraversal().tryNext()
                .filter(n -> !(n instanceof Double && ((Double) n).isNaN()))
                .map(l -> l instanceof Integer ? (Integer) l : ((Long) l).intValue());
    }

    @Override
    public int findFirstUnassignedVlan(int startTransitVlan) {
        if (!framedGraph().traverse(g -> g.V()
                .hasLabel(TransitVlanFrame.FRAME_LABEL)
                .has(TransitVlanFrame.VLAN_PROPERTY, startTransitVlan))
                .getRawTraversal().hasNext()) {
            return startTransitVlan;
        }

        return framedGraph().traverse(g -> g.V()
                .hasLabel(TransitVlanFrame.FRAME_LABEL)
                .has(TransitVlanFrame.VLAN_PROPERTY, P.gte(startTransitVlan))
                .values(TransitVlanFrame.VLAN_PROPERTY)
                .order().math("_ + 1").as("a")
                .where(__.not(__.V().hasLabel(TransitVlanFrame.FRAME_LABEL)
                        .values(TransitVlanFrame.VLAN_PROPERTY)
                        .where(P.eq("a"))))
                .select("a"))
                .getRawTraversal().tryNext()
                .map(l -> ((Double) l).intValue()).orElse(startTransitVlan);
    }

    @Override
    protected TransitVlanFrame doAdd(TransitVlanData data) {
        if (framedGraph().traverse(input -> input.V()
                .hasLabel(TransitVlanFrame.FRAME_LABEL)
                .has(TransitVlanFrame.VLAN_PROPERTY, data.getVlan()))
                .getRawTraversal().hasNext()) {
            throw new ConstraintViolationException("Unable to create a vertex with duplicated "
                    + TransitVlanFrame.VLAN_PROPERTY);
        }

        TransitVlanFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                TransitVlanFrame.FRAME_LABEL, TransitVlanFrame.class);
        TransitVlan.TransitVlanCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected TransitVlanData doRemove(TransitVlan entity, TransitVlanFrame frame) {
        TransitVlanData data = TransitVlan.TransitVlanCloner.INSTANCE.copy(frame);
        frame.remove();
        return data;
    }
}
