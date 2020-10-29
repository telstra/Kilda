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

import org.openkilda.wfm.error.ConfigurationException;
import org.openkilda.wfm.topology.Topology;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class CliArguments {
    @Option(name = "--local", usage = "Do not push topology onto storm server, execute it local.")
    private Boolean isLocal = false;

    @Option(name = "--name", usage = "Set topology name.")
    protected String topologyName;

    @Option(name = "--local-execution-time", usage = "Work time limit, when started in \"local\" execution mode.")
    protected Integer localExecutionTime;

    @Argument(metaVar = "CONFIG", multiValued = true,
            usage = "Extra configuration file(s) (can accept multiple paths).")
    private File[] extraConfiguration = {};

    protected Properties properties;

    public CliArguments(String[] args) throws CmdLineException, ConfigurationException {
        CmdLineParser parser = new CmdLineParser(this);
        parser.parseArgument(args);

        loadExtraConfig();
        topologyName = fixTopologyName();
    }

    public Boolean getIsLocal() {
        return isLocal;
    }

    public String getTopologyName() {
        return topologyName;
    }

    public Integer getLocalExecutionTime() {
        return localExecutionTime;
    }

    public Properties getProperties() {
        return properties;
    }

    private String fixTopologyName() {
        String value = null;
        if (properties != null) {
            value = properties.getProperty("--name");
        }
        if (value == null) {
            return null;
        }
        if (value.isEmpty()) {
            return null;
        }
        return value;
    }

    private void loadExtraConfig() throws ConfigurationException {
        properties = new Properties();
        try {
            properties.load(this.getClass().getResourceAsStream(Topology.TOPOLOGY_PROPERTIES));
        } catch (IOException e) {
            throw new ConfigurationException("Unable to load default properties.", e);
        }

        for (File path : extraConfiguration) {
            Properties override = new Properties(properties);
            try {
                FileInputStream source = new FileInputStream(path);
                override.load(source);
            } catch (IOException e) {
                throw new ConfigurationException(String.format("Unable to load properties from %s", path), e);
            }

            properties = override;
        }
    }
}
