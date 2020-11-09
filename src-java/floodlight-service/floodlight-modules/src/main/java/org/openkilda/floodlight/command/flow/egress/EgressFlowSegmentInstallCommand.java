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

package org.openkilda.floodlight.command.flow.egress;

import org.openkilda.floodlight.error.NotImplementedEncapsulationException;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.switchmanager.SwitchManager;
import org.openkilda.floodlight.utils.OfAdapter;
import org.openkilda.floodlight.utils.OfFlowModAddMultiTableMessageBuilderFactory;
import org.openkilda.floodlight.utils.OfFlowModAddSingleTableMessageBuilderFactory;
import org.openkilda.floodlight.utils.OfFlowModBuilderFactory;
import org.openkilda.floodlight.utils.metadata.AppsMetadata;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.MirrorConfig;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Getter
public class EgressFlowSegmentInstallCommand extends EgressFlowSegmentCommand {
    private static OfFlowModBuilderFactory makeFlowModBuilderFactory(boolean isMultiTable) {
        if (isMultiTable) {
            return new OfFlowModAddMultiTableMessageBuilderFactory(SwitchManager.FLOW_PRIORITY);
        } else {
            return new OfFlowModAddSingleTableMessageBuilderFactory(SwitchManager.FLOW_PRIORITY);
        }
    }

    @JsonCreator
    public EgressFlowSegmentInstallCommand(
            @JsonProperty("message_context") MessageContext context,
            @JsonProperty("command_id") UUID commandId,
            @JsonProperty("metadata") FlowSegmentMetadata metadata,
            @JsonProperty("endpoint") FlowEndpoint endpoint,
            @JsonProperty("ingress_endpoint") FlowEndpoint ingressEndpoint,
            @JsonProperty("isl_port") int islPort,
            @JsonProperty("encapsulation") FlowTransitEncapsulation encapsulation,
            @JsonProperty("mirror_config")MirrorConfig mirrorConfig) {
        super(
                context, commandId, metadata, endpoint, ingressEndpoint, islPort, encapsulation,
                makeFlowModBuilderFactory(metadata.isMultiTable()), mirrorConfig);
    }

    @Override
    protected List<OFInstruction> makeEgressModMessageInstructions(OFFactory of) {
        List<OFAction> applyActions = new ArrayList<>(makeTransformActions(of));
        applyActions.add(of.actions().buildOutput()
                                 .setPort(OFPort.of(endpoint.getPortNumber()))
                                 .build());


        List<OFInstruction> instructions = new ArrayList<>();
        instructions.add(of.instructions().applyActions(applyActions));
        if (mirrorConfig != null) {
            AppsMetadata appsMetadata = AppsMetadata.builder().encapsulationId(getEncapsulation().getId())
                    .isForward(false).build(getSwitchFeatures());
            instructions.add(of.instructions().writeMetadata(appsMetadata.getValue(), appsMetadata.getMask()));
            instructions.add(of.instructions().gotoTable(TableId.of(SwitchManager.APPLICATONS_TABLE_ID)));
        }
        return instructions;
    }

    private List<OFAction> makeTransformActions(OFFactory of) {
        switch (encapsulation.getType()) {
            case TRANSIT_VLAN:
                return makeVlanTransformActions(of);
            case VXLAN:
                return makeVxLanTransformActions(of);
            default:
                throw new NotImplementedEncapsulationException(
                        getClass(), encapsulation.getType(), switchId, metadata.getFlowId());
        }
    }

    private List<OFAction> makeVlanTransformActions(OFFactory of) {
        return OfAdapter.INSTANCE.makeVlanReplaceActions(
                of, FlowEndpoint.makeVlanStack(encapsulation.getId()), endpoint.getVlanStack());
    }

    private List<OFAction> makeVxLanTransformActions(OFFactory of) {
        List<OFAction> actions = new ArrayList<>();
        actions.add(of.actions().noviflowPopVxlanTunnel());
        // All ingress vlan tags have been removed on ingress side
        actions.addAll(OfAdapter.INSTANCE.makeVlanReplaceActions(
                of, Collections.emptyList(), endpoint.getVlanStack()));

        return actions;
    }

    @Override
    protected SegmentAction getSegmentAction() {
        return SegmentAction.INSTALL;
    }
}
