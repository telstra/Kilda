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

package org.openkilda.floodlight.switchmanager;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reset;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.openkilda.floodlight.KafkaChannel;
import org.openkilda.floodlight.error.SwitchNotFoundException;
import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.service.kafka.IKafkaProducerService;
import org.openkilda.floodlight.service.kafka.KafkaUtilityService;
import org.openkilda.floodlight.utils.CorrelationContext;
import org.openkilda.floodlight.utils.CorrelationContext.CorrelationContextClosable;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.discovery.NetworkDumpSwitchData;
import org.openkilda.messaging.info.event.SwitchChangeType;
import org.openkilda.messaging.info.event.SwitchInfoData;
import org.openkilda.messaging.model.SpeakerSwitchDescription;
import org.openkilda.messaging.model.SpeakerSwitchPortView;
import org.openkilda.messaging.model.SpeakerSwitchView;
import org.openkilda.model.SwitchFeature;
import org.openkilda.model.SwitchId;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.LogicalOFMessageCategory;
import net.floodlightcontroller.core.SwitchDescription;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.internal.OFConnection;
import net.floodlightcontroller.core.internal.OFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.easymock.Mock;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.projectfloodlight.openflow.protocol.OFControllerRole;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.ver13.OFFactoryVer13;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Ignore("nmarchenko must be fixed after mfl merging")
public class SwitchTrackingServiceTest extends EasyMockSupport {
    private static final String KAFKA_ISL_DISCOVERY_TOPIC = "kilda.topo.disco";
    private static final DatapathId dpId = DatapathId.of(0x7fff);
    private static String switchIpAddress;
    private static final Set<SwitchFeature> switchFeatures = Collections.singleton(
            SwitchFeature.METERS);

    private final SwitchTrackingService service = new SwitchTrackingService();

    private final FloodlightModuleContext moduleContext = new FloodlightModuleContext();

    private InetSocketAddress switchSocketAddress;
    private InetSocketAddress speakerSocketAddress;
    private final SpeakerSwitchDescription switchDescription = new SpeakerSwitchDescription(
            "(mock) getManufacturerDescription()",
            "(mock) getHardwareDescription()",
            "(mock) getSoftwareDescription()",
            "(mock) getSerialNumber()",
            "(mock) getDatapathDescription()");

    @Mock
    private SwitchManager switchManager;

    @Mock
    private FeatureDetectorService featureDetector;

    @Mock
    private IKafkaProducerService producerService;

    @Before
    public void setUp() throws Exception {
        injectMocks(this);

        switchSocketAddress = new InetSocketAddress(Inet4Address.getByName("127.0.1.1"), 32768);
        speakerSocketAddress = new InetSocketAddress(Inet4Address.getByName("127.0.1.254"), 6653);
        switchSocketAddress.getAddress().getHostName();  // force reverse path lookup

        moduleContext.addService(ISwitchManager.class, switchManager);
        moduleContext.addService(FeatureDetectorService.class, featureDetector);
        moduleContext.addService(IKafkaProducerService.class, producerService);

        IOFSwitchService iofSwitchService = createMock(IOFSwitchService.class);
        iofSwitchService.addOFSwitchListener(eq(service));
        moduleContext.addService(IOFSwitchService.class, iofSwitchService);

        KafkaChannel topics = createMock(KafkaChannel.class);
        expect(topics.getTopoDiscoTopic()).andReturn(KAFKA_ISL_DISCOVERY_TOPIC);
        expect(topics.getRegion()).andReturn("1");
        KafkaUtilityService kafkaUtility = createMock(KafkaUtilityService.class);
        expect(kafkaUtility.getKafkaChannel()).andReturn(topics);
        moduleContext.addService(KafkaUtilityService.class, kafkaUtility);

        replay(kafkaUtility, topics, iofSwitchService);
        service.setup(moduleContext);
        verify(iofSwitchService);

        reset(kafkaUtility, topics, iofSwitchService);
    }

    @After
    public void tearDown() {
        verifyAll();
    }

    @Test
    public void switchAdded() throws Exception {
        SpeakerSwitchView expectedSwitchView = makeSwitchRecord(dpId, switchFeatures, true, true);
        Capture<Message> producedMessage = prepareAliveSwitchEvent(expectedSwitchView);
        replayAll();
        service.switchAdded(dpId);
        verifySwitchEvent(SwitchChangeType.ADDED, expectedSwitchView, producedMessage);
    }

