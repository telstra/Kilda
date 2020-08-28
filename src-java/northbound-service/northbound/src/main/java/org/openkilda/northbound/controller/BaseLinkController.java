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

package org.openkilda.northbound.controller;

import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.MessageException;
import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.model.SwitchId;
import org.openkilda.northbound.utils.RequestCorrelationId;

public class BaseLinkController extends BaseController {
    protected NetworkEndpoint makeSourceEndpoint(SwitchId switchId, Integer portNumber) {
        return makeEndpoint(switchId, portNumber, "source");
    }

    protected NetworkEndpoint makeDestinationEndpoint(SwitchId switchId, Integer portNumber) {
        return makeEndpoint(switchId, portNumber, "destination");
    }

    protected NetworkEndpoint makeEndpoint(SwitchId switchId, Integer portNumber, String name) {
        try {
            return new NetworkEndpoint(switchId, portNumber);
        } catch (IllegalArgumentException e) {
            throw new MessageException(RequestCorrelationId.getId(), System.currentTimeMillis(), ErrorType.DATA_INVALID,
                    e.getMessage(), String.format("Invalid %s endpoint definition: %s", name, e.getMessage()));
        }
    }
}
