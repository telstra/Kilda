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

package org.openkilda.wfm.topology.network.service;

import org.openkilda.config.provider.PropertiesBasedConfigurationProvider;
import org.openkilda.messaging.info.event.SwitchChangeType;
import org.openkilda.messaging.info.event.SwitchInfoData;
import org.openkilda.messaging.model.SpeakerSwitchDescription;
import org.openkilda.messaging.model.SpeakerSwitchPortView;
import org.openkilda.messaging.model.SpeakerSwitchView;
import org.openkilda.model.SwitchFeature;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.InMemoryGraphPersistenceManager;
import org.openkilda.persistence.NetworkConfig;
import org.openkilda.wfm.topology.network.model.NetworkOptions;

import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(MockitoJUnitRunner.class)
public class NetworkIntegrationTest {
    private static InMemoryGraphPersistenceManager persistenceManager;


    @ClassRule
    public static TemporaryFolder fsData = new TemporaryFolder();

    private static final NetworkOptions options = NetworkOptions.builder()
            .bfdEnabled(true)
            .bfdLogicalPortOffset(200)
            .dbRepeatMaxDurationSeconds(30)
            .build();

    private final SpeakerSwitchDescription switchDescription = SpeakerSwitchDescription.builder()
            .manufacturer("OF vendor A")
            .hardware("AHW-0")
            .software("AOS-1")
            .serialNumber("aabbcc")
            .datapath("OpenFlow switch AABBCC")
            .build();

    private final InetSocketAddress speakerInetAddress = new InetSocketAddress(
            Inet4Address.getByName("127.1.0.254"), 6653);

    private final SwitchId alphaDatapath = new SwitchId(1);
    private final InetSocketAddress alphaInetAddress = new InetSocketAddress(
            Inet4Address.getByName("127.1.0.1"), 32768);

    private final String alphaDescription = String.format("%s OF_13 %s",
                                                          switchDescription.getManufacturer(),
                                                          switchDescription.getSoftware());

    private NetworkSwitchService switchService;
    private NetworkPortService portService;
    private NetworkBfdPortService bfdPortService;
    private NetworkUniIslService uniIslService;
    private NetworkIslService islService;

    private NetworkIntegrationCarrier integrationCarrier;

    public NetworkIntegrationTest() throws UnknownHostException {
        // This constructor is required to define exception list for field initializers.
    }

    @BeforeClass
    public static void setUpOnce() {
        persistenceManager = new InMemoryGraphPersistenceManager(
                new PropertiesBasedConfigurationProvider().getConfiguration(NetworkConfig.class));
    }

    @Before
    public void setUp() throws Exception {
        persistenceManager.clear();

        switchService = new NetworkSwitchService(null, persistenceManager, options);
        portService = new NetworkPortService(null, persistenceManager);
        bfdPortService = new NetworkBfdPortService(integrationCarrier, persistenceManager);
        uniIslService = new NetworkUniIslService(null);
        islService = new NetworkIslService(null, persistenceManager, options);

        integrationCarrier = new NetworkIntegrationCarrier(
                switchService,
                portService, bfdPortService,
                uniIslService, islService);
    }

    @Test
    @Ignore
    public void switchAdd() {
        Set<SwitchFeature> features = new HashSet<>();
        features.add(SwitchFeature.BFD);

        Integer bfdLocalPortOffset = options.getBfdLogicalPortOffset();
        List<SpeakerSwitchPortView> ports = ImmutableList.of(
                new SpeakerSwitchPortView(1, SpeakerSwitchPortView.State.UP),
                new SpeakerSwitchPortView(1 + bfdLocalPortOffset, SpeakerSwitchPortView.State.UP),
                new SpeakerSwitchPortView(2, SpeakerSwitchPortView.State.DOWN),
                new SpeakerSwitchPortView(2 + bfdLocalPortOffset, SpeakerSwitchPortView.State.DOWN));
        SpeakerSwitchView speakerSwitchView = new SpeakerSwitchView(
                alphaDatapath, alphaInetAddress, speakerInetAddress, "OF_13", switchDescription, features, ports);

        SwitchInfoData switchAddEvent = new SwitchInfoData(
                alphaDatapath, SwitchChangeType.ADDED,
                alphaInetAddress.toString(), alphaInetAddress.toString(), alphaDescription,
                speakerInetAddress.toString(),
                false,
                speakerSwitchView);
        switchService.switchEvent(switchAddEvent);

        SwitchInfoData switchActivateEvent = new SwitchInfoData(
                alphaDatapath, SwitchChangeType.ACTIVATED,
                alphaInetAddress.toString(), alphaInetAddress.toString(), alphaDescription,
                speakerInetAddress.toString(),
                false,
                speakerSwitchView);
        switchService.switchEvent(switchActivateEvent);
    }
}
