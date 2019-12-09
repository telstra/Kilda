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

package org.openkilda.wfm.topology.applications;

import org.openkilda.applications.AppData;
import org.openkilda.applications.error.ErrorAppType;
import org.openkilda.messaging.MessageData;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.error.ErrorType;

public interface AppsManagerCarrier {

    void emitErrorMessage(ErrorType errorType, String errorMessage);

    void emitNorthboundResponse(MessageData payload);

    void emitSpeakerCommand(CommandData payload);

    void emitAppError(ErrorAppType errorType, String errorMessage);

    void emitNotification(AppData payload);
}
