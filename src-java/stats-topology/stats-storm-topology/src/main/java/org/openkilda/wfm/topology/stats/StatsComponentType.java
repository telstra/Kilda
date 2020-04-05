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

package org.openkilda.wfm.topology.stats;

/**
 * Represents components used in {@link StatsTopology}.
 */
public enum StatsComponentType {
    STATS_OFS_KAFKA_SPOUT,
    STATS_OFS_BOLT,
    PORT_STATS_METRIC_GEN,
    METER_STATS_METRIC_GEN,
    METER_CFG_STATS_METRIC_GEN,
    SYSTEM_RULE_STATS_METRIC_GEN,
    FLOW_STATS_METRIC_GEN,
    TABLE_STATS_METRIC_GEN,
    PACKET_IN_OUT_STATS_METRIC_GEN,
    ERROR_BOLT,
    STATS_CACHE_BOLT,
    STATS_KILDA_SPEAKER_SPOUT,
    TICK_BOLT,
    STATS_REQUESTER_BOLT,
    STATS_KILDA_SPEAKER_BOLT,
    STATS_GRPC_SPEAKER_BOLT,
    STATS_CACHE_FILTER_BOLT,
    SPEAKER_REQUEST_DECODER,
    SERVER42_STATS_FLOW_RTT_SPOUT,
    SERVER42_STATS_FLOW_RTT_METRIC_GEN
}
