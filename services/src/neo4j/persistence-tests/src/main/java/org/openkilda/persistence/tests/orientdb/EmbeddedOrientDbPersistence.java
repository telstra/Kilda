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

package org.openkilda.persistence.tests.orientdb;

import org.openkilda.model.SwitchId;
import org.openkilda.persistence.ferma.OrientDbConfig;
import org.openkilda.persistence.ferma.OrientDbPersistenceManager;

import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.db.ODatabaseType;
import com.orientechnologies.orient.core.db.OrientDB;
import com.orientechnologies.orient.core.db.OrientDBConfig;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.graph.graphml.OGraphMLReader;
import com.orientechnologies.orient.server.OServer;
import com.orientechnologies.orient.server.OServerMain;
import com.orientechnologies.orient.server.config.OServerConfiguration;
import com.orientechnologies.orient.server.config.OServerNetworkConfiguration;
import com.orientechnologies.orient.server.config.OServerUserConfiguration;
import com.tinkerpop.blueprints.impls.orient.OrientGraph;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

@Slf4j
public class EmbeddedOrientDbPersistence implements OrientDbPersistence, AutoCloseable {
    static final String ORIENT_DB_PATH = "memory:./databases/";
    private final String serverUser = "root";
    private final String serverPassword = "root";
    private final String dbName;
    private final String dbUser = "admin";
    private final String dbPassword = "admin";

    public final OServer server;
    public final OrientDB orientDb;

    public EmbeddedOrientDbPersistence(boolean loadTestData) throws Exception {
        Logger.getLogger("").setLevel(Level.SEVERE);

        OServerConfiguration cfg = new OServerConfiguration();
        cfg.network = new OServerNetworkConfiguration(cfg);
        cfg.users = new OServerUserConfiguration[]{
                new OServerUserConfiguration(serverUser, serverPassword, "*")};

        server = OServerMain.create();
        server.startup(cfg).activate();

        this.dbName = "TEST_" + Instant.now().toEpochMilli();

        orientDb = new OrientDB(ORIENT_DB_PATH, null, null,
                OrientDBConfig.defaultConfig());
        if (orientDb.createIfNotExists(dbName, ODatabaseType.MEMORY)) {
            initSchema();

            if (loadTestData) {
                loadTestData();
            }
        }

        if (loadTestData) {
            try (OrientDbPersistenceManager persistenceManager = createPersistenceManager()) {
                if (!persistenceManager.getRepositoryFactory().createSwitchRepository().exists(new SwitchId(2))) {
                    throw new IllegalStateException("Failed to load to OrientDB");
                }
            }
        } else {
            truncateData();
        }
    }

    @Override
    public OrientDbPersistenceManager createPersistenceManager() {
        return new OrientDbPersistenceManager(new OrientDbConfig() {
            @Override
            public String getDbName() {
                return dbName;
            }

            @Override
            public String getDbType() {
                return ODatabaseType.MEMORY.name();
            }

            @Override
            public String getDbUser() {
                return dbUser;
            }

            @Override
            public String getDbPassword() {
                return dbPassword;
            }
        }, orientDb);
    }

    @Override
    public void close() {
        orientDb.drop(dbName);
        orientDb.close();
        server.shutdown();
    }

    @Override
    public String getDbName() {
        return dbName;
    }

    @Override
    public String getDbUser() {
        return dbUser;
    }

    @Override
    public String getDbPassword() {
        return dbPassword;
    }

    @Override
    public String getServerUser() {
        return serverUser;
    }

    @Override
    public String getServerPassword() {
        return serverPassword;
    }

    private void initSchema() {
        try (ODatabaseSession session = orientDb.open(dbName, dbUser, dbPassword)) {
            OSchema schema = session.getMetadata().getSchema();
            if (!schema.existsClass("isl")) {
                session.execute("sql", "CREATE CLASS isl EXTENDS E");
            }
            if (!schema.existsClass("switch")) {
                session.execute("sql", "CREATE CLASS switch EXTENDS V");
                session.execute("sql", "CREATE VERTEX switch");
                session.execute("sql", "CREATE PROPERTY switch.name STRING");
                session.execute("sql", "CREATE INDEX switchId ON switch (name) UNIQUE");
            }
            if (!schema.existsClass("flow")) {
                session.execute("sql", "CREATE CLASS flow EXTENDS V");
                session.execute("sql", "CREATE VERTEX flow");
                session.execute("sql", "CREATE PROPERTY flow.flow_id STRING");
                session.execute("sql", "CREATE INDEX flowId ON flow (flow_id) UNIQUE");
            }
            if (!schema.existsClass("flow_path")) {
                session.execute("sql", "CREATE CLASS flow_path EXTENDS V");
                session.execute("sql", "CREATE VERTEX flow_path");
                session.execute("sql", "CREATE PROPERTY flow_path.path_id STRING");
                session.execute("sql", "CREATE INDEX pathId ON flow_path (path_id) UNIQUE");
            }
            session.commit();
        }
    }

    private void truncateData() {
        try (ODatabaseSession session = orientDb.open(dbName, dbUser, dbPassword)) {
            session.execute("sql", "DELETE EDGE E");
            session.execute("sql", "DELETE VERTEX V");
            session.commit();
        }
    }


    private void loadTestData() {
        try (ODatabaseSession session = orientDb.open(dbName, dbUser, dbPassword)) {
            importFile(session, "/org/openkilda/persistence/tests/orientdb/data.graphml");
            session.commit();
        }
    }

    private void importFile(ODatabaseSession databaseSession, String file) {
        try (InputStream is = getClass().getResourceAsStream(file)) {
            OrientGraph graph = new OrientGraph((ODatabaseDocumentInternal) databaseSession);
            new OGraphMLReader(graph).inputGraph(is);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read a file: " + file, e);
        }
    }
}
