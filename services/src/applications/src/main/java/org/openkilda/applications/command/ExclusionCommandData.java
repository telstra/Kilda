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

package org.openkilda.applications.command;

import org.openkilda.applications.model.Exclusion;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public abstract class ExclusionCommandData extends FlowAppCommandData {
    private static final long serialVersionUID = 3980514294918884614L;

    @JsonProperty("exclusion")
    private Exclusion exclusion;

    public ExclusionCommandData(String flowId, String application, Exclusion exclusion) {
        super(flowId, application);
        this.exclusion = exclusion;
    }
}
