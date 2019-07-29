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
import org.openkilda.messaging.info.flow.FlowHistoryData;
import org.openkilda.messaging.nbtopology.request.BaseRequest;
import org.openkilda.messaging.nbtopology.request.GetFlowHistoryRequest;
import org.openkilda.messaging.nbtopology.request.PortHistoryRequest;
import org.openkilda.messaging.payload.history.FlowEventPayload;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.history.service.HistoryService;
import org.openkilda.wfm.share.mappers.HistoryMapper;

import org.apache.storm.tuple.Tuple;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class HistoryOperationsBolt extends PersistenceOperationsBolt {
    private transient HistoryService historyService;

    public HistoryOperationsBolt(PersistenceManager persistenceManager) {
        super(persistenceManager);
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
        } else {
            unhandledInput(tuple);
            return null;
        }
    }

    private List<InfoData> getFlowHistory(GetFlowHistoryRequest request) {
        List<FlowEventPayload> flowEvents = listFlowEvents(
                request.getFlowId(),
                Instant.ofEpochSecond(request.getTimestampFrom()),
                Instant.ofEpochSecond(request.getTimestampTo() + 1).minusMillis(1));
        return Collections.singletonList(new FlowHistoryData(flowEvents));
    }

    private List<InfoData> getPortHistory(PortHistoryRequest request) {
        return historyService.listPortHistory(request.getSwitchId(), request.getPortNumber(),
                request.getStart(), request.getEnd())
                .stream()
                .map(HistoryMapper.INSTANCE::map)
                .collect(Collectors.toList());
    }

    private List<FlowEventPayload> listFlowEvents(String flowId, Instant timeFrom, Instant timeTo) {
        return historyService.listFlowEvents(flowId, timeFrom, timeTo)
                .stream()
                .map(HistoryMapper.INSTANCE::map)
                .collect(Collectors.toList());
    }
}
