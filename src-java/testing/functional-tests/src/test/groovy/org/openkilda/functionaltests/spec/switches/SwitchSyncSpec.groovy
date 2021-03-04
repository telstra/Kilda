package org.openkilda.functionaltests.spec.switches

import static org.junit.jupiter.api.Assumptions.assumeFalse
import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.helpers.SwitchHelper.isDefaultMeter
import static org.openkilda.model.MeterId.MAX_SYSTEM_RULE_METER_ID
import static org.openkilda.model.MeterId.MIN_FLOW_METER_ID
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME

import org.openkilda.functionaltests.BaseSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.Message
import org.openkilda.messaging.command.CommandMessage
import org.openkilda.messaging.command.flow.BaseInstallFlow
import org.openkilda.messaging.command.flow.InstallFlowForSwitchManagerRequest
import org.openkilda.messaging.command.flow.InstallIngressFlow
import org.openkilda.messaging.command.flow.InstallTransitFlow
import org.openkilda.messaging.command.switches.DeleteRulesAction
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.OutputVlanType
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import spock.lang.Ignore
import spock.lang.See
import spock.lang.Unroll

@See(["https://github.com/telstra/open-kilda/tree/develop/docs/design/hub-and-spoke/switch-sync",
"https://github.com/telstra/open-kilda/blob/develop/docs/design/network-discovery/switch-FSM.png"])
@Tags([SMOKE_SWITCHES])
class SwitchSyncSpec extends BaseSpecification {

    @Value("#{kafkaTopicsConfig.getSpeakerTopic()}")
    String speakerTopic

    @Autowired
    @Qualifier("kafkaProducerProperties")
    Properties producerProps

    @Tidy
    @Unroll
    def "Able to synchronize switch without any rule and meter discrepancies (removeExcess=#removeExcess)"() {
        given: "An active switch"
        def sw = topology.activeSwitches.first()

        when: "Synchronize the switch that doesn't have any rule and meter discrepancies"
        def syncResult = northbound.synchronizeSwitch(sw.dpId, removeExcess)

        then: "Operation is successful"
        syncResult.rules.proper.findAll { !new Cookie(it).serviceFlag }.size() == 0
        syncResult.rules.excess.size() == 0
        syncResult.rules.missing.size() == 0
        syncResult.rules.removed.size() == 0
        syncResult.rules.installed.size() == 0

        def properMeters = syncResult.meters.proper.findAll({ !isDefaultMeter(it) })
        properMeters.size() == 0
        syncResult.meters.excess.size() == 0
        syncResult.meters.missing.size() == 0
        syncResult.meters.removed.size() == 0
        syncResult.meters.installed.size() == 0

        where:
        removeExcess << [false, true]
    }

    @Tidy
    def "Able to synchronize switch (install missing rules and meters)"() {
        given: "Two active not neighboring switches"
        assumeFalse(useMultitable, "This test should be fixed for multiTable mode + server42")
        def switchPair = topologyHelper.allNotNeighboringSwitchPairs.find { it.src.ofVersion != "OF_12" &&
                it.dst.ofVersion != "OF_12" } ?: assumeTrue(false, "No suiting switches found")

        and: "Create an intermediate-switch flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)

        and: "Drop all rules an meters from related switches (both default and non-default)"
        def involvedSwitches = pathHelper.getInvolvedSwitches(flow.flowId)
        def cookiesMap = involvedSwitches.collectEntries { sw ->
            [sw.dpId, northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
                !(it.cookie in sw.defaultCookies)
            }*.cookie]
        }
        Map<SwitchId, List<Long>> metersMap = involvedSwitches.collectEntries { sw ->
            [sw.dpId, northbound.getAllMeters(sw.dpId).meterEntries*.meterId]
        }

