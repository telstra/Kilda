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

package org.openkilda.persistence;

import org.openkilda.persistence.repositories.impl.Neo4jSessionFactory;

import com.google.common.annotations.VisibleForTesting;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;
import org.neo4j.ogm.transaction.Transaction.Status;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Neo4j OGM implementation of {@link TransactionManager}. Manages transaction boundaries.
 */
@Slf4j
final class Neo4jTransactionManager implements TransactionManager, Neo4jSessionFactory {
    private static final ThreadLocal<Session> SESSION_HOLDER = new ThreadLocal<>();

    @Getter(AccessLevel.PACKAGE)
    private final SessionFactory sessionFactory;
    private final RetryPolicy retryPolicyBlank;

    public Neo4jTransactionManager(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
        this.retryPolicyBlank = new RetryPolicy()
                .retryOn(RecoverablePersistenceException.class)
                .withJitter(50, TimeUnit.MICROSECONDS)
                .withBackoff(1, 10, TimeUnit.MILLISECONDS);
    }

    /**
     * Get the existing session if there's an active transaction, otherwise create a new session.
     *
     * @return the session.
     */
    @Override
    public Session getSession() {
        return Optional.ofNullable(SESSION_HOLDER.get()).orElseGet(sessionFactory::openSession);
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
        return new RetryPolicy(retryPolicyBlank);
    }

    private <T> T execute(RetryPolicy retryPolicy, Callable<T> action) {
        return Failsafe.with(retryPolicy)
                .onRetry(e -> log.warn("Retrying Neo4j transaction finished with exception", e))
                .onRetriesExceeded(e -> log.warn("No more retry attempt for Neo4j transaction, final failure", e))
                .get(() -> execute(action));
    }

    private <T> T execute(Callable<T> action) throws Exception {
        begin();

        try {
            T result = action.call();
            commit();
            return result;
        } catch (Exception ex) {
            rollback();
            throw ex;
        }
    }

    /**
     * Begin a new transaction.
     * <p/>
     * Only one transaction can be bound to a thread at any time, so calling this method within an active transactions
     * causes extending of transaction.
     * <p/>
     * See {@link org.neo4j.ogm.transaction.AbstractTransaction#extend}.
     */
    @VisibleForTesting
    void begin() {
        Session session = getSession();

        try {
            session.beginTransaction();
        } catch (Exception ex) {
            throw new PersistenceException("Unable to begin transaction.", ex);
        }

        SESSION_HOLDER.set(session);
    }

    /**
     * Commit the existing transaction.
     */
    @VisibleForTesting
    void commit() {
        Optional<Transaction> currentTx = Optional.ofNullable(SESSION_HOLDER.get())
                .map(Session::getTransaction);
        Transaction transaction = currentTx.orElseThrow(
                () -> new PersistenceException("Unable to commit transaction: there's no active Neo4j transaction."));

        try {
            transaction.commit();
            // Complete the transaction.
            transaction.close();
        } catch (Exception ex) {
            // We don't close the transaction on any failure
            // as it's up to a consumer to decide what to do with the failed transaction:
            // rollback it or retry commit.
            throw new PersistenceException("Unable to commit transaction.", ex);
        } finally {
            if (transaction.status() == Status.COMMITTED || transaction.status() == Status.CLOSED) {
                // Release the session associated with the transaction and the current thread.
                SESSION_HOLDER.remove();
            }
        }
    }

    /**
     * Rollback the existing transaction.
     */
    @VisibleForTesting
    void rollback() {
        Optional<Transaction> currentTx = Optional.ofNullable(SESSION_HOLDER.get())
                .map(Session::getTransaction);
        Transaction transaction = currentTx.orElseThrow(
                () -> new PersistenceException("Unable to commit transaction: there's no active Neo4j transaction."));

        try {
            transaction.rollback();
        } catch (Exception ex) {
            throw new PersistenceException("Unable to rollback transaction.", ex);
        } finally {
            // Enfoce closing of the transaction as a consumer already decided to abandon it.
            try {
                transaction.close();
            } catch (Exception ex) {
                log.error("Unable to close transaction: {}", transaction, ex);
            }

            if (transaction.status() == Status.ROLLEDBACK || transaction.status() == Status.CLOSED) {
                // Release the session associated with the transaction and the current thread.
                SESSION_HOLDER.remove();
            }
        }
    }

    private <T, E extends Throwable> Callable<T> callableOf(TransactionCallback<T, E> action) {
        return new Callable<T>() {
            @Override
            public T call() throws Exception {
                return action.doInTransaction();
            }
        };
    }

    private <T, E extends Throwable> Callable<T> callableOf(TransactionCallbackWithoutResult<E> action) {
        return new Callable<T>() {
            @Override
            public T call() throws Exception {
                action.doInTransaction();
                return null;
            }
        };
    }
}
