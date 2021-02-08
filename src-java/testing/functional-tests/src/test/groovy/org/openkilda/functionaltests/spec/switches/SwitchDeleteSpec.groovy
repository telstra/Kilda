package org.openkilda.functionaltests.spec.switches

import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.testing.Constants.NON_EXISTENT_SWITCH_ID
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.IterationTag
import org.openkilda.functionaltests.extension.tags.IterationTags
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType

import org.springframework.web.client.HttpClientErrorException
import spock.lang.Unroll

import java.util.concurrent.TimeUnit

class SwitchDeleteSpec extends HealthCheckSpecification {
    @Tidy
    def "Unable to delete a nonexistent switch"() {
        when: "Try to delete a nonexistent switch"
        northbound.deleteSwitch(NON_EXISTENT_SWITCH_ID, false)

        then: "Get 404 NotFound error"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
    }

    @Tidy
    @Tags(SMOKE)
    def "Unable to delete an active switch"() {
        given: "An active switch"
        def switchId = topology.getActiveSwitches()[0].dpId

        when: "Try to delete the switch"
        northbound.deleteSwitch(switchId, false)

        then: "Get 400 BadRequest error because the switch must be deactivated first"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        exc.responseBodyAsString.contains("Switch '$switchId' is in 'Active' state")
    }

    @Tidy
    def "Unable to delete an inactive switch with active ISLs"() {
        given: "An inactive switch with ISLs"
        def sw = topology.getActiveSwitches()[0]
        def swIsls = topology.getRelatedIsls(sw)
        def blockData = switchHelper.knockoutSwitch(sw, RW)

        when: "Try to delete the switch"
        northbound.deleteSwitch(sw.dpId, false)

        then: "Get 400 BadRequest error because the switch has ISLs"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        exc.responseBodyAsString.matches(".*Switch '$sw.dpId' has ${swIsls.size() * 2} active links\\. " +
                "Unplug and remove them first.*")

        cleanup: "Activate the switch back"
        switchHelper.reviveSwitch(sw, blockData, true)
    }