        involvedSwitches.each { sw ->
            northbound.deleteSwitchRules(sw.dpId, DeleteRulesAction.DROP_ALL)
            northbound.getAllMeters(sw.dpId).meterEntries.each { northbound.deleteMeter(sw.dpId, it.meterId) }
        }
        Wrappers.wait(RULES_DELETION_TIME) {
            def validationResultsMap = involvedSwitches.collectEntries { [it.dpId, northbound.validateSwitch(it.dpId)] }
            involvedSwitches[1..-2].each {
                assert validationResultsMap[it.dpId].rules.missing.size() == 2 + it.defaultCookies.size()
                assert validationResultsMap[it.dpId].rules.missingHex.size() == 2 + it.defaultCookies.size()
                assert validationResultsMap[it.dpId].meters.missing.meterId.sort() == it.defaultMeters.sort()
            }
            [switchPair.src, switchPair.dst].each {
                def swProps = northbound.getSwitchProperties(it.dpId)
                def amountOfSharedRules = (swProps.multiTable ? 1 : 0) + (swProps.server42FlowRtt ? 1 : 0)
                assert validationResultsMap[it.dpId].rules.missing.size() == 2 + it.defaultCookies.size() + amountOfSharedRules
                assert validationResultsMap[it.dpId].rules.missingHex.size() == 2 + it.defaultCookies.size() + amountOfSharedRules
                assert validationResultsMap[it.dpId].meters.missing.size() == 1 + it.defaultMeters.size()
            }
        }

        when: "Try to synchronize all switches"
        def syncResultsMap = involvedSwitches.collectEntries { [it.dpId, northbound.synchronizeSwitch(it.dpId, false)] }

        then: "System detects missing rules and meters (both default and flow-related), then installs them"
        involvedSwitches.each {
            assert syncResultsMap[it.dpId].rules.proper.size() == 0
            assert syncResultsMap[it.dpId].rules.excess.size() == 0
            assert syncResultsMap[it.dpId].rules.missing.containsAll(cookiesMap[it.dpId])
            assert syncResultsMap[it.dpId].rules.removed.size() == 0
            assert syncResultsMap[it.dpId].rules.installed.containsAll(cookiesMap[it.dpId])
        }
        [switchPair.src, switchPair.dst].each {
            assert syncResultsMap[it.dpId].meters.proper.size() == 0
            assert syncResultsMap[it.dpId].meters.excess.size() == 0
            assert syncResultsMap[it.dpId].meters.missing*.meterId == metersMap[it.dpId].sort()
            assert syncResultsMap[it.dpId].meters.removed.size() == 0
            assert syncResultsMap[it.dpId].meters.installed*.meterId == metersMap[it.dpId].sort()
        }

