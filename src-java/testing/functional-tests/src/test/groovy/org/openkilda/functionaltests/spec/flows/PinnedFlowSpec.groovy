package org.openkilda.functionaltests.spec.flows

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.model.MeterId.MAX_SYSTEM_RULE_METER_ID
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.cookie.Cookie

import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative

import java.util.concurrent.TimeUnit

@Narrative("""A new flag of flow that indicates that flow shouldn't be rerouted in case of auto-reroute.
- In case of isl down such flow should be marked as DOWN.
- On Isl up event such flow shouldn't be re-routed as well.
  Instead kilda should verify that it's path is online and mark flow as UP.""")
@Tags([LOW_PRIORITY])
class PinnedFlowSpec extends HealthCheckSpecification {

    def "System doesn't reroute(automatically) pinned flow when flow path is partially broken"() {
        given: "A pinned flow going through a long not preferable path"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue(false, "No suiting switches found")
        List<List<PathNode>> allPaths = database.getPaths(switchPair.src.dpId, switchPair.dst.dpId)*.path
        def longestPath = allPaths.max { it.size() }
        allPaths.findAll { it != longestPath }.collect { pathHelper.makePathMorePreferable(longestPath, it) }
        def flow = flowHelper.randomFlow(switchPair)
        flow.pinned = true
        flowHelper.addFlow(flow)

        def currentPath = pathHelper.convert(northbound.getFlowPath(flow.id))
        def altPath = switchPair.paths.findAll { it != currentPath }.min { it.size() }
        def involvedSwitches = pathHelper.getInvolvedSwitches(flow.id)

        when: "Make alt path more preferable than current path"
        switchPair.paths.findAll { it != altPath }.each { pathHelper.makePathMorePreferable(altPath, it) }

        and: "Init reroute by bringing current path's ISL down one by one"
        def currentIsls = pathHelper.getInvolvedIsls(currentPath)
        def newIsls = pathHelper.getInvolvedIsls(altPath)
        def islsToBreak = currentIsls.findAll { !newIsls.contains(it) }

        def cookiesMap = involvedSwitches.collectEntries { sw ->
            [sw.dpId, northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
                !new Cookie(it.cookie).serviceFlag
            }*.cookie]
        }
        def metersMap = involvedSwitches.findAll { it.ofVersion != "OF_12" }.collectEntries { sw ->
            [sw.dpId, northbound.getAllMeters(sw.dpId).meterEntries.findAll {
                it.meterId > MAX_SYSTEM_RULE_METER_ID
            }*.meterId]
        }

        antiflap.portDown(islsToBreak[0].srcSwitch.dpId, islsToBreak[0].srcPort)

        then: "Flow is not rerouted and marked as DOWN when the first ISL is broken"
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getFlowStatus(flow.id).status == FlowState.DOWN
            assert pathHelper.convert(northbound.getFlowPath(flow.id)) == currentPath
        }
        islsToBreak[1..-1].each { islToBreak ->
            antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        }

        and: "Rules and meters are not changed"
        def cookiesMapAfterReroute = involvedSwitches.collectEntries { sw ->
            [sw.dpId, northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
                !new Cookie(it.cookie).serviceFlag
            }*.cookie]
        }
        def metersMapAfterReroute = involvedSwitches.findAll { it.ofVersion != "OF_12" }.collectEntries { sw ->
            [sw.dpId, northbound.getAllMeters(sw.dpId).meterEntries.findAll {
                it.meterId > MAX_SYSTEM_RULE_METER_ID
            }*.meterId]
        }

        cookiesMap.sort() == cookiesMapAfterReroute.sort()
        metersMap.sort() == metersMapAfterReroute.sort()

        when: "The broken ISLs are restored one by one"
        islsToBreak[0..-2].each { islToBreak ->
            antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
            Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
                assert islUtils.getIslInfo(islToBreak).get().state == IslChangeType.DISCOVERED
                TimeUnit.SECONDS.sleep(rerouteDelay - 1)
                assert northbound.getFlowStatus(flow.id).status == FlowState.DOWN
                assert pathHelper.convert(northbound.getFlowPath(flow.id)) == currentPath
            }
        }
        antiflap.portUp(islsToBreak[-1].srcSwitch.dpId, islsToBreak[-1].srcPort)

        then: "Flow is marked as UP when the last ISL is restored"
        Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
            assert islUtils.getIslInfo(islsToBreak[-1]).get().state == IslChangeType.DISCOVERED
            assert northbound.getFlowStatus(flow.id).status == FlowState.UP
            assert pathHelper.convert(northbound.getFlowPath(flow.id)) == currentPath
        }

        and: "Cleanup: revert system to original state"
        flowHelper.deleteFlow(flow.id)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        database.resetCosts()
    }

    @Tidy
    def "System doesn't allow to create pinned and protected flow at the same time"() {
        when: "Try to create pinned and protected flow"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue(false, "No suiting switches found")
        def flow = flowHelper.randomFlow(switchPair)
        flow.pinned = true
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription == "Flow flags are not valid, unable to process pinned protected flow"

        cleanup:
        !exc && flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    def "System doesn't allow to enable the protected path flag on a pinned flow"() {
        given: "A pinned flow"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue(false, "No suiting switches found")
        def flow = flowHelper.randomFlow(switchPair)
        flow.pinned = true
        flowHelper.addFlow(flow)

        when: "Update flow: enable the allocateProtectedPath flag(allocateProtectedPath=true)"
        northbound.updateFlow(flow.id, flow.tap { it.allocateProtectedPath = true })

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not update flow"
        errorDetails.errorDescription == "Flow flags are not valid, unable to process pinned protected flow"

        cleanup:
        flowHelper.deleteFlow(flow.id)
    }
}
