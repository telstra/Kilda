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

package org.openkilda.messaging.nbtopology.response;

import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.EffectiveBfdProperties;

import com.fasterxml.jackson.databind.PropertyNamingStrategy.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.EqualsAndHashCode;
import lombok.Value;

@Value
@EqualsAndHashCode(callSuper = true)
@JsonNaming(value = SnakeCaseStrategy.class)
public class BfdPropertiesResponse extends InfoData {
    NetworkEndpoint source;
    NetworkEndpoint destination;

    BfdProperties goal;
    EffectiveBfdProperties effectiveSource;
    EffectiveBfdProperties effectiveDestination;

    // TODO(surabujin): can/should be dropped when /api/v1/links/enable-bfd NB endpoint will be removed
    IslInfoData linkData;
}
