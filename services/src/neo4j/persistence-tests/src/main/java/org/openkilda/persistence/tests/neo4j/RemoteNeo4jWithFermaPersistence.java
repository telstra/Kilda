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
import org.openkilda.persistence.ferma.Neo4jWithFermaPersistenceManager;

import com.steelbridgelabs.oss.neo4j.structure.Neo4JGraph;
import com.steelbridgelabs.oss.neo4j.structure.providers.Neo4JNativeElementIdProvider;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

@Slf4j
public class RemoteNeo4jWithFermaPersistence implements AutoCloseable {
    final Driver driver;

    public RemoteNeo4jWithFermaPersistence(String uri, String login, String password, boolean loadTestData) {
        Logger.getLogger("").setLevel(Level.SEVERE);

        driver = GraphDatabase.driver(uri, AuthTokens.basic(login, password));

        runQueriesInFile("/org/openkilda/persistence/tests/neo4j/schema");

        truncateData();

        if (loadTestData) {
            loadTestData();

            try (Neo4jWithFermaPersistenceManager persistenceManager = createPersistenceManager()) {
                if (!persistenceManager.getRepositoryFactory().createSwitchRepository().exists(new SwitchId(2))) {
                    throw new IllegalStateException("Failed to load to Neo4j");
                }
            }
        }
    }

    public Neo4jWithFermaPersistenceManager createPersistenceManager() {
        Neo4JNativeElementIdProvider vertexIdProvider = new Neo4JNativeElementIdProvider();
        Neo4JNativeElementIdProvider edgeIdProvider = new Neo4JNativeElementIdProvider();
        Neo4JGraph neo4JGraph = new Neo4JGraph(driver, vertexIdProvider, edgeIdProvider);
        return new Neo4jWithFermaPersistenceManager(neo4JGraph);
    }

    @Override
    public void close() {
        truncateData();
        driver.close();
    }

    private void truncateData() {
        try (Session session = driver.session()) {
            while (true) {
                StatementResult result =
                        session.run("MATCH (n) WITH n LIMIT 1000 DETACH DELETE n RETURN count(*)");
                if (!result.hasNext() || result.next().get(0).asLong() == 0) {
                    return;
                }
            }
        }
    }

    private void loadTestData() {
        IntStream.range(0, 22)
                .mapToObj(i -> format("/org/openkilda/persistence/tests/neo4j/data-%02d", i))
                .forEach(this::runQueriesInFile);
    }

    private void runQueriesInFile(String file) {
        try (Session session = driver.session()) {
            try (InputStream is = getClass().getResourceAsStream(file)) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                String line;
                while ((line = br.readLine()) != null) {
                    session.run(line);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read a file: " + file, e);
            }
        }
    }
}
