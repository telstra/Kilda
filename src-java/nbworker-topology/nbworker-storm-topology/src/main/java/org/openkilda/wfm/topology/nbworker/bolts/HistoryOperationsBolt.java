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
import org.openkilda.messaging.nbtopology.request.PortHistoryRequest;
import org.openkilda.messaging.payload.history.FlowDumpPayload;
import org.openkilda.messaging.payload.history.FlowHistoryPayload;
import org.openkilda.model.history.FlowEvent;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.history.service.HistoryService;
import org.openkilda.wfm.share.mappers.HistoryMapper;
import org.openkilda.wfm.share.metrics.PushToStreamMeterRegistry;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.nbworker.StreamType;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public class HistoryOperationsBolt extends PersistenceOperationsBolt {
    private transient PushToStreamMeterRegistry meterRegistry;
    private transient HistoryService historyService;

    public HistoryOperationsBolt(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected void init() {
        meterRegistry = new PushToStreamMeterRegistry("kilda.history_operations");
        meterRegistry.config().commonTags("bolt_id", this.getComponentId());

        historyService = new HistoryService(transactionManager, repositoryFactory);
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        try {
            super.handleInput(input);
        } finally {
            meterRegistry.pushMeters(getOutput(), StreamType.TO_METRICS_BOLT.name());
        }
    }

    @Override
    List<InfoData> processRequest(Tuple tuple, BaseRequest request) {
        if (request instanceof GetFlowHistoryRequest) {
            return getFlowHistory((GetFlowHistoryRequest) request);
        } else if (request instanceof PortHistoryRequest) {
            return getPortHistory((PortHistoryRequest) request);
        } else {
            unhandledInput(tuple);
            return null;
        }
    }

    private List<InfoData> getFlowHistory(GetFlowHistoryRequest request) {
        Sample sample = Timer.start();
        try {
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
        } finally {
            sample.stop(meterRegistry.timer("get_flow_history.execution"));
        }
    }

    private List<InfoData> getPortHistory(PortHistoryRequest request) {
        Sample sample = Timer.start();
        try {
            return historyService.listPortHistory(request.getSwitchId(), request.getPortNumber(),
                    request.getStart(), request.getEnd())
                    .stream()
                    .map(HistoryMapper.INSTANCE::map)
                    .collect(Collectors.toList());
        } finally {
            sample.stop(meterRegistry.timer("get_port_history.execution"));
        }
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

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        super.declareOutputFields(declarer);
        declarer.declareStream(StreamType.TO_METRICS_BOLT.name(), AbstractTopology.fieldMessage);
    }
}
