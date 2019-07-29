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

package org.openkilda.persistence.tests.neo4j;

import static java.lang.String.format;

import org.openkilda.model.SwitchId;
import org.openkilda.persistence.Neo4jConfig;
import org.openkilda.persistence.Neo4jPersistenceManager;
import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.PersistenceManager;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.ogm.testutil.TestServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
public class EmbeddedNeo4jOgmPersistence implements Neo4jOgmPersistence, AutoCloseable {
    public final TestServer testServer;

    public EmbeddedNeo4jOgmPersistence(boolean loadTestData) {
        Logger.getLogger("").setLevel(Level.SEVERE);

        testServer = new TestServer(true, true, 5);

        runQueriesInFile("/org/openkilda/persistence/tests/neo4j/schema");

        if (loadTestData) {
            loadTestData();

            try (PersistenceManager persistenceManager = createPersistenceManager()) {
                if (!persistenceManager.getRepositoryFactory().createSwitchRepository().exists(new SwitchId(2))) {
                    throw new IllegalStateException("Failed to load to Neo4j");
                }
            }
        }
    }

    @Override
    public PersistenceManager createPersistenceManager() {
        return new Neo4jPersistenceManager(new Neo4jConfig() {
            @Override
            public String getUri() {
                return testServer.getUri();
            }

            @Override
            public String getLogin() {
                return testServer.getUsername();
            }

            @Override
            public String getPassword() {
                return testServer.getPassword();
            }

            @Override
            public int getConnectionPoolSize() {
                return 50;
            }

            @Override
            public String getIndexesAuto() {
                return "validate";
            }
        }, new NetworkConfig() {
            @Override
            public int getIslUnstableTimeoutSec() {
                return 0;
            }
        });
    }

    @Override
    public void close() {
        testServer.shutdown();
    }

    private void loadTestData() {
        runCypherFiles(IntStream.range(0, 22)
                .mapToObj(i -> format("/org/openkilda/persistence/tests/neo4j/data-%02d", i)));
    }

    private void runQueriesInFile(String file) {
        GraphDatabaseService graphDatabaseService = testServer.getGraphDatabaseService();
        try (InputStream is = getClass().getResourceAsStream(file)) {
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = br.readLine()) != null) {
                graphDatabaseService.execute(line);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read a file: " + file, e);
        }
    }

    private void runCypherFiles(Stream<String> files) {
        String fileList = files.map(file -> {
            URL url = getClass().getResource(file);
            File tmpCopy;
            try {
                tmpCopy = File.createTempFile("persistence-tests-", ".cypher");
                FileUtils.copyURLToFile(url, tmpCopy);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read data file: " + file, e);
            }
            return format("'%s'", tmpCopy.getAbsoluteFile().toURI());
        }).collect(Collectors.joining(","));

        GraphDatabaseService graphDatabaseService = testServer.getGraphDatabaseService();
        graphDatabaseService.execute(format("CALL apoc.cypher.runFiles([%s])", fileList));
    }
}
