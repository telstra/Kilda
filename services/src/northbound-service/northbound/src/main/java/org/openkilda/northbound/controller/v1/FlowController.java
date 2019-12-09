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

package org.openkilda.northbound.controller.v1;

import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.MessageException;
import org.openkilda.messaging.info.flow.FlowInfoData;
import org.openkilda.messaging.info.meter.FlowMeterEntries;
import org.openkilda.messaging.payload.flow.FlowCreatePayload;
import org.openkilda.messaging.payload.flow.FlowIdStatusPayload;
import org.openkilda.messaging.payload.flow.FlowPathPayload;
import org.openkilda.messaging.payload.flow.FlowReroutePayload;
import org.openkilda.messaging.payload.flow.FlowResponsePayload;
import org.openkilda.messaging.payload.flow.FlowUpdatePayload;
import org.openkilda.messaging.payload.history.FlowEventPayload;
import org.openkilda.northbound.controller.BaseController;
import org.openkilda.northbound.dto.BatchResults;
import org.openkilda.northbound.dto.v1.flows.FlowAddAppDto;
import org.openkilda.northbound.dto.v1.flows.FlowAppsDto;
import org.openkilda.northbound.dto.v1.flows.FlowConnectedDevicesResponse;
import org.openkilda.northbound.dto.v1.flows.FlowDeleteAppDto;
import org.openkilda.northbound.dto.v1.flows.FlowPatchDto;
import org.openkilda.northbound.dto.v1.flows.FlowValidationDto;
import org.openkilda.northbound.dto.v1.flows.PingInput;
import org.openkilda.northbound.dto.v1.flows.PingOutput;
import org.openkilda.northbound.service.FlowService;
import org.openkilda.northbound.utils.ExtraAuthRequired;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


/**
 * REST Controller for flow requests.
 */
@RestController
@RequestMapping("/v1/flows")
@PropertySource("classpath:northbound.properties")
public class FlowController extends BaseController {
    /**
     * The logger.
     */
    private static final Logger logger = LoggerFactory.getLogger(FlowController.class);

    /**
     * The flow service instance.
     */
    @Autowired
    private FlowService flowService;

    /**
     * Creates new flow.
     *
     * @param flow          flow
     * @return flow
     */
    @ApiOperation(value = "Creates new flow", response = FlowResponsePayload.class)
    @PutMapping
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowResponsePayload> createFlow(@RequestBody FlowCreatePayload flow) {
        return flowService.createFlow(flow);
    }

    /**
     * Gets flow.
     *
     * @param flowId        flow id
     * @return flow
     */
    @ApiOperation(value = "Gets flow", response = FlowResponsePayload.class)
    @GetMapping(value = "/{flow-id:.+}")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowResponsePayload> getFlow(@PathVariable(name = "flow-id") String flowId) {
        return flowService.getFlow(flowId);
    }

    /**
     * Deletes flow.
     *
     * @param flowId        flow id
     * @return flow
     */
    @ApiOperation(value = "Deletes flow", response = FlowResponsePayload.class)
    @DeleteMapping(value = "/{flow-id:.+}")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowResponsePayload> deleteFlow(@PathVariable(name = "flow-id") String flowId) {
        return flowService.deleteFlow(flowId);
    }

    /**
     * Updates existing flow.
     *
     * @param flow          flow
     * @param flowId        flow id
     * @return flow
     */
    @ApiOperation(value = "Updates flow", response = FlowResponsePayload.class)
    @PutMapping(value = "/{flow-id:.+}")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowResponsePayload> updateFlow(@PathVariable(name = "flow-id") String flowId,
                                                             @RequestBody FlowUpdatePayload flow) {
        return flowService.updateFlow(flow);
    }

    /**
     * Updates max latency or priority of existing flow.
     *
     * @param flowPatchDto  flow parameters for update
     * @param flowId        flow id
     * @return flow
     */
    @ApiOperation(value = "Updates flow", response = FlowResponsePayload.class)
    @PatchMapping(value = "/{flow-id:.+}")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowResponsePayload> patchFlow(@PathVariable(name = "flow-id") String flowId,
                                                            @RequestBody FlowPatchDto flowPatchDto) {
        return flowService.patchFlow(flowId, flowPatchDto);
    }

    /**
     * Dumps all flows. Dumps all flows with specific status if specified.
     *
     * @return list of flow
     */
    @ApiOperation(value = "Dumps all flows", response = FlowResponsePayload.class, responseContainer = "List")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<List<FlowResponsePayload>> getFlows() {
        return flowService.getAllFlows();
    }

    /**
     * Delete all flows.
     *
     * @return list of flows that have been deleted
     */
    @ApiOperation(value = "Delete all flows. Requires special authorization", response = FlowResponsePayload.class,
            responseContainer = "List")
    @DeleteMapping
    @ResponseStatus(HttpStatus.OK)
    @ExtraAuthRequired
    public CompletableFuture<List<FlowResponsePayload>> deleteFlows() {
        return flowService.deleteAllFlows();
    }

