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

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

public class ErrorMessageJsonSerializeTest {

    @Test
    public void shouldSerializeToJson() throws Exception {
        ErrorAppData errorData = ErrorAppData.builder()
                .errorType(ErrorAppType.NOT_FOUND)
                .errorMessage("Error message")
                .errorDescription("Error description")
                .build();

        ErrorAppMessage errorMessage = ErrorAppMessage.builder()
                .payload(errorData)
                .correlationId("corr_id")
                .timestamp(System.currentTimeMillis())
                .build();

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(errorMessage);

        ErrorAppMessage errorMessageFromJson = mapper.readValue(json, ErrorAppMessage.class);

        assertEquals(errorMessage, errorMessageFromJson);
    }
}