    @Test
    public void switchAddedMissing() throws Exception {
        Capture<Message> producedMessage = prepareRemovedSwitchEvent();
        replayAll();
        service.switchAdded(dpId);
        verifySwitchEvent(SwitchChangeType.ADDED, null, producedMessage);
    }

    @Test
    public void switchRemoved() {
        Capture<Message> producedMessage = prepareSwitchEventCommon(dpId);
        switchManager.deactivate(eq(dpId));
        replayAll();
        service.switchRemoved(dpId);

        verifySwitchEvent(SwitchChangeType.REMOVED, null, producedMessage);
    }

    @Test
    public void switchActivate() throws Exception {
        SpeakerSwitchView expectedSwitchView = makeSwitchRecord(dpId, switchFeatures, true, true);
        switchActivateTest(prepareAliveSwitchEvent(expectedSwitchView), expectedSwitchView);
    }

    @Test
    public void switchActivateMissing() throws Exception {
        switchActivateTest(prepareRemovedSwitchEvent(), null);
    }

    private void switchActivateTest(Capture<Message> producedMessage, SpeakerSwitchView expectedSwitchView)
            throws Exception {
        switchManager.activate(dpId);
        expectLastCall().andAnswer(new IAnswer<Object>() {
            @Override
            public Object answer() throws Throwable {
                service.completeSwitchActivation((DatapathId) getCurrentArguments()[0]);
                return null;
            }
        });

        replayAll();

        service.switchActivated(dpId);
        verifySwitchEvent(SwitchChangeType.ACTIVATED, expectedSwitchView, producedMessage);
        assertEquals(1, producedMessage.getValues().size());
    }

    @Test
    public void switchDeactivated() {
        Capture<Message> producedMessage = prepareSwitchEventCommon(dpId);
        switchManager.deactivate(eq(dpId));
        replayAll();
        service.switchDeactivated(dpId);
        verifySwitchEvent(SwitchChangeType.DEACTIVATED, null, producedMessage);
    }

    @Test
    public void switchChanged() throws Exception {
        SpeakerSwitchView expectedSwitchRecord = makeSwitchRecord(dpId, switchFeatures, true, true);
        Capture<Message> producedMessage = prepareAliveSwitchEvent(expectedSwitchRecord);
        replayAll();
        service.switchChanged(dpId);
        verifySwitchEvent(SwitchChangeType.CHANGED, expectedSwitchRecord, producedMessage);
    }

    @Test
    public void switchChangedMissing() throws Exception {
        Capture<Message> producedMessage = prepareRemovedSwitchEvent();
        replayAll();
        service.switchChanged(dpId);
        verifySwitchEvent(SwitchChangeType.CHANGED, null, producedMessage);
    }

    private Capture<Message> prepareAliveSwitchEvent(SpeakerSwitchView switchView) throws Exception {
        IOFSwitch sw = createMock(IOFSwitch.class);
        expect(sw.getId()).andReturn(dpId).anyTimes();
        expect(sw.getInetAddress())
                .andReturn(new InetSocketAddress("127.0.1.1", 32768)).times(2);

        OFConnection connect = createMock(OFConnection.class);
        expect(connect.getRemoteInetAddress())
                .andReturn(new InetSocketAddress("127.0.1.254", 6653)).times(2);
        expect(sw.getConnectionByCategory(eq(LogicalOFMessageCategory.MAIN))).andReturn(connect).times(2);

        SwitchDescription description = createMock(SwitchDescription.class);
        expect(description.getManufacturerDescription()).andReturn("(mock) getManufacturerDescription()").times(2);
        expect(description.getHardwareDescription()).andReturn("(mock) getHardwareDescription()");
        expect(description.getSoftwareDescription()).andReturn("(mock) getSoftwareDescription()").times(2);
        expect(description.getSerialNumber()).andReturn("(mock) getSerialNumber()");
        expect(description.getDatapathDescription()).andReturn("(mock) getDatapathDescription()");
        expect(sw.getSwitchDescription()).andReturn(description).times(3);

        expect(sw.getOFFactory()).andStubReturn(new OFFactoryVer13());

        expect(switchManager.lookupSwitch(eq(dpId))).andReturn(sw);

        List<OFPortDesc> physicalPorts = new ArrayList<>(switchView.getPorts().size());
        int idx = 1;
        for (SpeakerSwitchPortView port : switchView.getPorts()) {
            physicalPorts.add(makePhysicalPortMock(idx++, port.getState() == SpeakerSwitchPortView.State.UP));
        }
        expect(switchManager.getPhysicalPorts(sw)).andReturn(physicalPorts);

        expect(featureDetector.detectSwitch(sw)).andReturn(ImmutableSet.of(SwitchFeature.METERS));

        return prepareSwitchEventCommon(dpId);
    }

