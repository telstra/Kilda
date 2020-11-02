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

package org.openkilda.floodlight.command.group;

import org.openkilda.floodlight.command.IOfErrorResponseHandler;
import org.openkilda.floodlight.command.SpeakerCommandProcessor;
import org.openkilda.floodlight.error.InvalidGroupIdException;
import org.openkilda.floodlight.error.SwitchErrorResponseException;
import org.openkilda.floodlight.error.SwitchGroupConflictException;
import org.openkilda.floodlight.error.SwitchIncorrectMirrorGroupException;
import org.openkilda.floodlight.error.SwitchMissingGroupException;
import org.openkilda.floodlight.error.UnsupportedSwitchOperationException;
import org.openkilda.floodlight.service.session.Session;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.GroupId;
import org.openkilda.model.MirrorConfig;
import org.openkilda.model.SwitchId;

import lombok.Getter;
import org.projectfloodlight.openflow.protocol.OFErrorMsg;
import org.projectfloodlight.openflow.protocol.OFGroupMod;
import org.projectfloodlight.openflow.protocol.OFGroupModFailedCode;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.errormsg.OFGroupModFailedErrorMsg;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Getter
public class GroupInstallCommand extends AbstractGroupInstall<GroupInstallReport> implements IOfErrorResponseHandler {

    private SpeakerCommandProcessor commandProcessor;

    public GroupInstallCommand(MessageContext messageContext, SwitchId switchId, MirrorConfig mirrorConfig) {
        super(messageContext, switchId, mirrorConfig);
    }

    @Override
    protected CompletableFuture<GroupInstallReport> makeExecutePlan(
            SpeakerCommandProcessor commandProcessor)
            throws UnsupportedSwitchOperationException, InvalidGroupIdException {
        this.commandProcessor = commandProcessor;

        ensureSwitchSupportGroups();
        ensureGroupIdValid();

        OFGroupMod groupMod = makeGroupAddMessage();
        return writeSwitchRequest(groupMod)
                .thenApply(ignore -> makeSuccessReport());
    }

    @Override
    protected GroupInstallReport makeReport(Exception error) {
        return new GroupInstallReport(this, error);
    }

    private GroupInstallReport makeSuccessReport() {
        return new GroupInstallReport(this);
    }

    protected CompletableFuture<Optional<OFMessage>> writeSwitchRequest(OFGroupMod request) {
        try (Session session = getSessionService().open(messageContext, getSw())) {
            return setupErrorHandler(session.write(request), this);
        }
    }

    @Override
    public CompletableFuture<Optional<OFMessage>> handleOfError(OFErrorMsg response) {
        CompletableFuture<Optional<OFMessage>> future = new CompletableFuture<>();
        if (!isInstallConflict(response)) {
            future.completeExceptionally(new SwitchErrorResponseException(getSw().getId(), String.format(
                    "Can't install group %s - %s", mirrorConfig.getGroupId(), response)));
            return future;
        }

        log.info("Group conflict detected sw:{} group:{}", getSw().getId(), mirrorConfig.getGroupId());
        GroupVerifyCommand verifyCommand = new GroupVerifyCommand(messageContext, switchId, mirrorConfig);
        propagateFutureResponse(
                future, commandProcessor.chain(verifyCommand)
                        .thenAccept(this::handleGroupVerify)
                        .thenApply(ignore -> Optional.empty()));
        return future;
    }

    private boolean isInstallConflict(OFErrorMsg response) {

        if (!(response instanceof OFGroupModFailedErrorMsg)) {
            return false;
        }
        return ((OFGroupModFailedErrorMsg) response).getCode() == OFGroupModFailedCode.GROUP_EXISTS;
    }

    private void handleGroupVerify(GroupVerifyReport report) {
        try {
            report.raiseError();
            log.warn(
                    "Reuse existing group sw:{} group:{} it match with requested group config",
                    getSw().getId(), mirrorConfig.getGroupId());
        } catch (SwitchIncorrectMirrorGroupException | SwitchMissingGroupException e) {
            throw maskCallbackException(new SwitchGroupConflictException(getSw().getId(), mirrorConfig.getGroupId()));
        } catch (Exception e) {
            throw maskCallbackException(e);
        }
    }

    private void ensureGroupIdValid() throws InvalidGroupIdException {
        GroupId groupId = mirrorConfig.getGroupId();
        if (groupId == null || groupId.getValue() <= 0L) {
            throw new InvalidGroupIdException(getSw().getId(), String.format(
                    "Invalid groupId value - expect not negative integer, got - %s", groupId));
        }
    }
}
