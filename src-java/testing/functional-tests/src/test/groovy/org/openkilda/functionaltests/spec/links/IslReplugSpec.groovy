package org.openkilda.functionaltests.spec.links

import static org.junit.Assume.assumeNotNull
import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.messaging.info.event.IslChangeType.DISCOVERED
import static org.openkilda.messaging.info.event.IslChangeType.FAILED
import static org.openkilda.messaging.info.event.IslChangeType.MOVED
import static org.openkilda.testing.Constants.STATS_LOGGING_TIMEOUT
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.model.SwitchFeature
import org.openkilda.testing.Constants.DefaultRule
import org.openkilda.testing.model.topology.TopologyDefinition.Isl

import org.springframework.beans.factory.annotation.Value
import spock.lang.Ignore
import spock.lang.Narrative

import java.util.concurrent.TimeUnit

@Narrative("Verify scenarios around replugging ISLs between different switches/ports.")
@Tags([TOPOLOGY_DEPENDENT])
class IslReplugSpec extends HealthCheckSpecification {

    @Value('${opentsdb.metric.prefix}')
    String metricPrefix

    def "Round-trip ISL status changes to MOVED when replugging it into another switch"() {
        given: "A connected a-switch link, round-trip-enabled"
        and: "A non-connected a-switch link with round-trip support"
        def (Isl isl, Isl notConnectedIsl) = topology.islsForActiveSwitches.findAll {
            it.aswitch?.inPort && it.aswitch?.outPort &&
                    it.srcSwitch.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD) &&
                    it.dstSwitch.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)
        }.findResult { fwIsl ->
            [fwIsl, fwIsl.reversed].findResult { fwOrReversedIsl ->
                def potentialNotConnected = topology.notConnectedIsls.find {
                    it.srcSwitch != fwOrReversedIsl.srcSwitch &&
                            it.srcSwitch.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)
                }
                potentialNotConnected ? [fwOrReversedIsl, potentialNotConnected] : null
            }
        } ?: [null, null]
        assumeTrue("Wasn't able to find enough of required a-switch links with round-trip", isl.asBoolean())
        assumeTrue("Wasn't able to find enough not connected a-switch links with round-trip",
            notConnectedIsl.asBoolean())

        when: "Replug one end of the connected link to the not connected one"
        def newIsl = islUtils.replug(isl, false, notConnectedIsl, true, false)

        then: "Replugged ISL status changes to MOVED"
        islUtils.waitForIslStatus([isl, isl.reversed], MOVED)

        and: "New ISL becomes DISCOVERED"
        Wrappers.wait(discoveryExhaustedInterval + WAIT_OFFSET) {
            [newIsl, newIsl.reversed].each { assert northbound.getLink(it).state == DISCOVERED }
        }

        when: "Replug the link back where it was"
        islUtils.replug(newIsl, true, isl, false, true)

        then: "Original ISL becomes DISCOVERED again"
        islUtils.waitForIslStatus([isl, isl.reversed], DISCOVERED)

        and: "Replugged ISL status changes to MOVED"
        islUtils.waitForIslStatus([newIsl, newIsl.reversed], MOVED)

        when: "Remove the MOVED ISL"
        assert northbound.deleteLink(islUtils.toLinkParameters(newIsl)).size() == 2

        then: "Moved ISL is removed"
        Wrappers.wait(WAIT_OFFSET) {
            assert !islUtils.getIslInfo(newIsl).isPresent()
            assert !islUtils.getIslInfo(newIsl.reversed).isPresent()
        }

        and: "The src and dst switches of the isl pass switch validation"
        [isl.srcSwitch.dpId, isl.dstSwitch.dpId, notConnectedIsl.srcSwitch.dpId].unique().each { swId ->
            with(northbound.validateSwitch(swId)) { validationResponse ->
                switchHelper.verifyRuleSectionsAreEmpty(validationResponse, ["missing", "excess", "misconfigured"])
            }
        }

        cleanup:
        database.resetCosts()
    }

    def "ISL status changes to MOVED when replugging ISL into another switch"() {
        given: "A connected a-switch link"
        def isl = topology.islsForActiveSwitches.find { it.getAswitch()?.inPort && it.getAswitch()?.outPort }
        assumeTrue("Wasn't able to find enough of required a-switch links", isl.asBoolean())

        and: "A non-connected a-switch link"
        def notConnectedIsl = topology.notConnectedIsls.find {
            it.srcSwitch != isl.srcSwitch && it.srcSwitch != isl.dstSwitch
        }
        assumeTrue("Wasn't able to find enough of required a-switch links", notConnectedIsl.asBoolean())

        when: "Replug one end of the connected link to the not connected one"
        def newIsl = islUtils.replug(isl, false, notConnectedIsl, true, true)

        then: "Replugged ISL status changes to MOVED"
        islUtils.waitForIslStatus([isl, isl.reversed], MOVED)

        and: "New ISL becomes DISCOVERED"
        Wrappers.wait(discoveryExhaustedInterval + WAIT_OFFSET) {
            [newIsl, newIsl.reversed].each { assert northbound.getLink(it).state == DISCOVERED }
        }

        when: "Replug the link back where it was"
        islUtils.replug(newIsl, true, isl, false, false)

        then: "Original ISL becomes DISCOVERED again"
        islUtils.waitForIslStatus([isl, isl.reversed], DISCOVERED)
        Wrappers.wait(discoveryExhaustedInterval + WAIT_OFFSET) {
            def isls = northbound.getAllLinks()
            [isl, isl.reversed].each { assert islUtils.getIslInfo(isls, it).get().actualState == DISCOVERED }
        }

        and: "Replugged ISL status changes to MOVED"
        islUtils.waitForIslStatus([newIsl, newIsl.reversed], MOVED)

        when: "Remove the MOVED ISL"
        assert northbound.deleteLink(islUtils.toLinkParameters(newIsl)).size() == 2

        then: "Moved ISL is removed"
        Wrappers.wait(WAIT_OFFSET) {
            assert !islUtils.getIslInfo(newIsl).isPresent()
            assert !islUtils.getIslInfo(newIsl.reversed).isPresent()
        }

        and: "The src and dst switches of the isl pass switch validation"
        [isl.srcSwitch.dpId, isl.dstSwitch.dpId, notConnectedIsl.srcSwitch.dpId].unique().each { swId ->
            with(northbound.validateSwitch(swId)) { validationResponse ->
                switchHelper.verifyRuleSectionsAreEmpty(validationResponse, ["missing", "excess", "misconfigured"])
            }
        }

        cleanup:
        database.resetCosts()
    }

    def "New potential self-loop ISL (the same port on the same switch) is not getting discovered when replugging"() {
        given: "A connected a-switch link"
        def isl = topology.islsForActiveSwitches.find { it.getAswitch()?.inPort && it.getAswitch()?.outPort }
        assumeTrue("Wasn't able to find enough of required a-switch links", isl.asBoolean())

        when: "Replug one end of the link into 'itself'"
        def loopedIsl = islUtils.replug(isl, false, isl, true, true)

        then: "Replugged ISL status changes to FAILED"
        Wrappers.wait(discoveryTimeout + WAIT_OFFSET) {
            def isls = northbound.getAllLinks()
            [isl, isl.reversed].each { assert islUtils.getIslInfo(isls, it).get().actualState == FAILED }
        }

        and: "The potential self-loop ISL is not present in the list of ISLs"
        def allLinks = northbound.getAllLinks()
        !islUtils.getIslInfo(allLinks, loopedIsl).present
        !islUtils.getIslInfo(allLinks, loopedIsl.reversed).present

        when: "Replug the link back where it was"
        islUtils.replug(loopedIsl, true, isl, false, false)

        then: "Original ISL becomes DISCOVERED again"
        islUtils.waitForIslStatus([isl, isl.reversed], DISCOVERED)
        Wrappers.wait(discoveryAuxiliaryInterval + WAIT_OFFSET) {
            def isls = northbound.getAllLinks()
            [isl, isl.reversed].each { assert islUtils.getIslInfo(isls, it).get().actualState == DISCOVERED }
        }

        cleanup:
        database.resetCosts()
    }

    @Tags(SMOKE)
    @Ignore("https://github.com/telstra/open-kilda/issues/3633")
    def "New potential self-loop ISL (different ports on the same switch) is not getting discovered when replugging"() {
        given: "Two a-switch links on a single switch"
        List<Isl> aSwIsls = []
        def sw = topology.activeSwitches.find { sw ->
            aSwIsls = topology.isls.findAll { it.srcSwitch.dpId == sw.dpId && it.aswitch?.inPort }
            sw.ofVersion != "OF_12" && aSwIsls.size() >= 2
        }
        assumeNotNull("Not able to find required switch with enough number of a-switch ISLs", sw)

        def islToPlug = aSwIsls[0]
        def islToPlugInto = aSwIsls[1]

        when: "Plug an ISL between two ports on the same switch"
        def beforeReplugTime = new Date()
        def dropCounterBefore = northbound.getSwitchRules(islToPlugInto.srcSwitch.dpId).flowEntries.find {
            it.cookie == DefaultRule.DROP_LOOP_RULE.cookie
        }.packetCount
        def expectedIsl = islUtils.replug(islToPlug, true, islToPlugInto, true, true)

        then: "The potential self-loop ISL is not present in the list of ISLs (wait for discovery interval)"
        TimeUnit.SECONDS.sleep(discoveryInterval + WAIT_OFFSET)
        def allLinks = northbound.getAllLinks()
        !islUtils.getIslInfo(allLinks, expectedIsl).present
        !islUtils.getIslInfo(allLinks, expectedIsl.reversed).present

        and: "Self-loop rule packet counter is incremented and logged in otsdb"
        def statsData = null
        Wrappers.wait(STATS_LOGGING_TIMEOUT, 2) {
            statsData = otsdb.query(beforeReplugTime, metricPrefix + "switch.flow.system.packets",
                    [switchid : expectedIsl.srcSwitch.dpId.toOtsdFormat(),
                     cookieHex: DefaultRule.DROP_LOOP_RULE.toHexString()]).dps
            assert statsData && !statsData.empty
            assert statsData.values().last().toLong() > dropCounterBefore
        }

        and: "Unplug the link how it was before"
        lockKeeper.removeFlows([expectedIsl.aswitch, expectedIsl.aswitch.reversed])
        def connectedIsls = [islToPlug, islToPlugInto].findAll { it.aswitch?.outPort }
        lockKeeper.addFlows(connectedIsls.collectMany { [it.aswitch, it.aswitch.reversed] })
        Wrappers.wait(WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            connectedIsls.each { assert islUtils.getIslInfo(links, it).get().state == DISCOVERED }
            assert links.size() == topology.islsForActiveSwitches.size() * 2
        }

        cleanup:
        database.resetCosts()
    }

    @Ignore("https://github.com/telstra/open-kilda/issues/3780")
    def "User is able to replug ISL with enabled BFD, receive new ISL, enable bfd on it and replug back"() {
        given: "An ISL with BFD and ability to replug"
        def isl = topology.islsForActiveSwitches.find { it.aswitch?.inPort && it.aswitch?.outPort &&
            [it.srcSwitch, it.dstSwitch].every { it.features.contains(SwitchFeature.BFD) } }
        assumeTrue("Require at least one BFD ISL", isl as boolean)
        def notConnectedIsl = topology.notConnectedIsls.find { it.srcSwitch.features.contains(SwitchFeature.BFD) &&
                it.srcSwitch != isl.dstSwitch }
        assumeTrue("Require at least one 'not connected' ISL", notConnectedIsl as boolean)
        northboundV2.setLinkBfd(isl)
        Wrappers.wait(WAIT_OFFSET) {
            [isl, isl.reversed].each {
                verifyAll(northbound.getLink(it)) {
                    it.enableBfd
                    it.bfdSessionStatus == "up"
                }
            }
        }

        when: "Replug a bfd-enabled link to some other free port"
        def newIsl = islUtils.replug(isl, true, notConnectedIsl, true, true)

        then: "Old ISL becomes Moved, new ISL is discovered"
        Wrappers.wait(discoveryExhaustedInterval + WAIT_OFFSET) {
            [isl, isl.reversed].each { assert northbound.getLink(it).state == MOVED }
            [newIsl, newIsl.reversed].each { assert northbound.getLink(it).state == DISCOVERED }
        }

        and: "Bfd on Moved ISL reports 'down' status"
        [isl, isl.reversed].each {
            verifyAll(northbound.getLink(it)) {
                enableBfd
                bfdSessionStatus == "down"
            }
        }

        when: "Turn on BFD for new ISL"
        northboundV2.setLinkBfd(newIsl)

        then: "BFD is turned on according to getLink API"
        Wrappers.wait(WAIT_OFFSET / 2) {
            [newIsl, newIsl.reversed].each {
                verifyAll(northbound.getLink(it)) {
                    it.enableBfd
                    it.bfdSessionStatus == "up"
                }
            }
        }

        when: "Replug a new link back where it was before"
        def newOldIsl = islUtils.replug(newIsl, true, isl, true, true)
        verifyAll(newOldIsl) {
            it.srcSwitch == isl.srcSwitch
            it.dstSwitch == isl.dstSwitch
            it.srcPort == isl.srcPort
            it.dstPort == isl.dstPort
        }

        then: "Initial ISL becomes Discovered again, replugged ISL becomes Moved"
        Wrappers.wait(discoveryExhaustedInterval + WAIT_OFFSET) {
            [newIsl, newIsl.reversed].each { assert northbound.getLink(it).state == MOVED }
            [newOldIsl, newOldIsl.reversed].each { assert northbound.getLink(it).state == DISCOVERED }
        }

        and: "Bfd on Moved ISL reports 'down' status"
        [newIsl, newIsl.reversed].each {
            verifyAll(northbound.getLink(it)) {
                enableBfd
                bfdSessionStatus == "down"
            }
        }

        and: "Bfd on Discovered ISL reports 'up' status"
        [newOldIsl, newOldIsl.reversed].each {
            verifyAll(northbound.getLink(it)) {
                enableBfd
                bfdSessionStatus == "up"
            }
        }

        cleanup: "Removed Moved ISL, turn off bfd" //this cleanup is not comprehensive
        newIsl && northbound.deleteLink(islUtils.toLinkParameters(newIsl))
        northboundV2.deleteLinkBfd(newOldIsl)
    }
}
