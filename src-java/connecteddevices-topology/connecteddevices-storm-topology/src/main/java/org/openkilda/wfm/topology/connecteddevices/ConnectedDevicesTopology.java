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

package org.openkilda.wfm.topology.connecteddevices;

import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.spi.PersistenceProvider;
import org.openkilda.wfm.LaunchEnvironment;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.connecteddevices.bolts.PacketBolt;

import org.apache.storm.generated.StormTopology;
import org.apache.storm.topology.TopologyBuilder;

public class ConnectedDevicesTopology extends AbstractTopology<ConnectedDevicesTopologyConfig> {
    public static final String CONNECTED_DEVICES_SPOUT_ID = "connected-devices-spout";
    public static final String PACKET_BOLT_ID = "packet-bolt";

    public ConnectedDevicesTopology(LaunchEnvironment env) {
        super(env, ConnectedDevicesTopologyConfig.class);
    }

    /**
     * Creating topology.
     */
    public StormTopology createTopology() {

        TopologyBuilder builder = new TopologyBuilder();
        PersistenceManager persistenceManager =
                PersistenceProvider.getInstance().getPersistenceManager(configurationProvider);

        createSpout(builder);
        createPacketBolt(builder, persistenceManager);

        return builder.createTopology();
    }

    private void createPacketBolt(TopologyBuilder builder, PersistenceManager persistenceManager) {
        PacketBolt routerBolt = new PacketBolt(persistenceManager);
        builder.setBolt(PACKET_BOLT_ID, routerBolt, topologyConfig.getNewParallelism())
                .shuffleGrouping(CONNECTED_DEVICES_SPOUT_ID);
    }

    private void createSpout(TopologyBuilder builder) {
        builder.setSpout(CONNECTED_DEVICES_SPOUT_ID, buildKafkaSpout(topologyConfig.getKafkaTopoConnectedDevicesTopic(),
                CONNECTED_DEVICES_SPOUT_ID));
    }

    /**
     * Main method to run topology.
     */
    public static void main(String[] args) {
        try {
            LaunchEnvironment env = new LaunchEnvironment(args);
            (new ConnectedDevicesTopology(env)).setup();
        } catch (Exception e) {
            System.exit(handleLaunchException(e));
        }
    }
}
