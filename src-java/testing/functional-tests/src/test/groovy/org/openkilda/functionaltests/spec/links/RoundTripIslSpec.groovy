package org.openkilda.functionaltests.spec.links

import static groovyx.gpars.GParsPool.withPool
import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.LOCKKEEPER
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.messaging.info.event.IslChangeType.DISCOVERED
import static org.openkilda.messaging.info.event.IslChangeType.FAILED
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.command.switches.DeleteRulesAction
import org.openkilda.messaging.command.switches.InstallRulesAction
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.model.SwitchFeature
import org.openkilda.northbound.dto.v2.switches.PortPropertiesDto
import org.openkilda.testing.model.topology.TopologyDefinition.Isl

import spock.lang.See
import spock.lang.Unroll

import java.util.concurrent.TimeUnit

@Tags(HARDWARE) // virtual env doesn't support round trip latency
@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/network-discovery")
class RoundTripIslSpec extends HealthCheckSpecification {

    /*we need this variable because it takes more time to DEACTIVATE a switch
    via the 'knockoutSwitch' method on the stage env*/
    Integer customWaitOffset = WAIT_OFFSET * 4

    @Unroll
    @Tidy
    def "Isl with round-trip properly changes status after port events(#descr)"() {
        given: "Round-trip ISL with a-switch"
        def cleanupActions = []
        def isl = topology.islsForActiveSwitches.find { it.aswitch?.inPort && it.aswitch?.outPort &&
                [it.srcSwitch, it.dstSwitch].every { it.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD) }
        }
        assumeTrue("Wasn't able to find round-trip ISL with a-switch", isl != null)
        bfd && northboundV2.setLinkBfd(isl)

