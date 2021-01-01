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

import org.openkilda.model.LinkProps;
import org.openkilda.model.LinkProps.LinkPropsData;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.LinkPropsFrame;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.LinkPropsRepository;
import org.openkilda.persistence.tx.TransactionManager;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link LinkPropsRepository}.
 */
public class FermaLinkPropsRepository extends FermaGenericRepository<LinkProps, LinkPropsData, LinkPropsFrame>
        implements LinkPropsRepository {
    public FermaLinkPropsRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Collection<LinkProps> findAll() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(LinkPropsFrame.FRAME_LABEL))
                .toListExplicit(LinkPropsFrame.class).stream()
                .map(LinkProps::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<LinkProps> findByEndpoints(SwitchId srcSwitch, Integer srcPort,
                                                 SwitchId dstSwitch, Integer dstPort) {
        return framedGraph().traverse(g -> {
            GraphTraversal<Vertex, Vertex> traversal = g.V()
                    .hasLabel(LinkPropsFrame.FRAME_LABEL);
            if (srcSwitch != null) {
                traversal = traversal.has(LinkPropsFrame.SRC_SWITCH_PROPERTY,
                        SwitchIdConverter.INSTANCE.toGraphProperty(srcSwitch));
            }
            if (srcPort != null) {
                traversal = traversal.has(LinkPropsFrame.SRC_PORT_PROPERTY, srcPort);
            }
            if (dstSwitch != null) {
                traversal = traversal.has(LinkPropsFrame.DST_SWITCH_PROPERTY,
                        SwitchIdConverter.INSTANCE.toGraphProperty(dstSwitch));
            }
            if (dstPort != null) {
                traversal = traversal.has(LinkPropsFrame.DST_PORT_PROPERTY, dstPort);
            }
            return traversal;
        }).toListExplicit(LinkPropsFrame.class).stream()
                .map(LinkProps::new)
                .collect(Collectors.toList());
    }

    @Override
    protected LinkPropsFrame doAdd(LinkPropsData data) {
        LinkPropsFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                LinkPropsFrame.FRAME_LABEL, LinkPropsFrame.class);
        LinkProps.LinkPropsCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(LinkPropsFrame frame) {
        frame.remove();
    }

    @Override
    protected LinkPropsData doDetach(LinkProps entity, LinkPropsFrame frame) {
        return LinkProps.LinkPropsCloner.INSTANCE.deepCopy(frame);
    }
}
