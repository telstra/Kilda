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

import static java.lang.String.format;
import static org.openkilda.model.ConnectedDeviceType.ARP;
import static org.openkilda.model.ConnectedDeviceType.LLDP;

import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.command.switches.SwitchValidateRequest;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.MessageException;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.event.DeactivateSwitchInfoData;
import org.openkilda.messaging.model.SwitchPropertiesDto;
import org.openkilda.messaging.nbtopology.request.BaseRequest;
import org.openkilda.messaging.nbtopology.request.DeleteSwitchRequest;
import org.openkilda.messaging.nbtopology.request.GetPortPropertiesRequest;
import org.openkilda.messaging.nbtopology.request.GetSwitchConnectedDevicesRequest;
import org.openkilda.messaging.nbtopology.request.GetSwitchPropertiesRequest;
import org.openkilda.messaging.nbtopology.request.GetSwitchRequest;
import org.openkilda.messaging.nbtopology.request.GetSwitchesRequest;
import org.openkilda.messaging.nbtopology.request.SwitchPatchRequest;
import org.openkilda.messaging.nbtopology.request.UpdateSwitchPropertiesRequest;
import org.openkilda.messaging.nbtopology.request.UpdateSwitchUnderMaintenanceRequest;
import org.openkilda.messaging.nbtopology.response.DeleteSwitchResponse;
import org.openkilda.messaging.nbtopology.response.GetSwitchResponse;
import org.openkilda.messaging.nbtopology.response.SwitchConnectedDeviceDto;
import org.openkilda.messaging.nbtopology.response.SwitchConnectedDevicesResponse;
import org.openkilda.messaging.nbtopology.response.SwitchPortConnectedDevicesDto;
import org.openkilda.messaging.nbtopology.response.SwitchPropertiesResponse;
import org.openkilda.messaging.payload.switches.PortPropertiesPayload;
import org.openkilda.model.FlowPath;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.PortProperties;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchConnectedDevice;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.server42.control.messaging.flowrtt.ActivateFlowMonitoringOnSwitchInfoData;
import org.openkilda.server42.control.messaging.flowrtt.DeactivateFlowMonitoringOnSwitchInfoData;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.error.IllegalSwitchPropertiesException;
import org.openkilda.wfm.error.IllegalSwitchStateException;
import org.openkilda.wfm.error.SwitchNotFoundException;
import org.openkilda.wfm.error.SwitchPropertiesNotFoundException;
import org.openkilda.wfm.share.mappers.ConnectedDeviceMapper;
import org.openkilda.wfm.share.mappers.PortMapper;
import org.openkilda.wfm.share.metrics.MeterRegistryHolder;
import org.openkilda.wfm.share.metrics.PushToStreamMeterRegistry;
import org.openkilda.wfm.share.metrics.TimedExecution;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.utils.KeyProvider;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.nbworker.StreamType;
import org.openkilda.wfm.topology.nbworker.services.FlowOperationsService;
import org.openkilda.wfm.topology.nbworker.services.ILinkOperationsServiceCarrier;
import org.openkilda.wfm.topology.nbworker.services.SwitchOperationsService;
import org.openkilda.wfm.topology.nbworker.services.SwitchOperationsServiceCarrier;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

