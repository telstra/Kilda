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

package org.openkilda.wfm.topology.flow.validation;

import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.openkilda.model.SwitchFeature.MULTI_TABLE;

import org.openkilda.model.DetectConnectedDevices;
import org.openkilda.model.Flow;
import org.openkilda.model.Isl;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.flow.TestFlowBuilder;

import com.google.common.collect.Sets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.Collections;
import java.util.Optional;

public class FlowValidatorTest {
    private FlowValidator target;

    private static final SwitchId SRC_SWITCH_ID = new SwitchId("00:00:00:00:00:00:00:01");
    private static final int SRC_PORT = 1;
    private static final int SRC_VLAN = 1;
    private static final SwitchId DST_SWITCH_ID = new SwitchId("00:00:00:00:00:00:00:02");
    private static final int DST_PORT = 5;
    private static final int DST_VLAN = 5;
    private static final SwitchId FAIL_SRC_SWITCH_ID = new SwitchId("00:00:00:00:00:00:00:03");
    private static final SwitchId FAIL_DST_SWITCH_ID = new SwitchId("00:00:00:00:00:00:00:04");
    private static final String FLOW_ID = "test_flow";
    private static final String ANOTHER_FLOW_ID = "another_test_flow";

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private void defaultSetUp(SwitchId srcSwitchId, int srtPort, int srcVlan,
                              SwitchId dstSwitchId, int dstPort, int dstVlan, String flowId) {
        SwitchRepository switchRepository = mock(SwitchRepository.class);
        Switch srcSwitch = Switch.builder().switchId(srcSwitchId).build();
        Switch dstSwitch = Switch.builder().switchId(dstSwitchId).build();
        when(switchRepository.findById(eq(srcSwitchId))).thenReturn(
                Optional.of(srcSwitch));
        when(switchRepository.findById(eq(dstSwitchId))).thenReturn(
                Optional.of(dstSwitch));

        IslRepository islRepository = mock(IslRepository.class);
        Isl isl = Isl.builder().srcSwitch(srcSwitch).srcPort(srtPort).destSwitch(dstSwitch).destPort(dstPort).build();
        when(islRepository.findByEndpoint(eq(srcSwitchId), eq(srtPort))).thenReturn(singletonList(isl));
        when(islRepository.findByEndpoint(eq(dstSwitchId), eq(dstPort))).thenReturn(singletonList(isl));

        Flow flow = new TestFlowBuilder(flowId)
                .srcSwitch(srcSwitch)
                .srcPort(srtPort)
                .srcVlan(srcVlan)
                .destSwitch(dstSwitch)
                .destPort(dstPort)
                .destVlan(dstVlan)
                .build();

        FlowRepository flowRepository = mock(FlowRepository.class);
        when(flowRepository.findByEndpoint(eq(srcSwitchId), eq(srtPort)))
                .thenReturn(singletonList(flow));
        when(flowRepository.findByEndpoint(eq(dstSwitchId), eq(dstPort)))
                .thenReturn(singletonList(flow));

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.createSwitchRepository()).thenReturn(switchRepository);
        when(repositoryFactory.createFlowRepository()).thenReturn(flowRepository);
        when(repositoryFactory.createIslRepository()).thenReturn(islRepository);

