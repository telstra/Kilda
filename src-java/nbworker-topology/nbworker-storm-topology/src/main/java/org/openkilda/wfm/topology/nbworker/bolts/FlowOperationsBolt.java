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

package org.openkilda.wfm.topology.nbworker.bolts;

import static java.lang.String.format;
import static org.openkilda.model.ConnectedDeviceType.ARP;
import static org.openkilda.model.ConnectedDeviceType.LLDP;

import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.MessageException;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.flow.FlowResponse;
import org.openkilda.messaging.info.flow.FlowsResponse;
import org.openkilda.messaging.model.FlowDto;
import org.openkilda.messaging.nbtopology.request.BaseRequest;
import org.openkilda.messaging.nbtopology.request.FlowConnectedDeviceRequest;
import org.openkilda.messaging.nbtopology.request.FlowReadRequest;
import org.openkilda.messaging.nbtopology.request.FlowsDumpRequest;
import org.openkilda.messaging.nbtopology.request.GetFlowLoopsRequest;
import org.openkilda.messaging.nbtopology.request.GetFlowPathRequest;
import org.openkilda.messaging.nbtopology.request.GetFlowsForIslRequest;
import org.openkilda.messaging.nbtopology.request.GetFlowsForSwitchRequest;
import org.openkilda.messaging.nbtopology.request.RerouteFlowsForIslRequest;
import org.openkilda.messaging.nbtopology.response.ConnectedDeviceDto;
import org.openkilda.messaging.nbtopology.response.FlowConnectedDevicesResponse;
import org.openkilda.messaging.nbtopology.response.FlowLoopDto;
import org.openkilda.messaging.nbtopology.response.FlowLoopsResponse;
import org.openkilda.messaging.nbtopology.response.GetFlowPathResponse;
import org.openkilda.messaging.nbtopology.response.TypedConnectedDevicesDto;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.SwitchConnectedDevice;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.error.FlowNotFoundException;
import org.openkilda.wfm.error.IslNotFoundException;
import org.openkilda.wfm.error.SwitchNotFoundException;
import org.openkilda.wfm.share.mappers.ConnectedDeviceMapper;
import org.openkilda.wfm.share.mappers.FlowMapper;
import org.openkilda.wfm.share.metrics.TimedExecution;
import org.openkilda.wfm.topology.nbworker.StreamType;
import org.openkilda.wfm.topology.nbworker.services.FlowOperationsService;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class FlowOperationsBolt extends PersistenceOperationsBolt {
    private transient FlowOperationsService flowOperationsService;

    public FlowOperationsBolt(PersistenceManager persistenceManager) {
        super(persistenceManager);

        enableMeterRegistry("kilda.flow_operations", StreamType.TO_METRICS_BOLT.name());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init() {
        this.flowOperationsService = new FlowOperationsService(repositoryFactory, transactionManager);
    }

    @Override
    @SuppressWarnings("unchecked")
    List<InfoData> processRequest(Tuple tuple, BaseRequest request) {
        List<? extends InfoData> result = null;
        if (request instanceof GetFlowsForIslRequest) {
            result = processGetFlowsForLinkRequest((GetFlowsForIslRequest) request);
        } else if (request instanceof GetFlowsForSwitchRequest) {
            result = processGetFlowsForSwitchRequest((GetFlowsForSwitchRequest) request);
        } else if (request instanceof RerouteFlowsForIslRequest) {
            result = processRerouteFlowsForLinkRequest((RerouteFlowsForIslRequest) request);
        } else if (request instanceof GetFlowPathRequest) {
            result = processGetFlowPathRequest((GetFlowPathRequest) request);
        } else if (request instanceof FlowConnectedDeviceRequest) {
            result = processFlowConnectedDeviceRequest((FlowConnectedDeviceRequest) request);
        } else if (request instanceof FlowReadRequest) {
            result = processFlowReadRequest((FlowReadRequest) request);
        } else if (request instanceof FlowsDumpRequest) {
            result = processFlowsDumpRequest((FlowsDumpRequest) request);
        } else if (request instanceof GetFlowLoopsRequest) {
            result = processGetFlowLoopsRequest((GetFlowLoopsRequest) request);
        } else {
            unhandledInput(tuple);
        }

        return (List<InfoData>) result;
    }

    @TimedExecution("get_flows_for_link")
    private List<FlowResponse> processGetFlowsForLinkRequest(GetFlowsForIslRequest request) {
        SwitchId srcSwitch = request.getSource().getDatapath();
        Integer srcPort = request.getSource().getPortNumber();
        SwitchId dstSwitch = request.getDestination().getDatapath();
        Integer dstPort = request.getDestination().getPortNumber();

        try {
            return flowOperationsService.getFlowPathsForLink(srcSwitch, srcPort, dstSwitch, dstPort).stream()
                    // NOTE(tdurakov): filter out paths here that are orphaned for the flow
                    .filter(flowPath -> flowPath.getFlow().isActualPathId(flowPath.getPathId()))
                    .map(FlowPath::getFlow)
                    .distinct()
                    .map(FlowMapper.INSTANCE::map)
                    .map(FlowResponse::new)
                    .collect(Collectors.toList());
        } catch (IslNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "ISL was not found.");
        }
    }

    @TimedExecution("get_flows_for_switch")
    private List<FlowResponse> processGetFlowsForSwitchRequest(GetFlowsForSwitchRequest request) {
        SwitchId srcSwitch = request.getSwitchId();
        Integer srcPort = request.getPort();

        try {
            return flowOperationsService.getFlowsForEndpoint(srcSwitch, srcPort).stream()
                    .distinct()
                    .map(FlowMapper.INSTANCE::map)
                    .map(FlowResponse::new)
                    .collect(Collectors.toList());
        } catch (SwitchNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "Switch was not found.");
        }
    }

    @TimedExecution("reroute_flows_for_link")
    private List<FlowsResponse> processRerouteFlowsForLinkRequest(RerouteFlowsForIslRequest message) {
        SwitchId srcSwitch = message.getSource().getDatapath();
        Integer srcPort = message.getSource().getPortNumber();
        SwitchId dstSwitch = message.getDestination().getDatapath();
        Integer dstPort = message.getDestination().getPortNumber();

        Collection<FlowPath> paths;
        try {
            paths = flowOperationsService.getFlowPathsForLink(srcSwitch, srcPort, dstSwitch, dstPort);
        } catch (IslNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "ISL was not found.");
        }

        Set<IslEndpoint> affectedIslEndpoints = new HashSet<>();
        affectedIslEndpoints.add(new IslEndpoint(srcSwitch, srcPort));
        affectedIslEndpoints.add(new IslEndpoint(dstSwitch, dstPort));

        sendRerouteRequest(paths, affectedIslEndpoints,
                format("initiated via Northbound, reroute all flows that go over the link %s_%d - %s_%d",
                        srcSwitch, srcPort, dstSwitch, dstPort));

        List<String> flowIds = paths.stream()
                .map(FlowPath::getFlow)
                .map(Flow::getFlowId)
                .distinct()
                .collect(Collectors.toList());
        return Collections.singletonList(new FlowsResponse(flowIds));
    }

    @TimedExecution("get_flow_path")
    private List<GetFlowPathResponse> processGetFlowPathRequest(GetFlowPathRequest request) {
        final String errorDescription = "Could not get flow path";

        try {
            return flowOperationsService.getFlowPath(request.getFlowId())
                    .stream()
                    .map(GetFlowPathResponse::new)
                    .collect(Collectors.toList());
        } catch (FlowNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), errorDescription);
        } catch (Exception e) {
            throw new MessageException(ErrorType.INTERNAL_ERROR, e.getMessage(), errorDescription);
        }
    }

    private List<FlowConnectedDevicesResponse> processFlowConnectedDeviceRequest(FlowConnectedDeviceRequest request) {

        Collection<SwitchConnectedDevice> devices;
        try {
            devices = flowOperationsService.getFlowConnectedDevice(request.getFlowId()).stream()
                    .filter(device -> request.getSince().isBefore(device.getTimeLastSeen())
                            || request.getSince().equals(device.getTimeLastSeen()))
                    .collect(Collectors.toList());
        } catch (FlowNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(),
                    "Could not get connected devices for non existent flow");
        }

        FlowConnectedDevicesResponse response = new FlowConnectedDevicesResponse(
                new TypedConnectedDevicesDto(new ArrayList<>(), new ArrayList<>()),
                new TypedConnectedDevicesDto(new ArrayList<>(), new ArrayList<>()));

        for (SwitchConnectedDevice device : devices) {
            ConnectedDeviceDto deviceDto = ConnectedDeviceMapper.INSTANCE.mapSwitchDeviceToFlowDeviceDto(device);
            if (device.getSource() == null) {
                log.warn("Switch Connected Device {} has Flow ID {} but has no 'source' property.",
                        device, device.getFlowId());
            } else if (device.getSource()) {
                if (device.getType() == LLDP) {
                    response.getSource().getLldp().add(deviceDto);
                } else if (device.getType() == ARP) {
                    response.getSource().getArp().add(deviceDto);
                }
            } else {
                if (device.getType() == LLDP) {
                    response.getDestination().getLldp().add(deviceDto);
                } else if (device.getType() == ARP) {
                    response.getDestination().getArp().add(deviceDto);
                }
            }
        }
        return Collections.singletonList(response);
    }

    @TimedExecution("flow_read")
    private List<FlowResponse> processFlowReadRequest(FlowReadRequest readRequest) {
        try {
            String flowId = readRequest.getFlowId();
            Flow f = flowOperationsService.getFlow(flowId);
            FlowDto dto = FlowMapper.INSTANCE.map(f);
            if (f.getGroupId() != null) {
                dto.setDiverseWith(flowOperationsService.getDiverseFlowsId(flowId, f.getGroupId()));
            }
            FlowResponse response = new FlowResponse(dto);
            return Collections.singletonList(response);
        } catch (FlowNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, "Can not get flow: " + e.getMessage(),
                    "Flow not found");
        } catch (Exception e) {
            throw new MessageException(ErrorType.INTERNAL_ERROR, "Can not get flow", "Internal Error");
        }
    }

    @TimedExecution("flow_dump")
    private List<FlowResponse> processFlowsDumpRequest(FlowsDumpRequest request) {
        try {
            return flowOperationsService.getAllFlows(request).stream()
                    .map(FlowMapper.INSTANCE::map)
                    .map(FlowResponse::new)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new MessageException(ErrorType.INTERNAL_ERROR, "Can not dump flows", "Internal Error");
        }
    }

    private List<InfoData> processGetFlowLoopsRequest(GetFlowLoopsRequest request) {
        try {
            SwitchId switchId = request.getSwitchId() == null ? null : new SwitchId(request.getSwitchId());
            List<FlowLoopDto> flowLoops = flowOperationsService.getLoopedFlows(request.getFlowId(), switchId).stream()
                    .map(this::map)
                    .collect(Collectors.toList());
            FlowLoopsResponse flowLoopsResponse = new FlowLoopsResponse();
            flowLoopsResponse.setPayload(flowLoops);
            return Collections.singletonList(flowLoopsResponse);
        } catch (Exception e) {
            throw new MessageException(ErrorType.INTERNAL_ERROR, "Can not dump flow loops", "Internal Error");
        }
    }

    private FlowLoopDto map(Flow flow) {
        return new FlowLoopDto(flow.getFlowId(), flow.getLoopSwitchId().toString());
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        super.declareOutputFields(declarer);
        declarer.declareStream(StreamType.REROUTE.toString(),
                new Fields(MessageEncoder.FIELD_ID_PAYLOAD, MessageEncoder.FIELD_ID_CONTEXT));
    }

    private void sendRerouteRequest(Collection<FlowPath> paths, Set<IslEndpoint> affectedIslEndpoints, String reason) {
        for (FlowRerouteRequest request : flowOperationsService.makeRerouteRequests(
                paths, affectedIslEndpoints, reason)) {
            CommandContext forkedContext = getCommandContext().fork(request.getFlowId());
            getOutput().emit(StreamType.REROUTE.toString(), getCurrentTuple(),
                    new Values(request, forkedContext.getCorrelationId()));
        }
    }
}
