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

package org.openkilda.wfm.share.metrics;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.Optional;

/**
 * Thread-local implementation of a registry holder. Keeps meter register bound to the current execution thread.
 */
public final class MeterRegistryHolder {
    public static final ThreadLocal<MeterRegistry> registries = new ThreadLocal<>();

    /**
     * Set the registry and bound to the current thread.
     *
     * @param registry Registry to set.
     */
    public static void setRegistry(MeterRegistry registry) {
        registries.set(registry);
    }

    /**
     * Get the registry which bound to the current thread.
     *
     * @return the registry.
     */
    public static Optional<MeterRegistry> getRegistry() {
        return Optional.ofNullable(registries.get());
    }

    /**
     * Unbound the registry bound to the current thread.
     */
    public static void removeRegistry() {
        registries.remove();
    }

    private MeterRegistryHolder() {
    }
}
