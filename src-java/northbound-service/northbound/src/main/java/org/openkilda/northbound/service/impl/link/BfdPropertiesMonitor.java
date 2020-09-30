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

package org.openkilda.northbound.service.impl.link;

import org.openkilda.messaging.nbtopology.response.BfdPropertiesResponse;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.EffectiveBfdProperties;
import org.openkilda.northbound.error.BfdPropertyApplyException;
import org.openkilda.northbound.service.LinkService;
import org.openkilda.northbound.utils.RequestCorrelationId;

import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class BfdPropertiesMonitor {
    private static final Duration REQUESTS_DELAY = Duration.ofSeconds(1);

    private final LinkService service;

    private final TaskScheduler scheduler;

    private final BfdProperties expect;

    private final String correlationId;

    private final Instant giveUpPoint;
    
    private final Clock clock;

    private final CompletableFuture<BfdPropertiesResponse> future;

    private int requestNumber = 0;

    public BfdPropertiesMonitor(
            LinkService service, TaskScheduler scheduler, BfdProperties expect, String correlationId, Duration period,
            Clock clock) {
        if (period.isNegative()) {
            throw new IllegalArgumentException(String.format("period(%s) argument must not be negative", period));
        }

        this.service = service;
        this.scheduler = scheduler;
        this.expect = expect;
        this.correlationId = RequestCorrelationId.chain(correlationId, "monitor");
        this.giveUpPoint = clock.instant().plus(period);
        this.clock = clock;
        
        future = new CompletableFuture<>();
    }

    /**
     * Consume BFD properties read response, verify expectation and schedule one more read request or mark current
     * operation as completed.
     */
    public CompletableFuture<BfdPropertiesResponse> consume(BfdPropertiesResponse current) {
        if (verifyExpectation(current)) {
            future.complete(current);
        } else {
            try {
                scheduleRead(current);
            } catch (BfdPropertyApplyException e) {
                future.completeExceptionally(e);
            }
        }
        return future;
    }

    private boolean verifyExpectation(BfdPropertiesResponse value) {
        return verifyExpectation(value.getGoal())
                && verifyExpectation(value.getEffectiveSource())
                && verifyExpectation(value.getEffectiveDestination());
    }

    private boolean verifyExpectation(EffectiveBfdProperties value) {
        return verifyExpectation(new BfdProperties(value));
    }

    private boolean verifyExpectation(BfdProperties value) {
        return expect.equals(value);
    }

    private void scheduleRead(BfdPropertiesResponse current) throws BfdPropertyApplyException {
        Optional<Date> at = nextExecutionTime();
        scheduler.schedule(
                () -> execute(current),
                at.orElseThrow(() -> new BfdPropertyApplyException(current)));
    }

    private void execute(BfdPropertiesResponse current) {
        service.readBfdProperties(current.getSource(), current.getDestination(), makeCorrelationId())
                .whenComplete((response, error) -> {
                    if (error == null) {
                        consume(response);
                    } else {
                        future.completeExceptionally(error);
                    }
                });
    }

    private Optional<Date> nextExecutionTime() {
        Instant now = Instant.now(clock);
        if (giveUpPoint.isBefore(now)) {
            return Optional.empty();
        }
        return Optional.of(new Date(now.plus(REQUESTS_DELAY).toEpochMilli()));
    }

    private String makeCorrelationId() {
        return RequestCorrelationId.chain(correlationId, String.valueOf(requestNumber++));
    }
}
