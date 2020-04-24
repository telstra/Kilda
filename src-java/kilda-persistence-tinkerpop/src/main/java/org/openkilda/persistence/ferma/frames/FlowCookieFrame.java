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

package org.openkilda.persistence.ferma.frames;

import org.openkilda.model.FlowCookie.FlowCookieData;

import com.syncleus.ferma.annotations.Property;

public abstract class FlowCookieFrame extends KildaBaseVertexFrame implements FlowCookieData {
    public static final String FRAME_LABEL = "flow_cookie";
    public static final String FLOW_ID_PROPERTY = "flow_id";
    public static final String UNMASKED_COOKIE_PROPERTY = "unmasked_cookie";

    @Override
    @Property(FLOW_ID_PROPERTY)
    public abstract String getFlowId();

    @Override
    @Property(FLOW_ID_PROPERTY)
    public abstract void setFlowId(String flowId);

    @Override
    @Property(UNMASKED_COOKIE_PROPERTY)
    public abstract long getUnmaskedCookie();

    @Override
    @Property(UNMASKED_COOKIE_PROPERTY)
    public abstract void setUnmaskedCookie(long unmaskedCookie);
}
