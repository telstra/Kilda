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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Duration;

@Getter
@ToString
@EqualsAndHashCode
public class BfdProperties {
    @JsonProperty("interval")
    protected final Duration interval;

    @JsonProperty("multiplier")
    protected short multiplier;

    public BfdProperties() {
        this(Duration.ZERO, (short) 0);
    }

    public BfdProperties(BfdProperties other) {
        this(other.getInterval(), other.getMultiplier());
    }

    @Builder
    public BfdProperties(Duration interval, short multiplier) {
        this.interval = interval;
        this.multiplier = multiplier;
    }

    @JsonIgnore
    public boolean isEnabled() {
        return 0 < multiplier && interval != null && ! interval.isZero();
    }
}