    private Capture<Message> prepareRemovedSwitchEvent() throws Exception {
        expect(switchManager.lookupSwitch(eq(dpId))).andThrow(new SwitchNotFoundException(dpId));
        return prepareSwitchEventCommon(dpId);
    }

    private Capture<Message> prepareSwitchEventCommon(DatapathId dpId) {
        Capture<Message> producedMessage = newCapture(CaptureType.ALL);
        producerService.sendMessageAndTrack(eq(KAFKA_ISL_DISCOVERY_TOPIC), eq(dpId.toString()),
                capture(producedMessage));
        expectLastCall().anyTimes();

        return producedMessage;
    }

    private void verifySwitchEvent(SwitchChangeType expectedState, SpeakerSwitchView expectedSwitchView,
                                   Capture<Message> producedMessage) {
        assertTrue(producedMessage.hasCaptured());

        Message message = producedMessage.getValues().get(0);
        assertTrue(message instanceof InfoMessage);

        InfoData data = ((InfoMessage) message).getData();
        assertTrue(data instanceof SwitchInfoData);

        SwitchInfoData switchInfo = (SwitchInfoData) data;
        assertEquals(new SwitchId(dpId.getLong()), switchInfo.getSwitchId());
        assertEquals(expectedState, switchInfo.getState());

        assertEquals(expectedSwitchView, switchInfo.getSwitchView());
    }

