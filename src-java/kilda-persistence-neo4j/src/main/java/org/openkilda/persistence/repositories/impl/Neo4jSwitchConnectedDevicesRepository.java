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

package org.openkilda.persistence.repositories.impl;

import static java.lang.String.format;
import static org.neo4j.ogm.cypher.ComparisonOperator.EQUALS;
import static org.openkilda.persistence.repositories.impl.Neo4jSwitchRepository.SWITCH_NAME_PROPERTY_NAME;

import org.openkilda.model.ConnectedDeviceType;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchConnectedDevice;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.repositories.SwitchConnectedDeviceRepository;

import org.neo4j.ogm.cypher.Filter;
import org.neo4j.ogm.cypher.Filters;

import java.util.Collection;
import java.util.Optional;

public class Neo4jSwitchConnectedDevicesRepository
        extends Neo4jGenericRepository<SwitchConnectedDevice> implements SwitchConnectedDeviceRepository {

    private static final String SWITCH_FIELD = "switchObj";
    private static final String FLOW_ID_PROPERTY_NAME = "flow_id";
    private static final String PORT_NUMBER_PROPERTY_NAME = "port_number";
    private static final String VLAN_PROPERTY_NAME = "vlan";
    private static final String INNER_VLAN_PROPERTY_NAME = "inner_vlan";
    private static final String TYPE_PROPERTY_NAME = "type";
    private static final String MAC_ADDRESS_PROPERTY_NAME = "mac_address";
    private static final String CHASSIS_ID_PROPERTY_NAME = "chassis_id";
    private static final String PORT_ID_PROPERTY_NAME = "port_id";
    private static final String IP_ADDRESS_PROPERTY_NAME = "ip_address";

    Neo4jSwitchConnectedDevicesRepository(Neo4jSessionFactory sessionFactory, TransactionManager transactionManager) {
        super(sessionFactory, transactionManager);
    }

    @Override
    public Collection<SwitchConnectedDevice> findBySwitchId(SwitchId switchId) {
        Filter switchFilter = new Filter(SWITCH_NAME_PROPERTY_NAME, EQUALS, switchId.toString());
        switchFilter.setNestedPath(new Filter.NestedPathSegment(SWITCH_FIELD, Switch.class));

        return loadAll(switchFilter);
    }

    @Override
    public Collection<SwitchConnectedDevice> findByFlowId(String flowId) {
        return loadAll(new Filter(FLOW_ID_PROPERTY_NAME, EQUALS, flowId));
    }

    @Override
    public Optional<SwitchConnectedDevice> findLldpByUniqueFieldCombination(
            SwitchId switchId, int portNumber, int vlan, int innerVlan, String macAddress, String chassisId,
            String portId) {

        Filter switchFilter = new Filter(SWITCH_NAME_PROPERTY_NAME, EQUALS, switchId.toString());
        switchFilter.setNestedPath(new Filter.NestedPathSegment(SWITCH_FIELD, Switch.class));

        Filters filters = new Filters(switchFilter);
        filters.and(new Filter(PORT_NUMBER_PROPERTY_NAME, EQUALS, portNumber));
        filters.and(new Filter(VLAN_PROPERTY_NAME, EQUALS, vlan));
        filters.and(new Filter(INNER_VLAN_PROPERTY_NAME, EQUALS, innerVlan));
        filters.and(new Filter(MAC_ADDRESS_PROPERTY_NAME, EQUALS, macAddress));
        filters.and(new Filter(TYPE_PROPERTY_NAME, EQUALS, ConnectedDeviceType.LLDP));
        filters.and(new Filter(CHASSIS_ID_PROPERTY_NAME, EQUALS, chassisId));
        filters.and(new Filter(PORT_ID_PROPERTY_NAME, EQUALS, portId));

        Collection<SwitchConnectedDevice> devices = loadAll(filters);

        if (devices.size() > 1) {
            throw new PersistenceException(format("Found more that 1 LLDP Connected Device by switch ID '%s', "
                            + "port number '%d', vlan '%d', mac address '%s', chassis ID '%s' and port ID '%s'",
                    switchId, portNumber, vlan, macAddress, chassisId, portId));
        }
        return  devices.isEmpty() ? Optional.empty() : Optional.of(devices.iterator().next());
    }

    @Override
    public Optional<SwitchConnectedDevice> findArpByUniqueFieldCombination(
            SwitchId switchId, int portNumber, int vlan, int innerVlan, String macAddress, String ipAddress) {

        Filter switchFilter = new Filter(SWITCH_NAME_PROPERTY_NAME, EQUALS, switchId.toString());
        switchFilter.setNestedPath(new Filter.NestedPathSegment(SWITCH_FIELD, Switch.class));

        Filters filters = new Filters(switchFilter);
        filters.and(new Filter(PORT_NUMBER_PROPERTY_NAME, EQUALS, portNumber));
        filters.and(new Filter(VLAN_PROPERTY_NAME, EQUALS, vlan));
        filters.and(new Filter(INNER_VLAN_PROPERTY_NAME, EQUALS, innerVlan));
        filters.and(new Filter(MAC_ADDRESS_PROPERTY_NAME, EQUALS, macAddress));
        filters.and(new Filter(IP_ADDRESS_PROPERTY_NAME, EQUALS, ipAddress));
        filters.and(new Filter(TYPE_PROPERTY_NAME, EQUALS, ConnectedDeviceType.ARP));

        Collection<SwitchConnectedDevice> devices = loadAll(filters);

        if (devices.size() > 1) {
            throw new PersistenceException(format("Found more that 1 ARP Connected Device by switch ID '%s', "
                            + "port number '%d', vlan '%d', mac address '%s', IP address '%s'",
                    switchId, portNumber, vlan, macAddress, ipAddress));
        }
        return  devices.isEmpty() ? Optional.empty() : Optional.of(devices.iterator().next());
    }

    @Override
    protected Class<SwitchConnectedDevice> getEntityType() {
        return SwitchConnectedDevice.class;
    }


    @Override
    protected int getDepthCreateUpdateEntity() {
        return 1;
    }
}
