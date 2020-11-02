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

package org.openkilda.floodlight.command.flow.ingress;

import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.model.RulesContext;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.MeterConfig;
import org.openkilda.model.MirrorConfig;
import org.openkilda.model.SwitchId;

import lombok.Getter;
import lombok.NonNull;

import java.util.UUID;

@Getter
public abstract class IngressFlowSegmentCommand extends IngressFlowSegmentBase {
    // payload
    protected final int islPort;
    protected final FlowTransitEncapsulation encapsulation;

    @SuppressWarnings("squid:S00107")
    IngressFlowSegmentCommand(
            MessageContext messageContext, UUID commandId, FlowSegmentMetadata metadata,
            FlowEndpoint endpoint, MeterConfig meterConfig, SwitchId egressSwitchId, int islPort,
            @NonNull FlowTransitEncapsulation encapsulation, RulesContext rulesContext, MirrorConfig mirrorConfig) {
        super(
                messageContext, endpoint.getSwitchId(), commandId, metadata, endpoint, meterConfig,
                egressSwitchId, rulesContext, mirrorConfig);
        this.islPort = islPort;
        this.encapsulation = encapsulation;
    }

    /**
     * Make string representation of object (irreversible).
     */
    public String toString() {
        return String.format(
                "<ingress-flow-segment-%s{id=%s, metadata=%s, endpoint=%s, isl_port=%s, encapsulation=%s, "
                        + "meterConfig=%s}>",
                getSegmentAction(), commandId, metadata, endpoint, islPort, encapsulation, meterConfig);
    }

    protected abstract SegmentAction getSegmentAction();
}
