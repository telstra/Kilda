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

import org.openkilda.floodlight.command.flow.egress.EgressFlowSegmentInstallCommand;
import org.openkilda.floodlight.command.flow.egress.EgressFlowSegmentRemoveCommand;
import org.openkilda.floodlight.command.flow.egress.EgressFlowSegmentVerifyCommand;
import org.openkilda.floodlight.command.flow.ingress.IngressFlowLoopSegmentInstallCommand;
import org.openkilda.floodlight.command.flow.ingress.IngressFlowLoopSegmentRemoveCommand;
import org.openkilda.floodlight.command.flow.ingress.IngressFlowLoopSegmentVerifyCommand;
import org.openkilda.floodlight.command.flow.ingress.IngressFlowSegmentInstallCommand;
import org.openkilda.floodlight.command.flow.ingress.IngressFlowSegmentRemoveCommand;
import org.openkilda.floodlight.command.flow.ingress.IngressFlowSegmentVerifyCommand;
import org.openkilda.floodlight.command.flow.ingress.OneSwitchFlowInstallCommand;
import org.openkilda.floodlight.command.flow.ingress.OneSwitchFlowRemoveCommand;
import org.openkilda.floodlight.command.flow.ingress.OneSwitchFlowVerifyCommand;
import org.openkilda.floodlight.command.flow.transit.TransitFlowLoopSegmentInstallCommand;
import org.openkilda.floodlight.command.flow.transit.TransitFlowLoopSegmentRemoveCommand;
import org.openkilda.floodlight.command.flow.transit.TransitFlowLoopSegmentVerifyCommand;
import org.openkilda.floodlight.command.flow.transit.TransitFlowSegmentInstallCommand;
import org.openkilda.floodlight.command.flow.transit.TransitFlowSegmentRemoveCommand;
import org.openkilda.floodlight.command.flow.transit.TransitFlowSegmentVerifyCommand;
import org.openkilda.floodlight.error.SessionErrorResponseException;
import org.openkilda.floodlight.error.SwitchNotFoundException;
import org.openkilda.floodlight.service.session.SessionService;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import org.projectfloodlight.openflow.protocol.OFErrorMsg;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.types.DatapathId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@JsonTypeInfo(use = Id.NAME, property = "clazz")
@JsonSubTypes({
        @Type(value = IngressFlowSegmentInstallCommand.class,
                name = "org.openkilda.floodlight.api.request.IngressFlowSegmentInstallRequest"),
        @Type(value = IngressFlowSegmentRemoveCommand.class,
                name = "org.openkilda.floodlight.api.request.IngressFlowSegmentRemoveRequest"),
        @Type(value = IngressFlowSegmentVerifyCommand.class,
                name = "org.openkilda.floodlight.api.request.IngressFlowSegmentVerifyRequest"),
        @Type(value = OneSwitchFlowInstallCommand.class,
                name = "org.openkilda.floodlight.api.request.OneSwitchFlowInstallRequest"),
        @Type(value = OneSwitchFlowRemoveCommand.class,
                name = "org.openkilda.floodlight.api.request.OneSwitchFlowRemoveRequest"),
        @Type(value = OneSwitchFlowVerifyCommand.class,
                name = "org.openkilda.floodlight.api.request.OneSwitchFlowVerifyRequest"),
        @Type(value = TransitFlowSegmentInstallCommand.class,
                name = "org.openkilda.floodlight.api.request.TransitFlowSegmentInstallRequest"),
        @Type(value = TransitFlowSegmentRemoveCommand.class,
                name = "org.openkilda.floodlight.api.request.TransitFlowSegmentRemoveRequest"),
        @Type(value = TransitFlowSegmentVerifyCommand.class,
                name = "org.openkilda.floodlight.api.request.TransitFlowSegmentVerifyRequest"),
        @Type(value = EgressFlowSegmentInstallCommand.class,
                name = "org.openkilda.floodlight.api.request.EgressFlowSegmentInstallRequest"),
        @Type(value = EgressFlowSegmentRemoveCommand.class,
                name = "org.openkilda.floodlight.api.request.EgressFlowSegmentRemoveRequest"),
        @Type(value = EgressFlowSegmentVerifyCommand.class,
                name = "org.openkilda.floodlight.api.request.EgressFlowSegmentVerifyRequest"),

        @Type(value = IngressFlowLoopSegmentInstallCommand.class,
                name = "org.openkilda.floodlight.api.request.IngressFlowLoopSegmentInstallRequest"),
        @Type(value = IngressFlowLoopSegmentRemoveCommand.class,
                name = "org.openkilda.floodlight.api.request.IngressFlowLoopSegmentRemoveRequest"),
        @Type(value = IngressFlowLoopSegmentVerifyCommand.class,
                name = "org.openkilda.floodlight.api.request.IngressFlowLoopSegmentVerifyRequest"),
        @Type(value = TransitFlowLoopSegmentInstallCommand.class,
                name = "org.openkilda.floodlight.api.request.TransitFlowLoopSegmentInstallRequest"),
        @Type(value = TransitFlowLoopSegmentRemoveCommand.class,
                name = "org.openkilda.floodlight.api.request.TransitFlowLoopSegmentRemoveRequest"),
        @Type(value = TransitFlowLoopSegmentVerifyCommand.class,
                name = "org.openkilda.floodlight.api.request.TransitFlowLoopSegmentVerifyRequest")
})
@Getter
public abstract class SpeakerCommand<T extends SpeakerCommandReport> {
    protected final Logger log = LoggerFactory.getLogger(getClass());

