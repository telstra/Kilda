package org.openkilda.functionaltests.spec.flows

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.VIRTUAL
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_ACTION
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_FAIL
import static org.openkilda.testing.Constants.PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.payload.flow.FlowPathPayload
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.testing.model.topology.TopologyDefinition.Isl
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import spock.lang.Ignore
import spock.lang.Narrative

import java.util.concurrent.TimeUnit

@Narrative("""
This test verifies that we do not perform a reroute as soon as we receive a reroute request (we talk only about
automatic reroutes here; manual reroutes are still performed instantly). Instead, system waits for 'reroute.delay'
seconds and if no new reroute requests are issued, it performs ONE reroute for each requested flowId. If ANY new reroute
is issued during 'reroute.delay' the timer is refreshed. 
System should stop refreshing the timer if 'reroute.hardtimeout' is reached and perform all the queued reroutes (unique 
for each flowId).
""")
@Slf4j
@Tags(VIRTUAL) //may be unstable on hardware. not tested
class ThrottlingRerouteSpec extends HealthCheckSpecification {

    @Value('${reroute.hardtimeout}')
    int rerouteHardTimeout

    @Tags(SMOKE)
    def "Reroute is not performed while new reroutes are being issued"() {
        given: "Multiple flows that can be rerouted independently (use short unique paths)"
        /* Here we will pick only short flows that consist of 2 switches, so that we can maximize amount of unique
        flows found*/
        def switchPairs = topologyHelper.getAllNeighboringSwitchPairs()

        assumeTrue(switchPairs.size() > 3, "Topology is too small to run this test")
        def flows = switchPairs.take(5).collect { switchPair ->
            def flow = flowHelperV2.randomFlow(switchPair)
            flowHelperV2.addFlow(flow)
            flow
        }
        def flowPaths = flows.collect { northbound.getFlowPath(it.flowId) }

        when: "All flows break one by one"
        def brokenIsls = flowPaths.collect {
            breakFlow(it)
            //don't sleep here, since there is already an antiFlapMin delay between actual port downs
        }
        /*At this point all reroute triggers have happened. Save this time in order to calculate when the actual
        reroutes will happen (time triggers stopped + reroute delay seconds)*/
        def rerouteTriggersEnd = new Date()
        def untilReroutesBegin = { rerouteTriggersEnd.time + rerouteDelay * 1000 - new Date().time }

        then: "The oldest broken flow is still not rerouted before rerouteDelay run out"
        sleep(untilReroutesBegin() - (long) (rerouteDelay * 1000 * 0.3)) //check after 70% of rerouteDelay has passed
        northbound.getFlowHistory(flows.first().flowId).last().action == "Flow creating" //reroute didn't start yet

        and: "The oldest broken flow is rerouted when the rerouteDelay runs out"
        def waitTime = untilReroutesBegin() / 1000.0 + PATH_INSTALLATION_TIME * 2
        Wrappers.wait(waitTime) {
            //Flow should go DOWN or change path on reroute. In our case it doesn't matter which of these happen.
            assert (northboundV2.getFlowStatus(flows.first().flowId).status == FlowState.DOWN &&
                    northbound.getFlowHistory(flows.first().flowId).find {
                        it.action == REROUTE_ACTION && it.taskId =~ (/.+ : retry #1/) })||
                    northbound.getFlowPath(flows.first().flowId) != flowPaths.first()
        }

        and: "The rest of the flows are rerouted too"
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            flowPaths[1..-1].each { flowPath ->
                assert (northboundV2.getFlowStatus(flowPath.id).status == FlowState.DOWN &&
                        northbound.getFlowHistory(flowPath.id).find {
                    it.action == REROUTE_ACTION && it.taskId =~ (/.+ : retry #1/)
                })  ||
                        (northbound.getFlowPath(flowPath.id) != flowPath &&
                                northboundV2.getFlowStatus(flowPath.id).status == FlowState.UP)
            }
        }

        and: "cleanup: restore broken paths and delete flows"
        flows.each { flowHelperV2.deleteFlow(it.flowId) }
        brokenIsls.each {
            antiflap.portUp(it.srcSwitch.dpId, it.srcPort)
        }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
    }

    @Ignore("Unstable")
    def "Reroute is performed after hard timeout even though new reroutes are still being issued"() {
        given: "Multiple flows that can be rerouted independently (use short unique paths)"
        /* Here we will pick only short flows that consist of 2 switches, so that we can maximize amount of unique
        flows found*/
        def switchPairs = topologyHelper.getAllNeighboringSwitchPairs()

        /*due to port anti-flap we cannot continuously quickly reroute one single flow until we reach hardTimeout,
        thus we need certain amount of flows to continuously provide reroute triggers for them in a loop.
        We can re-trigger a reroute on the same flow after antiflapCooldown + antiflapMin seconds*/
        int minFlowsRequired = (int) Math.min(rerouteHardTimeout / antiflapMin, antiflapCooldown / antiflapMin + 1) + 1
        assumeTrue(switchPairs.size() >= minFlowsRequired, "Topology is too small to run this test")
        def flows = switchPairs.collect { switchPair ->
            def flow = flowHelperV2.randomFlow(switchPair)
            flowHelperV2.addFlow(flow)
            flow
        }
        def flowPaths = flows.collect { northbound.getFlowPath(it.flowId) }

        when: "All flows begin to continuously reroute in a loop"
        def stop = false //flag to abort all reroute triggers
        def rerouteTriggers = flowPaths.collect { flowPath ->
            new Thread({
                while (!stop) {
                    def brokenIsl = breakFlow(flowPath)
                    antiflap.portUp(brokenIsl.srcSwitch.dpId, brokenIsl.srcPort)
                    Wrappers.wait(antiflapCooldown + WAIT_OFFSET) {
                        assert northbound.getLink(brokenIsl).state == IslChangeType.DISCOVERED
                    }
                }
            })
        }
        //do no start simultaneously, so that reroutes are triggered every X seconds (not at once)
        def starter = new Thread({
            rerouteTriggers.each {
                it.start()
                TimeUnit.SECONDS.sleep(rerouteDelay)
            }
        })
        starter.start()
        def rerouteTriggersStart = new Date()
        def hardTimeoutTime = rerouteTriggersStart.time + (antiflapMin + rerouteHardTimeout) * 1000
        def untilHardTimeoutEnds = { hardTimeoutTime - new Date().time }
        log.debug("Expect hard timeout at ${new Date(hardTimeoutTime)}")

        then: "Right until hard timeout should run out no flow reroutes happen"
        //check until 80% of hard timeout runs out
        while (System.currentTimeMillis() < rerouteTriggersStart.time + rerouteHardTimeout * 1000 * 0.8) {
            flowPaths.each { flowPath ->
                assert northboundV2.getFlowStatus(flowPath.id).status == FlowState.UP &&
                        northbound.getFlowPath(flowPath.id) == flowPath
            }
        }

        and: "Flows should start to reroute after hard timeout, eventhough reroutes are still being triggered"
        rerouteTriggers.any { it.alive }
        def flowPathsClone = flowPaths.collect()
        Wrappers.wait(untilHardTimeoutEnds() + WAIT_OFFSET) {
            flowPathsClone.removeAll { flowPath ->
                (northboundV2.getFlowStatus(flowPath.id).status == FlowState.DOWN && northbound
                        .getFlowHistory(flowPath.id).last().payload.find { it.action == REROUTE_FAIL }) ||
                        northbound.getFlowPath(flowPath.id) != flowPath
            }
            assert flowPathsClone.empty
        }

        and: "cleanup: delete flows"
        flows.each { flowHelperV2.deleteFlow(it.flowId) }

        cleanup: "revive all paths"
        stop = true
        starter.join()
        rerouteTriggers.each { it.join() } //each thread revives ISL after itself
        Wrappers.wait(WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
    }

    def "Flow can be safely deleted while it is in the reroute window waiting for reroute"() {
        given: "A flow"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)
        def path = northbound.getFlowPath(flow.flowId)

        when: "Init a flow reroute by breaking current path"
        def brokenIsl = breakFlow(path)

        and: "Immediately remove the flow before reroute delay runs out and flow is actually rerouted"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "The flow is not present in NB"
        northboundV2.getAllFlows().empty

        and: "Related switches have no excess rules"
        //wait, server42 rules may take some time to disappear after flow removal
        Wrappers.wait(RULES_DELETION_TIME) { pathHelper.getInvolvedSwitches(PathHelper.convert(path)).each {
            verifySwitchRules(it.dpId)
        }}

        and: "cleanup: restore broken path"
        antiflap.portUp(brokenIsl.srcSwitch.dpId, brokenIsl.srcPort)
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getLink(brokenIsl).state == IslChangeType.DISCOVERED }
    }

    def cleanup() {
        database.resetCosts()
    }

    /**
     * Breaks certain flow path. Ensures that the flow is indeed broken by waiting for ISL to actually get FAILED.
     * @param flowpath path to break
     * @return ISL which 'src' was brought down in order to break the path
     */
    Isl breakFlow(FlowPathPayload flowpath) {
        def sw = flowpath.forwardPath.first().switchId
        def port = flowpath.forwardPath.first().outputPort
        def brokenIsl = (topology.islsForActiveSwitches +
                topology.islsForActiveSwitches.collect { it.reversed }).find {
            it.srcSwitch.dpId == sw && it.srcPort == port
        }
        assert brokenIsl, "This should not be possible. Trying to switch port on ISL which is not present in config?"
        antiflap.portDown(sw, port)
        Wrappers.wait(WAIT_OFFSET, 0) {
            assert northbound.getLink(brokenIsl).state == IslChangeType.FAILED
        }
        return brokenIsl

    }
}
