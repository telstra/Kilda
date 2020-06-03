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

package org.openkilda.wfm.share.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.Optional;

/**
 * An aspect for intercepting types or methods annotated with {@code @TimedExecution}.
 */
@Aspect
@Slf4j
public class TimedExecutionAspect {
    /**
     * Wraps annotated method with timer start/stop operations.
     */
    @Around("@annotation(timedExecution)")
    public Object aroundAdvice(ProceedingJoinPoint joinPoint, TimedExecution timedExecution) throws Throwable {
        Optional<MeterRegistry> registry = MeterRegistryHolder.getRegistry();
        Sample sample = registry.map(Timer::start).orElse(null);
        try {
            return joinPoint.proceed(joinPoint.getArgs());
        } finally {
            if (sample != null) {
                sample.stop(registry.get().timer(timedExecution.value()));
            } else {
                log.error("MeterRegistry is not set, no sampling data is taken");
            }
        }
    }
}

