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

package org.openkilda.persistence.ferma.model;

import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

import java.time.Instant;

/**
 * Represents a switch.
 */
@Data
public class SwitchImpl implements Switch {

    private SwitchId switchId;
    private SwitchStatus status;
    private String address;
    private String hostname;
    private String controller;
    private String description;
    private String ofVersion;
    private String ofDescriptionManufacturer;
    private String ofDescriptionHardware;
    private String ofDescriptionSoftware;
    private String ofDescriptionSerialNumber;
    private String ofDescriptionDatapath;
    private boolean underMaintenance;
    private Instant timeCreate;
    private Instant timeModify;

    @Builder(toBuilder = true)
    public SwitchImpl(@NonNull SwitchId switchId, SwitchStatus status, String address,
                      String hostname, String controller, String description, boolean underMaintenance,
                      Instant timeCreate, Instant timeModify) {
        this.switchId = switchId;
        this.status = status;
        this.address = address;
        this.hostname = hostname;
        this.controller = controller;
        this.description = description;
        this.underMaintenance = underMaintenance;
        this.timeCreate = timeCreate;
        this.timeModify = timeModify;
    }

    public static SwitchImplBuilder clone(Switch sw) {
        if (sw instanceof SwitchImpl) {
            return ((SwitchImpl) sw).toBuilder();
        } else {
            return SwitchImpl.builder()
                    .switchId(sw.getSwitchId())
                    .status(sw.getStatus())
                    .address(sw.getAddress())
                    .hostname(sw.getHostname())
                    .controller(sw.getController())
                    .description(sw.getDescription())
                    .underMaintenance(sw.isUnderMaintenance())
                    .timeCreate(sw.getTimeCreate())
                    .timeModify(sw.getTimeModify());
        }
    }
}
