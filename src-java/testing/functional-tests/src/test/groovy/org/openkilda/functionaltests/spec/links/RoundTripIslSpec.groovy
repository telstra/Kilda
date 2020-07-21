package org.openkilda.functionaltests.spec.links

import static groovyx.gpars.GParsPool.withPool
import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.command.switches.DeleteRulesAction
import org.openkilda.messaging.command.switches.InstallRulesAction
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.model.IslStatus
import org.openkilda.model.SwitchFeature
import org.openkilda.testing.model.topology.TopologyDefinition.Isl

import spock.lang.See

@Tags(HARDWARE)
// virtual env doesn't support round trip latency
@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/network-discovery")
class RoundTripIslSpec extends HealthCheckSpecification {

    /*we need this variable because it takes more time to DEACTIVATE a switch
    via the 'knockoutSwitch' method on the stage env*/
    Integer customWaitOffset = WAIT_OFFSET * 4

    @Tidy
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
        def mgmtBlockData = lockKeeper.knockoutSwitch(swToDeactivate, mgmtFlManager)

        def isSwDeactivated = true
        Wrappers.wait(customWaitOffset) {
            assert northbound.getSwitch(swToDeactivate.dpId).state == SwitchChangeType.DEACTIVATED
        }

        and: "Wait discoveryTimeout"
        sleep(discoveryTimeout * 1000)

        then: "All non round trip latency ISLs are FAILED"
        Wrappers.wait(WAIT_OFFSET) {
            withPool {
                nonRoundTripIsls.eachParallel { assert northbound.getLink(it).state == IslChangeType.FAILED }
            }
        }


        and: "All round trip latency ISLs are still DISCOVERED (the system uses round trip latency status \
for ISL alive confirmation)"
        withPool {
            roundTripIsls.eachParallel { assert northbound.getLink(it).state == IslChangeType.DISCOVERED }
        }

