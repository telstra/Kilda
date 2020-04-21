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

package org.openkilda.floodlight.command.flow.ingress;

import org.openkilda.floodlight.command.SpeakerCommandProcessor;
import org.openkilda.floodlight.command.SpeakerCommandReport;
import org.openkilda.floodlight.command.flow.FlowSegmentCommand;
import org.openkilda.floodlight.command.flow.FlowSegmentReport;
import org.openkilda.floodlight.command.flow.ingress.of.IngressFlowModFactory;
import org.openkilda.floodlight.command.meter.MeterInstallCommand;
import org.openkilda.floodlight.command.meter.MeterInstallDryRunCommand;
import org.openkilda.floodlight.command.meter.MeterInstallReport;
import org.openkilda.floodlight.command.meter.MeterRemoveCommand;
import org.openkilda.floodlight.command.meter.MeterRemoveReport;
import org.openkilda.floodlight.command.meter.MeterVerifyCommand;
import org.openkilda.floodlight.command.meter.MeterVerifyReport;
import org.openkilda.floodlight.error.UnsupportedSwitchOperationException;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.floodlight.model.RulesContext;
import org.openkilda.floodlight.service.session.Session;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.MeterConfig;
import org.openkilda.model.MeterId;
import org.openkilda.model.SwitchFeature;
import org.openkilda.model.SwitchId;
import org.openkilda.model.of.MeterSchema;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Getter
public abstract class IngressFlowSegmentBase extends FlowSegmentCommand {
    // payload
    protected final FlowEndpoint endpoint;
    protected final MeterConfig meterConfig;
    protected final SwitchId egressSwitchId;
    protected final RulesContext rulesContext;

    // operation data
    @Getter(AccessLevel.PROTECTED)
    @Setter(AccessLevel.PROTECTED)
    private IngressFlowModFactory flowModFactory;

    IngressFlowSegmentBase(
            MessageContext messageContext, SwitchId switchId, UUID commandId, FlowSegmentMetadata metadata,
            @NonNull FlowEndpoint endpoint, MeterConfig meterConfig, @NonNull SwitchId egressSwitchId,
            RulesContext rulesContext) {
        super(messageContext, switchId, commandId, metadata);
        this.endpoint = endpoint;
        this.meterConfig = meterConfig;
        this.egressSwitchId = egressSwitchId;
        this.rulesContext = rulesContext;
    }

    @Override
    protected void setup(FloodlightModuleContext moduleContext) throws Exception {
        super.setup(moduleContext);

        ensureSwitchEnoughCapabilities();

        setupFlowModFactory();
    }

    protected abstract void setupFlowModFactory();

    protected CompletableFuture<FlowSegmentReport> makeInstallPlan(SpeakerCommandProcessor commandProcessor) {
        CompletableFuture<MeterId> future = CompletableFuture.completedFuture(null);
        if (meterConfig != null) {
            future = planMeterInstall(commandProcessor)
                    .thenApply(this::handleMeterReport);
        }
        return future.thenCompose(this::planOfFlowsInstall);
    }

    protected CompletableFuture<FlowSegmentReport> makeRemovePlan(SpeakerCommandProcessor commandProcessor) {
        CompletableFuture<MeterId> future = CompletableFuture.completedFuture(null);
        if (meterConfig != null) {
            future = planMeterDryRun(commandProcessor)
                    .thenApply(this::handleMeterReport);
        }

        return future.thenCompose(this::planOfFlowsRemove)
                .thenCompose(effectiveMeterId -> planMeterRemove(commandProcessor, effectiveMeterId))
                .thenApply(ignore -> makeSuccessReport());
    }

    protected CompletableFuture<FlowSegmentReport> makeVerifyPlan(SpeakerCommandProcessor commandProcessor) {
        CompletableFuture<MeterId> future = CompletableFuture.completedFuture(null);
        if (meterConfig != null) {
            future = planMeterVerify(commandProcessor)
                    .thenApply(this::handleMeterReport);
        }
        return future.thenCompose(this::planOfFlowsVerify);
    }

    private CompletableFuture<MeterInstallReport> planMeterInstall(SpeakerCommandProcessor commandProcessor) {
        MeterInstallCommand meterCommand = new MeterInstallCommand(messageContext, switchId, meterConfig);
        return commandProcessor.chain(meterCommand);
    }

    private CompletableFuture<Void> planMeterRemove(
            SpeakerCommandProcessor commandProcessor, MeterId effectiveMeterId) {
        if (effectiveMeterId == null) {
            if (meterConfig != null) {
                log.info(
                        "Do not remove meter {} on {} - switch do not support meters (i.e. it was not installed "
                                + "during flow segment install stage",
                        meterConfig, switchId);
            }
            return CompletableFuture.completedFuture(null);
        }

        MeterRemoveCommand removeCommand = new MeterRemoveCommand(messageContext, switchId, meterConfig.getId());
        return commandProcessor.chain(removeCommand)
                .thenAccept(this::handleMeterRemoveReport);
    }

