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

import org.openkilda.messaging.Utils;
import org.openkilda.messaging.command.switches.DeleteRulesAction;
import org.openkilda.messaging.command.switches.InstallRulesAction;
import org.openkilda.messaging.info.event.IslChangeType;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.messaging.info.meter.FlowMeterEntries;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.messaging.info.switches.PortDescription;
import org.openkilda.messaging.info.switches.SwitchPortsDescription;
import org.openkilda.messaging.model.HealthCheck;
import org.openkilda.messaging.model.system.FeatureTogglesDto;
import org.openkilda.messaging.model.system.KildaConfigurationDto;
import org.openkilda.messaging.payload.flow.FlowIdStatusPayload;
import org.openkilda.messaging.payload.flow.FlowPathPayload;
import org.openkilda.messaging.payload.flow.FlowPayload;
import org.openkilda.messaging.payload.flow.FlowReroutePayload;
import org.openkilda.messaging.payload.flow.FlowResponsePayload;
import org.openkilda.messaging.payload.history.FlowHistoryEntry;
import org.openkilda.messaging.payload.network.PathsDto;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PortStatus;
import org.openkilda.model.SwitchId;
import org.openkilda.northbound.dto.BatchResults;
import org.openkilda.northbound.dto.v1.flows.FlowConnectedDevicesResponse;
import org.openkilda.northbound.dto.v1.flows.FlowPatchDto;
import org.openkilda.northbound.dto.v1.flows.FlowValidationDto;
import org.openkilda.northbound.dto.v1.flows.PingInput;
import org.openkilda.northbound.dto.v1.flows.PingOutput;
import org.openkilda.northbound.dto.v1.links.LinkDto;
import org.openkilda.northbound.dto.v1.links.LinkEnableBfdDto;
import org.openkilda.northbound.dto.v1.links.LinkMaxBandwidthDto;
import org.openkilda.northbound.dto.v1.links.LinkMaxBandwidthRequest;
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
import org.openkilda.northbound.dto.v1.switches.SwitchSyncRequest;
import org.openkilda.northbound.dto.v1.switches.SwitchSyncResult;
import org.openkilda.northbound.dto.v1.switches.SwitchValidationResult;
import org.openkilda.northbound.dto.v1.switches.UnderMaintenanceDto;
import org.openkilda.northbound.dto.v2.flows.SwapFlowEndpointPayload;
import org.openkilda.northbound.dto.v2.flows.SwapFlowPayload;
import org.openkilda.testing.model.topology.TopologyDefinition.Isl;

