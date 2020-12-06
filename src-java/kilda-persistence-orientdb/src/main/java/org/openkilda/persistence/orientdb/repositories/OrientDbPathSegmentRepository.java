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

import org.openkilda.model.PathSegment;
import org.openkilda.persistence.ferma.frames.IslFrame;
import org.openkilda.persistence.ferma.frames.PathSegmentFrame;
import org.openkilda.persistence.ferma.frames.converters.PathIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.ferma.repositories.FermaPathSegmentRepository;
import org.openkilda.persistence.orientdb.OrientDbGraphFactory;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.PathSegmentRepository;
import org.openkilda.persistence.tx.TransactionManager;

import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.tinkerpop.gremlin.orientdb.executor.OGremlinResult;
import org.apache.tinkerpop.gremlin.orientdb.executor.OGremlinResultSet;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * OrientDB implementation of {@link PathSegmentRepository}.
 */
@Slf4j
public class OrientDbPathSegmentRepository extends FermaPathSegmentRepository {
    private final OrientDbGraphFactory orientDbGraphFactory;

    public OrientDbPathSegmentRepository(OrientDbGraphFactory orientDbGraphFactory,
                                         TransactionManager transactionManager, IslRepository islRepository) {
        super(orientDbGraphFactory, transactionManager, islRepository);
        this.orientDbGraphFactory = orientDbGraphFactory;
    }

    @Override
    public Optional<Long> addSegmentAndUpdateIslAvailableBandwidth(PathSegment segment) {
        Map params = ImmutableMap.builder()
                .put("path_id", PathIdConverter.INSTANCE.toGraphProperty(segment.getPathId()))
                .put("src_sw", SwitchIdConverter.INSTANCE.toGraphProperty(segment.getSrcSwitchId()))
                .put("src_port", segment.getSrcPort())
                .put("dst_sw", SwitchIdConverter.INSTANCE.toGraphProperty(segment.getDestSwitchId()))
                .put("dst_port", segment.getDestPort())
                .put("src_mt", segment.isSrcWithMultiTable())
                .put("dst_mt", segment.isDestWithMultiTable())
                .put("ignore_bw", segment.isIgnoreBandwidth())
                .put("bw", segment.getBandwidth())
                .put("seq_id", segment.getSeqId())
                .put("latency", segment.getLatency())
                .build();

        if (segment.isIgnoreBandwidth()) {
            try (OGremlinResultSet results = orientDbGraphFactory.getOrientGraph().executeSql(
                    format("INSERT INTO %s(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
                                    + "VALUES (:path_id,:src_sw,:src_port,:dst_sw,:dst_port,:src_mt,:dst_mt,"
                                    + ":ignore_bw,:bw,:seq_id,:latency,false)",
                            PathSegmentFrame.FRAME_LABEL,
                            PathSegmentFrame.PATH_ID_PROPERTY,
                            PathSegmentFrame.SRC_SWITCH_ID_PROPERTY,
                            PathSegmentFrame.SRC_PORT_PROPERTY,
                            PathSegmentFrame.DST_SWITCH_ID_PROPERTY,
                            PathSegmentFrame.DST_PORT_PROPERTY,
                            PathSegmentFrame.SRC_W_MULTI_TABLE_PROPERTY,
                            PathSegmentFrame.DST_W_MULTI_TABLE_PROPERTY,
                            PathSegmentFrame.IGNORE_BANDWIDTH_PROPERTY,
                            PathSegmentFrame.BANDWIDTH_PROPERTY,
                            PathSegmentFrame.SEQ_ID_PROPERTY,
                            PathSegmentFrame.LATENCY_PROPERTY,
                            PathSegmentFrame.FAILED_PROPERTY
                    ), params)) {
                return Optional.empty();
            }
        } else {
            try (OGremlinResultSet results = orientDbGraphFactory.getOrientGraph().execute("sql",
                    format("INSERT INTO %s(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
                                    + "VALUES (:path_id,:src_sw,:src_port,:dst_sw,:dst_port,:src_mt,:dst_mt,"
                                    + ":ignore_bw,:bw,:seq_id,:latency,false);"
                                    + "UPDATE %s SET %s = %s - :bw RETURN AFTER %s "
                                    + "WHERE %s = :src_sw AND %s = :dst_sw AND %s = :src_port AND %s = :dst_port "
                                    + "LOCK RECORD;",
                            PathSegmentFrame.FRAME_LABEL,
                            PathSegmentFrame.PATH_ID_PROPERTY,
                            PathSegmentFrame.SRC_SWITCH_ID_PROPERTY,
                            PathSegmentFrame.SRC_PORT_PROPERTY,
                            PathSegmentFrame.DST_SWITCH_ID_PROPERTY,
                            PathSegmentFrame.DST_PORT_PROPERTY,
                            PathSegmentFrame.SRC_W_MULTI_TABLE_PROPERTY,
                            PathSegmentFrame.DST_W_MULTI_TABLE_PROPERTY,
                            PathSegmentFrame.IGNORE_BANDWIDTH_PROPERTY,
                            PathSegmentFrame.BANDWIDTH_PROPERTY,
                            PathSegmentFrame.SEQ_ID_PROPERTY,
                            PathSegmentFrame.LATENCY_PROPERTY,
                            PathSegmentFrame.FAILED_PROPERTY,
                            IslFrame.FRAME_LABEL,
                            IslFrame.AVAILABLE_BANDWIDTH_PROPERTY, IslFrame.AVAILABLE_BANDWIDTH_PROPERTY,
                            IslFrame.AVAILABLE_BANDWIDTH_PROPERTY,
                            IslFrame.SRC_SWITCH_ID_PROPERTY, IslFrame.DST_SWITCH_ID_PROPERTY,
                            IslFrame.SRC_PORT_PROPERTY, IslFrame.DST_PORT_PROPERTY),
                    params)) {
                Iterator<OGremlinResult> it = results.iterator();
                if (it.hasNext()) {
                    Number result = it.next().getProperty(IslFrame.AVAILABLE_BANDWIDTH_PROPERTY);
                    if (result != null) {
                        return Optional.of(result.longValue());
                    }
                }
                return Optional.empty();
            }
        }
    }
}
