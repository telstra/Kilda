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

package org.openkilda.server42.control.kafka;

import org.openkilda.server42.control.config.SwitchToVlanMapping;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class KafkaRecordFilter implements RecordFilterStrategy<Object, Object> {

    private Set<String> switchList;

    private Map<Long, List<String>> vlanToSwitchMap;

    public KafkaRecordFilter(@Autowired SwitchToVlanMapping switchToVlanMapping) {
        vlanToSwitchMap = switchToVlanMapping.getVlan();
        switchList = vlanToSwitchMap.values().stream().flatMap(Collection::stream).collect(Collectors.toSet());
    }

    @Override
    public boolean filter(ConsumerRecord<Object, Object> consumerRecord) {
        String switchId = consumerRecord.key().toString();
        // Filter messages for supported dpid
        if (switchList.contains(switchId)) {
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug("Dropped message by dpid {}", switchId);
        }
        return true;
    }

}
