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

package org.openkilda.messaging.kafka.versioning;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
public class VersioningInterceptorBase {
    public static final int VERSION_IS_NOW_SET_LOG_TIMEOUT = 60;

    protected String componentName;
    protected String runId;
    protected Instant versionIsNotSetTimestamp = Instant.MIN;
    protected volatile String version;

    protected boolean isVersionIsNotTimeoutPassed() {
        return versionIsNotSetTimestamp.plus(VERSION_IS_NOW_SET_LOG_TIMEOUT, ChronoUnit.SECONDS)
                .isBefore(Instant.now());
    }
}
