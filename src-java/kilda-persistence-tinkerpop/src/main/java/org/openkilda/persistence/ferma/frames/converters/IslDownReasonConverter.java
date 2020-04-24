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

import org.openkilda.model.IslDownReason;

/**
 * Case-insensitive converter to convert {@link IslDownReason} to {@link String} and back.
 */
public class IslDownReasonConverter implements AttributeConverter<IslDownReason, String> {
    @Override
    public String toGraphProperty(IslDownReason value) {
        if (value == null) {
            return null;
        }
        return value.name().toLowerCase();
    }

    @Override
    public IslDownReason toEntityAttribute(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return IslDownReason.valueOf(value.toUpperCase());
    }
}
