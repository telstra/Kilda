package org.openkilda.functionaltests.helpers

enum FlowHistoryEvent {

    REROUTE_SUCCESS(FlowHistoryAction.FLOW_REROUTING, FlowHistoryAction.SET_THE_FLOW_STATUS_TO_UP),
    REROUTE_FAIL(FlowHistoryAction.FLOW_REROUTING, FlowHistoryAction.SET_THE_FLOW_STATUS_TO_DOWN),
    REROUTE_FAIL_PROTECTED_PATH(FlowHistoryAction.FLOW_REROUTING, FlowHistoryAction.FLOW_WAS_VALIDATED_SUCCESSFULLY),

    public final FlowHistoryAction initAction
    public final FlowHistoryAction finalAction

    FlowHistoryEvent(FlowHistoryAction initAction, FlowHistoryAction finalAction){
        this.initAction = initAction
        this.finalAction = finalAction
    }
}