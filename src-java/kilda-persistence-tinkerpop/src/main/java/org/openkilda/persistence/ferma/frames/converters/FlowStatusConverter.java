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

package org.openkilda.persistence.ferma.frames.converters;

import org.openkilda.model.FlowStatus;

/**
 * Case-insensitive converter to convert {@link FlowStatus} to {@link String} and back.
 */
public class FlowStatusConverter implements AttributeConverter<FlowStatus, String> {
    public static final FlowStatusConverter INSTANCE = new FlowStatusConverter();

    @Override
    public String toGraphProperty(FlowStatus value) {
        if (value == null) {
            return null;
        }
        return value.name().toLowerCase();
    }

    @Override
    public FlowStatus toEntityAttribute(String value) {
        if (value == null || value.trim().isEmpty()) {
            // Treat empty status as UP to support old storage schema.
            return FlowStatus.UP;
        }
        return FlowStatus.valueOf(value.toUpperCase());
    }
}
