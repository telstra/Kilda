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

import net.jodah.failsafe.RetryPolicy;

/**
 * Manager of transaction boundaries.
 */
public interface TransactionManager {
    /**
     * Execute the action specified by the given callback within a transaction.
     * <p/>
     * A RuntimeException thrown by the callback is treated as a fatal exception that enforces a rollback.
     * The exception is propagated to the caller.
     *
     * @param action the transactional action
     * @return a result returned by the callback
     */
    <T, E extends Throwable> T doInTransaction(TransactionCallback<T, E> action) throws E;

    <T, E extends Throwable> T doInTransaction(RetryPolicy retryPolicy, TransactionCallback<T, E> action) throws E;

    /**
     * Execute the action specified by the given callback within a transaction.
     * <p/>
     * A RuntimeException thrown by the callback is treated as a fatal exception that enforces a rollback.
     * The exception is propagated to the caller.
     *
     * @param action the transactional action
     */
    <E extends Throwable> void doInTransaction(TransactionCallbackWithoutResult<E> action) throws E;

    <E extends Throwable> void doInTransaction(RetryPolicy retryPolicy, TransactionCallbackWithoutResult<E> action)
            throws E;

    RetryPolicy getDefaultRetryPolicy();
}
