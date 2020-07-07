/* Copyright 2018 Telstra Open Source
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

package org.openkilda.pce.exception;

/**
 * Indicates that a path can't be found for a flow.
 */
public class UnroutableFlowException extends Exception {
    private final String flowId;
    private final boolean ignoreBandwidth;

    public UnroutableFlowException(String message) {
        super(message);
        this.flowId = null;
        this.ignoreBandwidth = false;
    }

    public UnroutableFlowException(String message, String flowId) {
        super(message);

        this.flowId = flowId;
        this.ignoreBandwidth = false;
    }

    public UnroutableFlowException(String message, Throwable cause, String flowId) {
        this(message, cause, flowId, false);
    }

    public UnroutableFlowException(String message, Throwable cause, String flowId, boolean ignoreBandwidth) {
        super(message, cause);

        this.flowId = flowId;
        this.ignoreBandwidth = ignoreBandwidth;
    }

    public String getFlowId() {
        return flowId;
    }

    public boolean isIgnoreBandwidth() {
        return ignoreBandwidth;
    }
}
