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

package org.openkilda.floodlight.api.request.factory;

import org.openkilda.floodlight.api.request.TransitFlowSegmentInstallRequest;
import org.openkilda.floodlight.api.request.TransitFlowSegmentRemoveRequest;
import org.openkilda.floodlight.api.request.TransitFlowSegmentRequest;
import org.openkilda.floodlight.api.request.TransitFlowSegmentVerifyRequest;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.SwitchId;

import lombok.Builder;

import java.util.UUID;

public class TransitFlowSegmentRequestFactory extends FlowSegmentRequestFactory {
    private final TransitFlowSegmentRequest requestBlank;

    @Builder
    public TransitFlowSegmentRequestFactory(
            MessageContext messageContext, SwitchId switchId, FlowSegmentMetadata metadata,
            int ingressIslPort, int egressIslPort, FlowTransitEncapsulation encapsulation) {
        this(new RequestBlank(messageContext, switchId, metadata, ingressIslPort, egressIslPort, encapsulation));
    }

    private TransitFlowSegmentRequestFactory(TransitFlowSegmentRequest requestBlank) {
        super(requestBlank);
        this.requestBlank = requestBlank;
    }

    @Override
    public TransitFlowSegmentInstallRequest makeInstallRequest(UUID commandId) {
        return new TransitFlowSegmentInstallRequest(requestBlank, commandId);
    }

    @Override
    public TransitFlowSegmentRemoveRequest makeRemoveRequest(UUID commandId) {
        return new TransitFlowSegmentRemoveRequest(requestBlank, commandId);
    }

    @Override
    public TransitFlowSegmentVerifyRequest makeVerifyRequest(UUID commandId) {
        return new TransitFlowSegmentVerifyRequest(requestBlank, commandId);
    }

    private static class RequestBlank extends TransitFlowSegmentRequest {
        RequestBlank(
                MessageContext context, SwitchId switchId, FlowSegmentMetadata metadata, int ingressIslPort,
                int egressIslPort, FlowTransitEncapsulation encapsulation) {
            super(context, switchId, dummyCommandId, metadata, ingressIslPort, egressIslPort, encapsulation, -1);
        }
    }
}
