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

import com.orientechnologies.common.concur.ONeedRetryException;
import com.orientechnologies.common.exception.OException;
import com.syncleus.ferma.DelegatingFramedGraph;
import lombok.extern.slf4j.Slf4j;

/**
 * OrientDB implementation of {@link TransactionManager}. Manages transaction boundaries.
 */
@Slf4j
public class OrientDbTransactionManager extends FermaTransactionManager {
    public OrientDbTransactionManager(FramedGraphFactory<DelegatingFramedGraph<?>> graphFactory) {
        super(graphFactory);
    }

    @Override
    protected Exception wrapPersistenceException(Exception ex) {
        if (ex instanceof ONeedRetryException) {
            return new RecoverablePersistenceException("Failure in transaction", ex);
        } else if (ex instanceof OException) {
            return new PersistenceException("Failure in transaction", ex);
        } else {
            return super.wrapPersistenceException(ex);
        }
    }
}