        and: "Switch validation doesn't complain about any missing rules and meters"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            involvedSwitches.each {
                def validationResult = northbound.validateSwitch(it.dpId)
                assert validationResult.rules.missing.size() == 0
                if(!it.dpId.description.contains("OF_12")) {
                    assert validationResult.meters.missing.size() == 0
                }
            }
        }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    def "Able to synchronize switch (delete excess rules and meters)"() {
        given: "Two active not neighboring switches"
        def switches = topology.getActiveSwitches()
        def allLinks = northbound.getAllLinks()
        def (Switch srcSwitch, Switch dstSwitch) = [switches, switches].combinations()
                .findAll { src, dst -> src != dst }.find { Switch src, Switch dst ->
            allLinks.every { link -> !(link.source.switchId == src.dpId && link.destination.switchId == dst.dpId) }
        } ?: assumeTrue(false, "No suiting switches found")

        and: "Create an intermediate-switch flow"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)

        and: "Reproduce situation when switches have excess rules and meters"
        def involvedSwitches = pathHelper.getInvolvedSwitches(flow.flowId)
        def cookiesMap = involvedSwitches.collectEntries { sw ->
            [sw.dpId, northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
                !(it.cookie in sw.defaultCookies)
            }*.cookie]
        }
        Map<SwitchId, List<Long>> metersMap = involvedSwitches.collectEntries { sw ->
            [sw.dpId, northbound.getAllMeters(sw.dpId).meterEntries*.meterId]
        }

        def producer = new KafkaProducer(producerProps)
        def excessRuleCookie = 1234567890L
        def excessMeterId = ((MIN_FLOW_METER_ID..100)
                - northbound.getAllMeters(srcSwitch.dpId).meterEntries*.meterId
                - northbound.getAllMeters(dstSwitch.dpId).meterEntries*.meterId)[0]

        producer.send(new ProducerRecord(speakerTopic, srcSwitch.dpId.toString(), buildMessage(
                new InstallIngressFlow(UUID.randomUUID(), flow.flowId, excessRuleCookie, srcSwitch.dpId, 1, 2, 1, 0, 1,
                        FlowEncapsulationType.TRANSIT_VLAN,
                        OutputVlanType.REPLACE, flow.maximumBandwidth, excessMeterId, dstSwitch.dpId, false,
                        false, false)).toJson()))
        involvedSwitches[1..-2].each { transitSw ->
            producer.send(new ProducerRecord(speakerTopic, transitSw.toString(), buildMessage(
                    new InstallTransitFlow(UUID.randomUUID(), flow.flowId, excessRuleCookie, transitSw.dpId, 1, 2, 1,
                            FlowEncapsulationType.TRANSIT_VLAN, false))
                    .toJson()))
        }
        producer.send(new ProducerRecord(speakerTopic, dstSwitch.dpId.toString(), buildMessage(
                new InstallIngressFlow(UUID.randomUUID(), flow.flowId, excessRuleCookie, dstSwitch.dpId, 1, 2, 1, 0, 1,
                        FlowEncapsulationType.TRANSIT_VLAN,
                        OutputVlanType.REPLACE, flow.maximumBandwidth, excessMeterId, srcSwitch.dpId, false,
                        false, false)).toJson()))

        Wrappers.wait(RULES_INSTALLATION_TIME) {
            def validationResultsMap = involvedSwitches.collectEntries { [it.dpId, northbound.validateSwitch(it.dpId)] }
            involvedSwitches.each {
                assert validationResultsMap[it.dpId].rules.excess.size() == 1
                assert validationResultsMap[it.dpId].rules.excessHex.size() == 1
            }
            [srcSwitch, dstSwitch].each { assert validationResultsMap[it.dpId].meters.excess.size() == 1 }
        }

        when: "Try to synchronize all switches"
        def syncResultsMap = involvedSwitches.collectEntries { [it.dpId, northbound.synchronizeSwitch(it.dpId, true)] }

        then: "System detects excess rules and meters, then deletes them"
        involvedSwitches.each {
            assert syncResultsMap[it.dpId].rules.proper.containsAll(cookiesMap[it.dpId])
            assert syncResultsMap[it.dpId].rules.excess == [excessRuleCookie]
            assert syncResultsMap[it.dpId].rules.missing.size() == 0
            assert syncResultsMap[it.dpId].rules.removed == [excessRuleCookie]
            assert syncResultsMap[it.dpId].rules.installed.size() == 0
        }
        [srcSwitch, dstSwitch].each {
            assert syncResultsMap[it.dpId].meters.proper*.meterId == metersMap[it.dpId].sort()
            assert syncResultsMap[it.dpId].meters.excess*.meterId == [excessMeterId]
            assert syncResultsMap[it.dpId].meters.missing.size() == 0
            assert syncResultsMap[it.dpId].meters.removed*.meterId == [excessMeterId]
            assert syncResultsMap[it.dpId].meters.installed.size() == 0
        }

        and: "Switch validation doesn't complain about excess rules and meters"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            involvedSwitches.each {
                def validationResult = northbound.validateSwitch(it.dpId)
                assert validationResult.rules.excess.size() == 0
                assert validationResult.rules.excessHex.size() == 0
                if(!it.dpId.description.contains("OF_12")) {
                    assert validationResult.meters.missing.size() == 0
                }
            }
        }

        and: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Tags(HARDWARE)
    @Ignore("https://github.com/telstra/open-kilda/issues/3021")
    def "Able to synchronize switch with 'vxlan' rule(install missing rules and meters)"() {
        given: "Two active not neighboring Noviflow switches"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { swP ->
            swP.src.noviflow && !swP.src.wb5164 && swP.dst.noviflow && !swP.dst.wb5164 && swP.paths.find { path ->
                pathHelper.getInvolvedSwitches(path).every { it.noviflow && !it.wb5164 }
            }
        } ?: assumeTrue(false, "Unable to find required switches in topology")

        and: "Create a flow with vxlan encapsulation"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.encapsulationType = FlowEncapsulationType.VXLAN
        flowHelperV2.addFlow(flow)

        and: "Reproduce situation when switches have missing rules and meters"
        def flowInfoFromDb = database.getFlow(flow.flowId)
        def involvedSwitches = pathHelper.getInvolvedSwitches(flow.flowId)
        def transitSwitchIds = involvedSwitches[1..-2]*.dpId
        def cookiesMap = involvedSwitches.collectEntries { sw ->
            [sw.dpId, northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
                !(it.cookie in sw.defaultCookies)
            }*.cookie]
        }
        def metersMap = involvedSwitches.collectEntries { sw ->
            [sw.dpId, northbound.getAllMeters(sw.dpId).meterEntries.findAll {
                it.meterId > MAX_SYSTEM_RULE_METER_ID
            }*.meterId]
        }

        involvedSwitches.each { northbound.deleteSwitchRules(it.dpId, DeleteRulesAction.IGNORE_DEFAULTS) }
        [switchPair.src, switchPair.dst].each { northbound.deleteMeter(it.dpId, metersMap[it.dpId][0]) }
        Wrappers.wait(RULES_DELETION_TIME) {
            def validationResultsMap = involvedSwitches.collectEntries { [it.dpId, northbound.validateSwitch(it.dpId)] }
            involvedSwitches.each { assert validationResultsMap[it.dpId].rules.missing.size() == 2 }
            [switchPair.src, switchPair.dst].each { assert validationResultsMap[it.dpId].meters.missing.size() == 1 }
        }

        when: "Try to synchronize all switches"
        def syncResultsMap = involvedSwitches.collectEntries { [it.dpId, northbound.synchronizeSwitch(it.dpId, false)] }

        then: "System detects missing rules and meters, then installs them"
        involvedSwitches.each {
            assert syncResultsMap[it.dpId].rules.proper.findAll { !new Cookie(it).serviceFlag }.size() == 0
            assert syncResultsMap[it.dpId].rules.excess.size() == 0
            assert syncResultsMap[it.dpId].rules.missing.containsAll(cookiesMap[it.dpId])
            assert syncResultsMap[it.dpId].rules.removed.size() == 0
            assert syncResultsMap[it.dpId].rules.installed.containsAll(cookiesMap[it.dpId])
        }
        [switchPair.src, switchPair.dst].each {
            assert syncResultsMap[it.dpId].meters.proper.findAll { !new Cookie(it).serviceFlag }.size() == 0
            assert syncResultsMap[it.dpId].meters.excess.size() == 0
            assert syncResultsMap[it.dpId].meters.missing*.meterId == metersMap[it.dpId]
            assert syncResultsMap[it.dpId].meters.removed.size() == 0
            assert syncResultsMap[it.dpId].meters.installed*.meterId == metersMap[it.dpId]
        }

        and: "Switch validation doesn't complain about missing rules and meters"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            involvedSwitches.each {
                def validationResult = northbound.validateSwitch(it.dpId)
                assert validationResult.rules.missing.size() == 0
                assert validationResult.meters.missing.size() == 0
            }
        }

        and: "Rules are synced correctly"
        // ingressRule should contain "pushVxlan"
        // egressRule should contain "tunnel-id"
        with(northbound.getSwitchRules(switchPair.src.dpId).flowEntries) { rules ->
            assert rules.find {
                it.cookie == flowInfoFromDb.forwardPath.cookie.value
            }.instructions.applyActions.pushVxlan
            assert rules.find {
                it.cookie == flowInfoFromDb.reversePath.cookie.value
            }.match.tunnelId
        }

        with(northbound.getSwitchRules(switchPair.dst.dpId).flowEntries) { rules ->
            assert rules.find {
                it.cookie == flowInfoFromDb.forwardPath.cookie.value
            }.match.tunnelId
            assert rules.find {
                it.cookie == flowInfoFromDb.reversePath.cookie.value
            }.instructions.applyActions.pushVxlan
        }

        transitSwitchIds.each { swId ->
            with(northbound.getSwitchRules(swId).flowEntries) { rules ->
                assert rules.find {
                    it.cookie == flowInfoFromDb.forwardPath.cookie.value
                }.match.tunnelId
                assert rules.find {
                    it.cookie == flowInfoFromDb.reversePath.cookie.value
                }.match.tunnelId
            }
        }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    private static Message buildMessage(final BaseInstallFlow commandData) {
        InstallFlowForSwitchManagerRequest data = new InstallFlowForSwitchManagerRequest(commandData)
        return new CommandMessage(data, System.currentTimeMillis(), UUID.randomUUID().toString(), null)
    }
}
