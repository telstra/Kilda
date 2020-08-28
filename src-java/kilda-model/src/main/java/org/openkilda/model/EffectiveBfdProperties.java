/* Copyright 2020 Telstra Open Source
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

package org.openkilda.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Duration;

@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class EffectiveBfdProperties extends BfdProperties {
    @JsonProperty("status")
    private final BfdSessionStatus status;

    public EffectiveBfdProperties(BfdProperties properties, BfdSessionStatus status) {
        this(properties.getInterval(), properties.getMultiplier(), status);
    }

    @JsonCreator
    public EffectiveBfdProperties(
            @JsonProperty("interval") Duration interval,
            @JsonProperty("multiplier") short multiplier,
            @JsonProperty("status") BfdSessionStatus status) {
        super(interval, multiplier);
        this.status = status;
    }
}
