package org.openkilda.functionaltests.spec.flows

import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.testing.Constants.NON_EXISTENT_FLOW_ID

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.tags.IterationTag
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.messaging.command.switches.DeleteRulesAction
import org.openkilda.messaging.error.MessageError
import org.openkilda.model.SwitchId
import org.openkilda.northbound.dto.v1.flows.FlowValidationDto

import groovy.util.logging.Slf4j
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Ignore
import spock.lang.Narrative
import spock.lang.Unroll

@Slf4j
@Narrative("""The specification covers the following scenarios:
              -- Deleting flow rule from a switch and check if switch and flow validation fails.
              -- Failed switch validation should not cause validation errors for flows with all rules in place.
              Test case permutations (full-factored):
                 - forward and reverse flows
                 - ingress, transit and egress switches
                 - Single switch, two switch and three+ switch flow spans.
            """)
@Tags([LOW_PRIORITY])
class FlowValidationNegativeSpec extends HealthCheckSpecification {

    @Unroll
    @IterationTag(tags = [SMOKE], iterationNameRegex = /reverse/)
    def "Flow and switch validation should fail in case of missing rules with #flowConfig configuration"() {
        given: "Two flows with #flowConfig configuration"
        def flowToBreak = flowHelper.randomFlow(switchPair, false)
        def intactFlow = flowHelper.randomFlow(switchPair, false, [flowToBreak])

        flowHelper.addFlow(flowToBreak)
        flowHelper.addFlow(intactFlow)

        and: "Both flows have the same switches in path"
        def damagedFlowSwitches = pathHelper.getInvolvedSwitches(flowToBreak.id)*.dpId
        def intactFlowSwitches = pathHelper.getInvolvedSwitches(intactFlow.id)*.dpId
        assert damagedFlowSwitches.equals(intactFlowSwitches)

        when: "#flowType flow rule from first flow on #switchNo switch gets deleted"
        def cookieToDelete = flowType == "forward" ? database.getFlow(flowToBreak.id).forwardPath.cookie.value :
                database.getFlow(flowToBreak.id).reversePath.cookie.value
        SwitchId damagedSwitch = damagedFlowSwitches[item]
        northbound.deleteSwitchRules(damagedSwitch, cookieToDelete)

        then: "Intact flow should be validated successfully"
        def intactFlowValidation = northbound.validateFlow(intactFlow.id)
        intactFlowValidation.each { direction ->
            assert direction.discrepancies.empty
            assert direction.asExpected
        }

        and: "Damaged #flowType flow validation should fail, while other direction should be validated successfully"
        def brokenFlowValidation = northbound.validateFlow(flowToBreak.id)
        brokenFlowValidation.findAll { it.discrepancies.empty && it.asExpected }.size() == 1
        def damagedDirection = brokenFlowValidation.findAll { !it.discrepancies.empty && !it.asExpected }
        damagedDirection.size() == 1

        and: "Flow rule discrepancy should contain dpID of the affected switch and cookie of the damaged flow"
        def rules = findRulesDiscrepancies(damagedDirection[0])
        rules.size() == 1
        rules[damagedSwitch.toString()] == cookieToDelete.toString()

        and: "Affected switch should have one missing rule with the same cookie as the damaged flow"
        def switchValidationResult = northbound.validateSwitchRules(damagedSwitch)
        switchValidationResult.missingRules.size() == 1
        switchValidationResult.missingRules[0] == cookieToDelete

        and: "There should be no excess rules on the affected switch"
        switchValidationResult.excessRules.size() == 0

        and: "Validation of non-affected switches (if any) should succeed"
        if (damagedFlowSwitches.size() > 1) {
            def nonAffectedSwitches = damagedFlowSwitches.findAll { it != damagedFlowSwitches[item] }
            assert nonAffectedSwitches.every { sw -> northbound.validateSwitchRules(sw).missingRules.size() == 0 }
            assert nonAffectedSwitches.every { sw -> northbound.validateSwitchRules(sw).excessRules.size() == 0 }
        }

        and: "Delete the flows"
        [flowToBreak.id, intactFlow.id].each { flowHelper.deleteFlow(it) }

        where:
        flowConfig      | switchPair                                        | item | switchNo | flowType
        "single switch" | getTopologyHelper().getSingleSwitchPair()         | 0    | "single" | "forward"
        "single switch" | getTopologyHelper().getSingleSwitchPair()         | 0    | "single" | "reverse"
        "neighbouring"  | getTopologyHelper().getNeighboringSwitchPair()    | 0    | "first"  | "forward"
        "neighbouring"  | getTopologyHelper().getNeighboringSwitchPair()    | 0    | "first"  | "reverse"
        "neighbouring"  | getTopologyHelper().getNeighboringSwitchPair()    | 1    | "last"   | "forward"
        "neighbouring"  | getTopologyHelper().getNeighboringSwitchPair()    | 1    | "last"   | "reverse"
        "transit"       | getTopologyHelper().getNotNeighboringSwitchPair() | 0    | "first"  | "forward"
        "transit"       | getTopologyHelper().getNotNeighboringSwitchPair() | 0    | "first"  | "reverse"
        "transit"       | getTopologyHelper().getNotNeighboringSwitchPair() | 1    | "middle" | "forward"
        "transit"       | getTopologyHelper().getNotNeighboringSwitchPair() | 1    | "middle" | "reverse"
        "transit"       | getTopologyHelper().getNotNeighboringSwitchPair() | -1   | "last"   | "forward"
        "transit"       | getTopologyHelper().getNotNeighboringSwitchPair() | -1   | "last"   | "reverse"
    }

