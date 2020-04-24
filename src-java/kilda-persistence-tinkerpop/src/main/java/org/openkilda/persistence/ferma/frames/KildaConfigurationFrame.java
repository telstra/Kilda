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

package org.openkilda.persistence.ferma.frames;

import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.KildaConfiguration.KildaConfigurationData;
import org.openkilda.model.PathComputationStrategy;
import org.openkilda.persistence.ferma.frames.converters.Convert;
import org.openkilda.persistence.ferma.frames.converters.FlowEncapsulationTypeConverter;
import org.openkilda.persistence.ferma.frames.converters.PathComputationStrategyConverter;

import com.syncleus.ferma.annotations.Property;

public abstract class KildaConfigurationFrame extends KildaBaseVertexFrame implements KildaConfigurationData {
    public static final String FRAME_LABEL = "kilda_configuration";

    @Override
    @Property("flow_encapsulation_type")
    @Convert(FlowEncapsulationTypeConverter.class)
    public abstract FlowEncapsulationType getFlowEncapsulationType();

    @Override
    @Property("flow_encapsulation_type")
    @Convert(FlowEncapsulationTypeConverter.class)
    public abstract void setFlowEncapsulationType(FlowEncapsulationType flowEncapsulationType);

    @Override
    @Property("use_multi_table")
    public abstract Boolean getUseMultiTable();

    @Override
    @Property("use_multi_table")
    public abstract void setUseMultiTable(Boolean useMultiTable);

    @Override
    @Property("path_computation_strategy")
    @Convert(PathComputationStrategyConverter.class)
    public abstract PathComputationStrategy getPathComputationStrategy();

    @Override
    @Property("path_computation_strategy")
    @Convert(PathComputationStrategyConverter.class)
    public abstract void setPathComputationStrategy(PathComputationStrategy pathComputationStrategy);
}
