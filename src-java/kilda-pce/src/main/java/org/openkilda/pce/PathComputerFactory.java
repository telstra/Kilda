/* Copyright 2018 Telstra Open Source
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

package org.openkilda.pce;

import org.openkilda.pce.finder.BestWeightAndShortestPathFinder;
import org.openkilda.pce.impl.InMemoryPathComputer;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A factory for {@link PathComputer} instances. It provides a specific {@link PathComputer} depending on configuration
 * ({@link PathComputerConfig}).
 */
public class PathComputerFactory {

    private PathComputerConfig config;
    private AvailableNetworkFactory availableNetworkFactory;
    private MeterRegistry meterRegistry;

    public PathComputerFactory(PathComputerConfig config, AvailableNetworkFactory availableNetworkFactory,
                               MeterRegistry meterRegistry) {
        this.config = config;
        this.availableNetworkFactory = availableNetworkFactory;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Creates a {@link PathComputer}.
     *
     * @return {@link PathComputer} instance
     */
    public PathComputer getPathComputer() {
        return new InMemoryPathComputer(availableNetworkFactory,
                new BestWeightAndShortestPathFinder(config.getMaxAllowedDepth()),
                config, meterRegistry);
    }
}