    // payload
    protected final MessageContext messageContext;
    protected final SwitchId switchId;
    protected final UUID commandId;

    @Setter
    private Instant commandArrivedAt;

    // operation data
    @Getter(AccessLevel.PROTECTED)
    private SessionService sessionService;

    @Getter(AccessLevel.PROTECTED)
    private IOFSwitch sw;

    public SpeakerCommand(MessageContext messageContext, SwitchId switchId) {
        this(messageContext, switchId, null);
    }

    public SpeakerCommand(@NonNull MessageContext messageContext, @NonNull SwitchId switchId, UUID commandId) {
        this.messageContext = messageContext;
        this.switchId = switchId;
        this.commandId = commandId;
    }

    /**
     * Schedule command execution, produce future object capable to return command result and/or chain more execution
     * of other commands.
     */
    public CompletableFuture<T> execute(SpeakerCommandProcessor commandProcessor) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            validate();
            setup(commandProcessor.getModuleContext());
            makeExecutePlan(commandProcessor)
                    .whenComplete((result, error) -> {
                        if (error == null) {
                            future.complete(result);
                        } else {
                            handleError(future, unwrapError(error));
                        }
                    });
        } catch (Exception e) {
            handleError(future, e);
        }
        return future;
    }

    protected abstract CompletableFuture<T> makeExecutePlan(SpeakerCommandProcessor commandProcessor) throws Exception;

    protected abstract T makeReport(Exception error);

    private void handleError(CompletableFuture<T> future, Throwable error) {
        if (error instanceof Exception) {
            future.complete(makeReport((Exception) error));
        } else {
            future.completeExceptionally(error);
        }
    }

    protected void validate() {
        // do nothing by default, inheritors can perform command fields consistency check here
    }

    protected void setup(FloodlightModuleContext moduleContext) throws Exception {
        IOFSwitchService ofSwitchManager = moduleContext.getServiceImpl(IOFSwitchService.class);
        sessionService = moduleContext.getServiceImpl(SessionService.class);

        DatapathId dpId = DatapathId.of(switchId.toLong());
        sw = ofSwitchManager.getActiveSwitch(dpId);
        if (sw == null) {
            throw new SwitchNotFoundException(dpId);
        }
    }

    protected CompletableFuture<Optional<OFMessage>> setupErrorHandler(
            CompletableFuture<Optional<OFMessage>> future, IOfErrorResponseHandler handler) {
        CompletableFuture<Optional<OFMessage>> branch = new CompletableFuture<>();

        future.whenComplete((response, error) -> {
            if (error == null) {
                branch.complete(response);
            } else {
                Throwable actualError = unwrapError(error);
                if (actualError instanceof SessionErrorResponseException) {
                    OFErrorMsg errorResponse = ((SessionErrorResponseException) error).getErrorResponse();
                    propagateFutureResponse(branch, handler.handleOfError(errorResponse));
                } else {
                    branch.completeExceptionally(actualError);
                }
            }
        });
        return branch;
    }

    protected <K> void propagateFutureResponse(CompletableFuture<K> outerStream, CompletableFuture<K> nested) {
        nested.whenComplete((result, error) -> {
            if (error == null) {
                outerStream.complete(result);
            } else {
                outerStream.completeExceptionally(error);
            }
        });
    }

    protected RuntimeException maskCallbackException(Throwable e) {
        if (e instanceof CompletionException) {
            return (CompletionException) e;
        }
        return new CompletionException(e);
    }

    protected Throwable unwrapError(Throwable error) {
        if (error == null) {
            return null;
        }

        if (error instanceof CompletionException) {
            return error.getCause();
        }
        return error;
    }
}
