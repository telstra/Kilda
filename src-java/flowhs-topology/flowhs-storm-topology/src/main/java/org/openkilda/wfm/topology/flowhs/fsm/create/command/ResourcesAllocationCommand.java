package org.openkilda.wfm.topology.flowhs.fsm.create.command;

import org.openkilda.wfm.topology.flowhs.fsm.create.FlowCreateContext;
import org.openkilda.wfm.topology.flowhs.service.DbCommand;

import lombok.Value;

@Value
public class ResourcesAllocationCommand implements DbCommand {
    FlowCreateContext context;
    String flowId;
}
