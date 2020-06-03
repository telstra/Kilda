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

package org.openkilda.floodlight.command;

import org.openkilda.floodlight.service.kafka.IKafkaProducerService;
import org.openkilda.floodlight.service.kafka.KafkaUtilityService;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.floodlightcontroller.core.module.FloodlightModuleContext;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class SpeakerCommandProcessor {
    @Getter
    private final FloodlightModuleContext moduleContext;

    public SpeakerCommandProcessor(@NonNull FloodlightModuleContext moduleContext) {
        this.moduleContext = moduleContext;
    }

    /**
     * Manage command execution and make basic error reporting in case of exceptional completion.
     */
    public <T extends SpeakerCommandReport> void process(SpeakerCommand<T> command, String kafkaKey) {
        log.debug("Process speaker command {} on {}", command, command.getSwitchId());
        Instant timeStart = Instant.now();
        execute(command)
                .whenComplete((response, error) -> handleResult(command, response, error, timeStart, kafkaKey));
    }

    public <T extends SpeakerCommandReport> CompletableFuture<T> chain(SpeakerCommand<T> command) {
        return execute(command);
    }

    private <T extends SpeakerCommandReport> CompletableFuture<T> execute(SpeakerCommand<T> command) {
        return command.execute(this);
    }

    private <T extends SpeakerCommandReport> void handleResult(
            SpeakerCommand<T> command, T report, Throwable error, Instant timeStart, String kafkaKey) {
        Duration execTime = Duration.between(timeStart, Instant.now());
        if (error == null) {
            handleResult(command, report, execTime, kafkaKey);
        } else {
            reportError(command, error, execTime);
        }
    }

    private <T extends SpeakerCommandReport> void handleResult(
            SpeakerCommand<T> command, T report, Duration execTime, String kafkaKey) {
        reportExecStatus(command, report, execTime);

        if (report instanceof SpeakerCommandRemoteReport) {
            SpeakerCommandRemoteReport speakerCommandRemoteReport = (SpeakerCommandRemoteReport) report;
            speakerCommandRemoteReport.setExecutionTime(execTime);
            handleResult(speakerCommandRemoteReport, kafkaKey);
        }
    }

    private void handleResult(SpeakerCommandRemoteReport report, String kafkaKey) {
        KafkaUtilityService kafkaUtil = moduleContext.getServiceImpl(KafkaUtilityService.class);
        IKafkaProducerService kafkaProducer = moduleContext.getServiceImpl(IKafkaProducerService.class);
        report.reply(kafkaUtil.getKafkaChannel(), kafkaProducer, kafkaKey);
    }

    private <T extends SpeakerCommandReport> void reportExecStatus(
            SpeakerCommand<T> command, T report, Duration execTime) {
        try {
            report.raiseError();
            reportSuccess(command, report, execTime);
        } catch (Exception e) {
            reportError(command, e, execTime);
        }
    }

    private <T extends SpeakerCommandReport> void reportSuccess(
            SpeakerCommand<T> command, T report, Duration execTime) {
        log.info(formatExecStats(command, String.format("completed with report %s", report), execTime));
    }

    private <T extends SpeakerCommandReport> void reportError(
            SpeakerCommand<T> command, Throwable error, Duration execTime) {
        log.error(formatExecStats(command, String.format("have failed with error: %s", error), execTime), error);
    }

    private <T extends SpeakerCommandReport>  String formatExecStats(
            SpeakerCommand<T> command, String message, Duration execTime) {
        return String.format("Command %s on %s %s (exec time: %s)", command, command.getSwitchId(), message, execTime);
    }
}
