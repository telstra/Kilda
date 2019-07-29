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

import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;
import org.openkilda.pce.exception.RecoverableException;
import org.openkilda.pce.exception.UnroutableFlowException;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Represents computation operations on flow path.
 */
public interface PathComputer {

    /**
     * Gets path between source and destination switches for specified flow. The path is built over available ISLs
     * only.
     *
     * @param flow the {@link Flow} instance
     * @return {@link PathPair} instances
     */
    default PathPair getPath(Flow flow) throws UnroutableFlowException, RecoverableException {
        return getPath(flow, Collections.emptyList());
    }

    /**
     * Gets path between source and destination switch for specified flow.
     *
     * @param flow the {@link Flow} instance.
     * @param reusePathsResources    allow already allocated path resources (bandwidth)
     *                               be reused in new path computation.
     * @return {@link PathPair} instances
     */
    PathPair getPath(Flow flow, Collection<PathId> reusePathsResources)
            throws UnroutableFlowException, RecoverableException;

    /**
     * Gets N best paths.
     *
     * @param srcSwitch source switchId
     * @param dstSwitch destination switchId
     * @param flowEncapsulationType target encapsulation type
     *
     * @return an list of N (or less) best paths ordered from best to worst.
     */
    List<Path> getNPaths(SwitchId srcSwitch, SwitchId dstSwitch, int count,
                         FlowEncapsulationType flowEncapsulationType, PathComputationStrategy pathComputationStrategy)
            throws RecoverableException, UnroutableFlowException;
}