        cleanup:
        if (isSwDeactivated) {
            lockKeeper.reviveSwitch(swToDeactivate, mgmtBlockData)
            Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
                assert northbound.getSwitch(swToDeactivate.dpId).state == SwitchChangeType.ACTIVATED
                assert northbound.getAllLinks().findAll {
                    it.state == IslChangeType.DISCOVERED
                }.size() == topology.islsForActiveSwitches.size() * 2
            }
        }
    }

    @Tidy
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
        def mgmtBlockDataSrcSw = lockKeeper.knockoutSwitch(srcSwToDeactivate, mgmtFlManager)
        def mgmtBlockDataDstSw = lockKeeper.knockoutSwitch(dstSwToDeactivate, mgmtFlManager)
        def areSwitchesDeactivated = true
        Wrappers.wait(customWaitOffset) {
            assert northbound.getSwitch(srcSwToDeactivate.dpId).state == SwitchChangeType.DEACTIVATED
            assert northbound.getSwitch(dstSwToDeactivate.dpId).state == SwitchChangeType.DEACTIVATED
        }

        then: "The round trip latency ISL is FAILED (because round_trip_status is not available in DB for current ISL \
on both switches)"
        Wrappers.wait(discoveryTimeout + WAIT_OFFSET / 2) {
            assert northbound.getLink(roundTripIsl).state == IslChangeType.FAILED
        }

        cleanup:
        if (areSwitchesDeactivated) {
            lockKeeper.reviveSwitch(srcSwToDeactivate, mgmtBlockDataSrcSw)
            lockKeeper.reviveSwitch(dstSwToDeactivate, mgmtBlockDataDstSw)
            Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
                assert northbound.getSwitch(srcSwToDeactivate.dpId).state == SwitchChangeType.ACTIVATED
                assert northbound.getSwitch(dstSwToDeactivate.dpId).state == SwitchChangeType.ACTIVATED
                assert northbound.getAllLinks().findAll {
                    it.state == IslChangeType.DISCOVERED
                }.size() == topology.islsForActiveSwitches.size() * 2
            }
        }
    }

    @Tidy
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
        [roundTripIsl, roundTripIsl.reversed].each { assert database.getIslRoundTripStatus(it) == IslStatus.ACTIVE }

        when: "Simulate connection lose between the src switch and FL, switches become DEACTIVATED and remain operable"
        def mgmtBlockData = lockKeeper.knockoutSwitch(srcSwToDeactivate, mgmtFlManager)
        def isSrcSwDeactivated = true
        Wrappers.wait(customWaitOffset) {
            assert northbound.getSwitch(srcSwToDeactivate.dpId).state == SwitchChangeType.DEACTIVATED
        }

        then: "Round trip status for forward direction is not available and ACTIVE in reverse direction"
        Wrappers.wait(discoveryTimeout + WAIT_OFFSET / 2) {
            assert database.getIslRoundTripStatus(roundTripIsl) == IslStatus.INACTIVE
            assert database.getIslRoundTripStatus(roundTripIsl.reversed) == IslStatus.ACTIVE
        }

        when: "Delete ROUND_TRIP_LATENCY_RULE_COOKIE on the dst switch"
        northbound.deleteSwitchRules(dstSw.dpId, DeleteRulesAction.REMOVE_ROUND_TRIP_LATENCY)
        def isRoundTripRuleDeleted = true
        Wrappers.wait(RULES_DELETION_TIME) {
            assert northbound.validateSwitch(dstSw.dpId).rules.missing.size() == 1
        }

        then: "The round trip latency ISL is FAILED"
        Wrappers.wait(discoveryTimeout + WAIT_OFFSET / 2) {
            assert northbound.getLink(roundTripIsl).state == IslChangeType.FAILED
        }

        and: "Round trip status is not available for the given ISL in both directions"
        Wrappers.wait(WAIT_OFFSET / 2) {
            [roundTripIsl, roundTripIsl.reversed].each {
                assert database.getIslRoundTripStatus(it) == IslStatus.INACTIVE
            }
        }

        when: "Restore connection between the src switch and FL"
        lockKeeper.reviveSwitch(srcSwToDeactivate, mgmtBlockData)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getSwitch(srcSwToDeactivate.dpId).state == SwitchChangeType.ACTIVATED
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.DISCOVERED
            }.size() == topology.islsForActiveSwitches.size() * 2
        }
        isSrcSwDeactivated = false

        then: "Round trip isl is DISCOVERED"
        northbound.getLink(roundTripIsl).state == IslChangeType.DISCOVERED

        and: "Round trip status is available for the given ISL in forward direction only"
        Wrappers.wait(WAIT_OFFSET / 2) {
            assert database.getIslRoundTripStatus(roundTripIsl) == IslStatus.ACTIVE
            assert database.getIslRoundTripStatus(roundTripIsl.reversed) == IslStatus.INACTIVE
        }

        when: "Install ROUND_TRIP_LATENCY_RULE_COOKIE on the dst switch"
        northbound.installSwitchRules(dstSw.dpId, InstallRulesAction.INSTALL_ROUND_TRIP_LATENCY)
        isRoundTripRuleDeleted = false
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert northbound.validateSwitch(dstSw.dpId).rules.missing.empty
        }

        then: "Round trip status is available for the given ISL in both directions"
        Wrappers.wait(WAIT_OFFSET / 2) {
            [roundTripIsl, roundTripIsl.reversed].each { assert database.getIslRoundTripStatus(it) == IslStatus.ACTIVE }
        }

        cleanup:
        isSrcSwDeactivated && lockKeeper.reviveSwitch(srcSwToDeactivate, mgmtBlockData)
        isRoundTripRuleDeleted && northbound.installSwitchRules(dstSw.dpId, InstallRulesAction.INSTALL_ROUND_TRIP_LATENCY)
        if (isSrcSwDeactivated || isRoundTripRuleDeleted) {
            Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
                assert northbound.getSwitch(srcSwToDeactivate.dpId).state == SwitchChangeType.ACTIVATED
                assert northbound.getAllLinks().findAll {
                    it.state == IslChangeType.DISCOVERED
                }.size() == topology.islsForActiveSwitches.size() * 2
                assert northbound.validateSwitch(dstSw.dpId).rules.missing.empty
            }
        }
    }
}
