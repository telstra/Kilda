package org.openkilda.wfm.topology.flowhs.service;

import org.openkilda.model.Flow;
import org.openkilda.wfm.share.flow.resources.FlowResources;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Singular;

import java.util.List;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@Builder
public class DbErrorResponse extends DbResponse {
    private String errorMessage;
    private ErrorCode errorCode;

    public DbErrorResponse(UUID commandId, Flow flow, List<FlowResources> flowResources, String errorMessage,
                           ErrorCode errorCode) {
        super(commandId, false, flow, flowResources);
        this.errorMessage = errorMessage;
        this.errorCode = errorCode;
    }

    public enum ErrorCode {
        NOT_FOUND,
        INTERNAL_ERROR,
        UNKNOWN;
    }
}
