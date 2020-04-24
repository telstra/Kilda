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

import org.openkilda.model.history.FlowDump;
import org.openkilda.model.history.FlowDump.FlowDumpCloner;
import org.openkilda.model.history.FlowDump.FlowDumpData;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.FlowDumpFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.repositories.history.FlowDumpRepository;

import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;

/**
 * Ferma (Tinkerpop) implementation of {@link FlowDumpRepository}.
 */
class FermaFlowDumpRepository extends FermaGenericRepository<FlowDump, FlowDumpData, FlowDumpFrame>
        implements FlowDumpRepository {
    FermaFlowDumpRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    protected FlowDumpFrame doAdd(FlowDumpData data) {
        FlowDumpFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                FlowDumpFrame.FRAME_LABEL, FlowDumpFrame.class);
        FlowDump.FlowDumpCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected FlowDumpData doRemove(FlowDump entity, FlowDumpFrame frame) {
        FlowDumpData data = FlowDumpCloner.INSTANCE.copy(frame);
        frame.getElement().edges(Direction.BOTH).forEachRemaining(Edge::remove);
        frame.remove();
        return data;
    }
}
