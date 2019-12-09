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

package org.openkilda.wfm.topology.switchmanager.fsm;

import static java.util.Collections.emptyList;
import static org.apache.storm.shade.org.apache.commons.collections.ListUtils.union;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncEvent.ERROR;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncEvent.METERS_REMOVED;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncEvent.NEXT;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncEvent.RULES_INSTALLED;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncEvent.RULES_REINSTALLED;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncEvent.RULES_REMOVED;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncEvent.RULES_SYNCHRONIZED;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncEvent.TIMEOUT;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncState.COMPUTE_INSTALL_RULES;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncState.COMPUTE_REMOVE_METERS;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncState.COMPUTE_REMOVE_RULES;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncState.FINISHED;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncState.FINISHED_WITH_ERROR;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncState.INITIALIZED;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncState.METERS_COMMANDS_SEND;
import static org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncState.RULES_COMMANDS_SEND;

import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.flow.BaseInstallFlow;
import org.openkilda.messaging.command.flow.InstallFlowForSwitchManagerRequest;
import org.openkilda.messaging.command.flow.ReinstallDefaultFlowForSwitchManagerRequest;
import org.openkilda.messaging.command.flow.RemoveFlow;
import org.openkilda.messaging.command.flow.RemoveFlowForSwitchManagerRequest;
import org.openkilda.messaging.command.switches.DeleterMeterForSwitchManagerRequest;
import org.openkilda.messaging.command.switches.SwitchValidateRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.rule.SwitchSyncErrorData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.flow.FlowReinstallResponse;
import org.openkilda.messaging.info.switches.MeterInfoEntry;
import org.openkilda.messaging.info.switches.MetersSyncEntry;
import org.openkilda.messaging.info.switches.RulesSyncEntry;
import org.openkilda.messaging.info.switches.SwitchSyncResponse;
import org.openkilda.model.Cookie;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.share.utils.AbstractBaseFsm;
import org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncEvent;
import org.openkilda.wfm.topology.switchmanager.fsm.SwitchSyncFsm.SwitchSyncState;
import org.openkilda.wfm.topology.switchmanager.model.ValidateMetersResult;
import org.openkilda.wfm.topology.switchmanager.model.ValidateRulesResult;
import org.openkilda.wfm.topology.switchmanager.model.ValidationResult;
import org.openkilda.wfm.topology.switchmanager.service.CommandBuilder;
import org.openkilda.wfm.topology.switchmanager.service.SwitchManagerCarrier;

