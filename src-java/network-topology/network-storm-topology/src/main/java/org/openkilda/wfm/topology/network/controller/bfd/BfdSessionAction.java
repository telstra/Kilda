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

package org.openkilda.wfm.topology.network.controller.bfd;

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.messaging.model.NoviBfdSession.Errors;

import lombok.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

abstract class BfdSessionAction {
    protected Logger log = LoggerFactory.getLogger(getClass());

    protected final String speakerRequestKey;

    protected boolean haveSpeakerResponse = false;
    private ActionResult result;

    BfdSessionAction(String requestKey) {
        this.speakerRequestKey = requestKey;
    }

    public final Optional<ActionResult> consumeSpeakerResponse(String requestKey, BfdSessionResponse response) {
        if (! haveSpeakerResponse && speakerRequestKey.equals(requestKey)) {
            haveSpeakerResponse = true;
            result = makeResult(response);
        }

        return Optional.ofNullable(result);
    }

    public abstract String getLogIdentifier();

    protected abstract ActionResult makeResult(BfdSessionResponse response);

    @Value
    static class ActionResult {
        boolean success;

        /**
         * Error code (null in case of timeout).
         */
        NoviBfdSession.Errors errorCode;

        static ActionResult of(BfdSessionResponse response, boolean allowMissing) {
            if (response == null) {
                return timeout();
            }
            if (response.getErrorCode() == null) {
                return success();
            }
            if (allowMissing && response.getErrorCode() == Errors.NOVI_BFD_DISCRIMINATOR_NOT_FOUND_ERROR) {
                return success();
            }
            return error(response.getErrorCode());
        }

        static ActionResult success() {
            return new ActionResult(true, null);
        }

        static ActionResult error(NoviBfdSession.Errors errorCode) {
            return new ActionResult(false, errorCode);
        }

        static ActionResult timeout() {
            return new ActionResult(false, null);
        }
    }
}
