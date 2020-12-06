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

import org.openkilda.model.FlowPath;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.PathSegment.PathSegmentData;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.PathSegmentFrame;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.PathSegmentRepository;
import org.openkilda.persistence.tx.TransactionManager;
import org.openkilda.persistence.tx.TransactionRequired;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link PathSegmentRepository}.
 */
public class FermaPathSegmentRepository extends FermaGenericRepository<PathSegment, PathSegmentData, PathSegmentFrame>
        implements PathSegmentRepository {
    protected final IslRepository islRepository;

    protected FermaPathSegmentRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager,
                                         IslRepository islRepository) {
        super(graphFactory, transactionManager);
        this.islRepository = islRepository;
    }

    @Override
    @TransactionRequired
    public void updateFailedStatus(FlowPath path, PathSegment segment, boolean failed) {
        PathSegment segmentToUpdate;
        if (segment.getData() instanceof PathSegmentFrame) {
            segmentToUpdate = segment;
        } else {
            segmentToUpdate = path.getSegments().stream()
                    .filter(pathSegment -> pathSegment.getSrcSwitchId().equals(segment.getSrcSwitchId())
                            && pathSegment.getSrcPort() == segment.getSrcPort()
                            && pathSegment.getDestSwitchId().equals(segment.getDestSwitchId())
                            && pathSegment.getDestPort() == segment.getDestPort())
                    .findAny().orElse(null);
        }

        if (segmentToUpdate == null) {
            throw new PersistenceException(
                    format("PathSegment not found to be updated: %s_%d - %s_%d. Path id: %s.",
                            segment.getSrcSwitchId(), segment.getSrcPort(),
                            segment.getDestSwitchId(), segment.getDestPort(), path.getPathId()));
        }

        segmentToUpdate.setFailed(failed);
    }

    @Override
    public List<PathSegment> findByPathId(PathId pathId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(PathSegmentFrame.FRAME_LABEL)
                .has(PathSegmentFrame.PATH_ID_PROPERTY, PathIdConverter.INSTANCE.toGraphProperty(pathId)))
                .toListExplicit(PathSegmentFrame.class).stream()
                .map(PathSegment::new)
                .sorted(Comparator.comparingInt(PathSegment::getSeqId))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Long> addSegmentAndUpdateIslAvailableBandwidth(PathSegment segment) {
        PathSegmentFrame.create(framedGraph(), segment.getData());
        return Optional.of(islRepository.updateAvailableBandwidth(segment.getSrcSwitchId(), segment.getSrcPort(),
                segment.getDestSwitchId(), segment.getDestPort()));
    }

    @Override
    protected PathSegmentFrame doAdd(PathSegmentData data) {
        return PathSegmentFrame.create(framedGraph(), data);
    }

    @Override
    protected void doRemove(PathSegmentFrame frame) {
        frame.remove();
    }

    @Override
    protected PathSegmentData doDetach(PathSegment entity, PathSegmentFrame frame) {
        return PathSegment.PathSegmentCloner.INSTANCE.deepCopy(frame);
    }
}
