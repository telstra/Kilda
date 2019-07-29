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

package org.openkilda.wfm.topology.reroute.service;

import static java.lang.String.format;

import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.reroute.RerouteResultInfoData;
import org.openkilda.messaging.info.reroute.error.RerouteError;
import org.openkilda.messaging.info.reroute.error.RerouteInProgressError;
import org.openkilda.messaging.info.reroute.error.SpeakerRequestError;
import org.openkilda.model.Flow;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.topology.reroute.model.FlowThrottlingData;
import org.openkilda.wfm.topology.reroute.model.RerouteQueue;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class RerouteQueueService {

    private int defaultFlowPriority;
    private int maxRetry;
    private FlowRepository flowRepository;

    private Map<String, RerouteQueue> reroutes = new HashMap<>();
    private IRerouteQueueCarrier carrier;

    public RerouteQueueService(IRerouteQueueCarrier carrier, PersistenceManager persistenceManager,
                               int defaultFlowPriority, int maxRetry) {
        this.carrier = carrier;
        flowRepository = persistenceManager.getRepositoryFactory().createFlowRepository();
        this.defaultFlowPriority = defaultFlowPriority;
        this.maxRetry = maxRetry;
    }

    /**
     * Put reroute request to throttling.
     *
     * @param flowId flow id
     * @param throttlingData reroute request params
     */
    public void processAutomaticRequest(String flowId, FlowThrottlingData throttlingData) {
        log.info("Puts reroute request for flow {} with correlationId {}.", flowId, throttlingData.getCorrelationId());
        RerouteQueue rerouteQueue = getRerouteQueue(flowId);
        rerouteQueue.putToThrottling(throttlingData);
        carrier.sendExtendTimeWindowEvent();
    }

    /**
     * Process manual reroute request.
     *
     * @param flowId flow id
     * @param throttlingData reroute request params
     */
    public void processManualRequest(String flowId, FlowThrottlingData throttlingData) {
        Optional<Flow> flow = flowRepository.findById(flowId);
        if (!flow.isPresent()) {
            String description = format("Flow %s not found", flowId);
            ErrorData errorData = new ErrorData(ErrorType.NOT_FOUND, "Could not reroute flow", description);
            carrier.emitFlowRerouteError(errorData);
            return;
        }
        RerouteQueue rerouteQueue = getRerouteQueue(flowId);
        if (rerouteQueue.hasInProgress()) {
            String description = format("Flow %s is in reroute process", flowId);
            ErrorData errorData = new ErrorData(ErrorType.UNPROCESSABLE_REQUEST, "Could not reroute flow",
                    description);
            carrier.emitFlowRerouteError(errorData);
        } else {
            rerouteQueue.putToInProgress(throttlingData);
            sendRerouteRequest(flowId, throttlingData);
        }
    }

    /**
     * Process reroute result. Check fail reason, decide if retry is needed and schedule it if yes.
     *
     * @param rerouteResultInfoData reroute result
     * @param correlationId correlation id
     */
    public void processRerouteResult(RerouteResultInfoData rerouteResultInfoData,
                                     String correlationId) {
        String flowId = rerouteResultInfoData.getFlowId();
        RerouteQueue rerouteQueue = getRerouteQueue(flowId);
        FlowThrottlingData inProgress = rerouteQueue.getInProgress();
        if (inProgress == null || !Objects.equals(inProgress.getCorrelationId(), correlationId)) {
            log.error("Skipped unexpected reroute result for flow {} with correlation id {}.", flowId, correlationId);
            return;
        }
        carrier.cancelTimeout(correlationId);

        if (rerouteResultInfoData.isSuccess()) {
            FlowThrottlingData toSend = rerouteQueue.processPending();
            sendRerouteRequest(flowId, toSend);
        } else {
            RerouteError rerouteError = rerouteResultInfoData.getRerouteError();
            if (isRetryRequired(flowId, rerouteError)) {
                injectRetry(flowId, rerouteQueue);
            } else {
                FlowThrottlingData toSend = rerouteQueue.processPending();
                sendRerouteRequest(flowId, toSend);
            }
        }
    }

    /**
     * Move reroute requests form throttling to pending/in-progress.
     */
    public void flushThrottling() {
        Map<String, FlowThrottlingData> requestsToSend = new HashMap<>();
        reroutes.forEach((flowId, rerouteQueue) -> rerouteQueue.flushThrottling()
                    .ifPresent(flowThrottlingData -> requestsToSend.put(flowId, flowThrottlingData)));
        log.info("Send reroute requests for flows {}", requestsToSend.keySet());
        requestsToSend.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(new FlowPriorityComparator()))
                .forEach(es -> {
                    String flowId = es.getKey();
                    FlowThrottlingData flowThrottlingData = es.getValue();
                    sendRerouteRequest(flowId, flowThrottlingData);
                });
    }

    /**
     * Handle timeout event for reroute with correlation id.
     *
     * @param correlationId reroute correlationId
     */
    public void handleTimeout(String correlationId) {
        log.warn("Reroute request with correlation id {} timed out.", correlationId);
        List<Entry<String, RerouteQueue>> foundReroutes = reroutes.entrySet().stream()
                .filter(es -> es.getValue().getInProgress() != null)
                .filter(es -> correlationId.equals(es.getValue().getInProgress().getCorrelationId()))
                .collect(Collectors.toList());
        if (foundReroutes.isEmpty()) {
            log.warn("No reroute with correlationId {} found. Timeout event skipped.", correlationId);
        } else if (foundReroutes.size() > 1) {
            log.error("Found more than one reroute with correlationId {}. Timed out all of them.", correlationId);
        }
        foundReroutes.forEach(entry -> injectRetry(entry.getKey(), entry.getValue()));
    }

    private boolean isRetryRequired(String flowId, RerouteError rerouteError) {
        if (rerouteError instanceof RerouteInProgressError) {
            return true;
        } else if (rerouteError instanceof SpeakerRequestError) {
            Flow flow = flowRepository.findById(flowId).orElse(null);
            if (flow == null) {
                log.error("Flow {} not found", flowId);
                return false;
            }
            SpeakerRequestError ruleFailedError = (SpeakerRequestError) rerouteError;
            return !ruleFailedError.getSwitches().contains(flow.getSrcSwitchId())
                    && !ruleFailedError.getSwitches().contains(flow.getDestSwitchId());
        }
        return false;
    }

    private void injectRetry(String flowId, RerouteQueue rerouteQueue) {
        FlowThrottlingData retryRequest = rerouteQueue.getInProgress();
        if (retryRequest == null) {
            throw new IllegalStateException(format("Can not retry 'null' reroute request for flow %s.", flowId));
        }
        if (retryRequest.getRetryCounter() < maxRetry) {
            retryRequest.increaseRetryCounter();
            String retryCorrelationId = new CommandContext(retryRequest.getCorrelationId())
                    .fork(format("retry #%d", retryRequest.getRetryCounter()))
                    .getCorrelationId();
            retryRequest.setCorrelationId(retryCorrelationId);
            FlowThrottlingData toSend = rerouteQueue.processRetryRequest(retryRequest, carrier);
            sendRerouteRequest(flowId, toSend);
        } else {
            log.error("No more retries available for reroute request {}.", retryRequest);
            FlowThrottlingData toSend = rerouteQueue.processPending();
            sendRerouteRequest(flowId, toSend);
        }
    }

    private void sendRerouteRequest(String flowId, FlowThrottlingData throttlingData) {
        if (throttlingData != null) {
            FlowRerouteRequest request = new FlowRerouteRequest(flowId, throttlingData.isForce(),
                    throttlingData.isEffectivelyDown(), throttlingData.getAffectedIsl(), throttlingData.getReason());
            carrier.sendRerouteRequest(throttlingData.getCorrelationId(), request);
        }
    }

    private RerouteQueue getRerouteQueue(String flowId) {
        return reroutes.computeIfAbsent(flowId, key -> RerouteQueue.empty());
    }

    @VisibleForTesting
    Map<String, RerouteQueue> getReroutes() {
        return reroutes;
    }

    private class FlowPriorityComparator implements Comparator<FlowThrottlingData> {
        @Override
        public int compare(FlowThrottlingData throttlingDataA, FlowThrottlingData throttlingDataB) {
            int priorityA = throttlingDataA.getPriority() == null ? defaultFlowPriority : throttlingDataA.getPriority();
            int priorityB = throttlingDataB.getPriority() == null ? defaultFlowPriority : throttlingDataB.getPriority();
            Instant timeCreateA = throttlingDataA.getTimeCreate();
            Instant timeCreateB = throttlingDataB.getTimeCreate();

            if (priorityA == priorityB && (timeCreateA != null || timeCreateB != null)) {
                if (timeCreateA == null) {
                    return -1;
                }
                if (timeCreateB == null) {
                    return 1;
                }
                return timeCreateA.compareTo(timeCreateB);
            }

            return Integer.compare(priorityA, priorityB);
        }
    }
}
