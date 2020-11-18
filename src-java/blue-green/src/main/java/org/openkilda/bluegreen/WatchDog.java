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


package org.openkilda.bluegreen;

public interface WatchDog {

    /**
     * Subscribe to lifecycle events.
     * @param observer target observer
     */
    void subscribe(LifeCycleObserver observer);

    /**
     * Unsubscribe to lifecycle events.
     * @param observer target observer
     */
    void unsubscribe(LifeCycleObserver observer);

}
