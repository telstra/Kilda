/* Copyright 2020 Telstra Open Source
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

import org.openkilda.persistence.context.PersistenceContextRequired;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.exceptions.RecoverablePersistenceException;
import org.openkilda.persistence.tx.TransactionCallback;
import org.openkilda.persistence.tx.TransactionCallbackWithoutResult;
import org.openkilda.persistence.tx.TransactionManager;

import com.syncleus.ferma.DelegatingFramedGraph;
import com.syncleus.ferma.WrappedTransaction;
import com.syncleus.ferma.tx.AbstractTx;
import com.syncleus.ferma.tx.Tx;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;

import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;

/**
 * Ferma implementation of {@link TransactionManager}. Manages transaction boundaries.
 */
@Slf4j
public class FermaTransactionManager implements TransactionManager {
    protected final FramedGraphFactory<DelegatingFramedGraph<?>> graphFactory;
    private final int transactionRetriesLimit;
    private final int transactionRetriesMaxDelay;

    public FermaTransactionManager(FramedGraphFactory<DelegatingFramedGraph<?>> graphFactory,
                                   int transactionRetriesLimit, int transactionRetriesMaxDelay) {
        this.graphFactory = graphFactory;
        this.transactionRetriesLimit = transactionRetriesLimit;
        this.transactionRetriesMaxDelay = transactionRetriesMaxDelay;
    }

    @SneakyThrows
    @Override
    public <T, E extends Throwable> T doInTransaction(TransactionCallback<T, E> action) throws E {
        if (isTxOpen()) {
            return execute(callableOf(action));
        } else {
            return execute(getDefaultRetryPolicy(), callableOf(action));
        }
    }

    @SneakyThrows
    @Override
    public <T, E extends Throwable> T doInTransaction(RetryPolicy<T> retryPolicy, TransactionCallback<T, E> action)
            throws E {
        if (isTxOpen()) {
            throw new PersistenceException("Nested transaction mustn't be retryable");
        }
        return execute(retryPolicy, callableOf(action));
    }

    @SneakyThrows
    @Override
    public <E extends Throwable> void doInTransaction(TransactionCallbackWithoutResult<E> action) throws E {
        if (isTxOpen()) {
            execute(callableOf(action));
        } else {
            execute(getDefaultRetryPolicy(), callableOf(action));
        }
    }

    @SneakyThrows
    @Override
    public <E extends Throwable> void doInTransaction(RetryPolicy<?> retryPolicy,
                                                      TransactionCallbackWithoutResult<E> action) throws E {
        if (isTxOpen()) {
            throw new PersistenceException("Nested transaction mustn't be retryable");
        }
        execute(retryPolicy, callableOf(action));
    }

    @Override
    public <T> RetryPolicy<T> getDefaultRetryPolicy() {
        RetryPolicy<T> retryPolicy = new RetryPolicy<T>()
                .handle(RecoverablePersistenceException.class)
                .withMaxRetries(transactionRetriesLimit);
        if (transactionRetriesMaxDelay > 0) {
            retryPolicy.withBackoff(Math.min(1, transactionRetriesMaxDelay), transactionRetriesMaxDelay,
                    ChronoUnit.MILLIS);
        }
        return retryPolicy;
    }

    @SneakyThrows
    private <T> T execute(RetryPolicy<T> retryPolicy, Callable<T> action) {
        try {
            return Failsafe.with(retryPolicy)
                    .onFailure(e -> log.debug("Failure in transaction. Retrying...", e))
                    .onComplete(e -> log.error("Failure in transaction. No more retries", e))
                    .get(() -> execute(action));
        } catch (FailsafeException ex) {
            throw ex.getCause();
        }
    }

    @PersistenceContextRequired
    protected <T> T execute(Callable<T> action) throws Exception {
        DelegatingFramedGraph<?> graph = graphFactory.getGraph();
        if (graph == null) {
            throw new PersistenceException("Unable to open a new transaction: there's no framed graph");
        }

        boolean isNewTx = !isTxOpen();
        if (isNewTx) {
            WrappedTransaction currentTx = graph.tx();
            if (currentTx.isOpen()) {
                log.debug("Closing an existing underlying transaction {} on graph {}", currentTx, graph);
                closeTransaction(currentTx);
            }

            Tx.setActive(openNewTransaction(graph));
        }
        Tx activeTx = Tx.getActive();

        try {
            T result = action.call();
            activeTx.success();
            return result;
        } catch (Exception ex) {
            log.debug("Failed transaction {} on graph {}", activeTx, graph, ex);
            activeTx.failure();
            throw wrapPersistenceException(ex);
        } finally {
            if (isNewTx) {
                Tx.setActive(null);
                closeTransaction(activeTx);
            }
        }
    }

    @Override
    public boolean isTxOpen() {
        Tx activeTx = Tx.getActive();
        return activeTx != null && activeTx.isOpen();
    }

    private void closeTransaction(WrappedTransaction tx) throws Exception {
        log.debug("Closing the transaction {}", tx);
        try {
            tx.close();
        } catch (Exception ex) {
            throw wrapPersistenceException(ex);
        }
    }

    protected Exception wrapPersistenceException(Exception ex) {
        return ex;
    }

    private Tx openNewTransaction(DelegatingFramedGraph<?> graph) {
        Tx tx = new AbstractTx(graph.getBaseGraph().tx(), graph) {
            private Boolean isSuccess = null;

            @Override
            public void success() {
                if (isSuccess == null) {
                    isSuccess = true;
                }
            }

            @Override
            public void failure() {
                isSuccess = false;
            }

            @Override
            protected boolean isSuccess() {
                return isSuccess != null ? isSuccess : false;
            }
        };
        if (tx.isOpen()) {
            throw new PersistenceException("Attempt to reopen transaction: " + tx);
        }
        log.debug("Opening a new transaction {} on graph {}", tx, graph);
        tx.open();
        return tx;
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
