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

package org.openkilda.wfm.topology.network.error;

import org.openkilda.wfm.share.model.Endpoint;

public class ControllerNotFoundException extends RuntimeException {
    public ControllerNotFoundException(String kind, String anchor) {
        super(String.format("%s controller for %s not found", kind, anchor));
    }

    protected static String makePhysicalEndpointReference(Endpoint endpoint) {
        return String.format("%s (physical)", endpoint);
    }

    protected static String makeLogicalEndpointReference(Endpoint endpoint) {
        return String.format("%s (logical)", endpoint);
    }
}
