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

package org.openkilda.floodlight.service;

import org.openkilda.floodlight.config.provider.FloodlightModuleConfigurationProvider;
import org.openkilda.floodlight.feature.AbstractFeature;
import org.openkilda.floodlight.feature.BfdFeature;
import org.openkilda.floodlight.feature.BfdReviewFeature;
import org.openkilda.floodlight.feature.GroupPacketOutFeature;
import org.openkilda.floodlight.feature.GroupsFeature;
import org.openkilda.floodlight.feature.HalfSizeMetadataFeature;
import org.openkilda.floodlight.feature.InaccurateMeterFeature;
import org.openkilda.floodlight.feature.InaccurateSetVlanVidAction;
import org.openkilda.floodlight.feature.LimitedBurstSizeFeature;
import org.openkilda.floodlight.feature.MatchUdpPortFeature;
import org.openkilda.floodlight.feature.MaxBurstCoefficientLimitationFeature;
import org.openkilda.floodlight.feature.MeterFeature;
import org.openkilda.floodlight.feature.MultiTableFeature;
import org.openkilda.floodlight.feature.NoviFlowCopyFieldFeature;
import org.openkilda.floodlight.feature.NoviFlowPushPopVxlanFeature;
import org.openkilda.floodlight.feature.NoviFlowSwapEthSrcEthDstFeature;
import org.openkilda.floodlight.feature.PktpsFlagFeature;
import org.openkilda.floodlight.feature.ResetCountsFlagFeature;
import org.openkilda.floodlight.switchmanager.SwitchManager;
import org.openkilda.model.SwitchFeature;

import com.google.common.collect.ImmutableList;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class FeatureDetectorService implements IService {
    private List<AbstractFeature> features;

    /**
     * Detect features supported by switch.
     *
     * @param sw target switch
     * @return supported features
     */
    public Set<SwitchFeature> detectSwitch(IOFSwitch sw) {
        return features.stream()
                .map(x -> x.discover(sw))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());
    }

    @Override
    public void setup(FloodlightModuleContext context) throws FloodlightModuleException {
        FloodlightModuleConfigurationProvider provider =
                FloodlightModuleConfigurationProvider.of(context, SwitchManager.class);
        FeatureDetectorServiceConfig config = provider.getConfiguration(FeatureDetectorServiceConfig.class);

        features = ImmutableList.of(
                new MeterFeature(config.isOvsMetersEnabled()),
                new InaccurateMeterFeature(),
                new BfdFeature(),
                new BfdReviewFeature(),
                new GroupPacketOutFeature(),
                new ResetCountsFlagFeature(),
                new LimitedBurstSizeFeature(),
                new NoviFlowCopyFieldFeature(),
                new PktpsFlagFeature(),
                new MatchUdpPortFeature(),
                new MaxBurstCoefficientLimitationFeature(),
                new MultiTableFeature(),
                new InaccurateSetVlanVidAction(),
                new NoviFlowPushPopVxlanFeature(),
                new HalfSizeMetadataFeature(),
                new NoviFlowSwapEthSrcEthDstFeature(),
                new GroupsFeature());
    }
}
