/* Copyright 2020 Telstra Open Source
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

package org.openkilda.persistence.ferma.repositories;

import static java.lang.String.format;

import org.openkilda.model.ConnectedDeviceType;
import org.openkilda.model.SwitchConnectedDevice;
import org.openkilda.model.SwitchConnectedDevice.SwitchConnectedDeviceData;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.SwitchConnectedDeviceFrame;
import org.openkilda.persistence.ferma.frames.converters.ConnectedDeviceTypeConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.SwitchConnectedDeviceRepository;
import org.openkilda.persistence.tx.TransactionManager;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link SwitchConnectedDeviceRepository}.
 */
public class FermaSwitchConnectedDevicesRepository
        extends FermaGenericRepository<SwitchConnectedDevice, SwitchConnectedDeviceData, SwitchConnectedDeviceFrame>
        implements SwitchConnectedDeviceRepository {
    FermaSwitchConnectedDevicesRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Collection<SwitchConnectedDevice> findAll() {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(SwitchConnectedDeviceFrame.FRAME_LABEL))
                .toListExplicit(SwitchConnectedDeviceFrame.class).stream()
                .map(SwitchConnectedDevice::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<SwitchConnectedDevice> findBySwitchId(SwitchId switchId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(SwitchConnectedDeviceFrame.FRAME_LABEL)
                .has(SwitchConnectedDeviceFrame.SWITCH_ID_PROPERTY,
                        SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .toListExplicit(SwitchConnectedDeviceFrame.class).stream()
                .map(SwitchConnectedDevice::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<SwitchConnectedDevice> findByFlowId(String flowId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(SwitchConnectedDeviceFrame.FRAME_LABEL)
                .has(SwitchConnectedDeviceFrame.FLOW_ID_PROPERTY, flowId))
                .toListExplicit(SwitchConnectedDeviceFrame.class).stream()
                .map(SwitchConnectedDevice::new)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<SwitchConnectedDevice> findLldpByUniqueFieldCombination(
            SwitchId switchId, int portNumber, int vlan, String macAddress, String chassisId, String portId) {
        Collection<? extends SwitchConnectedDeviceFrame> devices =
                framedGraph().traverse(g -> g.V()
                        .hasLabel(SwitchConnectedDeviceFrame.FRAME_LABEL)
                        .has(SwitchConnectedDeviceFrame.SWITCH_ID_PROPERTY,
                                SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                        .has(SwitchConnectedDeviceFrame.PORT_NUMBER_PROPERTY, portNumber)
                        .has(SwitchConnectedDeviceFrame.VLAN_PROPERTY, vlan)
                        .has(SwitchConnectedDeviceFrame.MAC_ADDRESS_PROPERTY, macAddress)
                        .has(SwitchConnectedDeviceFrame.TYPE_PROPERTY,
                                ConnectedDeviceTypeConverter.INSTANCE.toGraphProperty(ConnectedDeviceType.LLDP))
                        .has(SwitchConnectedDeviceFrame.CHASSIS_ID_PROPERTY, chassisId)
                        .has(SwitchConnectedDeviceFrame.PORT_ID_PROPERTY, portId))
                        .toListExplicit(SwitchConnectedDeviceFrame.class);
        if (devices.size() > 1) {
            throw new PersistenceException(format("Found more that 1 LLDP Connected Device by switch ID '%s', "
                            + "port number '%d', vlan '%d', mac address '%s', chassis ID '%s' and port ID '%s'",
                    switchId, portNumber, vlan, macAddress, chassisId, portId));
        }
        return devices.isEmpty() ? Optional.empty() :
                Optional.of(devices.iterator().next()).map(SwitchConnectedDevice::new);
    }

    @Override
    public Optional<SwitchConnectedDevice> findArpByUniqueFieldCombination(
            SwitchId switchId, int portNumber, int vlan, String macAddress, String ipAddress) {
        Collection<? extends SwitchConnectedDeviceFrame> devices =
                framedGraph().traverse(g -> g.V()
                        .hasLabel(SwitchConnectedDeviceFrame.FRAME_LABEL)
                        .has(SwitchConnectedDeviceFrame.SWITCH_ID_PROPERTY,
                                SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                        .has(SwitchConnectedDeviceFrame.PORT_NUMBER_PROPERTY, portNumber)
                        .has(SwitchConnectedDeviceFrame.VLAN_PROPERTY, vlan)
                        .has(SwitchConnectedDeviceFrame.MAC_ADDRESS_PROPERTY, macAddress)
                        .has(SwitchConnectedDeviceFrame.TYPE_PROPERTY,
                                ConnectedDeviceTypeConverter.INSTANCE.toGraphProperty(ConnectedDeviceType.ARP))
                        .has(SwitchConnectedDeviceFrame.IP_ADDRESS_PROPERTY, ipAddress))
                        .toListExplicit(SwitchConnectedDeviceFrame.class);
        if (devices.size() > 1) {
            throw new PersistenceException(format("Found more that 1 ARP Connected Device by switch ID '%s', "
                            + "port number '%d', vlan '%d', mac address '%s', IP address '%s'",
                    switchId, portNumber, vlan, macAddress, ipAddress));
        }
        return devices.isEmpty() ? Optional.empty() :
                Optional.of(devices.iterator().next()).map(SwitchConnectedDevice::new);
    }

    @Override
    protected SwitchConnectedDeviceFrame doAdd(SwitchConnectedDeviceData data) {
        SwitchConnectedDeviceFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                SwitchConnectedDeviceFrame.FRAME_LABEL, SwitchConnectedDeviceFrame.class);
        SwitchConnectedDevice.SwitchConnectedDeviceCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(SwitchConnectedDeviceFrame frame) {
        frame.remove();
    }

    @Override
    protected SwitchConnectedDeviceData doDetach(SwitchConnectedDevice entity, SwitchConnectedDeviceFrame frame) {
        return SwitchConnectedDevice.SwitchConnectedDeviceCloner.INSTANCE.deepCopy(frame);
    }
}
