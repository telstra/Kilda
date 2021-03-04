package org.openkilda.functionaltests.spec.switches

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.helpers.SwitchHelper.isDefaultMeter
import static org.openkilda.model.MeterId.MAX_SYSTEM_RULE_METER_ID
import static org.openkilda.model.MeterId.MIN_FLOW_METER_ID
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.SwitchHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.Message
import org.openkilda.messaging.command.CommandMessage
import org.openkilda.messaging.command.flow.BaseInstallFlow
import org.openkilda.messaging.command.flow.InstallEgressFlow
import org.openkilda.messaging.command.flow.InstallFlowForSwitchManagerRequest
import org.openkilda.messaging.command.flow.InstallIngressFlow
import org.openkilda.messaging.command.flow.InstallTransitFlow
import org.openkilda.messaging.command.switches.DeleteRulesAction
import org.openkilda.messaging.payload.flow.DetectConnectedDevicesPayload
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.FlowEndpoint
import org.openkilda.model.OutputVlanType
import org.openkilda.model.SwitchFeature
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.model.cookie.CookieBase.CookieType
import org.openkilda.northbound.dto.v1.switches.SwitchPropertiesDto
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import spock.lang.Ignore
import spock.lang.Narrative
import spock.lang.See
import spock.lang.Unroll

@See(["https://github.com/telstra/open-kilda/tree/develop/docs/design/hub-and-spoke/switch-validate",
        "https://github.com/telstra/open-kilda/tree/develop/docs/design/hub-and-spoke/switch-sync"])
@Narrative("""This test suite checks the switch validate feature followed by switch synchronization for different type
of rules and different rules states.
Rules states: excess, missing, proper.
Rule types: ingress, egress, transit, special (protected path rules, connected devices rules etc.)
Note that 'default' rules are not covered by this spec.
Description of fields:
- missing - those meters/rules, which are NOT present on a switch, but are present in db
- misconfigured - those meters which have different value (on a switch and in db) for the same parameter
- excess - those meters/rules, which are present on a switch, but are NOT present in db
- proper - meters/rules values are the same on a switch and in db
""")
@Tags([SMOKE, SMOKE_SWITCHES])
class SwitchValidationSpec extends HealthCheckSpecification {
    @Value("#{kafkaTopicsConfig.getSpeakerTopic()}")
    String speakerTopic
    @Autowired
    @Qualifier("kafkaProducerProperties")
    Properties producerProps