import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class SwitchSyncFsm extends AbstractBaseFsm<SwitchSyncFsm, SwitchSyncState, SwitchSyncEvent, Object> {

    private static final String FINISHED_WITH_ERROR_METHOD_NAME = "finishedWithError";
    private static final String FINISHED_METHOD_NAME = "finished";
    private static final String ERROR_LOG_MESSAGE = "Key: {}, message: {}";

    private final String key;
    private final SwitchValidateRequest request;
    private final SwitchManagerCarrier carrier;
    private final CommandBuilder commandBuilder;
    private SwitchId switchId;
    private ValidationResult validationResult;
    private List<Long> installRules;
    private List<Long> removeFlowRules;
    private List<Long> removeDefaultRules = new ArrayList<>();

    private List<CommandData> missingRules = emptyList();
    private List<RemoveFlow> excessRules = emptyList();
    private List<Long> excessMeters = emptyList();

    private int missingRulesPendingResponsesCount = 0;
    private int excessRulesPendingResponsesCount = 0;
    private int reinstallDefaultRulesPendingResponsesCount = 0;
    private int excessMetersPendingResponsesCount = 0;

    public SwitchSyncFsm(SwitchManagerCarrier carrier, String key, CommandBuilder commandBuilder,
                         SwitchValidateRequest request, ValidationResult validationResult) {
        this.carrier = carrier;
        this.key = key;
        this.commandBuilder = commandBuilder;
        this.request = request;
        this.validationResult = validationResult;
        this.switchId = request.getSwitchId();
    }

    /**
     * FSM builder.
     */
    public static StateMachineBuilder<SwitchSyncFsm, SwitchSyncState,
            SwitchSyncEvent, Object> builder() {
        StateMachineBuilder<SwitchSyncFsm, SwitchSyncState, SwitchSyncEvent, Object> builder =
                StateMachineBuilderFactory.create(
                        SwitchSyncFsm.class,
                        SwitchSyncState.class,
                        SwitchSyncEvent.class,
                        Object.class,
                        SwitchManagerCarrier.class,
                        String.class,
                        CommandBuilder.class,
                        SwitchValidateRequest.class,
                        ValidationResult.class);

        builder.onEntry(INITIALIZED).callMethod("initialized");
        builder.externalTransition().from(INITIALIZED).to(COMPUTE_INSTALL_RULES).on(NEXT)
                .callMethod("computeInstallRules");
        builder.externalTransition().from(COMPUTE_INSTALL_RULES).to(FINISHED_WITH_ERROR).on(ERROR)
                .callMethod(FINISHED_WITH_ERROR_METHOD_NAME);

        builder.externalTransition().from(COMPUTE_INSTALL_RULES).to(COMPUTE_REMOVE_RULES).on(NEXT)
                .callMethod("computeRemoveRules");
        builder.externalTransition().from(COMPUTE_REMOVE_RULES).to(FINISHED_WITH_ERROR).on(ERROR)
                .callMethod(FINISHED_WITH_ERROR_METHOD_NAME);

        builder.externalTransition().from(COMPUTE_REMOVE_RULES).to(COMPUTE_REMOVE_METERS).on(NEXT)
                .callMethod("computeRemoveMeters");
        builder.externalTransition().from(COMPUTE_REMOVE_METERS).to(FINISHED_WITH_ERROR).on(ERROR)
                .callMethod(FINISHED_WITH_ERROR_METHOD_NAME);

        builder.externalTransition().from(COMPUTE_REMOVE_METERS).to(RULES_COMMANDS_SEND).on(NEXT)
                .callMethod("sendRulesCommands");
        builder.internalTransition().within(RULES_COMMANDS_SEND).on(RULES_INSTALLED).callMethod("ruleInstalled");
        builder.internalTransition().within(RULES_COMMANDS_SEND).on(RULES_REMOVED).callMethod("ruleRemoved");
        builder.internalTransition().within(RULES_COMMANDS_SEND).on(RULES_REINSTALLED)
                .callMethod("defaultRuleReinstalled");

        builder.externalTransition().from(RULES_COMMANDS_SEND).to(FINISHED_WITH_ERROR).on(TIMEOUT)
                .callMethod("commandsProcessingFailedByTimeout");
        builder.externalTransition().from(RULES_COMMANDS_SEND).to(FINISHED_WITH_ERROR).on(ERROR)
                .callMethod(FINISHED_WITH_ERROR_METHOD_NAME);

        builder.externalTransition().from(RULES_COMMANDS_SEND).to(METERS_COMMANDS_SEND).on(RULES_SYNCHRONIZED)
                .callMethod("sendMetersCommands");
        builder.internalTransition().within(METERS_COMMANDS_SEND).on(METERS_REMOVED).callMethod("meterRemoved");

        builder.externalTransition().from(METERS_COMMANDS_SEND).to(FINISHED_WITH_ERROR).on(TIMEOUT)
                .callMethod("commandsProcessingFailedByTimeout");
        builder.externalTransition().from(METERS_COMMANDS_SEND).to(FINISHED_WITH_ERROR).on(ERROR)
                .callMethod(FINISHED_WITH_ERROR_METHOD_NAME);
        builder.externalTransition().from(METERS_COMMANDS_SEND).to(FINISHED).on(NEXT)
                .callMethod(FINISHED_METHOD_NAME);

        return builder;
    }

    public String getKey() {
        return key;
    }

    protected void initialized(SwitchSyncState from, SwitchSyncState to,
                               SwitchSyncEvent event, Object context) {
        log.info("Key: {}, sync FSM initialized", key);
    }

    protected void computeInstallRules(SwitchSyncState from, SwitchSyncState to,
                                       SwitchSyncEvent event, Object context) {
        installRules = new ArrayList<>(validationResult.getValidateRulesResult().getMissingRules());
        validationResult.getValidateRulesResult().getMisconfiguredRules().stream()
                .filter(Cookie::isMaskedAsFlowCookie)
                .filter(rule -> !installRules.contains(rule))
                .forEach(installRules::add);

        if (!installRules.isEmpty()) {
            log.info("Key: {}, compute install rules", key);
            try {
                missingRules = commandBuilder.buildCommandsToSyncMissingRules(switchId, installRules);
            } catch (Exception e) {
                sendException(e);
            }
        }
    }

    protected void computeRemoveRules(SwitchSyncState from, SwitchSyncState to,
                                      SwitchSyncEvent event, Object context) {
        removeFlowRules = new ArrayList<>(validationResult.getValidateRulesResult().getExcessRules());

        if (request.isRemoveExcess() && !removeFlowRules.isEmpty()) {
            log.info("Key: {}, compute remove rules", key);
            try {
                excessRules = commandBuilder.buildCommandsToRemoveExcessRules(
                        switchId, validationResult.getFlowEntries(), removeFlowRules);
            } catch (Exception e) {
                sendException(e);
            }
        }
    }

    protected void computeRemoveMeters(SwitchSyncState from, SwitchSyncState to,
                                       SwitchSyncEvent event, Object context) {
        if (request.isRemoveExcess() && validationResult.isProcessMeters()) {
            ValidateMetersResult validateMetersResult = validationResult.getValidateMetersResult();

            if (!validateMetersResult.getExcessMeters().isEmpty()) {
                log.info("Key: {}, compute remove meters", key);
                try {
                    excessMeters = validateMetersResult.getExcessMeters().stream()
                            .map(MeterInfoEntry::getMeterId)
                            .collect(Collectors.toList());
                } catch (Exception e) {
                    sendException(e);
                }
            }
        }
    }

    protected void sendRulesCommands(SwitchSyncState from, SwitchSyncState to,
                                SwitchSyncEvent event, Object context) {
        if (missingRules.isEmpty() && excessRules.isEmpty()) {
            log.info("Key: {}, nothing to do with rules", key);
            fire(NEXT);
        }

        if (!missingRules.isEmpty()) {
            log.info("Key: {}, request to install switch rules has been sent", key);
            missingRulesPendingResponsesCount = missingRules.size();

            for (CommandData command : missingRules) {
                if (command instanceof BaseInstallFlow) {
                    carrier.sendCommandToSpeaker(key,
                            new InstallFlowForSwitchManagerRequest((BaseInstallFlow) command));
                } else {
                    carrier.sendCommandToSpeaker(key, command);
                }
            }
        }

        if (!excessRules.isEmpty()) {
            log.info("Key: {}, request to remove switch rules has been sent", key);
            excessRulesPendingResponsesCount = excessRules.size();

            for (RemoveFlow command : excessRules) {
                carrier.sendCommandToSpeaker(key, new RemoveFlowForSwitchManagerRequest(switchId, command));
            }
        }

        List<Long> reinstallRules = getReinstallDefaultRules();
        if (!reinstallRules.isEmpty()) {
            log.info("Key: {}, request to reinstall default switch rules has been sent", key);
            reinstallDefaultRulesPendingResponsesCount = reinstallRules.size();

            for (Long rule : reinstallRules) {
                carrier.sendCommandToSpeaker(key, new ReinstallDefaultFlowForSwitchManagerRequest(switchId, rule));
            }
        }

        continueIfRulesSynchronized();
    }

    private List<Long> getReinstallDefaultRules() {
        ValidateRulesResult validateRulesResult = validationResult.getValidateRulesResult();
        List<Long> reinstallRules = validateRulesResult.getMisconfiguredRules().stream()
                .filter(Cookie::isDefaultRule)
                .collect(Collectors.toList());
        return reinstallRules;
    }

    protected void sendMetersCommands(SwitchSyncState from, SwitchSyncState to,
                                      SwitchSyncEvent event, Object context) {
        if (excessMeters.isEmpty()) {
            log.info("Key: {}, nothing to do with meters", key);
            fire(NEXT);
        } else {
            log.info("Key: {}, request to remove switch meters has been sent", key);
            excessMetersPendingResponsesCount = excessMeters.size();

            for (Long meterId : excessMeters) {
                carrier.sendCommandToSpeaker(key, new DeleterMeterForSwitchManagerRequest(switchId, meterId));
            }
        }
    }

    protected void ruleInstalled(SwitchSyncState from, SwitchSyncState to,
                                 SwitchSyncEvent event, Object context) {
        log.info("Key: {}, switch rule installed", key);
        missingRulesPendingResponsesCount--;
        continueIfRulesSynchronized();
    }

    protected void ruleRemoved(SwitchSyncState from, SwitchSyncState to,
                               SwitchSyncEvent event, Object context) {
        log.info("Key: {}, switch rule removed", key);
        excessRulesPendingResponsesCount--;
        continueIfRulesSynchronized();
    }

    protected void defaultRuleReinstalled(SwitchSyncState from, SwitchSyncState to,
                                          SwitchSyncEvent event, Object context) {
        log.info("Key: {}, default switch rule reinstalled", key);
        FlowReinstallResponse response = (FlowReinstallResponse) context;

        Long removedRule = response.getRemovedRule();
        if (removedRule != null && !removeDefaultRules.contains(removedRule)) {
            removeDefaultRules.add(removedRule);
        }

        Long installedRule = response.getInstalledRule();
        if (installedRule != null && !installRules.contains(installedRule)) {
            installRules.add(installedRule);
        }

        reinstallDefaultRulesPendingResponsesCount--;
        continueIfRulesSynchronized();
    }

    protected void meterRemoved(SwitchSyncState from, SwitchSyncState to,
                                SwitchSyncEvent event, Object context) {
        log.info("Key: {}, switch meter removed", key);
        excessMetersPendingResponsesCount--;
        continueIfMetersCommandsDone();
    }

    private void continueIfRulesSynchronized() {
        if (missingRulesPendingResponsesCount == 0
                && excessRulesPendingResponsesCount == 0
                && reinstallDefaultRulesPendingResponsesCount == 0) {
            fire(RULES_SYNCHRONIZED);
        }
    }

    private void continueIfMetersCommandsDone() {
        if (excessMetersPendingResponsesCount == 0) {
            fire(NEXT);
        }
    }

    protected void commandsProcessingFailedByTimeout(SwitchSyncState from, SwitchSyncState to,
                                                     SwitchSyncEvent event, Object context) {
        ErrorData errorData = new ErrorData(ErrorType.OPERATION_TIMED_OUT, "Commands processing failed by timeout",
                "Error when processing switch commands");
        ErrorMessage errorMessage = new ErrorMessage(errorData, System.currentTimeMillis(), key);

        log.warn(ERROR_LOG_MESSAGE, key, errorData.getErrorMessage());
        carrier.response(key, errorMessage);
    }

    protected void finished(SwitchSyncState from, SwitchSyncState to,
                            SwitchSyncEvent event, Object context) {
        ValidateRulesResult validateRulesResult = validationResult.getValidateRulesResult();
        ValidateMetersResult validateMetersResult = validationResult.getValidateMetersResult();

        RulesSyncEntry rulesEntry = new RulesSyncEntry(validateRulesResult.getMissingRules(),
                validateRulesResult.getMisconfiguredRules(),
                validateRulesResult.getProperRules(),
                validateRulesResult.getExcessRules(),
                installRules,
                request.isRemoveExcess() ? union(removeFlowRules, removeDefaultRules) : removeDefaultRules);

        MetersSyncEntry metersEntry = null;
        if (validationResult.isProcessMeters()) {
            metersEntry = new MetersSyncEntry(validateMetersResult.getMissingMeters(),
                    validateMetersResult.getMisconfiguredMeters(),
                    validateMetersResult.getProperMeters(),
                    validateMetersResult.getExcessMeters(),
                    validateMetersResult.getMissingMeters(),
                    request.isRemoveExcess() ? validateMetersResult.getExcessMeters() : emptyList());
        }

        SwitchSyncResponse response = new SwitchSyncResponse(switchId, rulesEntry, metersEntry);
        InfoMessage message = new InfoMessage(response, System.currentTimeMillis(), key);

        carrier.cancelTimeoutCallback(key);
        carrier.response(key, message);
    }

    protected void finishedWithError(SwitchSyncState from, SwitchSyncState to,
                                     SwitchSyncEvent event, Object context) {
        ErrorMessage sourceError = (ErrorMessage) context;
        ErrorMessage message = new ErrorMessage(sourceError.getData(), System.currentTimeMillis(), key);

        log.error(ERROR_LOG_MESSAGE, key, message.getData().getErrorMessage());

        carrier.cancelTimeoutCallback(key);
        carrier.response(key, message);
    }

    private void sendException(Exception e) {
        ErrorData errorData = new SwitchSyncErrorData(switchId, ErrorType.INTERNAL_ERROR, e.getMessage(),
                "Error in SwitchSyncFsm");
        ErrorMessage errorMessage = new ErrorMessage(errorData, System.currentTimeMillis(), key);
        fire(ERROR, errorMessage);
    }

    public enum SwitchSyncState {
        INITIALIZED,
        COMPUTE_INSTALL_RULES,
        COMPUTE_REMOVE_RULES,
        COMPUTE_REMOVE_METERS,
        RULES_COMMANDS_SEND,
        METERS_COMMANDS_SEND,
        FINISHED_WITH_ERROR,
        FINISHED
    }

    public enum SwitchSyncEvent {
        NEXT,
        RULES_INSTALLED,
        RULES_REMOVED,
        RULES_REINSTALLED,
        RULES_SYNCHRONIZED,
        METERS_REMOVED,
        TIMEOUT,
        ERROR,
        FINISH
    }
}
