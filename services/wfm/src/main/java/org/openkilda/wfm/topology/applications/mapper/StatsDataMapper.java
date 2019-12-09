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

package org.openkilda.wfm.topology.applications.mapper;

import org.openkilda.applications.info.stats.FlowStatsData;
import org.openkilda.applications.info.stats.FlowStatsEntry;
import org.openkilda.applications.info.stats.MeterStatsData;
import org.openkilda.applications.info.stats.MeterStatsEntry;
import org.openkilda.applications.info.stats.PortStatsData;
import org.openkilda.applications.info.stats.PortStatsEntry;
import org.openkilda.applications.info.stats.SwitchTableStatsData;
import org.openkilda.applications.info.stats.TableStatsEntry;
import org.openkilda.model.SwitchId;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * Convert messaging stats DTO to apps stats DTO.
 */
@Mapper
public abstract class StatsDataMapper {

    public static final StatsDataMapper INSTANCE = Mappers.getMapper(StatsDataMapper.class);

    public abstract PortStatsData map(org.openkilda.messaging.info.stats.PortStatsData data);

    public abstract PortStatsEntry map(org.openkilda.messaging.info.stats.PortStatsEntry entry);

    public abstract FlowStatsData map(org.openkilda.messaging.info.stats.FlowStatsData data);

    public abstract FlowStatsEntry map(org.openkilda.messaging.info.stats.FlowStatsEntry entry);

    @Mapping(source = "tableStatsEntries", target = "stats")
    public abstract SwitchTableStatsData map(org.openkilda.messaging.info.stats.SwitchTableStatsData data);

    public abstract TableStatsEntry map(org.openkilda.messaging.info.stats.TableStatsEntry entry);

    public abstract MeterStatsData map(org.openkilda.messaging.info.stats.MeterStatsData data);

    public abstract MeterStatsEntry map(org.openkilda.messaging.info.stats.MeterStatsEntry entry);

    public String toSwitchId(SwitchId switchId) {
        return switchId.toString();
    }
}
