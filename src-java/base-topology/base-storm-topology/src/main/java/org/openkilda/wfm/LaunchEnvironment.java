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

package org.openkilda.wfm;

import static java.util.Collections.singletonList;

import org.openkilda.config.EnvironmentConfig;
import org.openkilda.config.naming.KafkaNamingForConfigurationValueProcessor;
import org.openkilda.config.naming.KafkaNamingStrategy;
import org.openkilda.wfm.config.naming.TopologyNamingStrategy;
import org.openkilda.wfm.config.provider.MultiPrefixConfigurationProvider;
import org.openkilda.wfm.error.ConfigurationException;

import org.apache.commons.lang3.ArrayUtils;
import org.kohsuke.args4j.CmdLineException;

import java.util.Properties;

public class LaunchEnvironment {
    private static String CLI_OVERLAY = "cli";

    private final CliArguments cli;
    private Properties properties;

    public LaunchEnvironment(String[] args) throws CmdLineException, ConfigurationException {
        this.cli = new CliArguments(args);
        properties = cli.getProperties();
        properties = makeCliOverlay();
    }

    public String getTopologyName() {
        return cli.getTopologyName();
    }

    /**
     * Returns a provider for configuration entities.
     * <p/>
     * The provider is pre-configured for the environment settings and CLI options.
     *
     * @param prefixes is an ordered list of property prefixes to be used for configuration entries lookup.
     */
    public MultiPrefixConfigurationProvider getConfigurationProvider(String... prefixes) {
        String[] prefixesWithOverlay = ArrayUtils.addAll(ArrayUtils.toArray(CLI_OVERLAY), prefixes);

        // Apply Kafka naming to Kafka topics and groups in the configuration.
        return new MultiPrefixConfigurationProvider(getProperties(), prefixesWithOverlay,
                singletonList(new KafkaNamingForConfigurationValueProcessor(getKafkaNamingStrategy())));
    }

    public KafkaNamingStrategy getKafkaNamingStrategy() {
        return new KafkaNamingStrategy(getEnvironmentPrefix());
    }

    public TopologyNamingStrategy getTopologyNamingStrategy() {
        return new TopologyNamingStrategy(getEnvironmentPrefix());
    }

    private String getEnvironmentPrefix() {
        MultiPrefixConfigurationProvider configurationProvider = new MultiPrefixConfigurationProvider(getProperties(),
                ArrayUtils.toArray(CLI_OVERLAY));

        EnvironmentConfig environmentConfig = configurationProvider.getConfiguration(EnvironmentConfig.class);
        return environmentConfig.getNamingPrefix() != null ? environmentConfig.getNamingPrefix() : "";
    }

    public void setupOverlay(Properties overlay) {
        Properties newLayer = copyProperties(getProperties());
        for (String name : overlay.stringPropertyNames()) {
            newLayer.setProperty(name, overlay.getProperty(name));
        }
        properties = newLayer;
    }

    private Properties makeCliOverlay() {
        Properties overlay = copyProperties(getProperties());

        overlay.setProperty(CLI_OVERLAY + ".local", cli.getIsLocal() ? "true" : "false");
        if (cli.getLocalExecutionTime() != null) {
            overlay.setProperty(CLI_OVERLAY + ".local.execution.time", cli.getLocalExecutionTime().toString());
        }

        return overlay;
    }

    private Properties copyProperties(Properties properties) {
        Properties copy = new Properties();
        for (String name : properties.stringPropertyNames()) {
            copy.setProperty(name, properties.getProperty(name));
        }
        return copy;
    }

    protected Properties getProperties() {
        return properties;
    }
}