    @Unroll
    def "Unable to #action a non-existent flow"() {
        when: "Trying to #action a non-existent flow"
        northbound."${action}Flow"(NON_EXISTENT_FLOW_ID)

        then: "An error is received (404 code)"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        exc.responseBodyAsString.to(MessageError).errorMessage == message

        where:
        action        | message
        "get"         | "Can not get flow: Flow $NON_EXISTENT_FLOW_ID not found"
        "reroute"     | "Could not reroute flow: Flow $NON_EXISTENT_FLOW_ID not found"
        "validate"    | "Could not validate flow: Flow $NON_EXISTENT_FLOW_ID not found"
        "synchronize" | "Could not reroute flow: Flow $NON_EXISTENT_FLOW_ID not found"
    }

    @Ignore
    def "Able to detect discrepancies for a flow with protected path"() {
        when: "Create a flow with protected path"
        def switchPair = topologyHelper.getNotNeighboringSwitchPair()
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        then: "Flow with protected path is created"
        northbound.getFlowPath(flow.id).protectedPath

        and: "Validation of flow with protected path must be successful"
        northbound.validateFlow(flow.id).each { direction ->
            assert direction.discrepancies.empty
        }

        when: "Delete rule of protected path on the srcSwitch"
        def flowPathInfo = northbound.getFlowPath(flow.id)
        def protectedPath = flowPathInfo.protectedPath.forwardPath
        def rules = northbound.getSwitchRules(switchPair.src.dpId).flowEntries.findAll {
            !org.openkilda.model.Cookie.isDefaultRule(it.cookie)
        }

        def ruleToDelete = rules.find {
            it.instructions?.applyActions?.flowOutput == protectedPath[0].inputPort.toString() &&
                    it.match.inPort == protectedPath[0].outputPort.toString()
        }.cookie

        northbound.deleteSwitchRules(switchPair.src.dpId, ruleToDelete)

        then: "Flow validate detects discrepancies"
        //TODO(andriidovhan) try to extend this test when the issues/2302 is fixed
        def responseValidateFlow = northbound.validateFlow(flow.id).findAll { !it.discrepancies.empty }*.discrepancies
        assert responseValidateFlow.size() == 1
        responseValidateFlow[0].expectedValue[0] == ruleToDelete.toString()

        when: "Delete all rules except default on the all involved switches"
        def mainPath = flowPathInfo.forwardPath
        def involvedSwitchIds = (mainPath*.switchId + protectedPath*.switchId).unique()
        involvedSwitchIds.each { switchId ->
            northbound.deleteSwitchRules(switchId, DeleteRulesAction.IGNORE_DEFAULTS)
        }

        then: "Flow validate detects discrepancies for all deleted rules"
        def responseValidateFlow2 = northbound.validateFlow(flow.id).findAll { !it.discrepancies.empty }*.discrepancies
        assert responseValidateFlow2.size() == 4

        and: "Cleanup: delete the flow"
        flowHelper.deleteFlow(flow.id)
    }

    /**
     * Parses discrepancies in the flow validation result
     * @param flow - FlowValidationDto
     * @return Map in dpId:cookie format
     */
    Map<String, String> findRulesDiscrepancies(FlowValidationDto flow) {
        def discrepancies = flow.discrepancies.findAll { it.field != "meterId" }
        def cookies = [:]
        discrepancies.each { disc ->
            def dpId = (disc.rule =~ /sw:(.*?),/)[0][1]
            def cookie = (disc.rule =~ /ck:(.*?),/)[0][1]
            cookies[dpId] = cookie
        }
        return cookies
    }
}
