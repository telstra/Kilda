/* Copyright 2018 Telstra Open Source
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

package org.openkilda.testing.service.northbound;

import org.openkilda.messaging.command.switches.DeleteRulesAction;
import org.openkilda.messaging.command.switches.InstallRulesAction;
import org.openkilda.messaging.info.event.IslChangeType;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.SwitchInfoData;
import org.openkilda.messaging.info.meter.FlowMeterEntries;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.messaging.info.switches.PortDescription;
import org.openkilda.messaging.model.HealthCheck;
import org.openkilda.messaging.model.system.FeatureTogglesDto;
import org.openkilda.messaging.model.system.KildaConfigurationDto;
import org.openkilda.messaging.payload.flow.FlowIdStatusPayload;
import org.openkilda.messaging.payload.flow.FlowPathPayload;
import org.openkilda.messaging.payload.flow.FlowPayload;
import org.openkilda.messaging.payload.flow.FlowReroutePayload;
import org.openkilda.messaging.payload.flow.FlowResponsePayload;
import org.openkilda.messaging.payload.history.FlowEventPayload;
import org.openkilda.messaging.payload.network.PathsDto;
import org.openkilda.model.SwitchId;
import org.openkilda.northbound.dto.BatchResults;
import org.openkilda.northbound.dto.v1.flows.FlowAddAppDto;
import org.openkilda.northbound.dto.v1.flows.FlowAppsDto;
import org.openkilda.northbound.dto.v1.flows.FlowConnectedDevicesResponse;
import org.openkilda.northbound.dto.v1.flows.FlowDeleteAppDto;
import org.openkilda.northbound.dto.v1.flows.FlowValidationDto;
import org.openkilda.northbound.dto.v1.flows.PingInput;
import org.openkilda.northbound.dto.v1.flows.PingOutput;
import org.openkilda.northbound.dto.v1.links.LinkDto;
import org.openkilda.northbound.dto.v1.links.LinkEnableBfdDto;
import org.openkilda.northbound.dto.v1.links.LinkMaxBandwidthDto;
import org.openkilda.northbound.dto.v1.links.LinkParametersDto;
import org.openkilda.northbound.dto.v1.links.LinkPropsDto;
import org.openkilda.northbound.dto.v1.links.LinkUnderMaintenanceDto;
import org.openkilda.northbound.dto.v1.switches.DeleteMeterResult;
import org.openkilda.northbound.dto.v1.switches.DeleteSwitchResult;
import org.openkilda.northbound.dto.v1.switches.PortDto;
import org.openkilda.northbound.dto.v1.switches.RulesSyncResult;
import org.openkilda.northbound.dto.v1.switches.RulesValidationResult;
import org.openkilda.northbound.dto.v1.switches.SwitchDto;
import org.openkilda.northbound.dto.v1.switches.SwitchPropertiesDto;
import org.openkilda.northbound.dto.v1.switches.SwitchSyncResult;
import org.openkilda.northbound.dto.v1.switches.SwitchValidationResult;
import org.openkilda.northbound.dto.v2.flows.SwapFlowEndpointPayload;
import org.openkilda.northbound.dto.v2.flows.SwapFlowPayload;
import org.openkilda.testing.model.topology.TopologyDefinition.Isl;

import java.util.List;
import java.util.stream.Collectors;

public interface NorthboundService {

    HealthCheck getHealthCheck();

    //flows

    FlowResponsePayload getFlow(String flowId);

    List<FlowEventPayload> getFlowHistory(String flowId);

    List<FlowEventPayload> getFlowHistory(String flowId, Long timeFrom, Long timeTo);

    FlowPayload addFlow(FlowPayload payload);

    FlowPayload updateFlow(String flowId, FlowPayload payload);

    FlowPayload deleteFlow(String flowId);

    List<FlowPayload> deleteAllFlows();

    FlowPathPayload getFlowPath(String flowId);

    FlowIdStatusPayload getFlowStatus(String flowId);

    List<FlowPayload> getAllFlows();

    List<FlowValidationDto> validateFlow(String flowId);

    PingOutput pingFlow(String flowId, PingInput pingInput);

    FlowReroutePayload rerouteFlow(String flowId);

    FlowReroutePayload synchronizeFlow(String flowId);

    FlowMeterEntries resetMeters(String flowId);

    FlowPayload swapFlowPath(String flowId);

    SwapFlowEndpointPayload swapFlowEndpoint(SwapFlowPayload firstFlow, SwapFlowPayload secondFlow);

    FlowAppsDto getFlowApps(String flowId);

    FlowAppsDto addFlowApps(String flowId, String appName, FlowAddAppDto application);

    FlowAppsDto deleteFlowApps(String flowId, String appName, FlowDeleteAppDto application);

    //switches

    SwitchFlowEntries getSwitchRules(SwitchId switchId);

    List<Long> installSwitchRules(SwitchId switchId, InstallRulesAction installAction);

    List<Long> deleteSwitchRules(SwitchId switchId, DeleteRulesAction deleteAction);

    List<Long> deleteSwitchRules(SwitchId switchId, Integer inPort, Integer inVlan, String encapsulationType,
                                 Integer outPort);

    List<Long> deleteSwitchRules(SwitchId switchId, long cookie);

    List<Long> deleteSwitchRules(SwitchId switchId, int priority);

    RulesSyncResult synchronizeSwitchRules(SwitchId switchId);

    SwitchSyncResult synchronizeSwitch(SwitchId switchId, boolean removeExcess);

    RulesValidationResult validateSwitchRules(SwitchId switchId);

    List<SwitchInfoData> getAllSwitches();

    SwitchInfoData getSwitch(SwitchId switchId);

    SwitchDto setSwitchMaintenance(SwitchId switchId, boolean maintenance, boolean evacuate);

    DeleteMeterResult deleteMeter(SwitchId switchId, Long meterId);

    SwitchMeterEntries getAllMeters(SwitchId switchId);

    SwitchValidationResult validateSwitch(SwitchId switchId);

    DeleteSwitchResult deleteSwitch(SwitchId switchId, boolean force);

    PortDto configurePort(SwitchId switchId, Integer portNo, Object config);

    PortDto portDown(SwitchId switchId, Integer portNo);

    PortDto portUp(SwitchId switchId, Integer portNo);

    List<PortDescription> getPorts(SwitchId switchId);

    PortDescription getPort(SwitchId switchId, Integer portNo);

    List<FlowPayload> getSwitchFlows(SwitchId switchId);

    List<FlowPayload> getSwitchFlows(SwitchId switchId, Integer port);

    SwitchPropertiesDto getSwitchProperties(SwitchId switchId);

    SwitchPropertiesDto updateSwitchProperties(SwitchId switchId, SwitchPropertiesDto switchProperties);

    FlowConnectedDevicesResponse getFlowConnectedDevices(String flowId, String since);

    FlowConnectedDevicesResponse getFlowConnectedDevices(String flowId);

    //links

    List<IslInfoData> getAllLinks();

    IslInfoData getLink(Isl isl);

    List<IslInfoData> getLinks(SwitchId srcSwitch, Integer srcPort, SwitchId dstSwitch, Integer dstPort);

    List<LinkPropsDto> getAllLinkProps();

    List<LinkPropsDto> getLinkProps(SwitchId srcSwitch, Integer srcPort, SwitchId dstSwitch, Integer dstPort);

    BatchResults updateLinkProps(List<LinkPropsDto> keys);

    BatchResults deleteLinkProps(List<LinkPropsDto> keys);

    List<FlowPayload> getLinkFlows(SwitchId srcSwitch, Integer srcPort, SwitchId dstSwitch, Integer dstPort);

    List<String> rerouteLinkFlows(SwitchId srcSwitch, Integer srcPort, SwitchId dstSwitch, Integer dstPort);

    List<LinkDto> deleteLink(LinkParametersDto linkParameters);

    List<LinkDto> setLinkMaintenance(LinkUnderMaintenanceDto link);

    LinkMaxBandwidthDto updateLinkMaxBandwidth(SwitchId srcSwitch, Integer srcPort, SwitchId dstSwitch, Integer dstPort,
                                               Long linkMaxBandwidth);

    List<LinkDto> setLinkBfd(LinkEnableBfdDto link);

    //feature toggles

    FeatureTogglesDto getFeatureToggles();

    FeatureTogglesDto toggleFeature(FeatureTogglesDto request);

    //feature network

    PathsDto getPaths(SwitchId srcSwitch, SwitchId dstSwitch);

    // configuration

    KildaConfigurationDto getKildaConfiguration();

    KildaConfigurationDto updateKildaConfiguration(KildaConfigurationDto configuration);

    /**
     * Returns all active links.
     */
    default List<IslInfoData> getActiveLinks() {
        return getAllLinks().stream()
                .filter(sw -> sw.getState() == IslChangeType.DISCOVERED)
                .collect(Collectors.toList());
    }

    /**
     * Returns all active switches.
     */
    default List<SwitchInfoData> getActiveSwitches() {
        return getAllSwitches().stream()
                .filter(sw -> sw.getState().isActive())
                .collect(Collectors.toList());
    }

}
