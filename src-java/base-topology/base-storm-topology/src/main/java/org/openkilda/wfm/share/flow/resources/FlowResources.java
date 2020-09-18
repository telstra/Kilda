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

package org.openkilda.wfm.share.flow.resources;

import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;

import lombok.Builder;
import lombok.Value;

import java.io.Serializable;

@Value
@Builder
public class FlowResources implements Serializable {
    private final long unmaskedCookie;
    private final PathResources forward;
    private final PathResources reverse;

    @Value
    @Builder
    public static class PathResources implements Serializable {
        private final PathId pathId;
        private final MeterId meterId;
        private final EncapsulationResources encapsulationResources;
    }
}
