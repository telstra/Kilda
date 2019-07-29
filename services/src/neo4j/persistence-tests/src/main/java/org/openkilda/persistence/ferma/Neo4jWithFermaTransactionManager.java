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

package org.openkilda.persistence.ferma;

import org.openkilda.persistence.TransactionCallback;
import org.openkilda.persistence.TransactionCallbackWithoutResult;
import org.openkilda.persistence.TransactionManager;

import com.steelbridgelabs.oss.neo4j.structure.Neo4JGraph;
import com.syncleus.ferma.DelegatingFramedGraph;
import com.syncleus.ferma.FramedGraph;
import com.syncleus.ferma.WrappedTransaction;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Neo4j with Ferma implementation of {@link TransactionManager}. Manages transaction boundaries.
 */
@Slf4j
public final class Neo4jWithFermaTransactionManager implements TransactionManager, FermaGraphFactory {
    private static final ThreadLocal<FramedGraph> GRAPH_HOLDER = new ThreadLocal<>();

    @Getter(AccessLevel.PACKAGE)
    private final Neo4JGraph neo4JGraph;

    public Neo4jWithFermaTransactionManager(Neo4JGraph neo4JGraph) {
        this.neo4JGraph = neo4JGraph;
    }

    /**
     * Get a FramedGraph instance for an active transaction.
     *
     * @return the framed grapth.
     */
    @Override
    public FramedGraph getFramedGraph() {
        return Optional.ofNullable(GRAPH_HOLDER.get())
                .orElseGet(() -> new DelegatingFramedGraph(neo4JGraph, false, false));
    }

    @SneakyThrows
    @Override
    public <T, E extends Throwable> T doInTransaction(TransactionCallback<T, E> action) throws E {
        return execute(callableOf(action));
    }

    @SneakyThrows
    @Override
    public <T, E extends Throwable> T doInTransaction(RetryPolicy retryPolicy, TransactionCallback<T, E> action)
            throws E {
        return execute(retryPolicy, callableOf(action));
    }

    @SneakyThrows
    @Override
    public <E extends Throwable> void doInTransaction(TransactionCallbackWithoutResult<E> action) throws E {
        execute(callableOf(action));
    }

    @SneakyThrows
    @Override
    public <E extends Throwable> void doInTransaction(RetryPolicy retryPolicy,
                                                      TransactionCallbackWithoutResult<E> action) throws E {
        execute(retryPolicy, callableOf(action));
    }

    @Override
    public RetryPolicy makeRetryPolicyBlank() {
        return new RetryPolicy();
    }

    private <T> T execute(RetryPolicy retryPolicy, Callable<T> action) {
        return Failsafe.with(retryPolicy).get(() -> execute(action));
    }

    private <T> T execute(Callable<T> action) throws Exception {
        WrappedTransaction tx = getFramedGraph().tx();
        boolean alreadyOpen = tx.isOpen();
        try {
            if (!alreadyOpen) {
                tx.open();
            }

            try {
                T result = action.call();
                tx.commit();
                return result;
            } catch (Exception ex) {
                tx.rollback();
                throw ex;
            }
        } finally {
            // Close only if it's not an extended transaction.
            if (!alreadyOpen) {
                tx.close();
            }
        }
    }

    private <T, E extends Throwable> Callable<T> callableOf(TransactionCallback<T, E> action) {
        return action::doInTransaction;
    }

    private <T, E extends Throwable> Callable<T> callableOf(TransactionCallbackWithoutResult<E> action) {
        return () -> {
            action.doInTransaction();
            return null;
        };
    }
}