public class SwitchOperationsBolt extends PersistenceOperationsBolt implements ILinkOperationsServiceCarrier,
        SwitchOperationsServiceCarrier {
    private transient PushToStreamMeterRegistry meterRegistry;
    private transient SwitchOperationsService switchOperationsService;
    private transient FlowOperationsService flowOperationsService;

    public SwitchOperationsBolt(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init() {
        meterRegistry = new PushToStreamMeterRegistry("kilda.switch_operations");
        meterRegistry.config().commonTags("bolt_id", this.getComponentId());

        this.switchOperationsService =
                new SwitchOperationsService(repositoryFactory, transactionManager, this, this);
        this.flowOperationsService = new FlowOperationsService(repositoryFactory, transactionManager);
    }

    @Override
    protected void handleInput(Tuple input) throws Exception {
        MeterRegistryHolder.setRegistry(meterRegistry);
        try {
            super.handleInput(input);
        } finally {
            MeterRegistryHolder.removeRegistry();
            meterRegistry.pushMeters(getOutput(), StreamType.TO_METRICS_BOLT.name());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    List<InfoData> processRequest(Tuple tuple, BaseRequest request) {
        List<? extends InfoData> result = null;
        if (request instanceof GetSwitchesRequest) {
            result = getSwitches();
        } else if (request instanceof UpdateSwitchUnderMaintenanceRequest) {
            result = updateSwitchUnderMaintenanceFlag((UpdateSwitchUnderMaintenanceRequest) request, tuple);
        } else if (request instanceof GetSwitchRequest) {
            result = getSwitch((GetSwitchRequest) request);
        } else if (request instanceof DeleteSwitchRequest) {
            result = Collections.singletonList(deleteSwitch((DeleteSwitchRequest) request));
        } else if (request instanceof GetSwitchPropertiesRequest) {
            result = Collections.singletonList(getSwitchProperties((GetSwitchPropertiesRequest) request));
        } else if (request instanceof UpdateSwitchPropertiesRequest) {
            result = Collections.singletonList(updateSwitchProperties((UpdateSwitchPropertiesRequest) request));
        } else if (request instanceof GetPortPropertiesRequest) {
            result = Collections.singletonList(getPortProperties((GetPortPropertiesRequest) request));
        }  else if (request instanceof GetSwitchConnectedDevicesRequest) {
            result = Collections.singletonList(getSwitchConnectedDevices((GetSwitchConnectedDevicesRequest) request));
        }  else if (request instanceof SwitchPatchRequest) {
            result = Collections.singletonList(patchSwitch((SwitchPatchRequest) request));
        } else {
            unhandledInput(tuple);
        }

        return (List<InfoData>) result;
    }

    @TimedExecution("switch_dump.execution")
    private List<GetSwitchResponse> getSwitches() {
        return switchOperationsService.getAllSwitches();
    }

    @TimedExecution("get_switch.execution")
    private List<GetSwitchResponse> getSwitch(GetSwitchRequest request) {
        SwitchId switchId = request.getSwitchId();

        try {
            return Collections.singletonList(switchOperationsService.getSwitch(switchId));
        } catch (SwitchNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "Switch was not found.");
        }
    }

    private List<GetSwitchResponse> updateSwitchUnderMaintenanceFlag(UpdateSwitchUnderMaintenanceRequest request,
                                                                     Tuple tuple) {
        SwitchId switchId = request.getSwitchId();
        boolean underMaintenance = request.isUnderMaintenance();
        boolean evacuate = request.isEvacuate();

        Switch sw;
        try {
            sw = switchOperationsService.updateSwitchUnderMaintenanceFlag(switchId, underMaintenance);
        } catch (SwitchNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "Switch was not found.");
        }

        if (underMaintenance && evacuate) {
            Collection<FlowPath> paths = flowOperationsService.getFlowPathsForSwitch(switchId);

            Set<IslEndpoint> affectedIslEndpoint = new HashSet<>(
                    switchOperationsService.getSwitchIslEndpoints(switchId));
            String reason = format("evacuated due to switch maintenance %s", switchId);
            for (FlowRerouteRequest reroute : flowOperationsService.makeRerouteRequests(
                    paths, affectedIslEndpoint, reason)) {
                CommandContext forkedContext = getCommandContext().fork(reroute.getFlowId());
                getOutput().emit(StreamType.REROUTE.toString(), tuple,
                        new Values(reroute, forkedContext.getCorrelationId()));
            }
        }

        return Collections.singletonList(new GetSwitchResponse(sw));
    }

    private DeleteSwitchResponse deleteSwitch(DeleteSwitchRequest request) {
        SwitchId switchId = request.getSwitchId();
        boolean force = request.isForce();
        boolean deleted = transactionManager.doInTransaction(() -> {
            try {
                if (!force) {
                    switchOperationsService.checkSwitchIsDeactivated(switchId);
                    switchOperationsService.checkSwitchHasNoFlows(switchId);
                    switchOperationsService.checkSwitchHasNoFlowSegments(switchId);
                    switchOperationsService.checkSwitchHasNoIsls(switchId);
                }
                return switchOperationsService.deleteSwitch(switchId, force);
            } catch (SwitchNotFoundException e) {
                String message = format("Could not delete switch '%s': '%s'", switchId, e.getMessage());
                throw new MessageException(ErrorType.NOT_FOUND, message, "Switch is not found.");
            } catch (IllegalSwitchStateException e) {
                String message = format("Could not delete switch '%s': '%s'", switchId, e.getMessage());
                throw new MessageException(ErrorType.REQUEST_INVALID, message, "Switch is in illegal state");
            }
        });

        if (deleted) {
            DeactivateSwitchInfoData data = new DeactivateSwitchInfoData(switchId);
            getOutput().emit(StreamType.DISCO.toString(), getCurrentTuple(), new Values(data, getCorrelationId()));
        }

        log.info("{} deletion of switch '{}'", deleted ? "Successful" : "Unsuccessful", switchId);
        return new DeleteSwitchResponse(deleted);
    }

    private SwitchPropertiesResponse getSwitchProperties(GetSwitchPropertiesRequest request) {
        try {
            SwitchPropertiesDto found = switchOperationsService.getSwitchProperties(request.getSwitchId());
            return new SwitchPropertiesResponse(found);
        } catch (SwitchPropertiesNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "Failed to get switch properties.");
        }
    }

    private SwitchPropertiesResponse updateSwitchProperties(UpdateSwitchPropertiesRequest request) {
        try {
            SwitchPropertiesDto updated = switchOperationsService.updateSwitchProperties(request.getSwitchId(),
                    request.getSwitchProperties());
            return new SwitchPropertiesResponse(updated);
        } catch (SwitchPropertiesNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "Failed to update switch properties.");
        } catch (IllegalSwitchPropertiesException e) {
            throw new MessageException(ErrorType.PARAMETERS_INVALID, e.getMessage(),
                    "Failed to update switch properties.");
        } catch (IllegalArgumentException e) {
            throw new MessageException(ErrorType.PARAMETERS_INVALID, "Failed to update switch properties.",
                    e.getMessage());
        }
    }

    private PortPropertiesPayload getPortProperties(GetPortPropertiesRequest request) {
        try {
            PortProperties portProperties =
                    switchOperationsService.getPortProperties(request.getSwitchId(), request.getPort());
            return PortMapper.INSTANCE.map(portProperties);
        } catch (PersistenceException | SwitchNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "Couldn't get port properties");
        }
    }

    private SwitchConnectedDevicesResponse getSwitchConnectedDevices(GetSwitchConnectedDevicesRequest request) {
        Collection<SwitchConnectedDevice> devices;
        try {
            devices = switchOperationsService.getSwitchConnectedDevices(request.getSwitchId());
        } catch (SwitchNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(),
                    "Could not get connected devices for non existent switch");
        }


        Map<Integer, List<SwitchConnectedDevice>> deviceByPort = devices.stream()
                .filter(device -> request.getSince().isBefore(device.getTimeLastSeen())
                        || request.getSince().equals(device.getTimeLastSeen()))
                .collect(Collectors.groupingBy(SwitchConnectedDevice::getPortNumber, Collectors.toList()));

        List<SwitchPortConnectedDevicesDto> ports = new ArrayList<>();

        for (Entry<Integer, List<SwitchConnectedDevice>> entry : deviceByPort.entrySet()) {
            List<SwitchConnectedDeviceDto> lldpDevices = new ArrayList<>();
            List<SwitchConnectedDeviceDto> arpDevices = new ArrayList<>();
            for (SwitchConnectedDevice device : entry.getValue()) {
                if (device.getType() == LLDP) {
                    lldpDevices.add(ConnectedDeviceMapper.INSTANCE.map(device));
                } else if (device.getType() == ARP) {
                    arpDevices.add(ConnectedDeviceMapper.INSTANCE.map(device));
                }
            }
            lldpDevices.sort(Comparator.comparing(o -> Instant.parse(o.getTimeLastSeen())));
            arpDevices.sort(Comparator.comparing(o -> Instant.parse(o.getTimeLastSeen())));
            ports.add(new SwitchPortConnectedDevicesDto(entry.getKey(), lldpDevices, arpDevices));
        }
        return new SwitchConnectedDevicesResponse(ports);
    }

    private GetSwitchResponse patchSwitch(SwitchPatchRequest request) {
        try {
            return new GetSwitchResponse(
                    switchOperationsService.patchSwitch(request.getSwitchId(), request.getSwitchPatch()));
        } catch (SwitchNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "Switch was not found.");
        }
    }

    @Override
    public void requestSwitchSync(SwitchId switchId) {
        SwitchValidateRequest data = SwitchValidateRequest.builder()
                .switchId(switchId)
                .performSync(true)
                .processMeters(true)
                .removeExcess(true)
                .build();
        getOutput().emit(StreamType.TO_SWITCH_MANAGER.toString(), getCurrentTuple(),
                new Values(data, KeyProvider.generateChainedKey(getCorrelationId())));
    }

    @Override
    public void enableServer42FlowRttOnSwitch(SwitchId switchId) {
        ActivateFlowMonitoringOnSwitchInfoData data = ActivateFlowMonitoringOnSwitchInfoData.builder()
                .switchId(switchId)
                .build();
        getOutput().emit(StreamType.TO_SERVER42.toString(), getCurrentTuple(),
                new Values(data, getCorrelationId()));
    }

    @Override
    public void disableServer42FlowRttOnSwitch(SwitchId switchId) {
        DeactivateFlowMonitoringOnSwitchInfoData data = DeactivateFlowMonitoringOnSwitchInfoData.builder()
                .switchId(switchId)
                .build();
        getOutput().emit(StreamType.TO_SERVER42.toString(), getCurrentTuple(),
                new Values(data, getCorrelationId()));
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        super.declareOutputFields(declarer);
        declarer.declareStream(StreamType.REROUTE.toString(),
                new Fields(MessageEncoder.FIELD_ID_PAYLOAD, MessageEncoder.FIELD_ID_CONTEXT));
        declarer.declareStream(StreamType.DISCO.toString(),
                new Fields(MessageEncoder.FIELD_ID_PAYLOAD, MessageEncoder.FIELD_ID_CONTEXT));
        declarer.declareStream(StreamType.TO_SWITCH_MANAGER.toString(),
                new Fields(MessageEncoder.FIELD_ID_PAYLOAD, MessageEncoder.FIELD_ID_CONTEXT));
        declarer.declareStream(StreamType.TO_SERVER42.toString(),
                new Fields(MessageEncoder.FIELD_ID_PAYLOAD, MessageEncoder.FIELD_ID_CONTEXT));
        declarer.declareStream(StreamType.TO_METRICS_BOLT.name(), AbstractTopology.fieldMessage);
    }

    @Override
    public void islBfdPropertiesChanged(Endpoint source, Endpoint destination) {
        log.warn("Discard islBfdPropertiesChanged link carrier call");
    }
}
