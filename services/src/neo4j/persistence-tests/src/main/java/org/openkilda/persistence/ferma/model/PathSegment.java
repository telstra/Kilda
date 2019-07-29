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

package org.openkilda.persistence.ferma.model;

import org.openkilda.model.SwitchId;

public interface PathSegment {

    int getSrcPort();

    void setSrcPort(int srcPort);

    int getDestPort();

    void setDestPort(int destPort);

    Long getLatency();

    void setLatency(Long latency);

    boolean isFailed();

    void setFailed(boolean failed);

    int getSeqId();

    void setSeqId(int seqId);

    Switch getSrcSwitch();

    SwitchId getSrcSwitchId();

    void setSrcSwitch(Switch srcSwitch);

    Switch getDestSwitch();

    SwitchId getDestSwitchId();

    void setDestSwitch(Switch destSwitch);

    FlowPath getPath();

    /**
     * Checks whether endpoint belongs to segment or not.
     *
     * @param switchId target switch
     * @param port target port
     * @return result of check
     */
    default boolean containsNode(SwitchId switchId, int port) {
        if (switchId == null) {
            throw new IllegalArgumentException("Switch id must be not null");
        }
        return (switchId.equals(getSrcSwitchId()) && port == getSrcPort())
                || (switchId.equals(getDestSwitchId()) && port == getDestPort());
    }
}
