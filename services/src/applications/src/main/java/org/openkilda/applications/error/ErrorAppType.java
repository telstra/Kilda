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

package org.openkilda.applications.error;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum ErrorAppType {
    INTERNAL_ERROR("Internal service error"),

    NOT_IMPLEMENTED("Feature not implemented"),

    NOT_FOUND("Object was not found"),

    PARAMETERS_INVALID("Invalid request parameters"),

    ALREADY_EXISTS("Object already exists"),

    REQUEST_INVALID("Invalid request");

    @JsonValue
    private final String value;
}
