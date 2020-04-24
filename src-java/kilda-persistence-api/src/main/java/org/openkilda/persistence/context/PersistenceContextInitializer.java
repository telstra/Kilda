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

package org.openkilda.persistence.context;

import org.openkilda.persistence.spi.PersistenceProvider;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * An aspect for @NewPersistenceContextRequired which decorates each call with a new persistence context.
 */
@Aspect
@Slf4j
public class PersistenceContextInitializer {
    private final PersistenceProvider persistenceProvider = PersistenceProvider.getInstance();

    /**
     * Wraps annotated method with init/close operations for the persistence context.
     */
    @Around("@annotation(persistenceContextRequired)")
    public Object aroundAdvice(ProceedingJoinPoint joinPoint,
                               PersistenceContextRequired persistenceContextRequired) throws Throwable {
        PersistenceContextManager persistenceContextManager = persistenceProvider.getPersistenceContextManager();
        boolean isNewContext = !persistenceContextManager.isContextInitialized()
                || persistenceContextRequired.requiresNew();

        if (isNewContext) {
            persistenceContextManager.initContext();
        }

        try {
            return joinPoint.proceed(joinPoint.getArgs());
        } finally {
            if (isNewContext) {
                persistenceContextManager.closeContext();
            }
        }
    }
}
