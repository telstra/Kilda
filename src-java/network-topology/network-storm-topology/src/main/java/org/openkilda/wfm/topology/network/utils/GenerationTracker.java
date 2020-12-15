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

package org.openkilda.wfm.topology.network.utils;

import lombok.Getter;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class GenerationTracker<T> {
    @Getter
    private long actual = -1;

    private final long trackingLimit;

    private final Map<T, Long> generationById = new HashMap<>();
    private final LinkedList<T> knowGenerations = new LinkedList<>();

    public GenerationTracker(long trackingLimit) {
        this.trackingLimit = trackingLimit;
    }

    /**
     * Lookup {@code id} in existing generation, return if found, create new if not.
     */
    public long identify(T id) {
        Long generation = generationById.get(id);
        if (generation == null) {
            generation = registerGeneration(id);
        }
        return generation;
    }

    private long registerGeneration(T id) {
        long generation = ++actual;
        generationById.put(id, generation);

        knowGenerations.addFirst(id);
        while (0 < knowGenerations.size() && trackingLimit < knowGenerations.size()) {
            generationById.remove(knowGenerations.removeLast());
        }

        return generation;
    }
}