    /**
     * Gets flow status.
     *
     * @param flowId        flow id
     * @return list of flow
     */
    @ApiOperation(value = "Gets flow status", response = FlowIdStatusPayload.class)
    @GetMapping(value = "/status/{flow-id:.+}")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowIdStatusPayload> statusFlow(@PathVariable(name = "flow-id") String flowId) {
        return flowService.statusFlow(flowId);
    }

    /**
     * Gets flow path.
     *
     * @param flowId        flow id
     * @return list of flow
     */
    @ApiOperation(value = "Gets flow path", response = FlowPathPayload.class)
    @GetMapping(value = "/{flow-id}/path")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowPathPayload> pathFlow(@PathVariable(name = "flow-id") String flowId) {
        return flowService.pathFlow(flowId);
    }


    /**
     * Push flows to kilda ... this can be used to get flows into kilda without kilda creating them
     * itself. Kilda won't expect to create them .. it may (and should) validate them at some stage.
     *
     * @param externalFlows a list of flows to push to kilda for it to absorb without expectation of creating the flow
     *        rules
     * @return list of flow
     */
    @ApiOperation(value = "Push flows without expectation of modifying switches. It can push to switch and validate.",
            response = BatchResults.class)
    @PutMapping(path = "/push")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<BatchResults> pushFlows(
            @RequestBody List<FlowInfoData> externalFlows,
            @ApiParam(value = "default: false. If true, this will propagate rules to the switches.",
                    required = false)
            @RequestParam(value = "propagate", required = false) Optional<Boolean> propagate,
            @ApiParam(value = "default: false. If true, will wait until poll timeout for validation.",
                    required = false)
            @RequestParam("verify") Optional<Boolean> verify) {

        Boolean defaultPropagate = false;
        Boolean defaultVerify = false;
        return flowService.pushFlows(externalFlows, propagate.orElse(defaultPropagate), verify.orElse(defaultVerify));
    }


    /**
     * Unpush flows to kilda ... essentially the opposite of push.
     *
     * @param externalFlows a list of flows to unpush without propagation to Floodlight
     * @return list of flow
     */
    @ApiOperation(value = "Unpush flows without expectation of modifying switches. It can push to switch and validate.",
            response = BatchResults.class)
    @PutMapping(path = "/unpush")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<BatchResults> unpushFlows(
            @RequestBody List<FlowInfoData> externalFlows,
            @ApiParam(value = "default: false. If true, this will propagate rules to the switches.",
                    required = false)
            @RequestParam(value = "propagate", required = false) Optional<Boolean> propagate,
            @ApiParam(value = "default: false. If true, will wait until poll timeout for validation.",
                    required = false)
            @RequestParam(value = "verify", required = false) Optional<Boolean> verify) {
        Boolean defaultPropagate = false;
        Boolean defaultVerify = false;
        return flowService.unpushFlows(externalFlows, propagate.orElse(defaultPropagate), verify.orElse(defaultVerify));
    }


    /**
     * Initiates flow rerouting if any shorter paths are available.
     *
     * @param flowId id of flow to be rerouted.
     * @return flow payload with updated path.
     */
    @ApiOperation(value = "Reroute flow", response = FlowReroutePayload.class)
    @PatchMapping(path = "/{flow_id}/reroute")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowReroutePayload> rerouteFlow(@PathVariable("flow_id") String flowId) {
        return flowService.rerouteFlow(flowId);
    }

    /**
     * Initiates flow paths swapping for flow with protected path.
     *
     * @param flowId id of flow to swap paths.
     * @return flow payload.
     */
    @ApiOperation(value = "Swap paths for flow with protected path", response = FlowResponsePayload.class)
    @PatchMapping(path = "/{flow_id}/swap")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowResponsePayload> swapFlowPaths(@PathVariable("flow_id") String flowId) {
        return flowService.swapFlowPaths(flowId);
    }

    /**
     * Initiates flow synchronization (reinstalling). In other words it means flow update with newly generated rules.
     *
     * @param flowId id of flow to be rerouted.
     * @return flow payload with updated path.
     */
    @ApiOperation(value = "Sync flow", response = FlowReroutePayload.class)
    @ApiResponses(value = {
            @ApiResponse(code = 200, response = FlowReroutePayload.class, message = "Operation is successful")})
    @PatchMapping(path = "/{flow_id}/sync")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowReroutePayload> syncFlow(@PathVariable("flow_id") String flowId) {
        return flowService.syncFlow(flowId);
    }

    /**
     * Compares the Flow from the DB to what is on each switch.
     *
     * @param flowId id of flow to be rerouted.
     * @return flow payload with updated path.
     */
    @ApiOperation(value = "Validate flow, comparing the DB to each switch", response = FlowValidationDto.class,
            responseContainer = "List")
    @GetMapping(path = "/{flow_id}/validate")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<List<FlowValidationDto>> validateFlow(@PathVariable("flow_id") String flowId) {
        logger.debug("Received Flow Validation request with flow {}", flowId);
        return flowService.validateFlow(flowId);
    }

