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

package org.openkilda.wfm.share.mappers;

import org.openkilda.messaging.Utils;
import org.openkilda.messaging.payload.history.FlowDumpPayload;
import org.openkilda.messaging.payload.history.FlowEventPayload;
import org.openkilda.messaging.payload.history.FlowHistoryPayload;
import org.openkilda.messaging.payload.history.PortHistoryPayload;
import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookieSchema;
import org.openkilda.model.history.FlowDump;
import org.openkilda.model.history.FlowEvent;
import org.openkilda.model.history.FlowHistory;
import org.openkilda.model.history.PortHistory;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.history.model.FlowDumpData;
import org.openkilda.wfm.share.history.model.FlowDumpData.DumpType;
import org.openkilda.wfm.share.history.model.FlowEventData;
import org.openkilda.wfm.share.history.model.FlowHistoryData;
import org.openkilda.wfm.share.history.model.PortHistoryData;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Slf4j
@Mapper(uses = {FlowPathMapper.class})
public abstract class HistoryMapper {
    public static final HistoryMapper INSTANCE = Mappers.getMapper(HistoryMapper.class);

    @Mapping(target = "timestamp", expression = "java(flowEvent.getTimestamp().getEpochSecond())")
    @Mapping(target = "histories", ignore = true)
    @Mapping(target = "dumps", ignore = true)
    public abstract FlowEventPayload map(FlowEvent flowEvent);

    @Mapping(target = "timestamp", expression = "java(flowHistory.getTimestamp().getEpochSecond())")
    public abstract FlowHistoryPayload map(FlowHistory flowHistory);

    @Mapping(target = "forwardCookie",
            expression = "java(flowDump.getForwardCookie() != null ? flowDump.getForwardCookie().getValue() : null)")
    @Mapping(target = "reverseCookie",
            expression = "java(flowDump.getReverseCookie() != null ? flowDump.getReverseCookie().getValue() : null)")
    @Mapping(target = "forwardMeterId",
            expression = "java(flowDump.getForwardMeterId() != null ? flowDump.getForwardMeterId().getValue() : null)")
    @Mapping(target = "reverseMeterId",
            expression = "java(flowDump.getReverseMeterId() != null ? flowDump.getReverseMeterId().getValue() : null)")
    public abstract FlowDumpPayload map(FlowDump flowDump);

    @Mapping(target = "type", expression = "java(dumpData.getDumpType().getType())")
    @Mapping(target = "entityId", ignore = true)
    @Mapping(target = "taskId", ignore = true)
    public abstract FlowDump map(FlowDumpData dumpData);

    /**
     * Note: you have to additionally set {@link org.openkilda.wfm.share.history.model.FlowDumpData.DumpType}
     * to the dump data.
     */
    @Mapping(target = "sourceSwitch", expression = "java(flow.getSrcSwitch().getSwitchId())")
    @Mapping(target = "destinationSwitch", expression = "java(flow.getDestSwitch().getSwitchId())")
    @Mapping(source = "flow.srcPort", target = "sourcePort")
    @Mapping(source = "flow.destPort", target = "destinationPort")
    @Mapping(source = "flow.srcVlan", target = "sourceVlan")
    @Mapping(source = "flow.destVlan", target = "destinationVlan")
    @Mapping(source = "flow.flowId", target = "flowId")
    @Mapping(source = "flow.bandwidth", target = "bandwidth")
    @Mapping(source = "flow.ignoreBandwidth", target = "ignoreBandwidth")
    @Mapping(source = "forward.cookie", target = "forwardCookie")
    @Mapping(source = "reverse.cookie", target = "reverseCookie")
    @Mapping(source = "forward.meterId", target = "forwardMeterId")
    @Mapping(source = "reverse.meterId", target = "reverseMeterId")
    @Mapping(source = "forward.status", target = "forwardStatus")
    @Mapping(source = "reverse.status", target = "reverseStatus")
    @Mapping(target = "forwardPath", expression = "java(mapPath(forward))")
    @Mapping(target = "reversePath", expression = "java(mapPath(reverse))")
    @Mapping(source = "dumpType", target = "dumpType")
    public abstract FlowDumpData map(Flow flow, FlowPath forward, FlowPath reverse, DumpType dumpType);

    /**
     * Note: you have to additionally set {@link org.openkilda.wfm.share.history.model.FlowDumpData.DumpType}
     * to the dump data.
     */
    public FlowDumpData map(Flow flow, FlowResources resources, DumpType dumpType) {
        FlowDumpData result = autogeneratedMap(flow, resources, dumpType);

        Cookie blank = FlowSegmentCookieSchema.INSTANCE.make(resources.getUnmaskedCookie());
        result.setForwardCookie(FlowSegmentCookieSchema.INSTANCE.setDirection(blank, FlowPathDirection.FORWARD));
        result.setReverseCookie(FlowSegmentCookieSchema.INSTANCE.setDirection(blank, FlowPathDirection.REVERSE));

        return result;
    }

    @Mapping(source = "time", target = "timestamp")
    @Mapping(source = "description", target = "details")
    @Mapping(target = "entityId", ignore = true)
    @Mapping(target = "taskId", ignore = true)
    public abstract FlowHistory map(FlowHistoryData historyData);

    @Mapping(source = "eventData.initiator", target = "actor")
    @Mapping(source = "eventData.event.description", target = "action")
    @Mapping(source = "time", target = "timestamp")
    @Mapping(target = "entityId", ignore = true)
    @Mapping(target = "taskId", ignore = true)
    public abstract FlowEvent map(FlowEventData eventData);

    @Mapping(target = "switchId", expression = "java(data.getEndpoint().getDatapath())")
    @Mapping(target = "portNumber", expression = "java(data.getEndpoint().getPortNumber())")
    public abstract PortHistory map(PortHistoryData data);

    @Mapping(target = "timestamp", ignore = true)
    public abstract PortHistoryPayload map(PortHistory portHistory);

    public String map(SwitchId switchId) {
        return switchId.toString();
    }

    @Mapping(target = "sourceSwitch", expression = "java(flow.getSrcSwitch().getSwitchId())")
    @Mapping(target = "destinationSwitch", expression = "java(flow.getDestSwitch().getSwitchId())")
    @Mapping(source = "flow.srcPort", target = "sourcePort")
    @Mapping(source = "flow.destPort", target = "destinationPort")
    @Mapping(source = "flow.srcVlan", target = "sourceVlan")
    @Mapping(source = "flow.destVlan", target = "destinationVlan")
    @Mapping(source = "flow.flowId", target = "flowId")
    @Mapping(source = "flow.bandwidth", target = "bandwidth")
    @Mapping(source = "flow.ignoreBandwidth", target = "ignoreBandwidth")
    @Mapping(source = "resources.forward.meterId", target = "forwardMeterId")
    @Mapping(source = "resources.reverse.meterId", target = "reverseMeterId")
    @Mapping(source = "dumpType", target = "dumpType")
    @BeanMapping(ignoreByDefault = true)
    protected abstract FlowDumpData autogeneratedMap(Flow flow, FlowResources resources, DumpType dumpType);

    /**
     * Adds string representation of flow path into {@link FlowDumpData}.
     */
    protected String mapPath(FlowPath path) {
        try {
            return Utils.MAPPER.writeValueAsString(FlowPathMapper.INSTANCE.mapToPathNodes(path));
        } catch (JsonProcessingException ex) {
            log.error("Unable to map the path: {}", path, ex);
            return null;
        }
    }
}