    def "Able to validate and sync a terminating switch with proper rules and meters"() {
        given: "A flow"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches.findAll { it.ofVersion != "OF_12" }
        def flow = flowHelperV2.addFlow(flowHelperV2.randomFlow(srcSwitch, dstSwitch))

        expect: "Validate switch for src and dst contains expected meters data in 'proper' section"
        def srcSwitchValidateInfo = northbound.validateSwitch(srcSwitch.dpId)
        def dstSwitchValidateInfo = northbound.validateSwitch(dstSwitch.dpId)
        def srcSwitchCreatedCookies = getCookiesWithMeter(srcSwitch.dpId)
        def dstSwitchCreatedCookies = getCookiesWithMeter(dstSwitch.dpId)

        srcSwitchValidateInfo.meters.proper*.meterId.containsAll(getCreatedMeterIds(srcSwitch.dpId))
        dstSwitchValidateInfo.meters.proper*.meterId.containsAll(getCreatedMeterIds(dstSwitch.dpId))

        srcSwitchValidateInfo.meters.proper*.cookie.containsAll(srcSwitchCreatedCookies)
        dstSwitchValidateInfo.meters.proper*.cookie.containsAll(dstSwitchCreatedCookies)

        def srcSwitchProperMeters = srcSwitchValidateInfo.meters.proper.findAll({ !isDefaultMeter(it) })
        def dstSwitchProperMeters = dstSwitchValidateInfo.meters.proper.findAll({ !isDefaultMeter(it) })

        [srcSwitchProperMeters, dstSwitchProperMeters].each {
            it.each {
                assert it.rate == flow.maximumBandwidth
                assert it.flowId == flow.flowId
                assert ["KBPS", "BURST", "STATS"].containsAll(it.flags)
            }
        }

        Long srcSwitchBurstSize = switchHelper.getExpectedBurst(srcSwitch.dpId, flow.maximumBandwidth)
        Long dstSwitchBurstSize = switchHelper.getExpectedBurst(dstSwitch.dpId, flow.maximumBandwidth)
        switchHelper.verifyBurstSizeIsCorrect(srcSwitch, srcSwitchBurstSize,
            srcSwitchProperMeters*.burstSize[0])
        switchHelper.verifyBurstSizeIsCorrect(dstSwitch, dstSwitchBurstSize,
            dstSwitchProperMeters*.burstSize[0])

        and: "The rest fields in the 'meter' section are empty"
        [srcSwitchValidateInfo, dstSwitchValidateInfo].each {
            switchHelper.verifyMeterSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
        }

        and: "Created rules are stored in the 'proper' section"
        def createdCookies = srcSwitchCreatedCookies + dstSwitchCreatedCookies
        [srcSwitchValidateInfo, dstSwitchValidateInfo].each {
            it.rules.proper.containsAll(createdCookies)
        }

        and: "The rest fields in the 'rule' section are empty"
        [srcSwitchValidateInfo, dstSwitchValidateInfo].each {
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "excess"])
        }

        and: "Able to perform switch sync which does nothing"
        verifyAll(northbound.synchronizeSwitch(srcSwitch.dpId, true)) {
            it.rules.removed.empty
            it.rules.installed.empty
            it.meters.removed.empty
            it.meters.installed.empty
        }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "Switch validate request returns only default rules information"
        Wrappers.wait(WAIT_OFFSET) {
            def srcSwitchValidateInfoAfterDelete = northbound.validateSwitch(srcSwitch.dpId)
            def dstSwitchValidateInfoAfterDelete = northbound.validateSwitch(dstSwitch.dpId)
            [srcSwitchValidateInfoAfterDelete, dstSwitchValidateInfoAfterDelete].each {
                switchHelper.verifyRuleSectionsAreEmpty(it)
                switchHelper.verifyMeterSectionsAreEmpty(it)
            }
        }
    }

    def "Able to validate and sync a transit switch with proper rules and no meters"() {
        given: "Two active not neighboring switches"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { pair ->
            def possibleDefaultPaths = pair.paths.findAll { it.size() == pair.paths.min { it.size() }.size() }
            //ensure the path won't have only OF_12 intermediate switches
            !possibleDefaultPaths.find { path ->
                path[1..-2].every { it.switchId.description.contains("OF_12") }
            }
        } ?: assumeTrue(false, "No not-neighbouring switch pairs found")

        when: "Create an intermediate-switch flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))

        then: "The intermediate switch does not contain any information about meter"
        def switchToValidate = flowPath[1..-2].find { !it.switchId.description.contains("OF_12") }
        def intermediateSwitchValidateInfo = northbound.validateSwitch(switchToValidate.switchId)
        switchHelper.verifyMeterSectionsAreEmpty(intermediateSwitchValidateInfo)

        and: "Rules are stored in the 'proper' section on the transit switch"
        intermediateSwitchValidateInfo.rules.proper.findAll { !new Cookie(it).serviceFlag }.size() == 2
        switchHelper.verifyRuleSectionsAreEmpty(intermediateSwitchValidateInfo, ["missing", "excess"])

        and: "Able to perform switch sync which does nothing"
        verifyAll(northbound.synchronizeSwitch(switchToValidate.switchId, true)) {
            it.rules.removed.empty
            it.rules.installed.empty
            it.meters.removed.empty
            it.meters.installed.empty
        }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "Check that the switch validate request returns empty sections"
        pathHelper.getInvolvedSwitches(flowPath).each { sw ->
            def switchValidateInfo = northbound.validateSwitch(sw.dpId)
            switchHelper.verifyRuleSectionsAreEmpty(switchValidateInfo)
            if (sw.description.contains("OF_13")) {
                switchHelper.verifyMeterSectionsAreEmpty(switchValidateInfo)
            }
        }
    }

    def "Able to validate switch with 'misconfigured' meters"() {
        when: "Create a flow"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches.findAll { it.ofVersion != "OF_12" }
        def flow = flowHelperV2.addFlow(flowHelperV2.randomFlow(srcSwitch, dstSwitch))
        def srcSwitchCreatedMeterIds = getCreatedMeterIds(srcSwitch.dpId)
        def dstSwitchCreatedMeterIds = getCreatedMeterIds(dstSwitch.dpId)

        and: "Change bandwidth for the created flow directly in DB so that system thinks the installed meter is \
misconfigured"
        def newBandwidth = flow.maximumBandwidth + 100
        /** at this point meter is set for given flow. Now update flow bandwidth directly via DB,
         it is done just for moving meter from the 'proper' section into the 'misconfigured'*/
        database.updateFlowBandwidth(flow.flowId, newBandwidth)
        //at this point existing meters do not correspond with the flow

        and: "Validate src and dst switches"
        def srcSwitchValidateInfo = northbound.validateSwitch(srcSwitch.dpId)
        def dstSwitchValidateInfo = northbound.validateSwitch(dstSwitch.dpId)

        then: "Meters info is moved into the 'misconfigured' section"
        def srcSwitchCreatedCookies = getCookiesWithMeter(srcSwitch.dpId)
        def dstSwitchCreatedCookies = getCookiesWithMeter(dstSwitch.dpId)

        srcSwitchValidateInfo.meters.misconfigured*.meterId.containsAll(srcSwitchCreatedMeterIds)
        dstSwitchValidateInfo.meters.misconfigured*.meterId.containsAll(dstSwitchCreatedMeterIds)

        srcSwitchValidateInfo.meters.misconfigured*.cookie.containsAll(srcSwitchCreatedCookies)
        dstSwitchValidateInfo.meters.misconfigured*.cookie.containsAll(dstSwitchCreatedCookies)

        [srcSwitchValidateInfo, dstSwitchValidateInfo].each {
            assert it.meters.misconfigured.meterId.size() == 1
            it.meters.misconfigured.each {
                assert it.rate == flow.maximumBandwidth
                assert it.flowId == flow.flowId
                assert ["KBPS", "BURST", "STATS"].containsAll(it.flags)
            }
        }

        Long srcSwitchBurstSize = switchHelper.getExpectedBurst(srcSwitch.dpId, flow.maximumBandwidth)
        Long dstSwitchBurstSize = switchHelper.getExpectedBurst(dstSwitch.dpId, flow.maximumBandwidth)
        switchHelper.verifyBurstSizeIsCorrect(srcSwitch, srcSwitchBurstSize,
            srcSwitchValidateInfo.meters.misconfigured*.burstSize[0])
        switchHelper.verifyBurstSizeIsCorrect(dstSwitch, dstSwitchBurstSize,
            dstSwitchValidateInfo.meters.misconfigured*.burstSize[0])

        and: "Reason is specified why meter is misconfigured"
        [srcSwitchValidateInfo, dstSwitchValidateInfo].each {
            it.meters.misconfigured.each {
                assert it.actual.rate == flow.maximumBandwidth
                assert it.expected.rate == newBandwidth
            }
        }

        and: "The rest fields of 'meter' section are empty"
        [srcSwitchValidateInfo, dstSwitchValidateInfo].each {
            switchHelper.verifyMeterSectionsAreEmpty(it, ["proper", "missing", "excess"])
        }

        and: "Created rules are still stored in the 'proper' section"
        def createdCookies = srcSwitchCreatedCookies + dstSwitchCreatedCookies
        [srcSwitchValidateInfo, dstSwitchValidateInfo].each {
            assert it.rules.proper.containsAll(createdCookies)
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "excess"])
        }

        and: "Flow validation shows discrepancies"
        def involvedSwitches = pathHelper.getInvolvedSwitches(flow.flowId)*.dpId
        def totalSwitchRules = 0
        def totalSwitchMeters = 0
        involvedSwitches.each { swId ->
            totalSwitchRules += northbound.getSwitchRules(swId).flowEntries.size()
            totalSwitchMeters += northbound.getAllMeters(swId).meterEntries.size()
        }
        def flowValidateResponse = northbound.validateFlow(flow.flowId)
        flowValidateResponse.eachWithIndex { direction, i ->
            assert direction.discrepancies.size() == 2

            def rate = direction.discrepancies[0]
            assert rate.field == "meterRate"
            assert rate.expectedValue == newBandwidth.toString()
            assert rate.actualValue == flow.maximumBandwidth.toString()

            def burst = direction.discrepancies[1]
            assert burst.field == "meterBurstSize"
            // src/dst switches can be different
            // then as a result burstSize in forward/reverse directions can be different
            // that's why we use eachWithIndex and why we calculate burstSize for src/dst switches
            def sw = (i == 0) ? srcSwitch : dstSwitch
            def switchBurstSize = (i == 0) ? srcSwitchBurstSize : dstSwitchBurstSize
            Long newBurstSize = switchHelper.getExpectedBurst(sw.dpId, newBandwidth)
            switchHelper.verifyBurstSizeIsCorrect(sw, newBurstSize, burst.expectedValue.toLong())
            switchHelper.verifyBurstSizeIsCorrect(sw, switchBurstSize, burst.actualValue.toLong())

            assert direction.flowRulesTotal == 2
            assert direction.switchRulesTotal == totalSwitchRules
            assert direction.flowMetersTotal == 1
            assert direction.switchMetersTotal == totalSwitchMeters
        }

        when: "Restore correct bandwidth via DB"
        database.updateFlowBandwidth(flow.flowId, flow.maximumBandwidth)

        then: "Misconfigured meters are moved into the 'proper' section"
        def srcSwitchValidateInfoRestored = northbound.validateSwitch(srcSwitch.dpId)
        def dstSwitchValidateInfoRestored = northbound.validateSwitch(dstSwitch.dpId)

        srcSwitchValidateInfoRestored.meters.proper*.meterId.containsAll(srcSwitchCreatedMeterIds)
        dstSwitchValidateInfoRestored.meters.proper*.meterId.containsAll(dstSwitchCreatedMeterIds)

        [srcSwitchValidateInfoRestored, dstSwitchValidateInfoRestored].each {
            switchHelper.verifyMeterSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
        }

        and: "Flow validation shows no discrepancies"
        northbound.validateFlow(flow.flowId).each { direction ->
            assert direction.discrepancies.empty
            assert direction.asExpected
        }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "Check that the switch validate request returns empty sections"
        Wrappers.wait(WAIT_OFFSET) {
            def srcSwitchValidateInfoAfterDelete = northbound.validateSwitch(srcSwitch.dpId)
            def dstSwitchValidateInfoAfterDelete = northbound.validateSwitch(dstSwitch.dpId)
            [srcSwitchValidateInfoAfterDelete, dstSwitchValidateInfoAfterDelete].each {
                switchHelper.verifyRuleSectionsAreEmpty(it)
                switchHelper.verifyMeterSectionsAreEmpty(it)
            }
        }
    }

    def "Able to validate and sync a switch with missing ingress rule + meter"() {
        when: "Create a flow"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches.findAll { it.ofVersion != "OF_12" }
        def flow = flowHelperV2.addFlow(flowHelperV2.randomFlow(srcSwitch, dstSwitch))
        def srcSwitchCreatedMeterIds = getCreatedMeterIds(srcSwitch.dpId)
        def dstSwitchCreatedMeterIds = getCreatedMeterIds(dstSwitch.dpId)

        and: "Remove created meter on the srcSwitch"
        def forwardCookies = getCookiesWithMeter(srcSwitch.dpId)
        def reverseCookies = getCookiesWithMeter(dstSwitch.dpId)
        def sharedCookieOnSrcSw = northbound.getSwitchRules(srcSwitch.dpId).flowEntries.find {
            new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW
        }?.cookie
        def untouchedCookiesOnSrcSw = northbound.getSwitchProperties(srcSwitch.dpId).multiTable ?
            (reverseCookies + sharedCookieOnSrcSw).sort() : reverseCookies
        def cookiesOnDstSw = northbound.getSwitchRules(dstSwitch.dpId).flowEntries*.cookie
        northbound.deleteMeter(srcSwitch.dpId, srcSwitchCreatedMeterIds[0])

        then: "Meters info/rules are moved into the 'missing' section on the srcSwitch"
        verifyAll(northbound.validateSwitch(srcSwitch.dpId)) {
            it.rules.missing.sort() == forwardCookies
            it.rules.proper.findAll { !new Cookie(it).serviceFlag }.sort() == untouchedCookiesOnSrcSw//forward cookie's removed with meter

            it.meters.missing*.meterId == srcSwitchCreatedMeterIds
            it.meters.missing*.cookie == forwardCookies

            Long srcSwitchBurstSize = switchHelper.getExpectedBurst(srcSwitch.dpId, flow.maximumBandwidth)
            it.meters.missing.each {
                assert it.rate == flow.maximumBandwidth
                assert it.flowId == flow.flowId
                assert ["KBPS", "BURST", "STATS"].containsAll(it.flags)
                switchHelper.verifyBurstSizeIsCorrect(srcSwitch, srcSwitchBurstSize, it.burstSize)
            }
            switchHelper.verifyMeterSectionsAreEmpty(it, ["proper", "misconfigured", "excess"])
            switchHelper.verifyRuleSectionsAreEmpty(it, ["excess"])
        }

        and: "Meters info/rules are NOT moved into the 'missing' section on the dstSwitch"
        verifyAll(northbound.validateSwitch(dstSwitch.dpId)) {
            it.rules.proper.sort() == cookiesOnDstSw.sort()

            def properMeters = it.meters.proper.findAll({dto -> !isDefaultMeter(dto)})
            properMeters*.meterId == dstSwitchCreatedMeterIds
            properMeters.cookie.size() == 1
            properMeters*.cookie == reverseCookies

            Long dstSwitchBurstSize = switchHelper.getExpectedBurst(dstSwitch.dpId, flow.maximumBandwidth)
            properMeters.each {
                assert it.rate == flow.maximumBandwidth
                assert it.flowId == flow.flowId
                assert ["KBPS", "BURST", "STATS"].containsAll(it.flags)
                switchHelper.verifyBurstSizeIsCorrect(dstSwitch, dstSwitchBurstSize, it.burstSize)
            }
            switchHelper.verifyMeterSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "excess"])
        }

        when: "Synchronize switch with missing rule and meter"
        verifyAll(northbound.synchronizeSwitch(srcSwitch.dpId, false)) {
            it.rules.installed == forwardCookies
            it.meters.installed*.meterId == srcSwitchCreatedMeterIds as List<Long>
        }

        then: "Repeated validation shows no missing entities"
        with(northbound.validateSwitch(srcSwitch.dpId)) {
            switchHelper.verifyMeterSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "excess"])
        }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "Check that the switch validate request returns empty sections"
        Wrappers.wait(WAIT_OFFSET) {
            def srcSwitchValidateInfoAfterDelete = northbound.validateSwitch(srcSwitch.dpId)
            def dstSwitchValidateInfoAfterDelete = northbound.validateSwitch(dstSwitch.dpId)
            [srcSwitchValidateInfoAfterDelete, dstSwitchValidateInfoAfterDelete].each {
                switchHelper.verifyRuleSectionsAreEmpty(it)
                switchHelper.verifyMeterSectionsAreEmpty(it)
            }
        }
    }

    def "Able to validate and sync a switch with missing ingress rule (unmetered)"() {
        when: "Create a flow"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches.findAll { it.ofVersion != "OF_12" }
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flow.maximumBandwidth = 0
        flow.ignoreBandwidth = true
        flowHelperV2.addFlow(flow)

        and: "Remove ingress rule on the srcSwitch"
        def ingressCookie = database.getFlow(flow.flowId).forwardPath.cookie.value
        def egressCookie = database.getFlow(flow.flowId).reversePath.cookie.value
        northbound.deleteSwitchRules(srcSwitch.dpId, ingressCookie)

        then: "Ingress rule is moved into the 'missing' section on the srcSwitch"
        def sharedCookieOnSrcSw = northbound.getSwitchRules(srcSwitch.dpId).flowEntries.find {
            new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW
        }?.cookie
        def untouchedCookies = northbound.getSwitchProperties(srcSwitch.dpId).multiTable ?
            [egressCookie, sharedCookieOnSrcSw].sort() : [egressCookie]
        verifyAll(northbound.validateSwitch(srcSwitch.dpId)) {
            it.rules.missing == [ingressCookie]
            it.rules.proper.findAll {
                def cookie = new Cookie(it)
                !cookie.serviceFlag || cookie.type == CookieType.SHARED_OF_FLOW
            }.sort() == untouchedCookies
            switchHelper.verifyMeterSectionsAreEmpty(it)
            switchHelper.verifyRuleSectionsAreEmpty(it, ["excess"])
        }

        when: "Synchronize switch with missing unmetered rule"
        with(northbound.synchronizeSwitch(srcSwitch.dpId, false)) {
            rules.installed == [ingressCookie]
        }

        then: "Repeated validation shows no missing entities"
        with(northbound.validateSwitch(srcSwitch.dpId)) {
            switchHelper.verifyMeterSectionsAreEmpty(it)
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "excess"])
            it.rules.proper.findAll { !new Cookie(it).serviceFlag }.sort() == (untouchedCookies + ingressCookie).sort()
        }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "Check that the switch validate request returns empty sections"
        Wrappers.wait(WAIT_OFFSET) {
            def srcSwitchValidateInfoAfterDelete = northbound.validateSwitch(srcSwitch.dpId)
            def dstSwitchValidateInfoAfterDelete = northbound.validateSwitch(dstSwitch.dpId)
            [srcSwitchValidateInfoAfterDelete, dstSwitchValidateInfoAfterDelete].each {
                switchHelper.verifyRuleSectionsAreEmpty(it)
                switchHelper.verifyMeterSectionsAreEmpty(it)
            }
        }
    }

    def "Able to validate and sync a switch with missing transit rule"() {
        given: "Two active not neighboring switches"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { pair ->
            def possibleDefaultPaths = pair.paths.findAll { it.size() == pair.paths.min { it.size() }.size() }
            //ensure the path won't have only OF_12 intermediate switches
            def hasOf13Path = !possibleDefaultPaths.find { path ->
                path[1..-2].every { it.switchId.description.contains("OF_12") }
            }
            hasOf13Path && pair.src.ofVersion != "OF_12" && pair.dst.ofVersion != "OF_12"
        } ?: assumeTrue(false, "No not-neighbouring switch pairs found")

        and: "Create an intermediate-switch flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)

        when: "Delete created rules on the transit"
        def involvedSwitches = pathHelper.getInvolvedSwitches(flow.flowId)
        def transitSw = involvedSwitches[1]
        northbound.deleteSwitchRules(transitSw.dpId, DeleteRulesAction.IGNORE_DEFAULTS)

        then: "Rule info is moved into the 'missing' section"
        verifyAll(northbound.validateSwitch(transitSw.dpId)) {
            it.rules.missing.size() == 2
            it.rules.proper.findAll {
                !new Cookie(it).serviceFlag
            }.empty
            it.rules.excess.empty
        }

        when: "Synchronize the switch"
        with(northbound.synchronizeSwitch(transitSw.dpId, false)){
            rules.installed.size() == 2
        }

        then: "Repeated validation shows no discrepancies"
        verifyAll(northbound.validateSwitch(transitSw.dpId)) {
            it.rules.proper.findAll { !new Cookie(it).serviceFlag }.size() == 2
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "excess"])
        }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "Check that the switch validate request returns empty sections on all involved switches"
        Wrappers.wait(WAIT_OFFSET) {
            involvedSwitches.each { sw ->
                def switchValidateInfo = northbound.validateSwitch(sw.dpId)
                switchHelper.verifyRuleSectionsAreEmpty(switchValidateInfo)
                if (!sw.description.contains("OF_12")) {
                    switchHelper.verifyMeterSectionsAreEmpty(switchValidateInfo)
                }
            }
        }
    }

    def "Able to validate and sync a switch with missing egress rule"() {
        given: "Two active not neighboring switches"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { pair ->
            def possibleDefaultPaths = pair.paths.findAll { it.size() == pair.paths.min { it.size() }.size() }
            //ensure the path won't have only OF_12 intermediate switches
            def hasOf13Path = !possibleDefaultPaths.find { path ->
                path[1..-2].every { it.switchId.description.contains("OF_12") }
            }
            hasOf13Path && pair.src.ofVersion != "OF_12" && pair.dst.ofVersion != "OF_12"
        } ?: assumeTrue(false, "No not-neighbouring switch pairs found")

        and: "Create an intermediate-switch flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        def rulesOnSrc = northbound.getSwitchRules(switchPair.src.dpId).flowEntries
        def rulesOnDst = northbound.getSwitchRules(switchPair.dst.dpId).flowEntries

        when: "Delete created rules on the srcSwitch"
        def egressCookie = database.getFlow(flow.flowId).reversePath.cookie.value
        northbound.deleteSwitchRules(switchPair.src.dpId, egressCookie)

        then: "Rule info is moved into the 'missing' section on the srcSwitch"
        verifyAll(northbound.validateSwitch(switchPair.src.dpId)) {
            it.rules.missing == [egressCookie]
            it.rules.proper.size() == rulesOnSrc.size() - 1
            it.rules.excess.empty
        }

        and: "Rule info is NOT moved into the 'missing' section on the dstSwitch and transit switches"
        def dstSwitchValidateInfo = northbound.validateSwitch(switchPair.dst.dpId)
        dstSwitchValidateInfo.rules.proper.sort() == rulesOnDst*.cookie.sort()
        switchHelper.verifyRuleSectionsAreEmpty(dstSwitchValidateInfo, ["missing", "excess"])
        def involvedSwitches = pathHelper.getInvolvedSwitches(flow.flowId)*.dpId
        def transitSwitches = involvedSwitches[1..-2].findAll { !it.description.contains("OF_12") }

        transitSwitches.each { switchId ->
            def transitSwitchValidateInfo = northbound.validateSwitch(switchId)
            assert transitSwitchValidateInfo.rules.proper.findAll { !new Cookie(it).serviceFlag }.size() == 2
            switchHelper.verifyRuleSectionsAreEmpty(transitSwitchValidateInfo, ["missing", "excess"])
        }

        when: "Synchronize the switch"
        with(northbound.synchronizeSwitch(switchPair.src.dpId, false)) {
            rules.installed == [egressCookie]
        }

        then: "Repeated validation shows no discrepancies"
        verifyAll(northbound.validateSwitch(switchPair.dst.dpId)) {
            it.rules.proper.sort() == rulesOnDst*.cookie.sort()
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "excess"])
        }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "Check that the switch validate request returns empty sections on all involved switches"
        Wrappers.wait(WAIT_OFFSET) {
            involvedSwitches.findAll { !it.description.contains("OF_12") }.each { switchId ->
                def switchValidateInfo = northbound.validateSwitch(switchId)
                switchHelper.verifyRuleSectionsAreEmpty(switchValidateInfo)
                switchHelper.verifyMeterSectionsAreEmpty(switchValidateInfo)
            }
        }
    }

    def "Able to validate and sync an excess ingress/egress/transit rule + meter"() {
        given: "Two active not neighboring switches"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { pair ->
            def possibleDefaultPaths = pair.paths.findAll { it.size() == pair.paths.min { it.size() }.size() }
            //ensure the path won't have only OF_12 intermediate switches
            def hasOf13Path = !possibleDefaultPaths.find { path ->
                path[1..-2].every { it.switchId.description.contains("OF_12") }
            }
            hasOf13Path && pair.src.ofVersion != "OF_12" && pair.dst.ofVersion != "OF_12"
        } ?: assumeTrue(false, "Unable to find required switches in topology")

        and: "Create an intermediate-switch flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        def createdCookiesSrcSw = northbound.getSwitchRules(switchPair.src.dpId).flowEntries*.cookie
        def createdCookiesDstSw = northbound.getSwitchRules(switchPair.dst.dpId).flowEntries*.cookie
        def createdCookiesTransitSwitch = northbound.getSwitchRules(pathHelper.getInvolvedSwitches(flow.flowId)[1].dpId)
                .flowEntries*.cookie

        when: "Create excess rules on switches"
        def involvedSwitches = pathHelper.getInvolvedSwitches(flow.flowId)*.dpId

        def producer = new KafkaProducer(producerProps)
        //pick a meter id which is not yet used on src switch
        def excessMeterId = ((MIN_FLOW_METER_ID..100) - northbound.getAllMeters(switchPair.src.dpId)
                                                                  .meterEntries*.meterId).first()
        producer.send(new ProducerRecord(speakerTopic, switchPair.dst.dpId.toString(), buildMessage(
                new InstallEgressFlow(UUID.randomUUID(), flow.flowId, 1L, switchPair.dst.dpId, 1, 2, 1,
                        FlowEncapsulationType.TRANSIT_VLAN, 1, 0,
                        OutputVlanType.REPLACE, false, new FlowEndpoint(switchPair.src.dpId, 1))).toJson()))
        involvedSwitches[1..-1].findAll { !it.description.contains("OF_12") }.each { transitSw ->
            producer.send(new ProducerRecord(speakerTopic, transitSw.toString(), buildMessage(
                    new InstallTransitFlow(UUID.randomUUID(), flow.flowId, 1L, transitSw, 1, 2, 1,
                            FlowEncapsulationType.TRANSIT_VLAN, false)).toJson()))
        }
        producer.send(new ProducerRecord(speakerTopic, switchPair.src.dpId.toString(), buildMessage(
                new InstallIngressFlow(UUID.randomUUID(), flow.flowId, 1L, switchPair.src.dpId, 1, 2, 1, 0, 1,
                        FlowEncapsulationType.TRANSIT_VLAN,
                        OutputVlanType.REPLACE, flow.maximumBandwidth, excessMeterId,
                        switchPair.dst.dpId, false, false, false)).toJson()))
        producer.flush()

        then: "Switch validation shows excess rules and store them in the 'excess' section"
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getSwitchRules(switchPair.src.dpId).flowEntries.size() == createdCookiesSrcSw.size() + 1

            involvedSwitches.findAll { !it.description.contains("OF_12") }.each { switchId ->
                def involvedSwitchValidateInfo = northbound.validateSwitch(switchId)
                if (switchId == switchPair.src.dpId) {
                    assert involvedSwitchValidateInfo.rules.proper.sort() == createdCookiesSrcSw.sort()
                } else if (switchId == switchPair.dst.dpId) {
                    assert involvedSwitchValidateInfo.rules.proper.sort() == createdCookiesDstSw.sort()
                } else {
                    assert involvedSwitchValidateInfo.rules.proper.sort() == createdCookiesTransitSwitch.sort()
                }
                switchHelper.verifyRuleSectionsAreEmpty(involvedSwitchValidateInfo, ["missing"])

                assert involvedSwitchValidateInfo.rules.excess.size() == 1
                assert involvedSwitchValidateInfo.rules.excess == [1L]
                assert involvedSwitchValidateInfo.rules.excessHex.size() == 1
                assert involvedSwitchValidateInfo.rules.excessHex == [Long.toHexString(1L)]
            }
        }

        and: "Excess meter is shown on the srcSwitch only"
        Long burstSize = switchHelper.getExpectedBurst(switchPair.src.dpId, flow.maximumBandwidth)
        def validateSwitchInfo = northbound.validateSwitch(switchPair.src.dpId)
        assert validateSwitchInfo.meters.excess.size() == 1
        assert validateSwitchInfo.meters.excess.each {
            assert it.rate == flow.maximumBandwidth
            assert it.meterId == excessMeterId
            assert ["KBPS", "BURST", "STATS"].containsAll(it.flags)
            switchHelper.verifyBurstSizeIsCorrect(switchPair.src, burstSize, it.burstSize)
        }
        involvedSwitches[1..-1].findAll { !it.description.contains("OF_12") }.each { switchId ->
            assert northbound.validateSwitch(switchId).meters.excess.empty
        }

        when: "Try to synchronize every involved switch"
        def syncResultsMap = involvedSwitches.collectEntries { switchId ->
            [switchId, northbound.synchronizeSwitch(switchId, true)]
        }

        then: "System deletes excess rules and meters"
        involvedSwitches.findAll { !it.description.contains("OF_12") }.each { switchId ->
            assert syncResultsMap[switchId].rules.excess.size() == 1
            assert syncResultsMap[switchId].rules.excess[0] == 1L
            assert syncResultsMap[switchId].rules.removed.size() == 1
            assert syncResultsMap[switchId].rules.removed[0] == 1L
        }
        assert syncResultsMap[switchPair.src.dpId].meters.excess.size() == 1
        assert syncResultsMap[switchPair.src.dpId].meters.excess.meterId[0] == excessMeterId
        assert syncResultsMap[switchPair.src.dpId].meters.removed.size() == 1
        assert syncResultsMap[switchPair.src.dpId].meters.removed.meterId[0] == excessMeterId

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "Check that the switch validate request returns empty sections on all involved switches"
        Wrappers.wait(WAIT_OFFSET) {
            involvedSwitches.findAll { !it.description.contains("OF_12") }.each { switchId ->
                def switchValidateInfo = northbound.validateSwitch(switchId)
                switchHelper.verifyRuleSectionsAreEmpty(switchValidateInfo)
                switchHelper.verifyMeterSectionsAreEmpty(switchValidateInfo)
            }
        }

        cleanup:
        producer && producer.close()
    }

    @Tags(HARDWARE)
    @Ignore("https://github.com/telstra/open-kilda/issues/3021")
    def "Able to validate and sync a switch with missing 'vxlan' ingress/transit/egress rule + meter"() {
        given: "Two active not neighboring VXLAN supported switches"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { swP ->
            [swP.src, swP.dst].every {
                northbound.getSwitchProperties(it.dpId).supportedTransitEncapsulation
                        .contains(FlowEncapsulationType.VXLAN.toString().toLowerCase())
            } && swP.paths.find { path ->
                pathHelper.getInvolvedSwitches(path).every {
                    northbound.getSwitchProperties(it.dpId).supportedTransitEncapsulation
                            .contains(FlowEncapsulationType.VXLAN.toString().toLowerCase())
                }
            }
        } ?: assumeTrue(false, "Unable to find required switches in topology")

        and: "Create a flow with vxlan encapsulation"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.encapsulationType = FlowEncapsulationType.VXLAN
        flowHelperV2.addFlow(flow)

        and: "Remove required rules and meters from switches"
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
            involvedSwitches.each {
                assert validationResultsMap[it.dpId].rules.missing.size() == 2
                assert validationResultsMap[it.dpId].rules.missingHex.size() == 2
            }
            [switchPair.src, switchPair.dst].each { assert validationResultsMap[it.dpId].meters.missing.size() == 1 }
        }

        expect: "Switch validation shows missing rules and meters on every related switch"
        involvedSwitches.eachWithIndex { sw, i ->
            verifyAll(northbound.validateSwitch(sw.dpId)) { validation ->
                validation.rules.missing.size() == 2
                validation.rules.missingHex.size() == 2
                if (i == 0 || i == involvedSwitches.size() - 1) { //terminating switches
                    assert validation.meters.missing.size() == 1
                }
            }
        }

        when: "Try to synchronize all switches"
        def syncResultsMap = involvedSwitches.collectEntries { [it.dpId, northbound.synchronizeSwitch(it.dpId, false)] }

        then: "System installs missing rules and meters"
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
                assert validationResult.rules.missingHex.size() == 0
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

        and: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    def "Able to validate and sync a missing 'protected path' egress rule"() {
        given: "A flow with protected path"
        def swPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2
        } ?: assumeTrue(false, "No switch pair with at least 2 diverse paths")
        def flow = flowHelperV2.randomFlow(swPair).tap { allocateProtectedPath = true }
        flowHelperV2.addFlow(flow)
        def flowInfo = northbound.getFlowPath(flow.flowId)
        def allSwitches = (pathHelper.getInvolvedSwitches(pathHelper.convert(flowInfo.protectedPath)) +
                pathHelper.getInvolvedSwitches(pathHelper.convert(flowInfo))).unique { it.dpId }
        def rulesPerSwitch = allSwitches.collectEntries {
            [it.dpId, northbound.getSwitchRules(it.dpId).flowEntries*.cookie.sort()]
        }

        expect: "Upon validation all rules are stored in the 'proper' section"
        allSwitches*.dpId.each { switchId ->
            def rules = northbound.validateSwitchRules(switchId)
            assert rules.properRules.sort() == rulesPerSwitch[switchId]
            assert rules.missingRules.empty
            assert rules.excessRules.empty
        }

        when: "Delete rule of protected path on the srcSwitch (egress)"
        def protectedPath = northbound.getFlowPath(flow.flowId).protectedPath.forwardPath
        def srcSwitchRules = northbound.getSwitchRules(swPair.src.dpId).flowEntries.findAll {
            !new Cookie(it.cookie).serviceFlag
        }
        def ruleToDelete = srcSwitchRules.find {
            it.instructions?.applyActions?.flowOutput == protectedPath[0].inputPort.toString() &&
                    it.match.inPort == protectedPath[0].outputPort.toString()
        }.cookie
        northbound.deleteSwitchRules(swPair.src.dpId, ruleToDelete)

        then: "Deleted rule is moved to the 'missing' section on the srcSwitch"
        verifyAll(northbound.validateSwitch(swPair.src.dpId)) {
            it.rules.proper.sort() == rulesPerSwitch[swPair.src.dpId] - ruleToDelete
            it.rules.missing == [ruleToDelete]
            it.rules.excess.empty
        }

        and: "Rest switches are not affected by deleting the rule on the srcSwitch"
        allSwitches.findAll { it.dpId != swPair.src.dpId }.each { sw ->
            def validation = northbound.validateSwitch(sw.dpId)
            assert validation.rules.proper.sort() == rulesPerSwitch[sw.dpId]
            assert validation.rules.missing.empty
            assert validation.rules.excess.empty
        }

        when: "Synchronize switch with a missing protected path egress rule"
        with(northbound.synchronizeSwitch(swPair.src.dpId, false)) {
            rules.installed == [ruleToDelete]
        }

        then: "Switch validation no longer shows missing rules"
        verifyAll(northbound.validateSwitch(swPair.src.dpId)) {
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "excess"])
            it.rules.proper.sort() == rulesPerSwitch[swPair.src.dpId]
        }

        and: "Cleanup: delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Unroll
    def "Able to validate and sync a missing 'connected device' #data.descr rule"() {
        given: "A flow with enabled connected devices"
        def swPair = topologyHelper.switchPairs.find {
            [it.src, it.dst].every { it.features.contains(SwitchFeature.MULTI_TABLE) }
        }
        Map<Switch, SwitchPropertiesDto> initialProps = [swPair.src, swPair.dst]
                .collectEntries { [(it): enableMultiTableIfNeeded(true, it.dpId)] }
        def flow = flowHelper.randomFlow(swPair)
        flow.destination.detectConnectedDevices = new DetectConnectedDevicesPayload(true, true)
        flowHelper.addFlow(flow)

        expect: "Switch validation puts connected device lldp rule into 'proper' section"
        def deviceCookie = northbound.getSwitchRules(swPair.dst.dpId).flowEntries
                .find(data.cookieSearchClosure).cookie
        with(northbound.validateSwitch(flow.destination.datapath)) {
            it.rules.proper.contains(deviceCookie)
        }

        when: "Remove the connected device rule"
        northbound.deleteSwitchRules(flow.destination.datapath, deviceCookie)

        then: "Switch validation puts connected device rule into 'missing' section"
        verifyAll(northbound.validateSwitch(flow.destination.datapath)) {
            !it.rules.proper.contains(deviceCookie)
            it.rules.missing.contains(deviceCookie)
            it.rules.missingHex.contains(Long.toHexString(deviceCookie))
        }

        when: "Synchronize the switch"
        with(northbound.synchronizeSwitch(flow.destination.datapath, false)) {
            it.rules.installed == [deviceCookie]
        }

        then: "Switch validation no longer shows any discrepancies in rules nor meters"
        verifyAll(northbound.validateSwitch(flow.destination.datapath)) {
            it.rules.proper.contains(deviceCookie)
            it.rules.missing.empty
            it.rules.missingHex.empty
            it.rules.excess.empty
            it.rules.excessHex.empty
            it.meters.missing.empty
            it.meters.excess.empty
        }

        when: "Delete the flow"
        flowHelper.deleteFlow(flow.id)

        then: "Switch validation is empty"
        verifyAll(northbound.validateSwitch(flow.destination.datapath)) {
            switchHelper.verifyRuleSectionsAreEmpty(it)
            switchHelper.verifyMeterSectionsAreEmpty(it)
        }

        cleanup:
        initialProps.each {
            switchHelper.updateSwitchProperties(it.key, it.value)
        }

        where:
        data << [
                [
                        descr              : "LLDP",
                        cookieSearchClosure: {
                            new Cookie(it.cookie).getType() ==  CookieType.LLDP_INPUT_CUSTOMER_TYPE }
                ],

                [
                        descr              : "ARP",
                        cookieSearchClosure: {
                            new Cookie(it.cookie).getType() == CookieType.ARP_INPUT_CUSTOMER_TYPE }
                ]
        ]
    }

    List<Integer> getCreatedMeterIds(SwitchId switchId) {
        return northbound.getAllMeters(switchId).meterEntries.findAll { it.meterId > MAX_SYSTEM_RULE_METER_ID }*.meterId
    }

    List<Long> getCookiesWithMeter(SwitchId switchId) {
        return northbound.getSwitchRules(switchId).flowEntries.findAll {
            !new Cookie(it.cookie).serviceFlag && it.instructions.goToMeter
        }*.cookie.sort()
    }

    private static Message buildMessage(final BaseInstallFlow commandData) {
        InstallFlowForSwitchManagerRequest request = new InstallFlowForSwitchManagerRequest(commandData)
        return new CommandMessage(request, System.currentTimeMillis(), UUID.randomUUID().toString(), null)
    }

    private SwitchPropertiesDto enableMultiTableIfNeeded(boolean needDevices, SwitchId switchId) {
        def initialProps = northbound.getSwitchProperties(switchId)
        if (needDevices && !initialProps.multiTable) {
            def sw = topology.switches.find { it.dpId == switchId }
            switchHelper.updateSwitchProperties(sw, initialProps.jacksonCopy().tap {
                it.multiTable = true
            })
        }
        return initialProps
    }
}
