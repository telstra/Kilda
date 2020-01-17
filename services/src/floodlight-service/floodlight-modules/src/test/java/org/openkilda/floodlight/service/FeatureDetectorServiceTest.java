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

package org.openkilda.floodlight.service;

import static org.easymock.EasyMock.expect;
import static org.openkilda.model.SwitchFeature.BFD;
import static org.openkilda.model.SwitchFeature.BFD_REVIEW;
import static org.openkilda.model.SwitchFeature.GROUP_PACKET_OUT_CONTROLLER;
import static org.openkilda.model.SwitchFeature.INACCURATE_METER;
import static org.openkilda.model.SwitchFeature.INACCURATE_SET_VLAN_VID_ACTION;
import static org.openkilda.model.SwitchFeature.LIMITED_BURST_SIZE;
import static org.openkilda.model.SwitchFeature.MATCH_UDP_PORT;
import static org.openkilda.model.SwitchFeature.MAX_BURST_COEFFICIENT_LIMITATION;
import static org.openkilda.model.SwitchFeature.METERS;
import static org.openkilda.model.SwitchFeature.MULTI_TABLE;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_COPY_FIELD;
import static org.openkilda.model.SwitchFeature.NOVIFLOW_PUSH_POP_VXLAN;
import static org.openkilda.model.SwitchFeature.PKTPS_FLAG;
import static org.openkilda.model.SwitchFeature.RESET_COUNTS_FLAG;

import org.openkilda.model.SwitchFeature;

import com.google.common.collect.ImmutableSet;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.SwitchDescription;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import org.easymock.EasyMockSupport;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFVersion;

import java.util.Set;

public class FeatureDetectorServiceTest extends EasyMockSupport {
    private final FeatureDetectorService featuresDetector = new FeatureDetectorService();
    private final FloodlightModuleContext moduleContext = new FloodlightModuleContext();

    @Before
    public void setUp() throws Exception {
        injectMocks(this);

        featuresDetector.setup(moduleContext);
    }

    @After
    public void tearDown() {
        verifyAll();
    }

    @Test
    public void metersCommon() {
        discoveryCheck(makeSwitchMock("Common Inc", "Soft123", "Hard123", OFVersion.OF_13, 2),
                       ImmutableSet.of(GROUP_PACKET_OUT_CONTROLLER, METERS, RESET_COUNTS_FLAG, PKTPS_FLAG,
                               MATCH_UDP_PORT, MULTI_TABLE));
    }

    @Test
    public void metersOf12() {
        discoveryCheck(makeSwitchMock("Common Inc", "Soft123", "Hard123", OFVersion.OF_12, 1),
                       ImmutableSet.of(GROUP_PACKET_OUT_CONTROLLER, PKTPS_FLAG, MATCH_UDP_PORT));
    }

    @Test
    public void metersNicira() {
        discoveryCheck(makeSwitchMock("Nicira, Inc.", "Soft123", "Hard123", OFVersion.OF_13, 1),
                       ImmutableSet.of(GROUP_PACKET_OUT_CONTROLLER, RESET_COUNTS_FLAG, PKTPS_FLAG, MATCH_UDP_PORT));
    }

    @Test
    public void bfdCommon() {
        discoveryCheck(makeSwitchMock("NoviFlow Inc", "NW400.4.0", "NS21100", OFVersion.OF_13, 1),
                       ImmutableSet.of(
                               GROUP_PACKET_OUT_CONTROLLER, BFD, METERS, RESET_COUNTS_FLAG, NOVIFLOW_COPY_FIELD,
                               PKTPS_FLAG, MATCH_UDP_PORT, MAX_BURST_COEFFICIENT_LIMITATION, NOVIFLOW_PUSH_POP_VXLAN));
    }

    @Test
    public void bfdDevFirmware() {
        discoveryCheck(makeSwitchMock("NoviFlow Inc", "NW500.2.0_dev", "NS21100", OFVersion.OF_13, 1),
                       ImmutableSet.of(GROUP_PACKET_OUT_CONTROLLER, BFD, METERS, RESET_COUNTS_FLAG,
                                       NOVIFLOW_COPY_FIELD, PKTPS_FLAG, MATCH_UDP_PORT, NOVIFLOW_PUSH_POP_VXLAN,
                                       MAX_BURST_COEFFICIENT_LIMITATION));
    }

    @Test
    public void bfdReview() {
        discoveryCheck(makeSwitchMock("NoviFlow Inc", "NW400.4.0", "NS21100", OFVersion.OF_14, 1),
                       ImmutableSet.of(
                               GROUP_PACKET_OUT_CONTROLLER, BFD, BFD_REVIEW, METERS, RESET_COUNTS_FLAG,
                               NOVIFLOW_COPY_FIELD, PKTPS_FLAG, MATCH_UDP_PORT, MAX_BURST_COEFFICIENT_LIMITATION,
                               NOVIFLOW_PUSH_POP_VXLAN));
    }

