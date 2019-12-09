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

package org.openkilda.applications.command.apps;

import org.openkilda.applications.command.ExclusionCommandData;
import org.openkilda.applications.model.Exclusion;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class RemoveExclusion extends ExclusionCommandData {
    private static final long serialVersionUID = 8882968112474157462L;

    @Builder
    @JsonCreator
    public RemoveExclusion(@JsonProperty("flow_id") String flowId,
                           @JsonProperty("application") String application,
                           @JsonProperty("exclusion") Exclusion exclusion) {
        super(flowId, application, exclusion);
    }
}