        target = new FlowValidator(repositoryFactory);
    }

    private void setUpWithValidateSwitch(SwitchId srcSwitchId, SwitchId dstSwitchId) {
        SwitchRepository switchRepository = mock(SwitchRepository.class);
        when(switchRepository.exists(eq(srcSwitchId))).thenReturn(true);
        when(switchRepository.exists(eq(dstSwitchId))).thenReturn(true);

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.createSwitchRepository()).thenReturn(switchRepository);
        target = new FlowValidator(repositoryFactory);
    }

    private void setUpWithLldp(boolean srcLldpSupport, boolean dstLldpSupport) {
        Switch srcSwitch = Switch.builder()
                .switchId(SRC_SWITCH_ID)
                .features(srcLldpSupport ? newHashSet(MULTI_TABLE) : Sets.newHashSet())
                .build();
        Switch dstSwitch = Switch.builder()
                .switchId(DST_SWITCH_ID)
                .features(dstLldpSupport ? newHashSet(MULTI_TABLE) : Sets.newHashSet())
                .build();

        SwitchProperties srcProperties = SwitchProperties.builder()
                .switchObj(srcSwitch).multiTable(srcLldpSupport).build();
        SwitchProperties dstProperties = SwitchProperties.builder()
                .switchObj(dstSwitch).multiTable(dstLldpSupport).build();

        SwitchRepository switchRepository = mock(SwitchRepository.class);
        when(switchRepository.findById(eq(FlowValidatorTest.SRC_SWITCH_ID))).thenReturn(Optional.of(srcSwitch));
        when(switchRepository.findById(eq(FlowValidatorTest.DST_SWITCH_ID))).thenReturn(Optional.of(dstSwitch));
        when(switchRepository.findById(eq(FlowValidatorTest.FAIL_SRC_SWITCH_ID))).thenReturn(Optional.empty());
        when(switchRepository.findById(eq(FlowValidatorTest.FAIL_DST_SWITCH_ID))).thenReturn(Optional.empty());

        SwitchPropertiesRepository switchPropertiesRepository = mock(SwitchPropertiesRepository.class);
        when(switchPropertiesRepository.findBySwitchId(eq(SRC_SWITCH_ID))).thenReturn(Optional.of(srcProperties));
        when(switchPropertiesRepository.findBySwitchId(eq(DST_SWITCH_ID))).thenReturn(Optional.of(dstProperties));

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.createSwitchRepository()).thenReturn(switchRepository);
        when(repositoryFactory.createSwitchPropertiesRepository()).thenReturn(switchPropertiesRepository);
        target = new FlowValidator(repositoryFactory);
    }

    @Test(expected = SwitchValidationException.class)
    public void shouldFailLldpIfSourceSwitchDoesntNotExist() throws SwitchValidationException {
        setUpWithLldp(true, true);
        runFailLldpIfSwitchDoesntNotExist(FAIL_SRC_SWITCH_ID, DST_SWITCH_ID);
    }

    @Test(expected = SwitchValidationException.class)
    public void shouldFailLldpIfDestinationSwitchDoesntNotExist() throws SwitchValidationException {
        setUpWithLldp(true, true);
        runFailLldpIfSwitchDoesntNotExist(SRC_SWITCH_ID, FAIL_DST_SWITCH_ID);
    }

    @Test(expected = SwitchValidationException.class)
    public void shouldFailLldpIfSourceAndDestinationSwitchDoesntNotExist() throws SwitchValidationException {
        setUpWithLldp(true, true);
        runFailLldpIfSwitchDoesntNotExist(FAIL_SRC_SWITCH_ID, FAIL_DST_SWITCH_ID);
    }

    private void runFailLldpIfSwitchDoesntNotExist(SwitchId srcSwitchId, SwitchId dstSwitchId)
            throws SwitchValidationException {

        Flow flow = new TestFlowBuilder()
                .srcSwitch(Switch.builder().switchId(srcSwitchId).build())
                .destSwitch(Switch.builder().switchId(dstSwitchId).build())
                .detectConnectedDevices(new DetectConnectedDevices(
                        true, false, true, false, false, false, false, false))
                .build();

        target.checkSwitchesSupportLldpAndArpIfNeeded(flow);
    }

    @Test(expected = SwitchValidationException.class)
    public void shouldFailIfSSrcSwitchDoesntSupportLldpAndSrcLldpRequested() throws SwitchValidationException {
        runCheckSwitchesSupportLldpIfNeededTest(false, true, true, false);
    }

    @Test(expected = SwitchValidationException.class)
    public void shouldFailIfDstSwitchDoesntSupportLldpAndDstLldpRequested() throws SwitchValidationException {
        runCheckSwitchesSupportLldpIfNeededTest(false, false, false, true);
    }

    @Test
    public void shouldNotFailIfSrcSwitchDoesNotSupportLldpButSrtLldpNotRequested() throws SwitchValidationException {
        runCheckSwitchesSupportLldpIfNeededTest(false, false, false, false);
    }

    @Test
    public void shouldNotFailIfDstSwitchDoesNotSupportLldpButDstLldpNotRequested() throws SwitchValidationException {
        runCheckSwitchesSupportLldpIfNeededTest(true, false, false, false);
    }

    @Test
    public void shouldNotFailIfSrcSwitchSupportsLldpAndSrcLldpRequested() throws SwitchValidationException {
        runCheckSwitchesSupportLldpIfNeededTest(true, true, true, false);
    }

    @Test
    public void shouldNotFailIfDstSwitchSupportsLldpAndDstLldpRequested() throws SwitchValidationException {
        runCheckSwitchesSupportLldpIfNeededTest(false, true, false, false);
    }

    @Test
    public void shouldNotFailIfSrcAndSwitchSupportLldpAndSrcAndDstLldpRequested() throws SwitchValidationException {
        runCheckSwitchesSupportLldpIfNeededTest(true, true, true, true);
    }

    private void runCheckSwitchesSupportLldpIfNeededTest(boolean srcSwitchSupportsLldp, boolean dstSwitchSupportsLldp,
                                                         boolean srcLldpRequested, boolean dstLldpRequested)
            throws SwitchValidationException {
        setUpWithLldp(srcSwitchSupportsLldp, dstSwitchSupportsLldp);

        Flow flow = new TestFlowBuilder()
                .srcSwitch(Switch.builder().switchId(SRC_SWITCH_ID).build())
                .destSwitch(Switch.builder().switchId(DST_SWITCH_ID).build())
                .detectConnectedDevices(new DetectConnectedDevices(
                        srcLldpRequested, false, dstLldpRequested, false, false, false, false, false))
                .build();

        target.checkSwitchesSupportLldpAndArpIfNeeded(flow);
    }

    @Test(expected = FlowValidationException.class)
    public void shouldFailIfSourcePortIsOccupiedByIsl() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, 0, DST_SWITCH_ID, DST_PORT, 0, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .destSwitch(dstSwitch)
                .destPort(DST_PORT + 1)
                .build();

        target.checkFlowForIslConflicts(flow);
    }

    @Test(expected = FlowValidationException.class)
    public void shouldFailIfDestinationPortIsOccupiedByIsl() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, 0, DST_SWITCH_ID, DST_PORT, 0, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT + 1)
                .destSwitch(dstSwitch)
                .destPort(DST_PORT)
                .build();

        target.checkFlowForIslConflicts(flow);
    }

    @Test
    public void shouldNotFailIfPortIsNotOccupiedByIsl() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, 0, DST_SWITCH_ID, DST_PORT, 0, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT + 1)
                .destSwitch(dstSwitch)
                .destPort(DST_PORT + 1)
                .build();

        target.checkFlowForIslConflicts(flow);
    }

    @Test
    public void shouldNotFailIfSourceVlanIsZeroAndPortVlanIsOccupied() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, 0, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(new SwitchId("de:ad:be:af:de:ad:be:af")).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN)
                .destSwitch(dstSwitch)
                .build();
        target.checkFlowForEndpointConflicts(flow);
    }

    @Test(expected = FlowValidationException.class)
    public void shouldFailIfSourcePortIsOccupied() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, 0, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(new SwitchId("de:ad:be:af:de:ad:be:af")).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(0)
                .destSwitch(dstSwitch)
                .build();
        target.checkFlowForEndpointConflicts(flow);
    }

    @Test(expected = FlowValidationException.class)
    public void shouldFailOnSwapWhenEqualsEndpointsOnFirstFlow() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, 1, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);
        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch otherSwitch = Switch.builder().switchId(new SwitchId("ff:fe")).build();
        Flow firstFlow = new TestFlowBuilder()
                .flowId("firstFlow")
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN)
                .destSwitch(srcSwitch)
                .destPort(SRC_PORT)
                .destVlan(SRC_VLAN)
                .build();

        Flow secondFlow = new TestFlowBuilder()
                .flowId("secondFlow")
                .srcSwitch(otherSwitch)
                .destSwitch(otherSwitch)
                .build();

        target.checkForEqualsEndpoints(firstFlow, secondFlow);
    }

    @Test(expected = FlowValidationException.class)
    public void shouldFailOnSwapWhenEqualsEndpointsOnSecondFlow() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, 1, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);
        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch otherSwitch = Switch.builder().switchId(new SwitchId("ff:fe")).build();
        Flow firstFlow = new TestFlowBuilder()
                .flowId("firstFlow")
                .srcSwitch(otherSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN)
                .destSwitch(otherSwitch)
                .destPort(DST_PORT)
                .build();

        Flow secondFlow = new TestFlowBuilder()
                .flowId("secondFlow")
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN)
                .destSwitch(srcSwitch)
                .destPort(SRC_PORT)
                .destVlan(SRC_VLAN)
                .build();

        target.checkForEqualsEndpoints(firstFlow, secondFlow);
    }

    @Test(expected = FlowValidationException.class)
    public void shouldFailOnSwapWhenEqualsEndpointsOnFirstAndSecondFlow() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, 1, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);
        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch otherSwitch = Switch.builder().switchId(new SwitchId("ff:fe")).build();
        Flow firstFlow = new TestFlowBuilder()
                .flowId("firstFlow")
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN)
                .destSwitch(otherSwitch)
                .destPort(DST_PORT)
                .destVlan(DST_VLAN)
                .build();

        Flow secondFlow = new TestFlowBuilder()
                .flowId("secondFlow")
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN)
                .destSwitch(otherSwitch)
                .destPort(SRC_PORT)
                .destVlan(SRC_VLAN)
                .build();

        target.checkForEqualsEndpoints(firstFlow, secondFlow);
    }

    @Test(expected = FlowValidationException.class)
    public void shouldFailIfSourceVlanIsAlreadyOccupied() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(new SwitchId("de:ad:be:af:de:ad:be:af")).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN)
                .destSwitch(dstSwitch)
                .build();
        target.checkFlowForEndpointConflicts(flow);
    }

    @Test
    public void shouldNotFailIfSourceVlanIsOccupiedAndRequestedSrcVlanIsZero() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, 10, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(new SwitchId("de:ad:be:af:de:ad:be:af")).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .destSwitch(dstSwitch)
                .build();
        target.checkFlowForEndpointConflicts(flow);
    }

    @Test
    public void shouldNotFailIfSourceVlanIsNotOccupied() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN + 1)
                .destSwitch(dstSwitch)
                .destPort(DST_PORT)
                .destVlan(DST_VLAN + 1)
                .build();
        target.checkFlowForEndpointConflicts(flow);
    }

    @Test
    public void shouldNotFailIfDestinationVlanNotZeroAndPortIsOccupied() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, DST_PORT, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN + 1)
                .destSwitch(dstSwitch)
                .destPort(0)
                .destVlan(DST_VLAN)
                .build();
        target.checkFlowForEndpointConflicts(flow);
    }

    @Test
    public void shouldNotFailIfPortIsOccupiedAndDestinationVlanNotZero() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, 0, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN + 1)
                .destSwitch(dstSwitch)
                .destPort(DST_PORT)
                .destVlan(DST_VLAN)
                .build();
        target.checkFlowForEndpointConflicts(flow);
    }

    @Test(expected = FlowValidationException.class)
    public void shouldFailIfDestinationPortIsOccupied() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, 0, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN + 1)
                .destSwitch(dstSwitch)
                .destPort(DST_PORT)
                .destVlan(0)
                .build();
        target.checkFlowForEndpointConflicts(flow);
    }

    @Test(expected = FlowValidationException.class)
    public void shouldFailIfDestinationVlanIsAlreadyOccupied() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN + 1)
                .destSwitch(dstSwitch)
                .destPort(DST_PORT)
                .destVlan(DST_VLAN)
                .build();
        target.checkFlowForEndpointConflicts(flow);
    }

    @Test
    public void shouldNotFailIfDestinationVlanIsNotOccupied() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN + 1)
                .destSwitch(dstSwitch)
                .destPort(DST_PORT)
                .destVlan(DST_VLAN + 1)
                .build();
        target.checkFlowForEndpointConflicts(flow);
    }

    @Test(expected = FlowValidationException.class)
    public void shouldFailForNegativeBandwidth() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .bandwidth(-1)
                .build();
        target.checkBandwidth(flow);
    }

    @Test
    public void shouldNotFailOnCheckSwitches() throws SwitchValidationException {
        setUpWithValidateSwitch(SRC_SWITCH_ID, DST_SWITCH_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .build();
        target.checkSwitchesExists(flow);
    }

    @Test
    public void shouldNotFailOnSingleSwitchCheck() throws SwitchValidationException {
        setUpWithValidateSwitch(SRC_SWITCH_ID, SRC_SWITCH_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .destSwitch(srcSwitch)
                .build();
        target.checkSwitchesExists(flow);
    }

    @Test
    public void shouldFailOnSourceSwitchCheck() throws SwitchValidationException {
        setUpWithValidateSwitch(SRC_SWITCH_ID, DST_SWITCH_ID);

        Switch failSrcSwitch = Switch.builder().switchId(FAIL_SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(failSrcSwitch)
                .destSwitch(dstSwitch)
                .build();

        String expectedMessage = String.format("Source switch %s is not connected to the controller",
                FAIL_SRC_SWITCH_ID);

        thrown.expect(SwitchValidationException.class);
        thrown.expectMessage(expectedMessage);
        target.checkSwitchesExists(flow);
    }

    @Test
    public void shouldFailOnDestinationSwitchCheck() throws SwitchValidationException {
        setUpWithValidateSwitch(SRC_SWITCH_ID, DST_SWITCH_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch failDestSwitch = Switch.builder().switchId(FAIL_DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .destSwitch(failDestSwitch)
                .build();

        String expectedMessage =
                String.format("Destination switch %s is not connected to the controller", FAIL_DST_SWITCH_ID);

        thrown.expect(SwitchValidationException.class);
        thrown.expectMessage(expectedMessage);

        target.checkSwitchesExists(flow);
    }

    @Test
    public void shouldFailOnSourceAndDestinationSwitchCheck() throws SwitchValidationException {
        setUpWithValidateSwitch(SRC_SWITCH_ID, DST_SWITCH_ID);

        Switch failSrcSwitch = Switch.builder().switchId(FAIL_SRC_SWITCH_ID).build();
        Switch failDestSwitch = Switch.builder().switchId(FAIL_DST_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(failSrcSwitch)
                .destSwitch(failDestSwitch)
                .build();

        String expectedMessage =
                String.format("Source switch %s and Destination switch %s are not connected to the controller",
                        FAIL_SRC_SWITCH_ID, FAIL_DST_SWITCH_ID);

        thrown.expect(SwitchValidationException.class);
        thrown.expectMessage(expectedMessage);

        target.checkSwitchesExists(flow);
    }

    @Test
    public void shouldFailOnOneSwitchFlowWithEqualPortsAndVlans() throws SwitchValidationException {
        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        target = new FlowValidator(repositoryFactory);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN)
                .destSwitch(srcSwitch)
                .destPort(SRC_PORT)
                .destVlan(SRC_VLAN)
                .build();

        String expectedMessage = "It is not allowed to create one-switch flow for the same ports and vlans";

        thrown.expect(SwitchValidationException.class);
        thrown.expectMessage(expectedMessage);

        target.checkOneSwitchFlowHasNoConflicts(flow);
    }

    @Test
    public void shouldNotFailOnOneSwitchFlowWithEqualPortsButDifferentVlans() throws SwitchValidationException {
        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        target = new FlowValidator(repositoryFactory);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN)
                .destSwitch(srcSwitch)
                .destPort(SRC_PORT)
                .destVlan(DST_VLAN)
                .build();
        target.checkOneSwitchFlowHasNoConflicts(flow);
    }

    @Test
    public void shouldNotFailOnOneSwitchFlowWithEqualVlansButDifferentPorts() throws SwitchValidationException {
        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        target = new FlowValidator(repositoryFactory);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();

        Flow flow = new TestFlowBuilder()
                .flowId(FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN)
                .destSwitch(srcSwitch)
                .destPort(DST_PORT)
                .destVlan(SRC_VLAN)
                .build();

        target.checkOneSwitchFlowHasNoConflicts(flow);
    }

    @Test
    public void shouldNotFailIfExistentFlowIsDefaultOnSrc() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, 0, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(new SwitchId("ff:ff")).build();
        Switch otherSwitch = Switch.builder().switchId(new SwitchId("ff:fe")).build();

        Flow firstFlow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(1)
                .destSwitch(dstSwitch)
                .build();
        Flow secondFlow = new TestFlowBuilder()
                .flowId("secondFlow")
                .srcSwitch(otherSwitch)
                .destSwitch(otherSwitch)
                .build();
        target.checkFlowForEndpointConflicts(firstFlow, Collections.singleton(secondFlow.getFlowId()));
    }

    @Test
    public void shouldNotFailIfExistentFlowIsDefaultOnDst() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, 0, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();
        Switch otherSwitch = Switch.builder().switchId(new SwitchId("ff:fe")).build();

        Flow firstFlow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN + 1)
                .destSwitch(otherSwitch)
                .destPort(DST_PORT)
                .destVlan(DST_VLAN)
                .build();
        Flow secondFlow = new TestFlowBuilder()
                .flowId("secondFlow")
                .srcSwitch(otherSwitch)
                .destSwitch(otherSwitch)
                .build();
        target.checkFlowForEndpointConflicts(firstFlow, Collections.singleton(secondFlow.getFlowId()));
    }

    @Test(expected = ValidationException.class)
    public void shouldFailIfBothExistentAndSwappingFlowsAreDefault() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, 0, DST_SWITCH_ID, DST_PORT, 0, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch otherSwitch = Switch.builder().switchId(new SwitchId("ff:fe")).build();

        Flow firstFlow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(0)
                .destSwitch(otherSwitch)
                .destPort(DST_PORT)
                .destVlan(0)
                .build();
        Flow secondFlow = new TestFlowBuilder()
                .flowId("secondFlow")
                .srcSwitch(otherSwitch)
                .destSwitch(otherSwitch)
                .build();
        target.checkFlowForEndpointConflicts(firstFlow, Collections.singleton(secondFlow.getFlowId()));
    }

    @Test(expected = ValidationException.class)
    public void shouldFailIfConflictOnSourceEndpoints() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch otherSwitch = Switch.builder().switchId(new SwitchId("ff:fe")).build();

        Flow firstFlow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .srcPort(SRC_PORT)
                .srcVlan(SRC_VLAN)
                .destSwitch(otherSwitch)
                .build();
        Flow secondFlow = new TestFlowBuilder()
                .flowId("secondFlow")
                .srcSwitch(otherSwitch)
                .destSwitch(otherSwitch)
                .build();
        target.checkFlowForEndpointConflicts(firstFlow, Collections.singleton(secondFlow.getFlowId()));
    }

    @Test(expected = ValidationException.class)
    public void shouldFailIfConflictOnDestinationEndpoints() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();
        Switch otherSwitch = Switch.builder().switchId(new SwitchId("ff:fe")).build();

        Flow firstFlow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .destPort(DST_PORT)
                .destVlan(DST_VLAN)
                .build();
        Flow secondFlow = new TestFlowBuilder()
                .flowId("secondFlow")
                .srcSwitch(otherSwitch)
                .destSwitch(otherSwitch)
                .build();
        target.checkFlowForEndpointConflicts(firstFlow, Collections.singleton(secondFlow.getFlowId()));
    }

    @Test(expected = ValidationException.class)
    public void shouldFailIfConflictOnDifferentEndpoints() throws FlowValidationException {
        defaultSetUp(SRC_SWITCH_ID, SRC_PORT, SRC_VLAN, DST_SWITCH_ID, DST_PORT, DST_VLAN, FLOW_ID);

        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch dstSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();
        Switch otherSwitch = Switch.builder().switchId(new SwitchId("ff:fe")).build();

        Flow firstFlow = new TestFlowBuilder()
                .flowId(ANOTHER_FLOW_ID)
                .srcSwitch(dstSwitch)
                .destSwitch(srcSwitch)
                .destPort(SRC_PORT)
                .destVlan(SRC_VLAN)
                .build();
        Flow secondFlow = new TestFlowBuilder()
                .flowId("secondFlow")
                .srcSwitch(otherSwitch)
                .destSwitch(otherSwitch)
                .build();
        target.checkFlowForEndpointConflicts(firstFlow, Collections.singleton(secondFlow.getFlowId()));
    }

}