        when: "Port down event happens"
        antiflap.portDown(isl.srcSwitch.dpId, isl.srcPort)
        cleanupActions << { antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort) }

        then: "ISL changed status to FAILED"
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getLink(isl).state == FAILED
            assert northbound.getLink(isl.reversed).state == FAILED
        }

        when: "Port up event happens, but traffic goes only in one direction"
        lockKeeper.removeFlows([isl.aswitch])
        cleanupActions << { lockKeeper.addFlows([isl.aswitch]) }
        cleanupActions.pop().call() //antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)

        then: "ISL is not getting discovered"
        TimeUnit.SECONDS.sleep(discoveryInterval + 2)
        northbound.getLink(isl).state == FAILED
        northbound.getLink(isl.reversed).state == FAILED

        when: "Traffic starts to flow in both directions"
        cleanupActions.pop().call() //lockKeeper.addFlows([isl.aswitch])

        then: "ISL gets discovered"
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            def fw = northbound.getLink(isl)
            def rv = northbound.getLink(isl.reversed)
            assert fw.state == DISCOVERED
            assert fw.actualState == DISCOVERED
            assert rv.state == DISCOVERED
            assert rv.actualState == DISCOVERED
        }

        cleanup:
        cleanupActions.each { it() }
        bfd && isl && northboundV2.deleteLinkBfd(isl)
        isl && Wrappers.wait(WAIT_OFFSET) {
            def allLinks = northbound.getAllLinks()
            assert islUtils.getIslInfo(allLinks, isl).get().state == DISCOVERED
            assert islUtils.getIslInfo(allLinks, isl.reversed).get().state == DISCOVERED
            assert islUtils.getIslInfo(allLinks, isl).get().roundTripStatus == DISCOVERED
            assert islUtils.getIslInfo(allLinks, isl.reversed).get().roundTripStatus == DISCOVERED
        }
        database.resetCosts()

        where:
        bfd << [false, true]
        descr = "with${bfd ? '': 'out'} bfd"
    }

    @Tidy
    @Tags([SMOKE_SWITCHES, LOCKKEEPER])
    def "A round trip latency ISL doesn't go down when one switch lose connection to FL"() {
        given: "A switch with/without round trip latency ISLs"
        def roundTripIsls
        def nonRoundTripIsls
        def swToDeactivate = topology.activeSwitches.find { sw ->
            if (sw.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)) {
                roundTripIsls = topology.getRelatedIsls(sw).findAll {
                    it.dstSwitch.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)
                }
                nonRoundTripIsls = topology.getRelatedIsls(sw).findAll {
                    !it.dstSwitch.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)
                }
                roundTripIsls && nonRoundTripIsls
            }
        } ?: assumeTrue("Wasn't able to find a switch with suitable links", false)

        when: "Simulate connection lose between the switch and FL, the switch becomes DEACTIVATED and remains operable"
        def mgmtBlockData = lockKeeper.knockoutSwitch(swToDeactivate, RW)

        def isSwDeactivated = true
        Wrappers.wait(customWaitOffset) {
            assert northbound.getSwitch(swToDeactivate.dpId).state == SwitchChangeType.DEACTIVATED
        }

        and: "Wait discoveryTimeout"
        sleep(discoveryTimeout * 1000)

        then: "All non round trip latency ISLs are FAILED"
        Wrappers.wait(WAIT_OFFSET) {
            withPool {
                nonRoundTripIsls.eachParallel { assert northbound.getLink(it).state == FAILED }
            }
        }

        and: "All round trip latency ISLs are still DISCOVERED (the system uses round trip latency status \
for ISL alive confirmation)"
        withPool {
            roundTripIsls.eachParallel { assert northbound.getLink(it).state == DISCOVERED }
        }

        cleanup:
        if (isSwDeactivated) {
            lockKeeper.reviveSwitch(swToDeactivate, mgmtBlockData)
            Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
                assert northbound.getSwitch(swToDeactivate.dpId).state == SwitchChangeType.ACTIVATED
                assert northbound.getAllLinks().findAll {
                    it.state == DISCOVERED
                }.size() == topology.islsForActiveSwitches.size() * 2
            }
        }
        database.resetCosts()
    }

    @Tidy
    @Tags([SMOKE_SWITCHES, LOCKKEEPER])
    def "A round trip latency ISL goes down when both switches lose connection to FL"() {
        given: "A round trip latency ISL"
        Isl roundTripIsl
        def srcSwToDeactivate = topology.activeSwitches.find { sw ->
            if (sw.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)) {
                roundTripIsl = topology.getRelatedIsls(sw).find {
                    it.dstSwitch.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)
                }
                roundTripIsl
            }
        } ?: assumeTrue("Wasn't able to find a suitable link", false)
        def dstSwToDeactivate = roundTripIsl.dstSwitch

        when: "Switches lose connection to FL, switches become DEACTIVATED but keep processing packets"
        def mgmtBlockDataSrcSw = lockKeeper.knockoutSwitch(srcSwToDeactivate, RW)
        def mgmtBlockDataDstSw = lockKeeper.knockoutSwitch(dstSwToDeactivate, RW)
        def areSwitchesDeactivated = true
        Wrappers.wait(customWaitOffset) {
            assert northbound.getSwitch(srcSwToDeactivate.dpId).state == SwitchChangeType.DEACTIVATED
            assert northbound.getSwitch(dstSwToDeactivate.dpId).state == SwitchChangeType.DEACTIVATED
        }

        then: "The round trip latency ISL is FAILED (because round_trip_status is not available in DB for current ISL \
on both switches)"
        Wrappers.wait(discoveryTimeout + WAIT_OFFSET / 2) {
            assert northbound.getLink(roundTripIsl).state == FAILED
        }

        cleanup:
        if (areSwitchesDeactivated) {
            lockKeeper.reviveSwitch(srcSwToDeactivate, mgmtBlockDataSrcSw)
            lockKeeper.reviveSwitch(dstSwToDeactivate, mgmtBlockDataDstSw)
            Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
                assert northbound.getSwitch(srcSwToDeactivate.dpId).state == SwitchChangeType.ACTIVATED
                assert northbound.getSwitch(dstSwToDeactivate.dpId).state == SwitchChangeType.ACTIVATED
                def allLinks = northbound.getAllLinks()
                assert allLinks.findAll { it.state == DISCOVERED }.size() == topology.islsForActiveSwitches.size() * 2
                assert islUtils.getIslInfo(allLinks, roundTripIsl).get().roundTripStatus == DISCOVERED
                assert islUtils.getIslInfo(allLinks, roundTripIsl.reversed).get().roundTripStatus == DISCOVERED
            }
        }
        database.resetCosts()
    }

    @Tidy
    @Tags([SMOKE_SWITCHES, LOCKKEEPER])
    def "A round trip latency ISL goes down when the src switch lose connection to FL and \
round trip latency rule is removed on the dst switch"() {
        given: "A round trip latency ISL"
        Isl roundTripIsl
        def srcSwToDeactivate = topology.activeSwitches.find { sw ->
            if (sw.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)) {
                roundTripIsl = topology.getRelatedIsls(sw).find {
                    it.dstSwitch.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)
                }
                roundTripIsl
            }
        } ?: assumeTrue("Wasn't able to find a suitable link", false)
        def dstSw = roundTripIsl.dstSwitch

        and: "Round trip status is ACTIVE for the given ISL in both directions"
        [roundTripIsl, roundTripIsl.reversed].each {
            assert northbound.getLink(it).roundTripStatus == DISCOVERED
        }

        when: "Simulate connection lose between the src switch and FL, switches become DEACTIVATED and remain operable"
        def mgmtBlockData = lockKeeper.knockoutSwitch(srcSwToDeactivate, RW)
        def isSrcSwDeactivated = true
        Wrappers.wait(customWaitOffset) {
            assert northbound.getSwitch(srcSwToDeactivate.dpId).state == SwitchChangeType.DEACTIVATED
        }

        then: "Round trip status for forward direction is not available and ACTIVE in reverse direction"
        Wrappers.wait(discoveryTimeout + WAIT_OFFSET / 2) {
            assert northbound.getLink(roundTripIsl).roundTripStatus == FAILED
            assert northbound.getLink(roundTripIsl.reversed).roundTripStatus == DISCOVERED
        }

        when: "Delete ROUND_TRIP_LATENCY_RULE_COOKIE on the dst switch"
        northbound.deleteSwitchRules(dstSw.dpId, DeleteRulesAction.REMOVE_ROUND_TRIP_LATENCY)
        def isRoundTripRuleDeleted = true
        Wrappers.wait(RULES_DELETION_TIME) {
            assert northbound.validateSwitch(dstSw.dpId).rules.missing.size() == 1
        }

        then: "The round trip latency ISL is FAILED"
        Wrappers.wait(discoveryTimeout + WAIT_OFFSET / 2) {
            assert northbound.getLink(roundTripIsl).state == FAILED
        }

        and: "Round trip status is not available for the given ISL in both directions"
        Wrappers.wait(WAIT_OFFSET / 2) {
            [roundTripIsl, roundTripIsl.reversed].each {
                assert northbound.getLink(it).roundTripStatus == FAILED
            }
        }

        when: "Restore connection between the src switch and FL"
        lockKeeper.reviveSwitch(srcSwToDeactivate, mgmtBlockData)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getSwitch(srcSwToDeactivate.dpId).state == SwitchChangeType.ACTIVATED
            assert northbound.getAllLinks().findAll {
                it.state == DISCOVERED
            }.size() == topology.islsForActiveSwitches.size() * 2
        }
        isSrcSwDeactivated = false

        then: "Round trip isl is DISCOVERED"
        northbound.getLink(roundTripIsl).state == DISCOVERED

        and: "Round trip status is available for the given ISL in forward direction only"
        Wrappers.wait(WAIT_OFFSET / 2) {
            assert northbound.getLink(roundTripIsl).roundTripStatus == DISCOVERED
            assert northbound.getLink(roundTripIsl.reversed).roundTripStatus == FAILED
        }

        when: "Install ROUND_TRIP_LATENCY_RULE_COOKIE on the dst switch"
        northbound.installSwitchRules(dstSw.dpId, InstallRulesAction.INSTALL_ROUND_TRIP_LATENCY)
        isRoundTripRuleDeleted = false
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert northbound.validateSwitch(dstSw.dpId).rules.missing.empty
        }

        then: "Round trip status is available for the given ISL in both directions"
        Wrappers.wait(WAIT_OFFSET / 2) {
            [roundTripIsl, roundTripIsl.reversed].each {
                assert northbound.getLink(it).roundTripStatus == DISCOVERED }
        }

        cleanup:
        isSrcSwDeactivated && lockKeeper.reviveSwitch(srcSwToDeactivate, mgmtBlockData)
        isRoundTripRuleDeleted && northbound.installSwitchRules(dstSw.dpId, InstallRulesAction.INSTALL_ROUND_TRIP_LATENCY)
        if (isSrcSwDeactivated || isRoundTripRuleDeleted) {
            Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
                assert northbound.getSwitch(srcSwToDeactivate.dpId).state == SwitchChangeType.ACTIVATED
                def allLinks = northbound.getAllLinks()
                assert allLinks.findAll { it.state == DISCOVERED }.size() == topology.islsForActiveSwitches.size() * 2
                assert islUtils.getIslInfo(allLinks, roundTripIsl).get().roundTripStatus == DISCOVERED
                assert islUtils.getIslInfo(allLinks, roundTripIsl.reversed).get().roundTripStatus == DISCOVERED
                assert northbound.validateSwitch(dstSw.dpId).rules.missing.empty
            }
        }
        database.resetCosts()
    }

    @Tidy
    @Tags([SMOKE_SWITCHES])
    def "A round trip latency ISL goes down when portDiscovery property is disabled on the src/dst ports"() {
        given: "A round trip latency ISL"
        Isl roundTripIsl = topology.islsForActiveSwitches.find {
            it.srcSwitch.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD) &&
                    it.dstSwitch.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)
        } ?: assumeTrue("Wasn't able to find a suitable link", false)

        when: "Disable portDiscovery on the srcPort"
        northboundV2.updatePortProperties(roundTripIsl.srcSwitch.dpId, roundTripIsl.srcPort,
                new PortPropertiesDto(discoveryEnabled: false))
        def portDiscoveryIsEnabledOnSrcPort = false

        then: "Isl is still DISCOVERED"
        Wrappers.wait(WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            def islInfoForward = islUtils.getIslInfo(links, roundTripIsl,).get()
            def islInfoReverse = islUtils.getIslInfo(links, roundTripIsl.reversed).get()
            assert islInfoForward.state == DISCOVERED
            assert islInfoForward.actualState == FAILED
            assert islInfoReverse.state == DISCOVERED
            assert islInfoReverse.actualState == DISCOVERED
        }

        when: "Disable portDiscovery property on the dstPort"
        northboundV2.updatePortProperties(roundTripIsl.dstSwitch.dpId, roundTripIsl.dstPort,
                new PortPropertiesDto(discoveryEnabled: false))
        def portDiscoveryIsEnabledOnDstPort = false

        then: "Status of the link is changed to FAILED"
        //don't need to wait discoveryTimeout, disablePortDiscovery(on src/dst sides) == portDown
        Wrappers.wait(WAIT_OFFSET) {
            def allLinks = northbound.getAllLinks()
            def islInfoForward = islUtils.getIslInfo(allLinks, roundTripIsl).get()
            def islInfoReverse = islUtils.getIslInfo(allLinks, roundTripIsl.reversed).get()
            assert islInfoForward.state == FAILED
            assert islInfoForward.actualState == FAILED
            assert islInfoReverse.state == FAILED
            assert islInfoReverse.actualState == FAILED
        }

        when: "Enable portDiscovery on the src/dst ports"
        northboundV2.updatePortProperties(roundTripIsl.srcSwitch.dpId, roundTripIsl.srcPort,
                new PortPropertiesDto(discoveryEnabled: true))
        northboundV2.updatePortProperties(roundTripIsl.dstSwitch.dpId, roundTripIsl.dstPort,
                new PortPropertiesDto(discoveryEnabled: true))
        portDiscoveryIsEnabledOnSrcPort = true
        portDiscoveryIsEnabledOnDstPort = true

        then: "ISL is rediscovered"
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            def islInfoForward = islUtils.getIslInfo(links, roundTripIsl).get()
            def islInfoReverse = islUtils.getIslInfo(links, roundTripIsl.reversed).get()
            assert islInfoForward.state == DISCOVERED
            assert islInfoForward.actualState == DISCOVERED
            assert islInfoReverse.state == DISCOVERED
            assert islInfoReverse.actualState == DISCOVERED
        }

        cleanup:
        !portDiscoveryIsEnabledOnSrcPort && northboundV2.updatePortProperties(roundTripIsl.srcSwitch.dpId,
                roundTripIsl.srcPort, new PortPropertiesDto(discoveryEnabled: true))
        !portDiscoveryIsEnabledOnDstPort && northboundV2.updatePortProperties(roundTripIsl.dstSwitch.dpId,
                roundTripIsl.dstPort, new PortPropertiesDto(discoveryEnabled: true))
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert islUtils.getIslInfo(roundTripIsl).get().state == DISCOVERED
            assert islUtils.getIslInfo(roundTripIsl.reversed).get().state == DISCOVERED
        }
    }

    @Tidy
    def "Able to delete failed ISL without force if it was discovered with disabled portDiscovery on a switch"() {
        given: "A deleted round trip latency ISL with disabled port discovery"
        Isl roundTripIsl = topology.islsForActiveSwitches.find {
            it.srcSwitch.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD) &&
                    it.dstSwitch.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)
        } ?: assumeTrue("Wasn't able to find a suitable link", false)

        antiflap.portDown(roundTripIsl.srcSwitch.dpId, roundTripIsl.srcPort)
        def portIsUp = false
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getLink(roundTripIsl).actualState == FAILED }
        islUtils.changePortDiscovery(roundTripIsl, false)
        def portDiscoveryOnDstPort = false
        TimeUnit.SECONDS.sleep(discoveryInterval)
        northbound.deleteLink(islUtils.toLinkParameters(roundTripIsl))
        Wrappers.wait(WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            assert !islUtils.getIslInfo(links, roundTripIsl)
            assert !islUtils.getIslInfo(links, roundTripIsl.reversed)
        }

        when: "Enable portDiscovery only on the dstPort"
        northboundV2.updatePortProperties(roundTripIsl.dstSwitch.dpId, roundTripIsl.dstPort,
                new PortPropertiesDto(discoveryEnabled: true))
        portDiscoveryOnDstPort = true

        and: "Bring switch port up"
        antiflap.portUp(roundTripIsl.srcSwitch.dpId, roundTripIsl.srcPort)
        portIsUp = true

        then: "The ISL is rediscovered"
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            assert islUtils.getIslInfo(links, roundTripIsl).get().state == DISCOVERED
            assert islUtils.getIslInfo(links, roundTripIsl.reversed).get().state == DISCOVERED
        }

        and: "The src/dst switches are valid"
        //https://github.com/telstra/open-kilda/issues/3906
