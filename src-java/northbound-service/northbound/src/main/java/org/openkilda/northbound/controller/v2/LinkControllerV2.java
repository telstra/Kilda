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

package org.openkilda.northbound.controller.v2;

import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.model.SwitchId;
import org.openkilda.northbound.controller.BaseLinkController;
import org.openkilda.northbound.dto.v2.links.BfdProperties;
import org.openkilda.northbound.dto.v2.links.BfdPropertiesPayload;
import org.openkilda.northbound.service.LinkService;

import io.swagger.annotations.ApiOperation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@RestController
@RequestMapping("/v2/links")
public class LinkControllerV2 extends BaseLinkController {
    private static final int BFD_INTERVAL_MIN = 100;
    private static final short BFD_MULTIPLIER_MIN = 1;

    private final LinkService linkService;

    public LinkControllerV2(LinkService linkService) {
        this.linkService = linkService;
    }

    /**
     * Write/update/enable BFD properties for specific ISL.
     */
    @ApiOperation(value = "Set/update BFD properties", response = BfdPropertiesPayload.class)
    @PutMapping(value = "/{src-switch}_{src-port}/{dst-switch}_{dst-port}/bfd")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<BfdPropertiesPayload> bfdPropertiesWrite(
            @PathVariable("src-switch") SwitchId srcSwitchId,
            @PathVariable("src-port") int srcPortNumber,
            @PathVariable("dst-switch") SwitchId dstSwitchId,
            @PathVariable("dst-port") int dstPortNumber,
            @RequestBody BfdProperties payload) {
        verifyRequest(payload);
        NetworkEndpoint source = makeSourceEndpoint(srcSwitchId, srcPortNumber);
        NetworkEndpoint dest = makeDestinationEndpoint(dstSwitchId, dstPortNumber);
        return linkService.writeBfdProperties(source, dest, verifyRequest(payload));
    }

    /**
     * Read BFD properties for specific ISL.
     */
    @ApiOperation(value = "Read BFD properties", response = BfdPropertiesPayload.class)
    @GetMapping(value = "/{src-switch}_{src-port}/{dst-switch}_{dst-port}/bfd")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<BfdPropertiesPayload> bfdPropertiesRead(
            @PathVariable("src-switch") SwitchId srcSwitchId,
            @PathVariable("src-port") int srcPortNumber,
            @PathVariable("dst-switch") SwitchId dstSwitchId,
            @PathVariable("dst-port") int dstPortNumber) {
        NetworkEndpoint source = makeSourceEndpoint(srcSwitchId, srcPortNumber);
        NetworkEndpoint dest = makeDestinationEndpoint(dstSwitchId, dstPortNumber);
        return linkService.readBfdProperties(source, dest);
    }

    /**
     * Disable BFD for specific ISL.
     */
    @ApiOperation(value = "Delete/disable BFD")
    @DeleteMapping(value = "/{src-switch}_{src-port}/{dst-switch}_{dst-port}/bfd")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public CompletableFuture<BfdPropertiesPayload> bfdPropertiesDelete(
            @PathVariable("src-switch") SwitchId srcSwitchId,
            @PathVariable("src-port") int srcPortNumber,
            @PathVariable("dst-switch") SwitchId dstSwitchId,
            @PathVariable("dst-port") int dstPortNumber) {
        NetworkEndpoint source = makeSourceEndpoint(srcSwitchId, srcPortNumber);
        NetworkEndpoint dest = makeDestinationEndpoint(dstSwitchId, dstPortNumber);
        return linkService.deleteBfdProperties(source, dest);
    }

    private BfdProperties verifyRequest(BfdProperties request) {
        exposeBodyValidationResults(verifyBfdProperties(request));
        return request;
    }

    private Stream<Optional<String>> verifyBfdProperties(BfdProperties properties) {
        if (properties == null) { // defaults will be used
            return Stream.empty();
        }

        String interval = null;
        if (properties.getIntervalMs() < BFD_INTERVAL_MIN) {
            interval = String.format(
                    "Invalid BFD interval value: %d < %d", properties.getIntervalMs(), BFD_INTERVAL_MIN);
        }
        String multiplier = null;
        if (properties.getMultiplier() < BFD_MULTIPLIER_MIN) {
            multiplier = String.format(
                    "Invalid BFD multiplier value: %d < %d", properties.getMultiplier(), BFD_MULTIPLIER_MIN);
        }
        return Stream.of(interval, multiplier)
                .filter(Objects::nonNull)
                .map(Optional::of);
    }
}
