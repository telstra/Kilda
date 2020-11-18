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

package org.openkilda.wfm.topology.floodlightrouter;

public final class ComponentType {
    public static final String KILDA_FLOW_HS_REPLY_BOLT = "KILDA_FLOW_HS_REPLY_BOLT";
    public static final String KILDA_PING_REPLY_BOLT = "KILDA_PING_REPLY_BOLT";
    public static final String NORTHBOUND_REPLY_BOLT = "NORTHBOUND_REPLY_BOLT";
    public static final String KILDA_SWITCH_MANAGER_REPLY_BOLT = "KILDA_SWITCH_MANAGER_REPLY_BOLT";
    public static final String KILDA_STATS_REPLY_BOLT = "KILDA_STATS_REPLY_BOLT";
    public static final String KILDA_ISL_LATENCY_REPLY_BOLT = "KILDA_ISL_LATENCY_REPLY_BOLT";
    public static final String KILDA_CONNECTED_DEVICES_REPLY_BOLT = "KILDA_CONNECTED_DEVICES_REPLY_BOLT";
    public static final String KILDA_NB_WORKER_REPLY_BOLT = "KILDA_NB_WORKER_REPLY_BOLT";
    public static final String KILDA_TOPO_DISCO_REPLY_BOLT = "KILDA_TOPO_DISCO_REPLY_BOLT";
    public static final String KILDA_TOPO_DISCO_BOLT = "KILDA_TOPO_DISCO_BOLT";

    public static final String SPEAKER_FLOW_REQUEST_BOLT = "KILDA_FLOW_REQUEST_BOLT";
    public static final String SPEAKER_REQUEST_BOLT = "SPEAKER_REQUEST_BOLT";
    public static final String SPEAKER_DISCO_REQUEST_BOLT = "SPEAKER_DISCO_REQUEST_BOLT";

    public static final String SPEAKER_KAFKA_SPOUT = "ROUTER_SPEAKER_KAFKA_SPOUT";
    public static final String KILDA_FLOW_HS_KAFKA_SPOUT = "KILDA_FLOW_HS_KAFKA_SPOUT";
    public static final String SPEAKER_FLOW_HS_KAFKA_SPOUT = "ROUTER_SPEAKER_FLOW_HS_KAFKA_SPOUT";
    public static final String SPEAKER_PING_KAFKA_SPOUT = "SPEAKER_PING_KAFKA_SPOUT";
    public static final String KILDA_PING_KAFKA_SPOUT = "KILDA_PING_KAFKA_SPOUT";
    public static final String KILDA_STATS_KAFKA_SPOUT = "KILDA_STATS_KAFKA_SPOUT";
    public static final String KILDA_ISL_LATENCY_KAFKA_SPOUT = "KILDA_ISL_LATENCY_KAFKA_SPOUT";
    public static final String KILDA_CONNECTED_DEVICES_KAFKA_SPOUT = "KILDA_CONNECTED_DEVICES_KAFKA_SPOUT";
    public static final String KILDA_SWITCH_MANAGER_KAFKA_SPOUT = "KILDA_SWITCH_MANAGER_KAFKA_SPOUT";
    public static final String SPEAKER_DISCO_KAFKA_SPOUT = "SPEAKER_DISCO_KAFKA_SPOUT";
    public static final String KILDA_TOPO_DISCO_KAFKA_SPOUT = "KILDA_TOPO_DISCO_KAFKA_SPOUT";
    public static final String NORTHBOUND_REPLY_KAFKA_SPOUT = "NORTHBOUND_REPLY_KAFKA_SPOUT";
    public static final String KILDA_NB_WORKER_KAFKA_SPOUT = "KILDA_NB_WORKER_KAFKA_SPOUT";
    public static final String STATS_STATS_REQUEST_KAFKA_SPOUT = "STATS_STATS_REQUEST_KAFKA_SPOUT";
    public static final String STATS_STATS_REQUEST_BOLT = "STATS_STATS_REQUEST_BOLT";
    public static final String FL_STATS_SWITCHES_SPOUT = "FL_STATS_SWITCHES_SPOUT";
    public static final String FL_STATS_SWITCHES_REPLY_BOLT = "FL_STATS_SWITCHES_REPLY_BOLT";


    public static final String SWITCH_MONITOR = "switch-monitor";

    public static final String KAFKA_GENERIC_OUTPUT = "kafka-generic.output";
    public static final String KAFKA_HS_OUTPUT = "kafka-hs.output";

    public static final String CLOCK = "clock";

    private ComponentType() {}
}
