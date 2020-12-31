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

package org.openkilda.persistence.orientdb.repositories;

import static java.lang.String.format;

import org.openkilda.model.PathId;
import org.openkilda.persistence.ferma.frames.FlowFrame;
import org.openkilda.persistence.ferma.frames.FlowPathFrame;
import org.openkilda.persistence.ferma.frames.PathSegmentFrame;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.ferma.repositories.FermaFlowPathRepository;
import org.openkilda.persistence.orientdb.OrientDbGraphFactory;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.tx.TransactionManager;

import com.syncleus.ferma.FramedGraph;
import org.apache.tinkerpop.gremlin.orientdb.executor.OGremlinResult;
import org.apache.tinkerpop.gremlin.orientdb.executor.OGremlinResultSet;

import java.util.Collection;
import java.util.Iterator;
import java.util.stream.Collectors;

/**
 * OrientDB implementation of {@link FlowPathRepository}.
 */
public class OrientDbFlowPathRepository extends FermaFlowPathRepository {
    private final OrientDbGraphFactory orientDbGraphFactory;

    OrientDbFlowPathRepository(OrientDbGraphFactory orientDbGraphFactory, TransactionManager transactionManager) {
        super(orientDbGraphFactory, transactionManager);
        this.orientDbGraphFactory = orientDbGraphFactory;
    }

    @Override
    public Collection<PathId> findPathIdsByFlowGroupId(String flowGroupId) {
        try (OGremlinResultSet results = orientDbGraphFactory.getOrientGraph().querySql(
                format("SELECT %s FROM %s WHERE in('%s').%s = ?",
                        FlowPathFrame.PATH_ID_PROPERTY, FlowPathFrame.FRAME_LABEL,
                        FlowFrame.OWNS_PATHS_EDGE, FlowFrame.GROUP_ID_PROPERTY), flowGroupId)) {
            return results.stream()
                    .map(r -> r.getProperty(FlowPathFrame.PATH_ID_PROPERTY))
                    .map(pathId -> PathIdConverter.INSTANCE.toEntityAttribute((String) pathId))
                    .collect(Collectors.toList());
        }
    }

    @Override
    protected long getUsedBandwidthBetweenEndpoints(FramedGraph framedGraph,
                                                    String srcSwitchId, int srcPort, String dstSwitchId, int dstPort) {
        try (OGremlinResultSet results = orientDbGraphFactory.getOrientGraph().querySql(
                format("SELECT sum(%s) as bandwidth FROM %s WHERE %s = ? AND %s = ? AND %s = ? AND %s = ? AND %s = ?",
                        PathSegmentFrame.BANDWIDTH_PROPERTY,
                        PathSegmentFrame.FRAME_LABEL, PathSegmentFrame.SRC_SWITCH_ID_PROPERTY,
                        PathSegmentFrame.DST_SWITCH_ID_PROPERTY, PathSegmentFrame.SRC_PORT_PROPERTY,
                        PathSegmentFrame.DST_PORT_PROPERTY, PathSegmentFrame.IGNORE_BANDWIDTH_PROPERTY),
                srcSwitchId, dstSwitchId, srcPort, dstPort, false)) {
            Iterator<OGremlinResult> it = results.iterator();
            if (it.hasNext()) {
                Number result = it.next().getProperty("bandwidth");
                if (result != null) {
                    return result.longValue();
                }
            }
            return 0;
        }
    }
}
