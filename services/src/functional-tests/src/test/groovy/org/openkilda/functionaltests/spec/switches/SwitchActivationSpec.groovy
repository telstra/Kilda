package org.openkilda.functionaltests.spec.switches

import static org.junit.Assume.assumeTrue
import static org.openkilda.model.MeterId.MAX_SYSTEM_RULE_METER_ID
import static org.openkilda.model.MeterId.MIN_FLOW_METER_ID
import static org.openkilda.testing.Constants.NON_EXISTENT_FLOW_ID
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.tags.Tag
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.SwitchHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.Message
import org.openkilda.messaging.command.CommandData
import org.openkilda.messaging.command.CommandMessage
import org.openkilda.messaging.command.flow.InstallEgressFlow
import org.openkilda.messaging.command.flow.InstallIngressFlow
import org.openkilda.messaging.command.flow.InstallTransitFlow
import org.openkilda.messaging.command.switches.DeleteRulesAction
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.model.Cookie
import org.openkilda.model.FlowApplication
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.Metadata
import org.openkilda.model.OutputVlanType

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value

class SwitchActivationSpec extends HealthCheckSpecification {
    @Value("#{kafkaTopicsConfig.getSpeakerFlowTopic()}")
    String flowTopic
    @Autowired
    @Qualifier("kafkaProducerProperties")
    Properties producerProps
    @Autowired
    SwitchHelper switchHelper

    def "Missing flow rules/meters are installed on a new switch before connecting to the controller"() {
        given: "A switch with missing flow rules/meters and not connected to the controller"
        def switchPair = topologyHelper.getNeighboringSwitchPair()
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)

        def createdMeterIds = northbound.getAllMeters(switchPair.src.dpId).meterEntries.findAll {
            it.meterId > MAX_SYSTEM_RULE_METER_ID
        }*.meterId
        assert createdMeterIds.size() == 1
        def createdCookies = northbound.getSwitchRules(switchPair.src.dpId).flowEntries.findAll {
            !Cookie.isDefaultRule(it.cookie)
        }*.cookie
        assert createdCookies.size() == 2

        northbound.deleteMeter(switchPair.src.dpId, createdMeterIds[0])
        northbound.deleteSwitchRules(switchPair.src.dpId, DeleteRulesAction.IGNORE_DEFAULTS)
        Wrappers.wait(WAIT_OFFSET) {
            verifyAll(northbound.validateSwitch(switchPair.src.dpId)) {
                it.rules.missing.containsAll(createdCookies)
                switchHelper.verifyRuleSectionsAreEmpty(it, ["proper", "excess"])
                it.meters.missing.size() == 1
                switchHelper.verifyMeterSectionsAreEmpty(it, ["proper", "misconfigured", "excess"])
            }
        }

        lockKeeper.knockoutSwitch(switchPair.src)
        Wrappers.wait(WAIT_OFFSET) { assert !(switchPair.src.dpId in northbound.getActiveSwitches()*.switchId) }

        when: "Connect the switch to the controller"
        lockKeeper.reviveSwitch(switchPair.src)
        Wrappers.wait(WAIT_OFFSET) { assert switchPair.src.dpId in northbound.getActiveSwitches()*.switchId }