    /**
     * Verify flow integrity by sending "ping" package over flow path.
     */
    @ApiOperation(
            value = "Verify flow - using special network packet that is being routed in the same way as client traffic")
    @PutMapping(path = "/{flow_id}/ping")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<PingOutput> pingFlow(
            @RequestBody PingInput payload,
            @PathVariable("flow_id") String flowId) {
        return flowService.pingFlow(flowId, payload);
    }

    /**
     * Invalidate (purge) the flow resources cache and initialize it with DB data.
     */
    @ApiOperation(value = "Invalidate (purge) Flow Resources Cache(s)")
    @DeleteMapping(path = "/cache")
    @ResponseStatus(HttpStatus.OK)
    public void invalidateFlowCache() {
        //TODO: to be removed
    }

    /**
     * Update burst parameter in meter.
     */
    @ApiOperation(value = "Update burst parameter in meter")
    @PatchMapping(path = "/{flow_id}/meters")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowMeterEntries> updateMetersBurst(@PathVariable("flow_id") String flowId) {
        return flowService.modifyMeter(flowId);
    }

    /**
     * Gets flow history.
     */
    @ApiOperation(value = "Gets history for flow")
    @GetMapping(path = "/{flow_id}/history")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<List<FlowEventPayload>> getHistory(
            @PathVariable("flow_id") String flowId,
            @ApiParam(value = "default: the day before timeTo.", required = false)
            @RequestParam(value = "timeFrom", required = false) Optional<Long> optionalTimeFrom,
            @ApiParam(value = "default: now.", required = false)
            @RequestParam(value = "timeTo", required = false) Optional<Long> optionalTimeTo) {
        Long timeTo = optionalTimeTo.orElseGet(() -> Instant.now().getEpochSecond());
        Long timeFrom = optionalTimeFrom.orElseGet(
                () -> Instant.ofEpochSecond(timeTo).minus(1, ChronoUnit.DAYS).getEpochSecond()
        );
        return flowService.listFlowEvents(flowId, timeFrom, timeTo);
    }

    /**
     * Gets flow connected devices.
     */
    @ApiOperation(value = "Gets flow connected devices")
    @GetMapping(path = "/{flow_id}/devices")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowConnectedDevicesResponse> getHistory(
            @PathVariable("flow_id") String flowId,
            @ApiParam(value = "Device will be included in response if it's `time_last_seen` >= `since`. "
                    + "Example of `since` value: `2019-09-30T16:14:12.538Z`",
                    required = false)
            @RequestParam(value = "since", required = false) Optional<String> since) {
        Instant sinceInstant;

        if (!since.isPresent() || StringUtils.isEmpty(since.get())) {
            sinceInstant = Instant.MIN;
        } else {
            try {
                sinceInstant = Instant.parse(since.get());
            } catch (DateTimeParseException e) {
                String message = String.format("Invalid 'since' value '%s'. Correct example of 'since' value is "
                        + "'2019-09-30T16:14:12.538Z'", since.get());
                throw new MessageException(ErrorType.DATA_INVALID, message, "Invalid 'since' value");
            }
        }
        return flowService.getFlowConnectedDevices(flowId, sinceInstant);
    }

    /**
     * Get enabled flow applications.
     *
     * @param flowId        flow id.
     * @return enabled flow applications.
     */
    @ApiOperation(value = "Gets enabled flow applications", response = FlowAppsDto.class)
    @GetMapping(value = "/{flow-id:.+}/applications")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowAppsDto> getFlowApplications(@PathVariable(name = "flow-id") String flowId) {
        return flowService.getFlowApplications(flowId);
    }

    /**
     * Add flow application for the flow.
     *
     * @param flowId        flow id.
     * @param app           flow application.
     * @param flowAddAppDto describes the endpoint on which the application will be installed.
     * @return enabled flow applications.
     */
    @ApiOperation(value = "Add flow application for the flow. If you do not specify an endpoint, "
            + "then the application will be added for both flow endpoints.", response = FlowAppsDto.class)
    @PatchMapping(value = "/{flow-id:.+}/applications/{application}")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowAppsDto> addFlowApp(@PathVariable(name = "flow-id") String flowId,
                                                     @PathVariable(name = "application") String app,
                                                     @RequestBody(required = false) FlowAddAppDto flowAddAppDto) {
        return flowService.addFlowApplication(flowId, app, flowAddAppDto);
    }

    /**
     * Remove flow application for the flow.
     *
     * @param flowId            flow id
     * @param app               flow application.
     * @param flowDeleteAppDto  endpoint to delete.
     * @return enabled flow applications.
     */
    @ApiOperation(value = "Remove flow application for the flow. If you do not specify an endpoint, "
            + "then the application will be removed for both flow endpoints.", response = FlowAppsDto.class)
    @DeleteMapping(value = "/{flow-id:.+}/applications/{application}")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowAppsDto> removeFlowApp(
                                                        @PathVariable(name = "flow-id") String flowId,
                                                        @PathVariable(name = "application") String app,
                                                        @RequestBody FlowDeleteAppDto flowDeleteAppDto) {
        return flowService.removeFlowApplications(flowId, app, flowDeleteAppDto);
    }
}
