package org.openkilda.wfm.topology.flowhs.service;

import org.openkilda.model.Flow;
import org.openkilda.model.PathId;
import org.openkilda.wfm.share.flow.resources.FlowResources;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.Singular;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class DbResponse {
    protected UUID commandId;
    private boolean success;
    private Flow flow;
    @Singular
    private List<FlowResources> flowResources;
}
