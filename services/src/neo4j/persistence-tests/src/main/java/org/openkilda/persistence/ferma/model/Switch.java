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

import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.regex.Pattern;

public interface Switch {
    Pattern NOVIFLOW_SOFTWARE_REGEX = Pattern.compile("(.*)NW\\d{3}\\.\\d+\\.\\d+(.*)");

    SwitchId getSwitchId();

    void setSwitchId(SwitchId switchId);

    SwitchStatus getStatus();

    void setStatus(SwitchStatus status);

    String getAddress();


    void setAddress(String address);

    String getHostname();


    void setHostname(String hostname);

    String getController();

    void setController(String controller);

    String getDescription();


    void setDescription(String description);


    String getOfVersion();

    void setOfVersion(String ofVersion);

    String getOfDescriptionManufacturer();


    void setOfDescriptionManufacturer(String ofDescriptionManufacturer);

    String getOfDescriptionHardware();

    void setOfDescriptionHardware(String ofDescriptionHardware);

    String getOfDescriptionSoftware();

    void setOfDescriptionSoftware(String ofDescriptionSoftware);

    String getOfDescriptionSerialNumber();

    void setOfDescriptionSerialNumber(String ofDescriptionSerialNumber);

    String getOfDescriptionDatapath();

    void setOfDescriptionDatapath(String ofDescriptionDatapath);

    boolean isUnderMaintenance();

    void setUnderMaintenance(boolean underMaintenance);

    Instant getTimeCreate();

    void setTimeCreate(Instant timeCreate);

    Instant getTimeModify();

    void setTimeModify(Instant timeModify);

    default boolean isCentecSwitch(String manufacturerDescription) {
        return StringUtils.contains(manufacturerDescription.toLowerCase(), "centec");
    }

    default boolean isNoviflowSwitch(String softwareDescription) {
        return NOVIFLOW_SOFTWARE_REGEX.matcher(softwareDescription).matches();
    }
}