//        [roundTripIsl.srcSwitch, roundTripIsl.dstSwitch].each {
//            def validateInfo = northbound.validateSwitch(it.dpId).rules
//            assert validateInfo.missing.empty
//            assert validateInfo.excess.empty
//            assert validateInfo.misconfigured.empty
//        }

        when: "Disable portDiscovery on the dstPort"
        northboundV2.updatePortProperties(roundTripIsl.dstSwitch.dpId, roundTripIsl.dstPort,
                new PortPropertiesDto(discoveryEnabled: false))
        portDiscoveryOnDstPort = false

        then: "The ISL is failed"
        Wrappers.wait(WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            assert islUtils.getIslInfo(links, roundTripIsl).get().state == FAILED
            assert islUtils.getIslInfo(links, roundTripIsl.reversed).get().state == FAILED
        }

        when: "Delete the ISL without the 'force' option"
        northbound.deleteLink(islUtils.toLinkParameters(roundTripIsl))

        then: "The ISL is deleted"
        Wrappers.wait(WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            assert !islUtils.getIslInfo(links, roundTripIsl).present
            assert !islUtils.getIslInfo(links, roundTripIsl.reversed).present
        }

        cleanup:
        if (roundTripIsl) {
            northboundV2.updatePortProperties(roundTripIsl.srcSwitch.dpId,
                    roundTripIsl.srcPort, new PortPropertiesDto(discoveryEnabled: true))
            !portDiscoveryOnDstPort && northboundV2.updatePortProperties(roundTripIsl.dstSwitch.dpId,
                    roundTripIsl.dstPort, new PortPropertiesDto(discoveryEnabled: true))
            !portIsUp && antiflap.portUp(roundTripIsl.srcSwitch.dpId, roundTripIsl.srcPort)
            Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
                def links = northbound.getAllLinks()
                assert islUtils.getIslInfo(links, roundTripIsl).get().state == DISCOVERED
                assert islUtils.getIslInfo(links, roundTripIsl.reversed).get().state == DISCOVERED
            }
        }
    }
}
