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

import org.openkilda.northbound.dto.NorthboundPayload;

import org.springframework.http.HttpStatus;

public class InconclusiveException extends NorthboundException {
    private static final String prefix = "Request can't be fully processed";

    public InconclusiveException(NorthboundPayload payload) {
        super(prefix, payload);
    }

    public InconclusiveException(String message, NorthboundPayload payload) {
        super(prefix + ": " + message, payload);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.CONFLICT;
    }
}