    @Tidy
    def "Unable to delete an inactive switch with inactive ISLs (ISL ports are down)"() {
        given: "An inactive switch with ISLs"
        def sw = topology.getActiveSwitches()[0]
        def swIsls = topology.getRelatedIsls(sw)
        // deactivate all active ISLs on switch
        swIsls.each { antiflap.portDown(sw.dpId, it.srcPort) }
        Wrappers.wait(WAIT_OFFSET) {
            swIsls.each { assert islUtils.getIslInfo(it).get().state == IslChangeType.FAILED }
        }
        // deactivate switch
        def blockData = switchHelper.knockoutSwitch(sw, RW)

        when: "Try to delete the switch"
        northbound.deleteSwitch(sw.dpId, false)

        then: "Get 400 BadRequest error because the switch has ISLs"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        exc.responseBodyAsString.matches(".*Switch '$sw.dpId' has ${swIsls.size() * 2} inactive links\\. " +
                "Remove them first.*")

        cleanup: "Activate the switch back and reset costs"
        lockKeeper.reviveSwitch(sw, blockData)
        swIsls.each { antiflap.portUp(sw.dpId, it.srcPort) }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            swIsls.each { assert islUtils.getIslInfo(links, it).get().state == IslChangeType.DISCOVERED }
        }
        database.resetCosts()
    }

    @Unroll
    @Tidy
    @IterationTags([@IterationTag(tags = [LOW_PRIORITY], take = 1)])
    def "Unable to delete an inactive switch with a #flowType flow assigned"() {
        given: "A flow going through a switch"
        flowHelperV2.addFlow(flow)

        when: "Deactivate the switch"
        def swToDeactivate = topology.switches.find { it.dpId == flow.source.switchId }
        def blockData = switchHelper.knockoutSwitch(swToDeactivate, RW)

        and: "Try to delete the switch"
        northbound.deleteSwitch(flow.source.switchId, false)

        then: "Got 400 BadRequest error because the switch has the flow assigned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        exc.responseBodyAsString.matches(".*Switch '${flow.source.switchId}' has 1 assigned flows: \\[${flow.flowId}\\].*")

        cleanup: "Activate the switch back and remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)
        switchHelper.reviveSwitch(swToDeactivate, blockData)

        where:
        flowType        | flow
        "single-switch" | getFlowHelperV2().singleSwitchFlow(getTopology().getActiveSwitches()[0])
        "casual"        | getFlowHelperV2().randomFlow(*getTopology().getActiveSwitches()[0..1])
    }

    @Tidy
    def "Able to delete an inactive switch without any ISLs"() {
        given: "An inactive switch without any ISLs"
        def sw = topology.getActiveSwitches()[0]
        def swIsls = topology.getRelatedIsls(sw)
        swIsls.each { antiflap.portDown(sw.dpId, it.srcPort) } // port down on all active ISLs on switch
        Wrappers.wait(WAIT_OFFSET) {
            swIsls.each { assert northbound.getLink(it).state == IslChangeType.FAILED }
        }
        swIsls.each { islUtils.changePortDiscovery(it, false) }
        TimeUnit.SECONDS.sleep(discoveryInterval)
        swIsls.each { northbound.deleteLink(islUtils.toLinkParameters(it)) } // delete all ISLs on switch
        def blockData = switchHelper.knockoutSwitch(sw, RW) // deactivate switch

        when: "Try to delete the switch"
        def response = northbound.deleteSwitch(sw.dpId, false)

        then: "The switch is actually deleted"
        response.deleted
        Wrappers.wait(WAIT_OFFSET) { assert !northbound.allSwitches.any { it.switchId == sw.dpId } }

        cleanup: "Activate the switch back, restore ISLs and reset costs"
        switchHelper.reviveSwitch(sw, blockData)
        swIsls.each {
            islUtils.changePortDiscovery(it, true)
            antiflap.portUp(sw.dpId, it.srcPort)
        }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            swIsls.each { assert islUtils.getIslInfo(links, it).get().state == IslChangeType.DISCOVERED }
        }
        database.resetCosts()
    }

    @Tidy
    def "Able to delete an active switch with active ISLs if using force delete"() {
        given: "An active switch with active ISLs"
        def sw = topology.getActiveSwitches()[0]
        def swIsls = topology.getRelatedIsls(sw)

        when: "Try to force delete the switch"
        def response = northbound.deleteSwitch(sw.dpId, true)

        then: "The switch is actually deleted"
        response.deleted
        Wrappers.wait(WAIT_OFFSET) {
            assert !northbound.allSwitches.any { it.switchId == sw.dpId }

            def links = northbound.getAllLinks()
            swIsls.each {
                assert !islUtils.getIslInfo(links, it)
                assert !islUtils.getIslInfo(links, it.reversed)
            }
        }

        cleanup: "Restore the switch, ISLs and reset costs"
        // restore switch
        def blockData = lockKeeper.knockoutSwitch(sw, RW)
        Wrappers.wait(WAIT_OFFSET) {
            flHelper.getFlsByMode(RW).each { fl ->
                assert fl.floodlightService.getSwitches().every { it.switchId != sw.dpId }
            }
        }
        switchHelper.reviveSwitch(sw, blockData)
        // restore ISLs
        swIsls.each { antiflap.portDown(it.srcSwitch.dpId, it.srcPort) }
        TimeUnit.SECONDS.sleep(antiflapMin)
        swIsls.each { antiflap.portUp(it.srcSwitch.dpId, it.srcPort) }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            swIsls.collectMany { [it, it.reversed] }
                    .each { assert islUtils.getIslInfo(links, it).get().state == IslChangeType.DISCOVERED }
        }
        database.resetCosts()
    }
}
