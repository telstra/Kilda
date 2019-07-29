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

package org.openkilda.persistence.ferma;

import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.repositories.FermaRepositoryFactory;

import com.google.common.annotations.VisibleForTesting;
import com.orientechnologies.orient.core.db.ODatabaseType;
import com.orientechnologies.orient.core.db.OrientDB;
import com.syncleus.ferma.ext.orientdb.OrientTransactionFactory;
import com.syncleus.ferma.ext.orientdb.impl.OrientTransactionFactoryImpl;
import com.syncleus.ferma.tx.Tx;
import org.apache.tinkerpop.gremlin.orientdb.OrientGraphFactory;

/**
 * OrientDB implementation of {@link PersistenceManager}.
 */
public class OrientDbPersistenceManager implements AutoCloseable {
    private final OrientDbConfig config;
    @VisibleForTesting
    public final OrientDB orientDb;

    private transient volatile FermaTransactionManager transactionManager;

    public OrientDbPersistenceManager(OrientDbConfig config, OrientDB orientDb) {
        this.config = config;
        this.orientDb = orientDb;
    }

    public TransactionManager getTransactionManager() {
        return getOrientDbTransactionManager();
    }

    public FermaRepositoryFactory getRepositoryFactory() {
        return new FermaRepositoryFactory(getOrientDbTransactionManager(), getTransactionManager());
    }

    private FermaTransactionManager getOrientDbTransactionManager() {
        if (transactionManager == null) {
            synchronized (this) {
                if (transactionManager == null) {
                    OrientGraphFactory factory = new OrientGraphFactory(orientDb,
                            config.getDbName(),
                            ODatabaseType.valueOf(config.getDbType()),
                            config.getDbUser(),
                            config.getDbPassword());
                    OrientTransactionFactory txFactory =
                            new OrientTransactionFactoryImpl(factory, false);
                    transactionManager = new FermaTransactionManager(txFactory);
                }
            }
        }

        return transactionManager;
    }

    @Override
    public void close() {
        if (transactionManager != null) {
            synchronized (this) {
                if (transactionManager != null) {
                    Tx tx = Tx.getActive();
                    if (tx != null) {
                        System.err.println("Closing an open TX on closing TX factory...");
                        tx.close();
                    }
                    ((OrientTransactionFactory) transactionManager.getTxFactory()).getFactory().close();
                    transactionManager = null;
                }
            }
        }
    }
}
