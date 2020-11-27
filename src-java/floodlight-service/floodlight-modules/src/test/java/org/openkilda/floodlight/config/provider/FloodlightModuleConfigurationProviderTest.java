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

package org.openkilda.floodlight.config.provider;

import static org.easymock.EasyMock.niceMock;
import static org.junit.Assert.assertEquals;

import org.openkilda.floodlight.KafkaChannelConfig;
import org.openkilda.floodlight.config.EnvironmentFloodlightConfig;

import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.IFloodlightModule;
import org.junit.Test;

public class FloodlightModuleConfigurationProviderTest {
    private static final String TEST_BOOTSTRAP_SERVERS = "test_server";
    private static final String TEST_ZOOKEEPER_HOSTS = "test_zookeeper_host";
    private static final String TEST_PREFIX = "test_prefix";

    @Test
    public void shouldCreateConfigFromContextParameters() {
        FloodlightModuleContext context = new FloodlightModuleContext();
        IFloodlightModule module = niceMock(IFloodlightModule.class);

        context.addConfigParam(module, "bootstrap-servers", TEST_BOOTSTRAP_SERVERS);
        context.addConfigParam(module, "zookeeper-hosts", TEST_ZOOKEEPER_HOSTS);

        FloodlightModuleConfigurationProvider provider = FloodlightModuleConfigurationProvider.of(context, module);
        KafkaChannelConfig kafkaConfig = provider.getConfiguration(KafkaChannelConfig.class);

        assertEquals(TEST_BOOTSTRAP_SERVERS, kafkaConfig.getBootstrapServers());
        assertEquals(TEST_ZOOKEEPER_HOSTS, kafkaConfig.getZooKeeperHosts());
    }

    @Test
    public void shouldCreateEnvConfigFromContextParameters() {
        FloodlightModuleContext context = new FloodlightModuleContext();
        IFloodlightModule module = niceMock(IFloodlightModule.class);

        context.addConfigParam(module, "environment-naming-prefix", TEST_PREFIX);

        FloodlightModuleConfigurationProvider provider = FloodlightModuleConfigurationProvider.of(context, module);
        EnvironmentFloodlightConfig environmentConfig = provider.getConfiguration(EnvironmentFloodlightConfig.class);

        assertEquals(TEST_PREFIX, environmentConfig.getNamingPrefix());
    }

    @Test
    public void shouldCreateConfigWithEnvPrefix() {
        FloodlightModuleContext context = new FloodlightModuleContext();
        IFloodlightModule module = niceMock(IFloodlightModule.class);

        context.addConfigParam(module, "environment-naming-prefix", TEST_PREFIX);

        FloodlightModuleConfigurationProvider provider = FloodlightModuleConfigurationProvider.of(context, module);
        KafkaChannelConfig kafkaConsumerConfig = provider.getConfiguration(KafkaChannelConfig.class);

        assertEquals(TEST_PREFIX + "_floodlight", kafkaConsumerConfig.getGroupId());
    }
}
