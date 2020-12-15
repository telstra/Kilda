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

package org.openkilda.wfm.topology.network;

import org.openkilda.wfm.topology.AbstractTopologyConfig;

import com.sabre.oss.conf4j.annotation.Configuration;
import com.sabre.oss.conf4j.annotation.Default;
import com.sabre.oss.conf4j.annotation.IgnoreKey;
import com.sabre.oss.conf4j.annotation.Key;

public interface NetworkTopologyConfig extends AbstractTopologyConfig {
    @IgnoreKey
    DiscoveryConfig getDiscoveryConfig();

    default int getDiscoveryGenericInterval() {
        return getDiscoveryConfig().getDiscoveryGenericInterval();
    }

    default int getDiscoveryExhaustedInterval() {
        return getDiscoveryConfig().getDiscoveryExhaustedInterval();
    }

    default int getDiscoveryAuxiliaryInterval() {
        return getDiscoveryConfig().getDiscoveryAuxiliaryInterval();
    }

    default int getDiscoveryPacketTtl() {
        return getDiscoveryConfig().getDiscoveryPacketTtl();
    }

    default int getDiscoveryTimeout() {
        return getDiscoveryConfig().getDiscoveryTimeout();
    }

    default boolean isBfdEnabled() {
        return getDiscoveryConfig().isBfdEnabled();
    }

    @Key("bfd.port.offset")
    @Default("200")
    int getBfdPortOffset();

    @Key("speaker.io.timeout.seconds")
    @Default("60")
    int getSpeakerIoTimeoutSeconds();

    @Key("swmanager.io.timeout.seconds")
    // This time was calculated as SwitchManager sync runtime * 1.5
    @Default("30")
    int getSwitchManagerIoTimeoutSeconds();

    @Key("port.up.down.throttling.delay.seconds.min")
    int getPortUpDownThrottlingDelaySecondsMin();

    @Key("port.up.down.throttling.delay.seconds.warm.up")
    int getPortUpDownThrottlingDelaySecondsWarmUp();

    @Key("port.up.down.throttling.delay.seconds.cool.down")
    int getPortUpDownThrottlingDelaySecondsCoolDown();

    @Key("network.remove.excess.when.switch.sync")
    @Default("true")
    boolean isRemoveExcessWhenSwitchSync();

    @Key("network.count.sync.attempts")
    // If the value of this parameter is 0 or less than zero,
    // then synchronization will not be performed when the switch is activated.
    @Default("2")
    int getCountSynchronizationAttempts();

    @Key("network.count.isl.rules.attempts")
    // If the value of this parameter is 0 or less than zero,
    // then synchronization will not be performed when the switch is activated.
    @Default("2")
    int getRulesSynchronizationAttempts();

    @Key("port.antiflap.stats.dumping.interval.seconds")
    @Default("60")
    int getPortAntiFlapStatsDumpingInterval();

    @Key("switch.offline.generation.lag")
    @Default("3")
    long getSwitchOfflineGenerationLag();

    @Configuration
    @Key("discovery")
    interface DiscoveryConfig {
        @Key("generic.interval")
        int getDiscoveryGenericInterval();

        @Key("exhausted.interval")
        int getDiscoveryExhaustedInterval();

        @Key("auxiliary.interval")
        int getDiscoveryAuxiliaryInterval();

        @Key("packet.ttl")
        int getDiscoveryPacketTtl();

        @Key("timeout")
        int getDiscoveryTimeout();

        @Key("use-bfd")
        boolean isBfdEnabled();

        @Key("db.write.repeats.time.frame")
        @Default("30")
        long getDbRepeatsTimeFrameSeconds();
    }
}
