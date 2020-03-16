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

import static org.easymock.EasyMock.expect;

import org.openkilda.floodlight.switchmanager.SwitchManager;
import org.openkilda.model.Cookie;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.MeterConfig;
import org.openkilda.model.MeterId;
import org.openkilda.model.SwitchFeature;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookieSchema;

import com.google.common.collect.ImmutableSet;
import net.floodlightcontroller.core.IOFSwitch;
import org.easymock.EasyMockSupport;
import org.easymock.Mock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionGotoTable;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.ver13.OFFactoryVer13;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

abstract class IngressFlowModFactoryTest extends EasyMockSupport {
    protected static final OFFactory of = new OFFactoryVer13();

    protected static final DatapathId datapathIdAlpha = DatapathId.of(1);
    protected static final DatapathId datapathIdBeta = DatapathId.of(2);

    protected static final FlowTransitEncapsulation encapsulationVlan = new FlowTransitEncapsulation(
            500, FlowEncapsulationType.TRANSIT_VLAN);
    protected static final FlowTransitEncapsulation encapsulationVxLan = new FlowTransitEncapsulation(
            501, FlowEncapsulationType.VXLAN);

    protected static final Set<SwitchFeature> switchFeatures = ImmutableSet.of(
            SwitchFeature.METERS);

    protected static final String flowId = "flow-id-unit-test";
    protected static final Cookie cookie = FlowSegmentCookieSchema.INSTANCE.make(1, FlowPathDirection.FORWARD);

    protected static final FlowEndpoint endpointZeroVlan = new FlowEndpoint(
            new SwitchId(datapathIdAlpha.getLong()), 10, 0);
    protected static final FlowEndpoint endpointSingleVlan = new FlowEndpoint(
            new SwitchId(datapathIdAlpha.getLong()), 11, 100);
    protected static final FlowEndpoint endpointDoubleVlan = new FlowEndpoint(
            new SwitchId(datapathIdAlpha.getLong()), 12, 200, 210);

    protected static final MeterConfig meterConfig = new MeterConfig(new MeterId(20), 200);

    @Mock
    protected IOFSwitch sw;

    @Before
    public void setUp() throws Exception {
        injectMocks(this);

        expect(sw.getId()).andReturn(datapathIdAlpha).anyTimes();
        expect(sw.getOFFactory()).andReturn(of).anyTimes();

        replayAll();
    }

    @After
    public void tearDown() throws Exception {
        verifyAll();
    }

    // --- makeOuterVlanMatchAndRemoveMessage

    /*
    @Test
    public void makeOuterVlanMatchAndRemoveMessage() {
        final IngressFlowModFactory factory = makeFactory();
        final IngressFlowSegmentBase command = factory.getCommand();
        final FlowEndpoint endpoint = command.getEndpoint();
        MetadataAdapter.MetadataMatch metadata = MetadataAdapter.INSTANCE.addressOuterVlan(
                OFVlanVidMatch.ofVlan(command.getEndpoint().getOuterVlanId()));
        OFFlowAdd expected = of.buildFlowAdd()
                .setTableId(getTargetPreIngressTableId())
                .setPriority(FlowSegmentCommand.FLOW_PRIORITY)
                .setCookie(U64.of(
                        IngressSegmentCookie.convert(command.getCookie())
                                .setSubType(IngressSegmentCookie.IngressSegmentSubType.OUTER_VLAN_MATCH_AND_REMOVE)
                                .getValue()))
                .setMatch(OfAdapter.INSTANCE.matchVlanId(of, of.buildMatch(), endpoint.getOuterVlanId())
                        .setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber()))
                        .build())
                .setInstructions(ImmutableList.of(
                        of.instructions().applyActions(Collections.singletonList(of.actions().popVlan())),
                        of.instructions().writeMetadata(metadata.getValue(), metadata.getMask()),
                        of.instructions().gotoTable(TableId.of(SwitchManager.INGRESS_TABLE_ID))))
                .build();
        verifyOfMessageEquals(expected, factory.makeOuterVlanMatchAndRemoveMessage());
    }
    */

    // --- makeCustomerPortSharedCatchInstallMessage

    @Test
    public void makeCustomerPortSharedCatchInstallMessage() {
        IngressFlowModFactory factory = makeFactory();

        FlowEndpoint endpoint = factory.getCommand().getEndpoint();

        OFFlowMod expected = of.buildFlowAdd()
                .setTableId(TableId.of(SwitchManager.INPUT_TABLE_ID))
                .setPriority(SwitchManager.INGRESS_CUSTOMER_PORT_RULE_PRIORITY_MULTITABLE)
                .setCookie(U64.of(Cookie.encodeIngressRulePassThrough(endpoint.getPortNumber())))
                .setMatch(of.buildMatch().setExact(MatchField.IN_PORT, OFPort.of(endpoint.getPortNumber())).build())
                .setInstructions(Collections.singletonList(of.instructions().gotoTable(
                        TableId.of(SwitchManager.PRE_INGRESS_TABLE_ID))))
                .build();
        verifyOfMessageEquals(expected, factory.makeCustomerPortSharedCatchMessage());
    }

    // --- verify methods
    // FIXME: split on 2 methods
    protected void verifyGoToTableInstruction(Optional<TableId> expected, OFFlowMod message) {
        OFInstructionGotoTable match = null;
        for (OFInstruction instruction : message.getInstructions()) {
            if (instruction instanceof OFInstructionGotoTable) {
                match = (OFInstructionGotoTable) instruction;
                break;
            }
        }

        if (expected.isPresent()) {
            Assert.assertNotNull(match);
            Assert.assertEquals(expected.get(), match.getTableId());
        } else {
            Assert.assertNull(match);
        }
    }

    protected void verifyOfMessageEquals(OFMessage expected, OFMessage actual) {
        if (! expected.equalsIgnoreXid(actual)) {
            Assert.assertEquals(expected, actual);
        }
    }

    abstract IngressFlowModFactory makeFactory();

    abstract TableId getTargetPreIngressTableId();

    abstract TableId getTargetIngressTableId();

    MeterId getEffectiveMeterId(MeterConfig meterConfig) {
        if (meterConfig != null) {
            return meterConfig.getId();
        }
        return null;
    }
}
