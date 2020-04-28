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

package org.openkilda.messaging.info.stats;

import org.openkilda.messaging.info.InfoData;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"switch_id", "flow_id", "t0", "t1"})
@EqualsAndHashCode(callSuper = false)
public class FlowRttStatsData extends InfoData {

    private static final long serialVersionUID = 1L;

    @JsonProperty("flow_id")
    private String flowId;

    @JsonProperty("direction")
    private String direction;

    @JsonProperty("t0")
    private Long t0;

    @JsonProperty("t1")
    private Long t1;

    public FlowRttStatsData(@JsonProperty("flow_id") String flowId,
                            @JsonProperty("direction") String direction,
                            @JsonProperty("t0") Long t0,
                            @JsonProperty("t1") Long t1) {
        this.flowId = flowId;
        this.direction = direction;
        this.t0 = t0;
        this.t1 = t1;
    }
}
