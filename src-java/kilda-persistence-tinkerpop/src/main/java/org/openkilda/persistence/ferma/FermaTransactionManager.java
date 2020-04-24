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

import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.TransactionCallback;
import org.openkilda.persistence.TransactionCallbackWithoutResult;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.context.PersistenceContextRequired;

import com.syncleus.ferma.DelegatingFramedGraph;
import com.syncleus.ferma.WrappedTransaction;
import com.syncleus.ferma.tx.AbstractTx;
import com.syncleus.ferma.tx.Tx;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;

import java.util.concurrent.Callable;

/**
 * Ferma implementation of {@link TransactionManager}. Manages transaction boundaries.
 */
@Slf4j
public class FermaTransactionManager implements TransactionManager {
    protected final FramedGraphFactory<DelegatingFramedGraph<?>> graphFactory;

    public FermaTransactionManager(FramedGraphFactory<DelegatingFramedGraph<?>> graphFactory) {
        this.graphFactory = graphFactory;
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

    @PersistenceContextRequired
    protected <T> T execute(Callable<T> action) throws Exception {
        DelegatingFramedGraph<?> graph = graphFactory.getGraph();
        if (graph == null) {
            throw new PersistenceException("Unable to open a new transaction: there's no framed graph");
        }
        WrappedTransaction currentTx = graph.tx();
        boolean isUnderlyingTxOpen = currentTx.isOpen();
        boolean newTx = (Tx.getActive() == null || !Tx.getActive().isOpen());
        if (newTx) {
            if (isUnderlyingTxOpen) {
                log.debug("Closing an existing underlying transaction");
                currentTx.close();
            }

            Tx.setActive(openNewTransaction(graph));
        }

        try {
            T result = action.call();
            Tx.getActive().success();
            return result;
        } catch (Exception ex) {
            Tx.getActive().failure();
            throw ex;
        } finally {
            if (newTx) {
                log.debug("Closing the transaction {}", Tx.getActive());
                try {
                    Tx.getActive().close();
                } finally {
                    Tx.setActive(null);
                }
            }
        }
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
        log.debug("Opening a new transaction {}", tx);
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
