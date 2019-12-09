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

package org.openkilda.applications.info;

import org.openkilda.applications.model.Exclusion;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public abstract class ExclusionInfoData extends FlowAppInfoData {
    private static final long serialVersionUID = 8904373571016951443L;

    @JsonProperty("exclusion")
    private Exclusion exclusion;

    @JsonProperty("success")
    private Boolean success;

    public ExclusionInfoData(String flowId, long tunnelId, String switchId, String application, Exclusion exclusion,
                             Boolean success) {
        super(flowId, tunnelId, switchId, application);
        this.exclusion = exclusion;
        this.success = success;
    }
}