import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class NorthboundServiceImpl implements NorthboundService {

    private static final String KILDA_CONTROLLER = "kilda";

    @Autowired
    @Qualifier("northboundRestTemplate")
    private RestTemplate restTemplate;

    @Override
    public HealthCheck getHealthCheck() {
        return restTemplate.exchange("/api/v1/health-check", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), HealthCheck.class).getBody();
    }

    @Override
    public FlowResponsePayload getFlow(String flowId) {
        return restTemplate.exchange("/api/v1/flows/{flow_id}", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), FlowResponsePayload.class, flowId).getBody();
    }

    @Override
    public List<FlowHistoryEntry> getFlowHistory(String flowId) {
        return getFlowHistory(flowId, null, null);
    }

    @Override
    public List<FlowHistoryEntry> getFlowHistory(String flowId, Long timeFrom, Long timeTo) {
        return getFlowHistory(flowId, timeFrom, timeTo, null);
    }

    @Override
    public List<FlowHistoryEntry> getFlowHistory(String flowId, Integer maxCount) {
        return getFlowHistory(flowId, null, null, maxCount);
    }

    @Override
    public List<FlowHistoryEntry> getFlowHistory(String flowId, Long timeFrom, Long timeTo, Integer maxCount) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/flows/{flow_id}/history");
        if (timeFrom != null) {
            uriBuilder.queryParam("timeFrom", timeFrom);
        }
        if (timeTo != null) {
            uriBuilder.queryParam("timeTo", timeTo);
        }
        if (maxCount != null) {
            uriBuilder.queryParam("max_count", maxCount);
        }
        FlowHistoryEntry[] flowHistory = restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), FlowHistoryEntry[].class, flowId).getBody();
        return Arrays.asList(flowHistory);
    }

    @Override
    public FlowPayload addFlow(FlowPayload payload) {
        HttpEntity<FlowPayload> httpEntity = new HttpEntity<>(payload, buildHeadersWithCorrelationId());
        return restTemplate.exchange("/api/v1/flows", HttpMethod.PUT, httpEntity, FlowPayload.class).getBody();
    }

    @Override
    public FlowPayload updateFlow(String flowId, FlowPayload payload) {
        HttpEntity<FlowPayload> httpEntity = new HttpEntity<>(payload, buildHeadersWithCorrelationId());
        return restTemplate.exchange("/api/v1/flows/{flow_id}", HttpMethod.PUT, httpEntity,
                FlowPayload.class, flowId).getBody();
    }

    @Override
    public FlowPayload deleteFlow(String flowId) {
        try {
            return restTemplate.exchange("/api/v1/flows/{flow_id}", HttpMethod.DELETE,
                    new HttpEntity(buildHeadersWithCorrelationId()), FlowPayload.class, flowId).getBody();
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw ex;
            }

            return null;
        }
    }

    @Override
    public List<FlowPayload> deleteAllFlows() {
        HttpHeaders httpHeaders = buildHeadersWithCorrelationId();
        httpHeaders.set(Utils.EXTRA_AUTH, String.valueOf(System.currentTimeMillis()));
        FlowPayload[] deletedFlows = restTemplate.exchange("/api/v1/flows", HttpMethod.DELETE,
                new HttpEntity(httpHeaders), FlowPayload[].class).getBody();
        return Arrays.asList(deletedFlows);
    }

    @Override
    public FlowPathPayload getFlowPath(String flowId) {
        return restTemplate.exchange("/api/v1/flows/{flow_id}/path/", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), FlowPathPayload.class, flowId).getBody();
    }

    @Override
    public FlowIdStatusPayload getFlowStatus(String flowId) {
        try {
            return restTemplate.exchange("/api/v1/flows/status/{flow_id}", HttpMethod.GET,
                    new HttpEntity(buildHeadersWithCorrelationId()), FlowIdStatusPayload.class, flowId).getBody();
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw ex;
            }
            return null;
        }
    }

    @Override
    public List<FlowPayload> getAllFlows() {
        FlowPayload[] flows = restTemplate.exchange("/api/v1/flows", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), FlowPayload[].class).getBody();
        return Arrays.asList(flows);
    }

    @Override
    public List<Long> installSwitchRules(SwitchId switchId, InstallRulesAction installAction) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/switches/{switch_id}/rules");
        uriBuilder.queryParam("install-action", installAction);

        HttpHeaders httpHeaders = buildHeadersWithCorrelationId();
        httpHeaders.set(Utils.EXTRA_AUTH, String.valueOf(System.currentTimeMillis()));

        Long[] installedRules = restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.PUT,
                new HttpEntity(httpHeaders), Long[].class, switchId).getBody();
        return Arrays.asList(installedRules);
    }

    @Override
    public List<Long> deleteSwitchRules(SwitchId switchId, DeleteRulesAction deleteAction) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/switches/{switch_id}/rules");
        uriBuilder.queryParam("delete-action", deleteAction);

        HttpHeaders httpHeaders = buildHeadersWithCorrelationId();
        httpHeaders.set(Utils.EXTRA_AUTH, String.valueOf(System.currentTimeMillis()));

        Long[] deletedRules = restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.DELETE,
                new HttpEntity(httpHeaders), Long[].class, switchId).getBody();
        return Arrays.asList(deletedRules);
    }

    @Override
    public List<Long> deleteSwitchRules(SwitchId switchId, Integer inPort, Integer inVlan, String encapsulationType,
                                        Integer outPort) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/switches/{switch_id}/rules");
        if (inPort != null) {
            uriBuilder.queryParam("in-port", inPort);
        }
        if (inVlan != null) {
            uriBuilder.queryParam("in-vlan", inVlan);
        }
        if (encapsulationType != null) {
            uriBuilder.queryParam("encapsulation-type", encapsulationType);
        }
        if (outPort != null) {
            uriBuilder.queryParam("out-port", outPort);
        }

        HttpHeaders httpHeaders = buildHeadersWithCorrelationId();
        httpHeaders.set(Utils.EXTRA_AUTH, String.valueOf(System.currentTimeMillis()));

        Long[] deletedRules = restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.DELETE,
                new HttpEntity(httpHeaders), Long[].class, switchId).getBody();
        return Arrays.asList(deletedRules);
    }

    @Override
    public List<Long> deleteSwitchRules(SwitchId switchId, long cookie) {
        HttpHeaders httpHeaders = buildHeadersWithCorrelationId();
        httpHeaders.set(Utils.EXTRA_AUTH, String.valueOf(System.currentTimeMillis()));

        Long[] deletedRules = restTemplate.exchange(
                "/api/v1/switches/{switch_id}/rules?cookie={cookie}",
                HttpMethod.DELETE, new HttpEntity(httpHeaders), Long[].class, switchId, cookie).getBody();
        return Arrays.asList(deletedRules);
    }

    @Override
    public List<Long> deleteSwitchRules(SwitchId switchId, int priority) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/switches/{switch_id}/rules");
        uriBuilder.queryParam("priority", priority);

        HttpHeaders httpHeaders = buildHeadersWithCorrelationId();
        httpHeaders.set(Utils.EXTRA_AUTH, String.valueOf(System.currentTimeMillis()));

        Long[] deletedRules = restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.DELETE,
                new HttpEntity(httpHeaders), Long[].class, switchId).getBody();
        return Arrays.asList(deletedRules);
    }

    @Override
    public RulesSyncResult synchronizeSwitchRules(SwitchId switchId) {
        return restTemplate.exchange("/api/v1/switches/{switch_id}/rules/synchronize", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), RulesSyncResult.class, switchId).getBody();
    }

    @Override
    public SwitchSyncResult synchronizeSwitch(SwitchId switchId, boolean removeExcess) {
        HttpEntity<SwitchSyncRequest> httpEntity = new HttpEntity<>(new SwitchSyncRequest(removeExcess),
                buildHeadersWithCorrelationId());
        return restTemplate.exchange("/api/v1/switches/{switch_id}/synchronize", HttpMethod.PATCH, httpEntity,
                SwitchSyncResult.class, switchId).getBody();
    }

    @Override
    public List<FlowValidationDto> validateFlow(String flowId) {
        FlowValidationDto[] flowValidations = restTemplate.exchange("/api/v1/flows/{flow_id}/validate",
                HttpMethod.GET, new HttpEntity(buildHeadersWithCorrelationId()),
                FlowValidationDto[].class, flowId).getBody();
        return Arrays.asList(flowValidations);
    }

    @Override
    public PingOutput pingFlow(String flowId, PingInput pingInput) {
        HttpEntity<PingInput> httpEntity = new HttpEntity<>(pingInput, buildHeadersWithCorrelationId());
        return restTemplate.exchange("/api/v1/flows/{flow_id}/ping", HttpMethod.PUT, httpEntity,
                PingOutput.class, flowId).getBody();
    }

    @Override
    public FlowReroutePayload rerouteFlow(String flowId) {
        return restTemplate.exchange("/api/v1/flows/{flowId}/reroute", HttpMethod.PATCH,
                new HttpEntity(buildHeadersWithCorrelationId()), FlowReroutePayload.class, flowId).getBody();
    }

    @Override
    public FlowReroutePayload synchronizeFlow(String flowId) {
        return restTemplate.exchange("/api/v1/flows/{flowId}/sync", HttpMethod.PATCH,
                new HttpEntity(buildHeadersWithCorrelationId()), FlowReroutePayload.class, flowId).getBody();
    }

    @Override
    public FlowMeterEntries resetMeters(String flowId) {
        return restTemplate.exchange("/api/v1/flows/{flowId}/meters", HttpMethod.PATCH,
                new HttpEntity(buildHeadersWithCorrelationId()), FlowMeterEntries.class, flowId).getBody();
    }

    @Override
    public FlowPayload swapFlowPath(String flowId) {
        return restTemplate.exchange("/api/v1/flows/{flowId}/swap", HttpMethod.PATCH,
                new HttpEntity(buildHeadersWithCorrelationId()), FlowPayload.class, flowId).getBody();
    }

    @Override
    public SwapFlowEndpointPayload swapFlowEndpoint(SwapFlowPayload firstFlow, SwapFlowPayload secondFlow) {
        log.debug("Swap flow endpoints. First flow: {}. Second flow: {}", firstFlow, secondFlow);
        HttpEntity<SwapFlowEndpointPayload> httpEntity = new HttpEntity<>(
                new SwapFlowEndpointPayload(firstFlow, secondFlow), buildHeadersWithCorrelationId());
        return restTemplate.exchange("/api/v2/flows/swap-endpoint", HttpMethod.POST, httpEntity,
                SwapFlowEndpointPayload.class).getBody();
    }

    @Override
    public FlowResponsePayload partialUpdate(String flowId, FlowPatchDto payload) {
        return restTemplate.exchange("/api/v1/flows/{flow_id}", HttpMethod.PATCH,
                new HttpEntity<>(payload, buildHeadersWithCorrelationId()), FlowResponsePayload.class, flowId)
                .getBody();
    }

    @Override
    public SwitchFlowEntries getSwitchRules(SwitchId switchId) {
        return restTemplate.exchange("/api/v1/switches/{switch_id}/rules", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), SwitchFlowEntries.class, switchId).getBody();
    }

    @Override
    public RulesValidationResult validateSwitchRules(SwitchId switchId) {
        return restTemplate.exchange("/api/v1/switches/{switch_id}/rules/validate", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), RulesValidationResult.class, switchId).getBody();
    }

    @Override
    public List<IslInfoData> getAllLinks() {
        return getLinks(null, null, null, null);
    }

    @Override
    public IslInfoData getLink(Isl isl) {
        return getLinks(isl.getSrcSwitch().getDpId(), isl.getSrcPort(), isl.getDstSwitch().getDpId(),
                isl.getDstPort()).get(0);
    }

    @Override
    public List<IslInfoData> getLinks(SwitchId srcSwitch, Integer srcPort, SwitchId dstSwitch, Integer dstPort) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/links");
        if (srcSwitch != null) {
            uriBuilder.queryParam("src_switch", srcSwitch);
        }
        if (srcPort != null) {
            uriBuilder.queryParam("src_port", srcPort);
        }
        if (dstSwitch != null) {
            uriBuilder.queryParam("dst_switch", dstSwitch);
        }
        if (dstPort != null) {
            uriBuilder.queryParam("dst_port", dstPort);
        }
        LinkDto[] links = restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), LinkDto[].class).getBody();
        return Stream.of(links)
                .map(this::convertToIslInfoData)
                .collect(Collectors.toList());
    }

    @Override
    public List<LinkPropsDto> getAllLinkProps() {
        return getLinkProps(null, null, null, null);
    }

    @Override
    public List<LinkPropsDto> getLinkProps(SwitchId srcSwitch, Integer srcPort, SwitchId dstSwitch, Integer dstPort) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/link/props");
        if (srcSwitch != null) {
            uriBuilder.queryParam("src_switch", srcSwitch);
        }
        if (srcPort != null) {
            uriBuilder.queryParam("src_port", srcPort);
        }
        if (dstSwitch != null) {
            uriBuilder.queryParam("dst_switch", dstSwitch);
        }
        if (dstPort != null) {
            uriBuilder.queryParam("dst_port", dstPort);
        }
        LinkPropsDto[] linkProps = restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), LinkPropsDto[].class).getBody();
        return Arrays.asList(linkProps);
    }

    @Override
    public BatchResults updateLinkProps(List<LinkPropsDto> keys) {
        HttpEntity<List<LinkPropsDto>> httpEntity = new HttpEntity<>(keys, buildHeadersWithCorrelationId());
        return restTemplate.exchange("/api/v1/link/props", HttpMethod.PUT, httpEntity,
                BatchResults.class).getBody();
    }

    @Override
    public BatchResults deleteLinkProps(List<LinkPropsDto> keys) {
        HttpEntity<List<LinkPropsDto>> httpEntity = new HttpEntity<>(keys, buildHeadersWithCorrelationId());
        return restTemplate.exchange("/api/v1/link/props", HttpMethod.DELETE, httpEntity,
                BatchResults.class).getBody();
    }

    @Override
    public List<FlowPayload> getLinkFlows(SwitchId srcSwitch, Integer srcPort,
                                          SwitchId dstSwitch, Integer dstPort) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/links/flows");
        if (srcSwitch != null) {
            uriBuilder.queryParam("src_switch", srcSwitch);
        }
        if (srcPort != null) {
            uriBuilder.queryParam("src_port", srcPort);
        }
        if (dstSwitch != null) {
            uriBuilder.queryParam("dst_switch", dstSwitch);
        }
        if (dstPort != null) {
            uriBuilder.queryParam("dst_port", dstPort);
        }
        FlowPayload[] linkFlows = restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), FlowPayload[].class).getBody();
        return Arrays.asList(linkFlows);
    }

    @Override
    public List<String> rerouteLinkFlows(SwitchId srcSwitch, Integer srcPort,
                                         SwitchId dstSwitch, Integer dstPort) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/links/flows/reroute");
        if (srcSwitch != null) {
            uriBuilder.queryParam("src_switch", srcSwitch);
        }
        if (srcPort != null) {
            uriBuilder.queryParam("src_port", srcPort);
        }
        if (dstSwitch != null) {
            uriBuilder.queryParam("dst_switch", dstSwitch);
        }
        if (dstPort != null) {
            uriBuilder.queryParam("dst_port", dstPort);
        }
        String[] flowIds = restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.PATCH,
                new HttpEntity(buildHeadersWithCorrelationId()), String[].class).getBody();
        return Arrays.asList(flowIds);
    }

    @Override
    public List<LinkDto> deleteLink(LinkParametersDto linkParameters) {
        return deleteLink(linkParameters, false);
    }

    @Override
    public List<LinkDto> deleteLink(LinkParametersDto linkParameters, boolean force) {
        LinkDto[] updatedLink = restTemplate.exchange("/api/v1/links?force={force}", HttpMethod.DELETE,
                new HttpEntity<>(linkParameters, buildHeadersWithCorrelationId()),
                LinkDto[].class, force).getBody();
        return Arrays.asList(updatedLink);
    }

    @Override
    public List<LinkDto> setLinkMaintenance(LinkUnderMaintenanceDto link) {
        LinkDto[] updatedLink = restTemplate.exchange("/api/v1/links/under-maintenance", HttpMethod.PATCH,
                new HttpEntity<>(link, buildHeadersWithCorrelationId()), LinkDto[].class).getBody();
        return Arrays.asList(updatedLink);
    }

    @Override
    public LinkMaxBandwidthDto updateLinkMaxBandwidth(SwitchId srcSwitch, Integer srcPort, SwitchId dstSwitch,
                                                      Integer dstPort, Long linkMaxBandwidth) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/links/bandwidth");
        if (srcSwitch != null) {
            uriBuilder.queryParam("src_switch", srcSwitch);
        }
        if (srcPort != null) {
            uriBuilder.queryParam("src_port", srcPort);
        }
        if (dstSwitch != null) {
            uriBuilder.queryParam("dst_switch", dstSwitch);
        }
        if (dstPort != null) {
            uriBuilder.queryParam("dst_port", dstPort);
        }
        return restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.PATCH,
                new HttpEntity<>(new LinkMaxBandwidthRequest(linkMaxBandwidth), buildHeadersWithCorrelationId()),
                LinkMaxBandwidthDto.class).getBody();
    }

    @Override
    public List<LinkDto> setLinkBfd(LinkEnableBfdDto link) {
        log.debug("Changing bfd status to '{}' for link {}:{}-{}:{}", link.isEnableBfd(), link.getSrcSwitch(),
                link.getSrcPort(), link.getDstSwitch(), link.getDstPort());
        LinkDto[] updatedLinks = restTemplate.exchange("api/v1/links/enable-bfd", HttpMethod.PATCH,
                new HttpEntity<>(link, buildHeadersWithCorrelationId()), LinkDto[].class).getBody();
        return Arrays.asList(updatedLinks);
    }

    @Override
    public FeatureTogglesDto getFeatureToggles() {
        return restTemplate.exchange("/api/v1/features", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), FeatureTogglesDto.class).getBody();
    }

    @Override
    public FeatureTogglesDto toggleFeature(FeatureTogglesDto request) {
        HttpEntity<FeatureTogglesDto> httpEntity = new HttpEntity<>(request, buildHeadersWithCorrelationId());
        return restTemplate.exchange("/api/v1/features", HttpMethod.PATCH, httpEntity,
                FeatureTogglesDto.class).getBody();
    }

    @Override
    public List<SwitchDto> getAllSwitches() {
        return Arrays.asList(restTemplate.exchange("/api/v1/switches", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), SwitchDto[].class).getBody());
    }

    @Override
    public SwitchDto getSwitch(SwitchId switchId) {
        return restTemplate.exchange("/api/v1/switches/{switch_id}", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), SwitchDto.class, switchId).getBody();
    }

    @Override
    public SwitchDto setSwitchMaintenance(SwitchId switchId, boolean maintenance, boolean evacuate) {
        return restTemplate.exchange("api/v1/switches/{switch_id}/under-maintenance", HttpMethod.POST,
                new HttpEntity<>(new UnderMaintenanceDto(maintenance, evacuate), buildHeadersWithCorrelationId()),
                SwitchDto.class, switchId).getBody();
    }

    @Override
    public DeleteMeterResult deleteMeter(SwitchId switchId, Long meterId) {
        return restTemplate.exchange("/api/v1/switches/{switch_id}/meter/{meter_id}", HttpMethod.DELETE,
                new HttpEntity(buildHeadersWithCorrelationId()), DeleteMeterResult.class, switchId, meterId).getBody();
    }

    @Override
    public SwitchMeterEntries getAllMeters(SwitchId switchId) {
        return restTemplate.exchange("/api/v1/switches/{switch_id}/meters", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), SwitchMeterEntries.class, switchId).getBody();
    }

    @Override
    public SwitchValidationResult validateSwitch(SwitchId switchId) {
        log.debug("Switch validating '{}'", switchId);
        return restTemplate.exchange("/api/v1/switches/{switch_id}/validate", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), SwitchValidationResult.class, switchId).getBody();
    }

    @Override
    public DeleteSwitchResult deleteSwitch(SwitchId switchId, boolean force) {
        HttpHeaders httpHeaders = buildHeadersWithCorrelationId();
        httpHeaders.set(Utils.EXTRA_AUTH, String.valueOf(System.currentTimeMillis()));
        String url = "/api/v1/switches/{switch_id}?force={force}";
        return restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity(httpHeaders),
                DeleteSwitchResult.class, switchId, force).getBody();
    }

    @Override
    public PortDto configurePort(SwitchId switchId, Integer portNo, Object config) {
        HttpEntity<Object> httpEntity = new HttpEntity<>(config, buildHeadersWithCorrelationId());
        return restTemplate.exchange("/api/v1/switches/{switch_id}/port/{port_id}/config", HttpMethod.PUT,
                httpEntity, PortDto.class, switchId, portNo).getBody();
    }

    @Override
    public PortDto portUp(SwitchId switchId, Integer portNo) {
        log.debug("Bringing port up for switch {}, port {}", switchId, portNo);
        return configurePort(switchId, portNo, ImmutableMap.of("status", PortStatus.UP));
    }

    @Override
    public PortDto portDown(SwitchId switchId, Integer portNo) {
        log.debug("Bringing port down for switch {}, port {}", switchId, portNo);
        return configurePort(switchId, portNo, ImmutableMap.of("status", PortStatus.DOWN));
    }

    @Override
    public List<PortDescription> getPorts(SwitchId switchId) {
        SwitchPortsDescription ports = restTemplate.exchange("/api/v1/switches/{switch_id}/ports", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), SwitchPortsDescription.class, switchId).getBody();
        return ports.getPortsDescription();
    }

    @Override
    public PortDescription getPort(SwitchId switchId, Integer portNo) {
        return restTemplate.exchange("/api/v1/switches/{switch_id}/ports/{port_id}", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), PortDescription.class, switchId, portNo).getBody();
    }

    @Override
    public List<FlowPayload> getSwitchFlows(SwitchId switchId) {
        FlowPayload[] switchFlows = restTemplate.exchange("/api/v1/switches/{switch_id}/flows", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), FlowPayload[].class, switchId).getBody();
        return Arrays.asList(switchFlows);
    }

    @Override
    public List<FlowPayload> getSwitchFlows(SwitchId switchId, Integer port) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/switches/{switch_id}/flows");
        uriBuilder.queryParam("port", port);
        FlowPayload[] switchFlows = restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), FlowPayload[].class, switchId).getBody();
        return Arrays.asList(switchFlows);
    }

    @Override
    public PathsDto getPaths(SwitchId srcSwitch, SwitchId dstSwitch, FlowEncapsulationType flowEncapsulationType,
                             PathComputationStrategy pathComputationStrategy) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/network/paths");
        if (srcSwitch != null) {
            uriBuilder.queryParam("src_switch", srcSwitch);
        }
        if (dstSwitch != null) {
            uriBuilder.queryParam("dst_switch", dstSwitch);
        }
        if (flowEncapsulationType != null) {
            uriBuilder.queryParam("encapsulation_type", flowEncapsulationType);
        }
        if (pathComputationStrategy != null) {
            uriBuilder.queryParam("path_computation_strategy", pathComputationStrategy);
        }

        return restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), PathsDto.class, srcSwitch, dstSwitch).getBody();
    }

    @Override
    public SwitchPropertiesDto getSwitchProperties(SwitchId switchId) {
        return restTemplate.exchange("/api/v1/switches/{switch_id}/properties", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), SwitchPropertiesDto.class, switchId).getBody();
    }

    @Override
    public SwitchPropertiesDto updateSwitchProperties(SwitchId switchId, SwitchPropertiesDto switchFeatures) {
        log.debug("Update switch properties on the switch: {} with new properties: {}", switchId, switchFeatures);
        return restTemplate.exchange("/api/v1/switches/{switch_id}/properties", HttpMethod.PUT,
                new HttpEntity<>(switchFeatures, buildHeadersWithCorrelationId()), SwitchPropertiesDto.class,
                switchId).getBody();
    }

    @Override
    public FlowConnectedDevicesResponse getFlowConnectedDevices(String flowId, String since) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString("/api/v1/flows/{flow_id}/devices");
        if (since != null) {
            uriBuilder.queryParam("since", since);
        }
        return restTemplate.exchange(uriBuilder.build().toString(), HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()),
                FlowConnectedDevicesResponse.class, flowId).getBody();
    }

    @Override
    public FlowConnectedDevicesResponse getFlowConnectedDevices(String flowId) {
        return getFlowConnectedDevices(flowId, null);
    }

    @Override
    public KildaConfigurationDto getKildaConfiguration() {
        return restTemplate.exchange("/api/v1/config", HttpMethod.GET,
                new HttpEntity(buildHeadersWithCorrelationId()), KildaConfigurationDto.class).getBody();
    }

    @Override
    public KildaConfigurationDto updateKildaConfiguration(KildaConfigurationDto configuration) {
        log.debug("Update kilda configuration: {}", configuration);
        return restTemplate.exchange("/api/v1/config", HttpMethod.PATCH,
                new HttpEntity<>(configuration, buildHeadersWithCorrelationId()),
                KildaConfigurationDto.class).getBody();
    }

    private HttpHeaders buildHeadersWithCorrelationId() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(Utils.CORRELATION_ID, "fn-tests-" + UUID.randomUUID().toString());
        return headers;
    }

    private IslInfoData convertToIslInfoData(LinkDto dto) {
        List<PathNode> path = dto.getPath().stream()
                .map(pathDto -> new PathNode(
                        new SwitchId(pathDto.getSwitchId()),
                        pathDto.getPortNo(),
                        pathDto.getSeqId(),
                        pathDto.getSegLatency()))
                .collect(Collectors.toList());
        return IslInfoData.builder()
                .source(path.get(0))
                .destination(path.get(1))
                .speed(dto.getSpeed())
                .state(IslChangeType.from(dto.getState().toString()))
                .actualState(IslChangeType.from(dto.getActualState().toString()))
                .cost(dto.getCost())
                .availableBandwidth(dto.getAvailableBandwidth())
                .defaultMaxBandwidth(dto.getDefaultMaxBandwidth())
                .maxBandwidth(dto.getMaxBandwidth())
                .underMaintenance(dto.isUnderMaintenance())
                .latency(dto.getLatency())
                .bfdSessionStatus(dto.getBfdSessionStatus())
                .enableBfd(dto.isEnableBfd())
                .roundTripStatus(dto.getRoundTripStatus() != null 
                        ? IslChangeType.from(dto.getRoundTripStatus().toString()) : null)
                .build();
    }
}
