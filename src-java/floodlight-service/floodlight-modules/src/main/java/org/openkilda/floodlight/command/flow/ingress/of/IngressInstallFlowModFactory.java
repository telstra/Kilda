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

package org.openkilda.floodlight.command.flow.ingress.of;

import org.openkilda.floodlight.command.flow.ingress.IngressFlowSegmentBase;
import org.openkilda.floodlight.switchmanager.SwitchManager;
import org.openkilda.floodlight.utils.OfAdapter;
import org.openkilda.floodlight.utils.OfFlowModBuilderFactory;
import org.openkilda.model.MeterId;
import org.openkilda.model.SwitchFeature;

import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public abstract class IngressInstallFlowModFactory extends IngressFlowModFactory {
    public IngressInstallFlowModFactory(
            OfFlowModBuilderFactory flowModBuilderFactory, IngressFlowSegmentBase command, IOFSwitch sw,
            Set<SwitchFeature> features) {
        super(flowModBuilderFactory, command, sw, features);
    }

    protected List<OFInstruction> makeForwardMessageInstructions(OFFactory of, MeterId effectiveMeterId) {
        List<OFAction> applyActions = new ArrayList<>();
        List<OFInstruction> instructions = new ArrayList<>();

        if (effectiveMeterId != null) {
            OfAdapter.INSTANCE.makeMeterCall(of, effectiveMeterId, applyActions, instructions);
        }

        applyActions.addAll(makeTransformActions());
        applyActions.add(makeOutputAction());

        instructions.add(of.instructions().applyActions(applyActions));
        if (command.getMetadata().isMultiTable()) {
            instructions.add(of.instructions().gotoTable(TableId.of(SwitchManager.POST_INGRESS_TABLE_ID)));
            instructions.addAll(makeMetadataInstructions());
        }

        return instructions;
    }

    @Override
    protected List<OFInstruction> makeServer42FlowRttMessageInstructions(OFFactory of, MeterId effectiveMeterId) {
        List<OFAction> applyActions = new ArrayList<>();
        List<OFInstruction> instructions = new ArrayList<>();

        if (effectiveMeterId != null) {
            OfAdapter.INSTANCE.makeMeterCall(of, effectiveMeterId, applyActions, instructions);
        }

        applyActions.addAll(makeServer42FlowRttTransformActions());
        applyActions.add(makeOutputAction());

        instructions.add(of.instructions().applyActions(applyActions));
        if (command.getMetadata().isMultiTable()) {
            instructions.add(of.instructions().gotoTable(TableId.of(SwitchManager.POST_INGRESS_TABLE_ID)));
        }

        return instructions;
    }

    protected abstract List<OFAction> makeTransformActions();

    protected abstract List<OFAction> makeServer42FlowRttTransformActions();

    protected abstract List<OFInstruction> makeMetadataInstructions();

    protected abstract OFAction makeOutputAction();

    protected final OFAction makeOutputAction(OFPort port) {
        return of.actions().buildOutput()
                .setPort(port)
                .build();
    }
}
