/* Copyright 2019 Telstra Open Source
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

package org.openkilda.wfm.topology.nbworker.bolts;

import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.nbtopology.request.BaseRequest;
import org.openkilda.messaging.nbtopology.request.GetFlowHistoryRequest;
import org.openkilda.messaging.nbtopology.request.GetFlowStatusTimestampsRequest;
import org.openkilda.messaging.nbtopology.request.PortHistoryRequest;
import org.openkilda.messaging.payload.history.FlowDumpPayload;
import org.openkilda.messaging.payload.history.FlowHistoryPayload;
import org.openkilda.model.history.FlowEvent;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.history.service.HistoryService;
import org.openkilda.wfm.share.mappers.HistoryMapper;
import org.openkilda.wfm.share.metrics.TimedExecution;
import org.openkilda.wfm.topology.nbworker.StreamType;

import org.apache.storm.tuple.Tuple;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public class HistoryOperationsBolt extends PersistenceOperationsBolt {
    private transient HistoryService historyService;

    public HistoryOperationsBolt(PersistenceManager persistenceManager) {
        super(persistenceManager);

        enableMeterRegistry("kilda.history_operations", StreamType.TO_METRICS_BOLT.name());
    }

    @Override
    protected void init() {
        historyService = new HistoryService(transactionManager, repositoryFactory);
    }

    @Override
    List<InfoData> processRequest(Tuple tuple, BaseRequest request) {
        if (request instanceof GetFlowHistoryRequest) {
            return getFlowHistory((GetFlowHistoryRequest) request);
        } else if (request instanceof PortHistoryRequest) {
            return getPortHistory((PortHistoryRequest) request);
        } else if (request instanceof GetFlowStatusTimestampsRequest) {
            return getFlowStatusTimestamps((GetFlowStatusTimestampsRequest) request);
        } else {
            unhandledInput(tuple);
            return null;
        }
    }

    @TimedExecution("get_flow_history")
    private List<InfoData> getFlowHistory(GetFlowHistoryRequest request) {
        Instant timeFrom = Instant.ofEpochSecond(request.getTimestampFrom());
        Instant timeTo = Instant.ofEpochSecond(request.getTimestampTo() + 1).minusMillis(1);
        return historyService.listFlowEvents(
                request.getFlowId(), timeFrom, timeTo, request.getMaxCount()).stream()
                .map(entry -> {
                    List<FlowHistoryPayload> payload = listFlowHistories(entry);
                    List<FlowDumpPayload> dumps = listFlowDumps(entry);
                    return HistoryMapper.INSTANCE.map(entry, payload, dumps);
                })
                .collect(Collectors.toList());
    }

    @TimedExecution("get_port_history")
    private List<InfoData> getPortHistory(PortHistoryRequest request) {
        return historyService.listPortHistory(request.getSwitchId(), request.getPortNumber(),
                request.getStart(), request.getEnd())
                .stream()
                .map(HistoryMapper.INSTANCE::map)
                .collect(Collectors.toList());
    }

    private List<InfoData> getFlowStatusTimestamps(GetFlowStatusTimestampsRequest request) {
        Instant timeFrom = Instant.ofEpochSecond(request.getTimestampFrom());
        Instant timeTo = Instant.ofEpochSecond(request.getTimestampTo() + 1).minusMillis(1);
        return historyService.getFlowStatusTimestamps(request.getFlowId(), timeFrom, timeTo, request.getMaxCount())
                .stream()
                .map(HistoryMapper.INSTANCE::map)
                .collect(Collectors.toList());
    }

    private List<FlowHistoryPayload> listFlowHistories(FlowEvent flowEvent) {
        return flowEvent.getHistoryRecords().stream()
                .map(HistoryMapper.INSTANCE::map)
                .collect(Collectors.toList());
    }

    private List<FlowDumpPayload> listFlowDumps(FlowEvent flowEvent) {
        return flowEvent.getFlowDumps()
                .stream()
                .map(HistoryMapper.INSTANCE::map)
                .collect(Collectors.toList());
    }
}
