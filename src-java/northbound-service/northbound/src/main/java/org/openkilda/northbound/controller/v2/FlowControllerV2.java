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

package org.openkilda.northbound.controller.v2;

import org.openkilda.messaging.Utils;
import org.openkilda.messaging.payload.flow.FlowIdStatusPayload;
import org.openkilda.northbound.controller.BaseController;
import org.openkilda.northbound.dto.v2.flows.FlowEndpointV2;
import org.openkilda.northbound.dto.v2.flows.FlowPatchV2;
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2;
import org.openkilda.northbound.dto.v2.flows.FlowRerouteResponseV2;
import org.openkilda.northbound.dto.v2.flows.FlowResponseV2;
import org.openkilda.northbound.dto.v2.flows.SwapFlowEndpointPayload;
import org.openkilda.northbound.service.FlowService;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@RestController
@RequestMapping("/v2/flows")
public class FlowControllerV2 extends BaseController {

    @Autowired
    private FlowService flowService;

    @ApiOperation(value = "Creates new flow", response = FlowResponseV2.class)
    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowResponseV2> createFlow(@RequestBody FlowRequestV2 flow) {
        verifyRequest(flow);
        return flowService.createFlow(flow);
    }

    @ApiOperation(value = "Updates flow", response = FlowResponseV2.class)
    @PutMapping(value = "/{flow_id:.+}")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowResponseV2> updateFlow(@PathVariable(name = "flow_id") String flowId,
                                                        @RequestBody FlowRequestV2 flow) {
        verifyRequest(flow);
        return flowService.updateFlow(flow);
    }

    /**
     * Initiates flow rerouting if any shorter path is available.
     *
     * @param flowId id of flow to be rerouted.
     * @return the flow with updated path.
     */
    @ApiOperation(value = "Reroute flow", response = FlowRerouteResponseV2.class)
    @PostMapping(path = "/{flow_id}/reroute")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowRerouteResponseV2> rerouteFlow(@PathVariable("flow_id") String flowId) {
        return flowService.rerouteFlowV2(flowId);
    }

    @ApiOperation(value = "Deletes flow", response = FlowResponseV2.class)
    @DeleteMapping(value = "/{flow_id:.+}")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowResponseV2> deleteFlow(@PathVariable(name = "flow_id") String flowId) {
        return flowService.deleteFlowV2(flowId);
    }

    /**
     * Gets flow.
     *
     * @param flowId        flow id
     * @return flow
     */
    @ApiOperation(value = "Gets flow", response = FlowResponseV2.class)
    @GetMapping(value = "/{flow_id:.+}")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowResponseV2> getFlow(@PathVariable(name = "flow_id") String flowId) {
        return flowService.getFlowV2(flowId);
    }

    /**
     * Dumps all flows. Dumps all flows with specific status if specified.
     *
     * @return list of flow
     */
    @ApiOperation(value = "Dumps all flows", response = FlowResponseV2.class, responseContainer = "List")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<List<FlowResponseV2>> getFlows(
            @RequestParam(value = "status", required = false) String status) {
        return flowService.getAllFlowsV2(status);
    }

    /**
     * Gets flow status.
     *
     * @param flowId        flow id
     * @return list of flow
     */
    @ApiOperation(value = "Gets flow status", response = FlowIdStatusPayload.class)
    @GetMapping(value = "/status/{flow_id:.+}")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowIdStatusPayload> statusFlow(@PathVariable(name = "flow_id") String flowId) {
        return flowService.statusFlow(flowId);
    }

    /**
     * Bulk update for flow.
     */
    @ApiOperation(value = "Swap flow endpoints", response = SwapFlowEndpointPayload.class)
    @PostMapping("/swap-endpoint")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<SwapFlowEndpointPayload> swapFlowEndpoint(@RequestBody SwapFlowEndpointPayload payload) {
        return flowService.swapFlowEndpoint(payload);
    }

    /**
     * Updates existing flow params.
     *
     * @param flowPatchDto  flow parameters for update
     * @param flowId        flow id
     * @return flow
     */
    @ApiOperation(value = "Updates flow", response = FlowResponseV2.class)
    @PatchMapping(value = "/{flow_id:.+}")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowResponseV2> patchFlow(@PathVariable(name = "flow_id") String flowId,
                                                       @ApiParam(value = "To remove flow from a diverse group, "
                                                               + "need to pass the parameter \"diverse_flow_id\" "
                                                               + "equal to the empty string.")
                                                       @RequestBody FlowPatchV2 flowPatchDto) {
        return flowService.patchFlow(flowId, flowPatchDto);
    }

    private void verifyRequest(FlowRequestV2 request) {
        exposeBodyValidationResults(Stream.concat(
                verifyFlowEndpoint(request.getSource(), "source"),
                verifyFlowEndpoint(request.getDestination(), "destination")));
    }

    private Stream<Optional<String>> verifyFlowEndpoint(FlowEndpointV2 endpoint, String name) {
        return Stream.of(
                verifyEndpointVlanId(name, "vlanId", endpoint.getVlanId()),
                verifyEndpointVlanId(name, "innerVlanId", endpoint.getInnerVlanId()));
    }

    private Optional<String> verifyEndpointVlanId(String endpoint, String field, int value) {
        if (! Utils.validateVlanRange(value)) {
            return Optional.of(String.format("Invalid %s value %d into %s endpoint", field, value, endpoint));
        }
        return Optional.empty();
    }
}