    private CompletableFuture<MeterVerifyReport> planMeterVerify(SpeakerCommandProcessor commandProcessor) {
        MeterVerifyCommand meterVerify = new MeterVerifyCommand(messageContext, switchId, meterConfig);
        return commandProcessor.chain(meterVerify);
    }

    private CompletableFuture<MeterInstallReport> planMeterDryRun(SpeakerCommandProcessor commandProcessor) {
        MeterInstallDryRunCommand meterDryRun = new MeterInstallDryRunCommand(messageContext, switchId, meterConfig);
        return commandProcessor.chain(meterDryRun);
    }

    private CompletableFuture<FlowSegmentReport> planOfFlowsInstall(MeterId effectiveMeterId) {
        List<OFFlowMod> ofMessages = makeIngressModMessages(effectiveMeterId);
        List<CompletableFuture<Optional<OFMessage>>> writeResults = new ArrayList<>(ofMessages.size());
        try (Session session = getSessionService().open(messageContext, getSw())) {
            for (OFFlowMod message : ofMessages) {
                writeResults.add(session.write(message));
            }
        }
        return CompletableFuture.allOf(writeResults.toArray(new CompletableFuture[0]))
                .thenApply(ignore -> makeSuccessReport());
    }

    private CompletableFuture<MeterId> planOfFlowsRemove(MeterId effectiveMeterId) {
        List<OFFlowMod> ofMessages = new ArrayList<>(makeIngressModMessages(effectiveMeterId));

        List<CompletableFuture<?>> requests = new ArrayList<>(ofMessages.size());
        try (Session session = getSessionService().open(messageContext, getSw())) {
            for (OFFlowMod message : ofMessages) {
                requests.add(session.write(message));
            }
        }

        return CompletableFuture.allOf(requests.toArray(new CompletableFuture<?>[0]))
                .thenApply(ignore -> effectiveMeterId);
    }

    private CompletableFuture<FlowSegmentReport> planOfFlowsVerify(MeterId effectiveMeterId) {
        return makeVerifyPlan(makeIngressModMessages(effectiveMeterId));
    }

    private MeterId handleMeterReport(MeterInstallReport report) {
        ensureMeterSuccess(report);
        return report.getMeterId()
                .orElse(null);
    }

    private MeterId handleMeterReport(MeterVerifyReport report) {
        ensureMeterSuccess(report);
        return report.getSchema()
                .map(MeterSchema::getMeterId)
                .orElse(null);
    }

    private void handleMeterRemoveReport(MeterRemoveReport report) {
        try {
            report.raiseError();
        } catch (UnsupportedSwitchOperationException e) {
            log.info("Do not remove meter id {} from {} - {}", meterConfig.getId(), switchId, e.getMessage());
        } catch (Exception e) {
            throw maskCallbackException(e);
        }
    }

    protected List<OFFlowMod> makeIngressModMessages(MeterId effectiveMeterId) {
        List<OFFlowMod> ofMessages = new ArrayList<>();
        if (FlowEndpoint.isVlanIdSet(endpoint.getVlanId())) {
            ofMessages.add(flowModFactory.makeOuterVlanOnlyForwardMessage(effectiveMeterId));
        } else {
            ofMessages.add(flowModFactory.makeDefaultPortFlowMatchAndForwardMessage(effectiveMeterId));
        }
        return ofMessages;
    }

    protected List<OFFlowMod> makeIngressServer42FlowRttModMessages(MeterId effectiveMeterId) {
        List<OFFlowMod> ofMessages = new ArrayList<>();
        if (FlowEndpoint.isVlanIdSet(endpoint.getVlanId())) {
            ofMessages.add(flowModFactory.makeOuterVlanOnlyServer42FlowRttMessage(effectiveMeterId));
        } else {
            ofMessages.add(flowModFactory.makeDefaultPortServer42FlowRttMessage(effectiveMeterId));
        }
        return ofMessages;
    }

    protected Set<SwitchFeature> getRequiredFeatures() {
        return new HashSet<>();
    }

    protected void ensureMeterSuccess(SpeakerCommandReport report) {
        try {
            report.raiseError();
        } catch (UnsupportedSwitchOperationException e) {
            log.info(
                    "Meter id {} on {} ignored by command {} - - {}",
                    meterConfig.getId(), switchId, getClass().getCanonicalName(), e.getMessage());
            // switch do not support meters, setup rules without meter
        } catch (Exception e) {
            throw maskCallbackException(e);
        }
    }

    private void ensureSwitchEnoughCapabilities() throws UnsupportedSwitchOperationException {
        Set<SwitchFeature> required = getRequiredFeatures();
        required.removeAll(getSwitchFeatures());
        if (required.isEmpty()) {
            return;
        }

        String requiredAsString = required.stream()
                .map(SwitchFeature::name)
                .sorted()
                .collect(Collectors.joining(", "));
        throw new UnsupportedSwitchOperationException(
                getSw().getId(), String.format("Switch %s do not support %s", switchId, requiredAsString));
    }
}
