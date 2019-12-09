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

package org.openkilda.applications.info.stats;

import org.openkilda.applications.ClassPropertyWrapper;

import com.fasterxml.jackson.databind.PropertyNamingStrategy.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Builder
@EqualsAndHashCode(callSuper = true)
@JsonNaming(value = SnakeCaseStrategy.class)
public class PortStatsEntry extends ClassPropertyWrapper {
    private static final long serialVersionUID = 5783119228663114693L;

    private int portNo;
    private long rxPackets;
    private long txPackets;
    private long rxBytes;
    private long txBytes;
    private long rxDropped;
    private long txDropped;
    private long rxErrors;
    private long txErrors;
    private long rxFrameErr;
    private long rxOverErr;
    private long rxCrcErr;
    private long collisions;
}
