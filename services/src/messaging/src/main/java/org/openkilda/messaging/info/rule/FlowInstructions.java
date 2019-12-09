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

package org.openkilda.messaging.info.rule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.io.Serializable;

@Value
@Builder
public class FlowInstructions implements Serializable {

    @JsonProperty("apply_actions")
    private FlowApplyActions applyActions;
    @JsonProperty("none")
    private String none;
    @JsonProperty("instruction_goto_meter")
    private Long goToMeter;
    @JsonProperty("instruction_goto_table")
    private Short goToTable;
    @JsonProperty("write_metadata")
    private Long writeMetadata;

    @JsonCreator
    public FlowInstructions(
            @JsonProperty("apply_actions") FlowApplyActions applyActions,
            @JsonProperty("none") String none,
            @JsonProperty("instruction_goto_meter") Long goToMeter,
            @JsonProperty("instruction_goto_table") Short goToTable,
            @JsonProperty("write_metadata") Long writeMetadata) {
        this.applyActions = applyActions;
        this.none = none;
        this.goToMeter = goToMeter;
        this.goToTable = goToTable;
        this.writeMetadata = writeMetadata;
    }
}
