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

package org.openkilda.floodlight.api.request.factory;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.Cookie;

import java.io.Serializable;
import java.util.UUID;

public abstract class FlowSegmentRequestFactory implements Serializable {
    protected static UUID dummyCommandId = new UUID(0L, 0L);

    private final FlowSegmentRequest requestBlank;

    protected FlowSegmentRequestFactory(FlowSegmentRequest requestBlank) {
        this.requestBlank = requestBlank;
    }

    public abstract FlowSegmentRequest makeInstallRequest(UUID commandId);

    public abstract FlowSegmentRequest makeRemoveRequest(UUID commandId);

    public abstract FlowSegmentRequest makeVerifyRequest(UUID commandId);

    public UUID getCommandId() {
        return requestBlank.getCommandId();
    }

    public SwitchId getSwitchId() {
        return requestBlank.getSwitchId();
    }

    public String getFlowId() {
        return requestBlank.getMetadata().getFlowId();
    }

    public Cookie getCookie() {
        return requestBlank.getMetadata().getCookie();
    }
}
