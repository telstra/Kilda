package org.openkilda.functionaltests.helpers

enum FlowHistoryAction {
    //action
    FLOW_CREATING("Flow creating"),
    FLOW_UPDATING("Flow updating"),
    FLOW_REROUTING("Flow rerouting"),

    //nested action
    SET_THE_FLOW_STATUS_TO_UP("Set the flow status to UP."),
    SET_THE_FLOW_STATUS_TO_DOWN("Set the flow status to down."),
    FLOW_WAS_VALIDATED_SUCCESSFULLY("Flow was validated successfully"),

    public final String action

    FlowHistoryAction(String action) {
        this.action = action
    }

    @Override
    String toString() {
        return action
    }
}
