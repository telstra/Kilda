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

package org.openkilda.floodlight.model;

import org.openkilda.model.MacAddress;

import com.fasterxml.jackson.databind.PropertyNamingStrategy.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(value = SnakeCaseStrategy.class)
@Builder
public class RulesContext implements Serializable {
    private boolean removeCustomerCatchRule;
    private boolean removeCustomerLldpRule;
    private boolean removeCustomerArpRule;
    private boolean removeOuterVlanMatchSharedRule;
    @Builder.Default
    private boolean updateMeter = true;


    private boolean removeServer42InputRule;
    private boolean removeServer42IngressRule;
    private boolean removeServer42OuterVlanMatchSharedRule;
    private boolean installServer42InputRule;
    private boolean installServer42IngressRule;
    private boolean installServer42OuterVlanMatchSharedRule;
    private Integer server42Port;
    private MacAddress server42MacAddress;
}
