/* Copyright 2017 Telstra Open Source
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

import org.openkilda.floodlight.KafkaChannel;
import org.openkilda.floodlight.converter.IofSwitchConverter;
import org.openkilda.floodlight.converter.OfPortDescConverter;
import org.openkilda.floodlight.error.SwitchNotFoundException;
import org.openkilda.floodlight.error.SwitchOperationException;
import org.openkilda.floodlight.service.FeatureDetectorService;
import org.openkilda.floodlight.service.IService;
import org.openkilda.floodlight.service.kafka.IKafkaProducerService;
import org.openkilda.floodlight.service.kafka.KafkaUtilityService;
import org.openkilda.floodlight.utils.CorrelationContext;
import org.openkilda.floodlight.utils.FloodlightDashboardLogger;
import org.openkilda.floodlight.utils.NewCorrelationContextRequired;
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

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.LogicalOFMessageCategory;
import net.floodlightcontroller.core.PortChangeType;
import net.floodlightcontroller.core.SwitchDescription;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import org.projectfloodlight.openflow.protocol.OFControllerRole;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.types.DatapathId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class SwitchTrackingService implements IOFSwitchListener, IService {
    private static final Logger logger = LoggerFactory.getLogger(SwitchTrackingService.class);

    private static final FloodlightDashboardLogger dashboardLogger = new FloodlightDashboardLogger(logger);

    private final ReadWriteLock discoveryLock = new ReentrantReadWriteLock();

    private IKafkaProducerService producerService;
    private ISwitchManager switchManager;
    private FeatureDetectorService featureDetector;

    private String discoveryTopic;
    private String region;

    /**
     * Send dump contain all connected at this moment switches.
     */
    public void dumpAllSwitches(String dumpId) {
        discoveryLock.writeLock().lock();
        try {
            dumpAllSwitchesAction(dumpId);
        } finally {
            discoveryLock.writeLock().unlock();
        }
    }

    /**
     * Desired to be used on the end of switch activation to send discovery messages.
     */
    public void completeSwitchActivation(DatapathId dpId) {
        discoveryLock.readLock().lock();
        try {
            switchDiscoveryAction(dpId, SwitchChangeType.ACTIVATED);
        } finally {
            discoveryLock.readLock().unlock();
        }
    }

    @Override
    @NewCorrelationContextRequired
    public void switchAdded(final DatapathId switchId) {
        dashboardLogger.onSwitchEvent(switchId, SwitchChangeType.ADDED);
        switchDiscovery(switchId, SwitchChangeType.ADDED);
    }

    @Override
    @NewCorrelationContextRequired
    public void switchRemoved(final DatapathId switchId) {
        dashboardLogger.onSwitchEvent(switchId, SwitchChangeType.REMOVED);

        // TODO(surabujin): must figure out events order/set during lost connection
        switchManager.deactivate(switchId);
        switchDiscovery(switchId, SwitchChangeType.REMOVED);
    }

    @Override
    @NewCorrelationContextRequired
    public void switchActivated(final DatapathId switchId) {
        dashboardLogger.onSwitchEvent(switchId, SwitchChangeType.ACTIVATED);

        try {
            switchManager.activate(switchId);
        } catch (SwitchOperationException e) {
            logger.error("Switch {} activation have failed", switchId, e);
        }
    }

    @Override
    @NewCorrelationContextRequired
    public void switchDeactivated(final DatapathId switchId) {
        dashboardLogger.onSwitchEvent(switchId, SwitchChangeType.DEACTIVATED);

        switchManager.deactivate(switchId);
        switchDiscovery(switchId, SwitchChangeType.DEACTIVATED);
    }

    @Override
    @NewCorrelationContextRequired
    public void switchChanged(final DatapathId switchId) {
        dashboardLogger.onSwitchEvent(switchId, SwitchChangeType.CHANGED);
        switchDiscovery(switchId, SwitchChangeType.CHANGED);
    }

    @Override
    @NewCorrelationContextRequired
    public void switchPortChanged(final DatapathId switchId, final OFPortDesc portDesc, final PortChangeType type) {
        dashboardLogger.onPortEvent(switchId, portDesc, type);

        if (! OfPortDescConverter.INSTANCE.isReservedPort(portDesc.getPortNo())) {
            portDiscovery(switchId, portDesc, type);
        }
    }

    @Override
    public void setup(FloodlightModuleContext context) {
        producerService = context.getServiceImpl(IKafkaProducerService.class);
        switchManager = context.getServiceImpl(ISwitchManager.class);
        featureDetector = context.getServiceImpl(FeatureDetectorService.class);
        KafkaChannel kafkaChannel = context.getServiceImpl(KafkaUtilityService.class).getKafkaChannel();
        discoveryTopic = kafkaChannel.getTopoDiscoTopic();
        region = kafkaChannel.getRegion();

        context.getServiceImpl(IOFSwitchService.class).addOFSwitchListener(this);
    }

    private void dumpAllSwitchesAction(String dumpId) {
        Collection<IOFSwitch> iofSwitches = switchManager.getAllSwitchMap(true).values();
        for (IOFSwitch sw : iofSwitches) {
            NetworkDumpSwitchData payload = new NetworkDumpSwitchData(
                    buildSwitch(sw), dumpId, sw.getControllerRole() != OFControllerRole.ROLE_SLAVE);
            emitDiscoveryEvent(sw.getId(), payload);
        }
    }

    private void switchDiscovery(DatapathId dpId, SwitchChangeType state) {
        discoveryLock.readLock().lock();
        try {
            switchDiscoveryAction(dpId, state);
        } finally {
            discoveryLock.readLock().unlock();
        }
    }

    private void portDiscovery(DatapathId dpId, OFPortDesc portDesc, PortChangeType changeType) {
        discoveryLock.readLock().lock();
        try {
            portDiscoveryAction(dpId, portDesc, changeType);
        } finally {
            discoveryLock.readLock().unlock();
        }
    }

    private void switchDiscoveryAction(DatapathId dpId, SwitchChangeType event) {
        logger.info("Send switch discovery ({} - {})", dpId, event);
        SwitchInfoData payload = null;
        if (SwitchChangeType.DEACTIVATED != event && SwitchChangeType.REMOVED != event) {
            try {
                IOFSwitch sw = switchManager.lookupSwitch(dpId);
                SpeakerSwitchView switchView = buildSwitch(sw);
                payload = buildSwitchMessage(sw, switchView, event);
            } catch (SwitchNotFoundException e) {
                logger.error(
                        "Switch {} is not in management state now({}), switch ISL discovery details will be degraded.",
                        dpId, e.getMessage());
            }
        }
        if (payload == null) {
            payload = buildSwitchMessage(dpId, event);
        }

        emitDiscoveryEvent(dpId, payload);
    }

    private void portDiscoveryAction(DatapathId dpId, OFPortDesc portDesc, PortChangeType changeType) {
        logger.info("Send port discovery ({}-{} - {})", dpId, portDesc.getPortNo(), changeType);
        InfoData payload = OfPortDescConverter.INSTANCE.toPortInfoData(dpId, portDesc, changeType);
        emitDiscoveryEvent(dpId, payload);
    }

    private void emitDiscoveryEvent(DatapathId dpId, InfoData payload) {
        Message message = buildMessage(payload);
        producerService.sendMessageAndTrack(discoveryTopic, dpId.toString(), message);
    }

    /**
     * Builds fully filled switch ISL discovery message.
     *
     * @param sw switch instance
     * @param eventType type of event
     * @return Message
     */
    private SwitchInfoData buildSwitchMessage(IOFSwitch sw, SpeakerSwitchView switchView, SwitchChangeType eventType) {
        return IofSwitchConverter.buildSwitchInfoData(sw, switchView, eventType);
    }

    /**
     * Builds degraded switch ISL discovery message.
     *
     * @param dpId switch datapath
     * @param eventType type of event
     * @return Message
     */
    private SwitchInfoData buildSwitchMessage(DatapathId dpId, SwitchChangeType eventType) {
        return new SwitchInfoData(new SwitchId(dpId.getLong()), eventType);
    }

    /**
     * Builds a generic message object.
     *
     * @param data data to use in the message body
     * @return Message
     */
    private Message buildMessage(final InfoData data) {
        return new InfoMessage(data, System.currentTimeMillis(), CorrelationContext.getId(), null, region);
    }

    private SpeakerSwitchView buildSwitch(IOFSwitch sw) {
        SwitchDescription ofDescription = sw.getSwitchDescription();
        SpeakerSwitchDescription description = SpeakerSwitchDescription.builder()
                .manufacturer(ofDescription.getManufacturerDescription())
                .hardware(ofDescription.getHardwareDescription())
                .software(ofDescription.getSoftwareDescription())
                .serialNumber(ofDescription.getSerialNumber())
                .datapath(ofDescription.getDatapathDescription())
                .build();
        Set<SwitchFeature> features = featureDetector.detectSwitch(sw);
        List<SpeakerSwitchPortView> ports = switchManager.getPhysicalPorts(sw).stream()
                .map(port -> new SpeakerSwitchPortView(
                        port.getPortNo().getPortNumber(),
                        port.isEnabled()
                                ? SpeakerSwitchPortView.State.UP
                                : SpeakerSwitchPortView.State.DOWN))
                .collect(Collectors.toList());
        return new SpeakerSwitchView(new SwitchId(sw.getId().getLong()),
                                     (InetSocketAddress) sw.getInetAddress(),
                                     (InetSocketAddress) (sw.getConnectionByCategory(
                                                     LogicalOFMessageCategory.MAIN).getRemoteInetAddress()),
                                     sw.getOFFactory().getVersion().toString(),
                                     description, features, ports);
    }
}
