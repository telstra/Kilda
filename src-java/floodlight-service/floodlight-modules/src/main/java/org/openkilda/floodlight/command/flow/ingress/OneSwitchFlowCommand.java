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
import org.openkilda.model.MeterConfig;

import lombok.Getter;
import lombok.NonNull;
import org.projectfloodlight.openflow.protocol.OFFlowMod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
public abstract class OneSwitchFlowCommand extends IngressFlowSegmentBase {
    protected final FlowEndpoint egressEndpoint;

    OneSwitchFlowCommand(
            MessageContext messageContext, UUID commandId, FlowSegmentMetadata metadata,
            FlowEndpoint endpoint, MeterConfig meterConfig, @NonNull FlowEndpoint egressEndpoint,
            RulesContext rulesContext) {
        super(
                messageContext, endpoint.getSwitchId(), commandId, metadata, endpoint, meterConfig,
                egressEndpoint.getSwitchId(), rulesContext, null);
        this.egressEndpoint = egressEndpoint;
    }

    @Override
    protected void validate() {
        super.validate();

        if (! getSwitchId().equals(egressEndpoint.getSwitchId())) {
            throw new IllegalArgumentException(String.format(
                    "Ingress(%s) and egress(%s) switches must match in %s",
                    getSwitchId(), egressEndpoint.getSwitchId(), getClass().getName()));
        }
    }

    @Override
    protected List<OFFlowMod> makeIngressServer42IngressFlowModMessages() {
        return new ArrayList<>();
    }

    /**
     * Make string representation of object (irreversible).
     */
    public String toString() {
        return String.format(
                "<one-switch-flow-%s{id=%s, metadata=%s, endpoint=%s, egressEndpoint=%s, meterConfig=%s}>",
                getSegmentAction(), commandId, metadata, endpoint, egressEndpoint, meterConfig);
    }

    protected abstract SegmentAction getSegmentAction();
}
