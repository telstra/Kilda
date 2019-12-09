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

package org.openkilda.testing.service.traffexam.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategy.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.github.javafaker.Faker;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(SnakeCaseStrategy.class)
public class UdpData {
    @NonNull
    String srcMacAddress;
    @NonNull
    String dstMacAddress;
    @NonNull
    String srcIp;
    @NonNull
    Integer srcPort;
    @NonNull
    String dstIp;
    @NonNull
    Integer dstPort;
    @NonNull
    Integer ethType;

    /**
     * Build UdpData object with random values.
     */
    public static UdpData buildRandom() {
        Faker faker = new Faker();
        return UdpData.builder()
                .srcMacAddress(faker.internet().macAddress("10"))
                .dstMacAddress(faker.internet().macAddress("10"))
                .srcIp(faker.internet().ipV4Address())
                .srcPort(faker.number().numberBetween(1, 65535))
                .dstIp(faker.internet().ipV4Address())
                .dstPort(faker.number().numberBetween(1, 65535))
                .ethType(0x0800)
                .build();
    }
}