    @Test
    public void copyFieldOnESwitches() {
        discoveryCheck(makeSwitchMock("E", "NW400.4.0", "WB5164", OFVersion.OF_13, 2),
                ImmutableSet.of(GROUP_PACKET_OUT_CONTROLLER, BFD, METERS, RESET_COUNTS_FLAG, MATCH_UDP_PORT,
                        MAX_BURST_COEFFICIENT_LIMITATION, MULTI_TABLE));
    }

    @Test
    public void copyFieldOnNoviflowVirtualSwitches() {
        discoveryCheck(makeSwitchMock("NoviFlow Inc", "NW500.0.1", "SM5000-SM", OFVersion.OF_13, 2),
                ImmutableSet.of(GROUP_PACKET_OUT_CONTROLLER, BFD, METERS, RESET_COUNTS_FLAG, MATCH_UDP_PORT,
                        MAX_BURST_COEFFICIENT_LIMITATION, MULTI_TABLE, NOVIFLOW_PUSH_POP_VXLAN));
    }

    @Test
    public void roundTripCentec() {
        discoveryCheck(makeSwitchMock("2004-2016 Centec Networks Inc", "2.8.16.21", "48T", OFVersion.OF_13, 2),
                       ImmutableSet.of(LIMITED_BURST_SIZE, INACCURATE_SET_VLAN_VID_ACTION, MULTI_TABLE, METERS));
    }

    @Test
    public void roundTripActon() {
        discoveryCheck(makeSwitchMock("Sonus Networks Inc, 4 Technology Park Dr, Westford, MA 01886, USA",
                "8.1.0.14", "VX3048", OFVersion.OF_12, 2),
                ImmutableSet.of(PKTPS_FLAG, MATCH_UDP_PORT, MULTI_TABLE));
    }

    @Test
    public void eswitch500Software() {
        discoveryCheck(makeSwitchMock("NoviFlow Inc", "NW500.0.1", "WB5164-E", OFVersion.OF_13, 2),
                ImmutableSet.of(
                        GROUP_PACKET_OUT_CONTROLLER, BFD, METERS, RESET_COUNTS_FLAG, INACCURATE_METER, MATCH_UDP_PORT,
                        MAX_BURST_COEFFICIENT_LIMITATION, MULTI_TABLE));
    }

    @Test
    public void pktpsFlagCommon() {
        discoveryCheck(makeSwitchMock("NoviFlow Inc", "NW400.4.0", "NS21100", OFVersion.OF_13, 1),
                ImmutableSet.of(
                        GROUP_PACKET_OUT_CONTROLLER, BFD, METERS, RESET_COUNTS_FLAG, NOVIFLOW_COPY_FIELD,
                        PKTPS_FLAG, MATCH_UDP_PORT, MAX_BURST_COEFFICIENT_LIMITATION, NOVIFLOW_PUSH_POP_VXLAN));
    }

    @Test
    public void pktpsFlagCentec() {
        discoveryCheck(makeSwitchMock("2004-2016 Centec Networks Inc", "2.8.16.21", "48T", OFVersion.OF_13, 3),
                ImmutableSet.of(LIMITED_BURST_SIZE, INACCURATE_SET_VLAN_VID_ACTION, MULTI_TABLE, METERS));
    }

    @Test
    public void pktpsFlagESwitch() {
        discoveryCheck(makeSwitchMock("E", "NW400.4.0", "WB5164", OFVersion.OF_13, 2),
                ImmutableSet.of(GROUP_PACKET_OUT_CONTROLLER, BFD, METERS, RESET_COUNTS_FLAG, MATCH_UDP_PORT,
                        MAX_BURST_COEFFICIENT_LIMITATION, MULTI_TABLE));
    }

    @Test
    public void centecSwitch() {
        discoveryCheck(makeSwitchMock("2004-2014 Centec Networks Inc", "2.8.16.15", "48T", OFVersion.OF_13, 2),
                ImmutableSet.of(
                        METERS, INACCURATE_SET_VLAN_VID_ACTION, LIMITED_BURST_SIZE, MULTI_TABLE));
    }

    private void discoveryCheck(IOFSwitch sw, Set<SwitchFeature> expectedFeatures) {
        replayAll();

        Set<SwitchFeature> actualFeatures = featuresDetector.detectSwitch(sw);
        Assert.assertEquals(expectedFeatures, actualFeatures);
    }

    private IOFSwitch makeSwitchMock(String manufacturer, String softwareDescription, String hardwareDescription,
                                     OFVersion version, int numTables) {
        SwitchDescription description = createMock(SwitchDescription.class);
        expect(description.getManufacturerDescription()).andReturn(manufacturer).anyTimes();
        expect(description.getSoftwareDescription()).andReturn(softwareDescription).anyTimes();
        expect(description.getHardwareDescription()).andReturn(hardwareDescription).anyTimes();

        OFFactory ofFactory = createMock(OFFactory.class);
        expect(ofFactory.getVersion()).andReturn(version).anyTimes();

        IOFSwitch sw = createMock(IOFSwitch.class);
        expect(sw.getSwitchDescription()).andReturn(description).anyTimes();
        expect(sw.getOFFactory()).andReturn(ofFactory).anyTimes();
        expect(sw.getNumTables()).andReturn((short) numTables).anyTimes();

        return sw;
    }
}
