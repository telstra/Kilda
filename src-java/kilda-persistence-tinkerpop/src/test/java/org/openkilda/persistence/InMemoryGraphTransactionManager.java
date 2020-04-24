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

package org.openkilda.persistence;

import org.openkilda.persistence.ferma.FermaTransactionManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;

import com.syncleus.ferma.DelegatingFramedGraph;

import java.util.concurrent.Callable;

/**
 * In-memory implementation of {@link TransactionManager}.
 * Built on top of Tinkerpop / Ferma implementation.
 */
class InMemoryGraphTransactionManager extends FermaTransactionManager {
    public InMemoryGraphTransactionManager(FramedGraphFactory<DelegatingFramedGraph<?>> graphFactory) {
        super(graphFactory);
    }

    @Override
    protected <T> T execute(Callable<T> action) throws Exception {
        // In-memory graph doesn't support transactions
        return action.call();
    }
}
