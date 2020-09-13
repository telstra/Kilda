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

package org.openkilda.wfm.topology.flowhs.service;

import org.openkilda.messaging.MessageContext;
import org.openkilda.messaging.error.ErrorType;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;

import java.util.UUID;

@Getter
@EqualsAndHashCode(callSuper = true)
@Value
public class DbErrorResponse extends DbResponse {
    ErrorType responseErrorType;
    DbOperationErrorType operationErrorType;
    String errorMessage;

    public DbErrorResponse(@NonNull MessageContext messageContext, UUID commandId, ErrorType responseErrorType,
                           DbOperationErrorType operationErrorType, String errorMessage) {
        super(messageContext, commandId);
        this.responseErrorType = responseErrorType;
        this.operationErrorType = operationErrorType;
        this.errorMessage = errorMessage;
    }
}
