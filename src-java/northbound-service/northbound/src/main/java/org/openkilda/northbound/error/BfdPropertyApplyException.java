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

package org.openkilda.northbound.error;

import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.messaging.nbtopology.response.BfdPropertiesResponse;

import lombok.Getter;

public class BfdPropertyApplyException extends Exception {
    @Getter
    private final BfdPropertiesResponse properties;

    public BfdPropertyApplyException(BfdPropertiesResponse properties) {
        super(formatErrorMessage(properties));
        this.properties = properties;
    }

    private static String formatErrorMessage(BfdPropertiesResponse properties) {
        NetworkEndpoint source = properties.getSource();
        NetworkEndpoint destination = properties.getDestination();

        return String.format(
                "Unable to apply BFD properties %s on %s_%s <===> %s_%s "
                + "(effective source: %s, effective destination: %s",
                properties.getGoal(),
                source.getDatapath(), source.getPortNumber(), destination.getDatapath(), destination.getPortNumber(),
                properties.getEffectiveSource(), properties.getEffectiveDestination());
    }
}