        then: "Missing flow rules/meters were synced during switch activation"
        verifyAll(northbound.validateSwitch(switchPair.src.dpId)) {
            it.rules.proper.containsAll(createdCookies)
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "excess"])
            it.meters.proper*.meterId == createdMeterIds
            switchHelper.verifyMeterSectionsAreEmpty(it, ["missing", "excess", "misconfigured"])
        }

        and: "Cleanup: Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    def "Excess rules/meters are synced from a new switch before connecting to the controller"() {
        given: "A switch with excess rules/meters and not connected to the controller"
        def sw = topology.getActiveSwitches().find { it.virtual }
        assumeTrue("Unable to find required switches in topology", sw as boolean)

        def producer = new KafkaProducer(producerProps)
        //pick a meter id which is not yet used on src switch
        def excessMeterId = ((MIN_FLOW_METER_ID..100) - northbound.getAllMeters(sw.dpId)
                                                                  .meterEntries*.meterId).first()
        producer.send(new ProducerRecord(flowTopic, sw.dpId.toString(), buildMessage(
                new InstallEgressFlow(UUID.randomUUID(), NON_EXISTENT_FLOW_ID, 1L, sw.dpId, 1, 2, 1,
                        FlowEncapsulationType.TRANSIT_VLAN, 1, OutputVlanType.REPLACE, false,
                        new HashSet<FlowApplication>(), Metadata.builder().build())).toJson()))

        producer.send(new ProducerRecord(flowTopic, sw.dpId.toString(), buildMessage(
                new InstallTransitFlow(UUID.randomUUID(), NON_EXISTENT_FLOW_ID, 2L, sw.dpId, 3, 4, 1,
                        FlowEncapsulationType.TRANSIT_VLAN, false)).toJson()))

        producer.send(new ProducerRecord(flowTopic, sw.dpId.toString(), buildMessage(
                new InstallIngressFlow(UUID.randomUUID(), NON_EXISTENT_FLOW_ID, 3L, sw.dpId, 5, 6, 1, 1,
                        FlowEncapsulationType.TRANSIT_VLAN,
                        OutputVlanType.REPLACE, 300, excessMeterId, sw.dpId, false, false,
                        new HashSet<FlowApplication>(), Metadata.builder().build())).toJson()))
        producer.flush()

        Wrappers.wait(WAIT_OFFSET) {
            verifyAll(northbound.validateSwitch(sw.dpId)) {
                it.rules.excess.size() == 3
                switchHelper.verifyRuleSectionsAreEmpty(it, ["proper", "missing"])
                it.meters.excess.size() == 1
                switchHelper.verifyMeterSectionsAreEmpty(it, ["missing", "proper", "misconfigured"])
            }
        }

        lockKeeper.knockoutSwitch(sw)
        Wrappers.wait(WAIT_OFFSET) { assert !(sw.dpId in northbound.getActiveSwitches()*.switchId) }

        when: "Connect the switch to the controller"
        lockKeeper.reviveSwitch(sw)
        Wrappers.wait(WAIT_OFFSET) { assert sw.dpId in northbound.getActiveSwitches()*.switchId }

        then: "Excess meters/rules were synced during switch activation"
        verifyAll(northbound.validateSwitch(sw.dpId)) {
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "excess", "proper"])
        }
    }

    @Tags([Tag.VIRTUAL])
    def "New connected switch is properly discovered with related ISLs in a reasonable time"() {
        /*antiflapCooldown should be rather big in order for test to understand that there is no 'antiflap' during
        isl discovery*/
        assumeTrue(antiflapCooldown >= discoveryInterval + WAIT_OFFSET / 2)

        setup: "Disconnect one of the switches and remove it from DB. Pretend this switch never existed"
        def sw = topology.activeSwitches.first()
        def isls = topology.getRelatedIsls(sw)
        lockKeeper.knockoutSwitch(sw)
        Wrappers.wait(discoveryTimeout + WAIT_OFFSET) {
            assert northbound.getSwitch(sw.dpId).state == SwitchChangeType.DEACTIVATED
            assert northbound.getAllLinks().findAll { it.state == IslChangeType.FAILED }.size() == isls.size() * 2
        }
        isls.each { northbound.deleteLink(islUtils.toLinkParameters(it)) }
        northbound.deleteSwitch(sw.dpId, false)

        when: "New switch connects"
        lockKeeper.reviveSwitch(sw)

        then: "Switch is activated"
        Wrappers.wait(WAIT_OFFSET / 2) {
            assert northbound.getSwitch(sw.dpId).state == SwitchChangeType.ACTIVATED
        }

        and: "Related ISLs are discovered without antiflap"
        Wrappers.wait(discoveryInterval + WAIT_OFFSET / 2) {
            def allIsls = northbound.getAllLinks()
            isls.each {
                assert islUtils.getIslInfo(allIsls, it).get().state == IslChangeType.DISCOVERED
                assert islUtils.getIslInfo(allIsls, it.reversed).get().state == IslChangeType.DISCOVERED
            }
        }
    }

    private static Message buildMessage(final CommandData data) {
        return new CommandMessage(data, System.currentTimeMillis(), UUID.randomUUID().toString(), null)
    }
}