    @Test
    public void networkDumpTest() throws Exception {
        // Cook mock data for ISwitchManager::getAllSwitchMap
        // Two switches with two ports on each

        // switches for ISwitchManager::getAllSwitchMap
        OFSwitch iofSwitch1 = mock(OFSwitch.class);
        OFSwitch iofSwitch2 = mock(OFSwitch.class);

        final DatapathId swAid = DatapathId.of(1);
        final DatapathId swBid = DatapathId.of(2);
        Map<DatapathId, IOFSwitch> switches = ImmutableMap.of(
                swAid, iofSwitch1,
                swBid, iofSwitch2
        );
        Map<DatapathId, InetSocketAddress> switchAddresses = ImmutableMap.of(
                swAid, new InetSocketAddress(Inet4Address.getByName("127.0.1.1"), 32768),
                swBid, new InetSocketAddress(Inet4Address.getByName("127.0.1.2"), 32768)
        );

        SwitchDescription ofSwitchDescription = new SwitchDescription(
                switchDescription.getManufacturer(),
                switchDescription.getHardware(),
                switchDescription.getSoftware(),
                switchDescription.getSerialNumber(),
                switchDescription.getDatapath());
        OFFactoryVer13 ofFactory = new OFFactoryVer13();
        for (DatapathId swId : switches.keySet()) {
            IOFSwitch sw = switches.get(swId);
            expect(sw.getOFFactory()).andStubReturn(ofFactory);
            expect(sw.isActive()).andReturn(true).anyTimes();
            expect(sw.getId()).andReturn(swId).anyTimes();
            expect(sw.getSwitchDescription()).andReturn(ofSwitchDescription);
            expect(sw.getInetAddress()).andReturn(switchAddresses.get(swId));
            expect(sw.getControllerRole()).andStubReturn(OFControllerRole.ROLE_EQUAL);

            OFConnection connect = createMock(OFConnection.class);
            expect(connect.getRemoteInetAddress()).andReturn(speakerSocketAddress);
            expect(sw.getConnectionByCategory(eq(LogicalOFMessageCategory.MAIN))).andReturn(connect);
        }

        expect(switchManager.getAllSwitchMap(true)).andReturn(switches);

        expect(switchManager.getPhysicalPorts(eq(iofSwitch1))).andReturn(ImmutableList.of(
                makePhysicalPortMock(1, true),
                makePhysicalPortMock(2, true)
        ));
        expect(switchManager.getPhysicalPorts(eq(iofSwitch2))).andReturn(ImmutableList.of(
                makePhysicalPortMock(3, true),
                makePhysicalPortMock(4, true),
                makePhysicalPortMock(5, false)
        ));

        expect(featureDetector.detectSwitch(iofSwitch1))
                .andReturn(ImmutableSet.of(SwitchFeature.METERS));
        expect(featureDetector.detectSwitch(iofSwitch2))
                .andReturn(ImmutableSet.of(SwitchFeature.METERS, SwitchFeature.BFD));

        ArrayList<Message> producedMessages = new ArrayList<>();
        // setup hook for verify that we create new message for producer
        producerService.sendMessageAndTrack(eq(KAFKA_ISL_DISCOVERY_TOPIC), anyObject(), anyObject(InfoMessage.class));
        expectLastCall().andAnswer(new IAnswer<Object>() {
            @Override
            public Object answer() {
                Message sentMessage = (Message) getCurrentArguments()[2];
                sentMessage.setTimestamp(0);
                producedMessages.add(sentMessage);
                return null;
            }
        }).anyTimes();

        replayAll();

        String correlationId = "unit-test-correlation-id";
        String dumpId = "dummy-dump-id";
        try (CorrelationContextClosable dummy = CorrelationContext.create(correlationId)) {
            service.dumpAllSwitches(dumpId);
        }

        verify(producerService);

        ArrayList<Message> expectedMessages = new ArrayList<>();
        expectedMessages.add(new InfoMessage(
                        new NetworkDumpSwitchData(new SpeakerSwitchView(
                                new SwitchId(swAid.getLong()),
                                new InetSocketAddress(Inet4Address.getByName("127.0.1.1"), 32768),
                                new InetSocketAddress(Inet4Address.getByName("127.0.1.254"), 6653),
                                "OF_13",
                                switchDescription,
                                ImmutableSet.of(SwitchFeature.METERS),
                                ImmutableList.of(
                                        new SpeakerSwitchPortView(1, SpeakerSwitchPortView.State.UP),
                                        new SpeakerSwitchPortView(2, SpeakerSwitchPortView.State.UP))), dumpId, true),
                0, correlationId));
        expectedMessages.add(new InfoMessage(
                new NetworkDumpSwitchData(new SpeakerSwitchView(
                        new SwitchId(swBid.getLong()),
                        new InetSocketAddress(Inet4Address.getByName("127.0.1.2"), 32768),
                        new InetSocketAddress(Inet4Address.getByName("127.0.1.254"), 6653),
                        "OF_13",
                        switchDescription,
                        ImmutableSet.of(SwitchFeature.METERS, SwitchFeature.BFD),
                        ImmutableList.of(
                                new SpeakerSwitchPortView(3, SpeakerSwitchPortView.State.UP),
                                new SpeakerSwitchPortView(4, SpeakerSwitchPortView.State.UP),
                                new SpeakerSwitchPortView(5, SpeakerSwitchPortView.State.DOWN))), dumpId, true),
                0, correlationId));
        assertEquals(expectedMessages, producedMessages);
    }

    private SpeakerSwitchView makeSwitchRecord(DatapathId datapath, Set<SwitchFeature> features,
                                               boolean... portState) {
        List<SpeakerSwitchPortView> ports = new ArrayList<>(portState.length);
        for (int idx = 0; idx < portState.length; idx++) {
            ports.add(new SpeakerSwitchPortView(idx + 1,
                                                portState[idx]
                                                        ? SpeakerSwitchPortView.State.UP
                                                        : SpeakerSwitchPortView.State.DOWN));
        }
        return new SpeakerSwitchView(
                new SwitchId(datapath.getLong()), switchSocketAddress, speakerSocketAddress, "OF_13",
                switchDescription, features, ports);
    }

    private OFPortDesc makePhysicalPortMock(int number, boolean isEnabled) {
        OFPortDesc port = createMock(OFPortDesc.class);
        expect(port.getPortNo()).andReturn(OFPort.of(number)).anyTimes();
        expect(port.isEnabled()).andReturn(isEnabled).anyTimes();
        return port;
    }
}
