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

import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.PathSegment.PathSegmentData;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowPathFrame;
import org.openkilda.persistence.ferma.frames.PathSegmentFrame;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.repositories.PathSegmentRepository;

import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;

/**
 * Ferma (Tinkerpop) implementation of {@link PathSegmentRepository}.
 */
class FermaPathSegmentRepository extends FermaGenericRepository<PathSegment, PathSegmentData, PathSegmentFrame>
        implements PathSegmentRepository {
    FermaPathSegmentRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public void updateFailedStatus(PathId pathId, PathSegment segment, boolean failed) {
        PathSegment.PathSegmentData data = segment.getData();
        transactionManager.doInTransaction(() -> {
            PathSegmentFrame segmentFrame;
            if (data instanceof PathSegmentFrame) {
                segmentFrame = (PathSegmentFrame) data;
            } else {
                FlowPathFrame pathFrame = framedGraph().traverse(g -> g.V()
                        .hasLabel(FlowPathFrame.FRAME_LABEL)
                        .has(FlowPathFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                        .nextOrDefaultExplicit(FlowPathFrame.class, null);
                if (pathFrame == null) {
                    throw new IllegalArgumentException("Unable to locate the path " + pathId);
                }
                segmentFrame = (PathSegmentFrame) pathFrame.getSegments().stream()
                        .filter(pathSegment -> pathSegment.getSrcSwitchId().equals(segment.getSrcSwitchId())
                                && pathSegment.getSrcPort() == segment.getSrcPort()
                                && pathSegment.getDestSwitchId().equals(segment.getDestSwitchId())
                                && pathSegment.getDestPort() == segment.getDestPort())
                        .findAny()
                        .map(PathSegment::getData).orElse(null);
            }

            if (segmentFrame == null) {
                throw new PersistenceException(
                        format("PathSegment not found to be updated: %s_%d - %s_%d. Path id: %s.",
                                segment.getSrcSwitchId(), segment.getSrcPort(),
                                segment.getDestSwitchId(), segment.getDestPort(), pathId));
            }

            segmentFrame.setFailed(failed);
        });
    }

    @Override
    protected PathSegmentFrame doAdd(PathSegmentData data) {
        return PathSegmentFrame.create(framedGraph(), data);
    }

    @Override
    protected PathSegmentData doRemove(PathSegment entity, PathSegmentFrame frame) {
        PathSegmentData data = PathSegment.PathSegmentCloner.INSTANCE.copy(frame);
        frame.getElement().edges(Direction.BOTH).forEachRemaining(Edge::remove);
        frame.remove();
        return data;
    }
}
