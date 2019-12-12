package org.openkilda.functionaltests.spec.flows

import static groovyx.gpars.GParsPool.withPool
import static org.junit.Assume.assumeTrue
import static org.openkilda.testing.Constants.NON_EXISTENT_FLOW_ID
import static org.openkilda.testing.Constants.NON_EXISTENT_SWITCH_ID
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME

import org.openkilda.applications.command.CommandAppData
import org.openkilda.applications.command.CommandAppMessage
import org.openkilda.applications.command.apps.CreateExclusion
import org.openkilda.applications.model.Exclusion
import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.error.MessageError
import org.openkilda.model.Cookie
import org.openkilda.model.FlowApplication
import org.openkilda.model.SwitchId
import org.openkilda.northbound.dto.v1.flows.FlowAddAppDto
import org.openkilda.northbound.dto.v1.flows.FlowDeleteAppDto
import org.openkilda.northbound.dto.v1.flows.FlowEndpoint
import org.openkilda.northbound.dto.v1.flows.PingInput
import org.openkilda.northbound.dto.v1.switches.SwitchPropertiesDto
import org.openkilda.testing.model.topology.TopologyDefinition.Isl
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Ignore
import spock.lang.See
import spock.lang.Unroll

import javax.inject.Provider

@See("https://github.com/telstra/open-kilda/blob/27a8a18a7cdad524c8fef07883ecaebf32a7d410/docs/design/applications/apps.md")
//@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/applications")
class FlowAppsSpec extends HealthCheckSpecification {

    @Autowired
    Provider<TraffExamService> traffExamProvider

    @Value("#{kafkaTopicsConfig.getAppsNotificationTopic()}")
    String appsNotificationTopic

    @Autowired
    @Qualifier("kafkaProducerProperties")
    Properties producerProps

//    @Unroll
    def "System allows to CRUD Application on a #desc"() {
        given: "Two active traffGen switches"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeTraffGens*.switchConnected
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
//        flow.tap {
//            it.source.vlanId = "$descr".startsWith("default") ? 0 : it.source.vlanId
//            it.destination.vlanId = "$descr".startsWith("default") ? 0 : it.destination.vlanId
//        }
        // Application is disabled by default
        Map<SwitchId, SwitchPropertiesDto> initSwProps = [flow.source, flow.destination].collectEntries {
            [(it.switchId): northbound.getSwitchProperties(it.switchId)]
        }
        [flow.source, flow.destination].each {
            enableAppOnSwitch(it.switchId, initSwProps[it.switchId])
        }

        when: "Create a flow"
        flowHelperV2.addFlow(flow)

        then: "Flow created without application"
        with(northbound.getFlowApps(flow.flowId)) { response ->
            response.flowId == flow.flowId
            response.srcApps.endpointSwitch == flow.source.switchId
            response.srcApps.applications.empty
            response.dstApps.endpointSwitch == flow.destination.switchId
            response.dstApps.applications.empty
        }

        when: "Add application for the flow on the src switch"
        def appName = FlowApplication.TELESCOPE.toString().toLowerCase()
        def srcFlowEndpoint = new FlowEndpoint(flow.source.switchId, flow.source.portNumber, flow.source.vlanId)
        def addResponse = northbound.addFlowApps(flow.flowId, appName, new FlowAddAppDto(srcFlowEndpoint))

        then: "Response contains info about added application"
        with(addResponse) { response ->
            response.flowId == flow.flowId
            response.srcApps.endpointSwitch == flow.source.switchId
            response.srcApps.applications.size() == 1
            response.srcApps.applications[0] == appName
            response.dstApps.endpointSwitch == flow.destination.switchId
            response.dstApps.applications.empty
        }

        and: "Application is really added"
        with(northbound.getFlowApps(flow.flowId)) { response ->
            response.flowId == flow.flowId
            response.srcApps.endpointSwitch == flow.source.switchId
            response.srcApps.applications.size() == 1
            response.srcApps.applications[0] == appName
            response.dstApps.endpointSwitch == flow.destination.switchId
            response.dstApps.applications.empty
        }

        and: "Application's rules are installed on the src switch only"
        def srcSwAppRules = northbound.getSwitchRules(srcSwitch.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }
        srcSwAppRules.size() == 2
        srcSwAppRules.each { assert it.packetCount == 0 }
        northbound.getSwitchRules(dstSwitch.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }.empty

        and: "Flow history contains information about adding application"
        with(northbound.getFlowHistory(flow.flowId)) { it.last().action == "Flow Application Added" }

        when: "Generate packages on apps rules by passing traffic via flow"
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam)
            .buildBidirectionalExam(flowHelperV2.toV1(flow), 1000, 3)
        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        then: "Packet counter is increased on the apps rules on the src switch"
//        northbound.getSwitchRules(srcSwitch.dpId).flowEntries.findAll { it.cookie in srcSwAppRules*.cookie }.each {
//            assert it.packetCount > 0
//        }

        when:  "Try to update the application(add app on the dstSw)"
        def dstFlowEndpoint = new FlowEndpoint(flow.destination.switchId, flow.destination.portNumber,
                                                flow.destination.vlanId)
        def updateResponse = northbound.addFlowApps(flow.flowId, appName, new FlowAddAppDto(dstFlowEndpoint))

        then: "Response contains info about updated application"
        with(updateResponse) { response ->
            response.flowId == flow.flowId
            response.srcApps.endpointSwitch == flow.source.switchId
            response.srcApps.applications.size() == 1
            response.srcApps.applications[0] == appName
            response.dstApps.endpointSwitch == flow.destination.switchId
            response.dstApps.applications.size() == 1
            response.dstApps.applications[0] == appName
        }

        and: "Application is really updated"
        with(northbound.getFlowApps(flow.flowId)) { response ->
            response.flowId == flow.flowId
            response.srcApps.endpointSwitch == flow.source.switchId
            response.srcApps.applications.size() == 1
            response.srcApps.applications[0] == appName
            response.dstApps.endpointSwitch == flow.destination.switchId
            response.dstApps.applications.size() == 1
            response.dstApps.applications[0] == appName
        }

        and: "Application's rules are installed on the dst switch"
        def dstSwAppRules = northbound.getSwitchRules(dstSwitch.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }
        dstSwAppRules.size() == 2
//        dstSwAppRules.each { assert it.packetCount == 0 }

        when: "Generate packages on apps rules by passing traffic via flow"
        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        then: "Packet counter is increased on the apps rules on the dst switch"
//        northbound.getSwitchRules(dstSwitch.dpId).flowEntries.findAll { it.cookie in dstSwAppRules*.cookie }.each {
//            assert it.packetCount > 0
//        }

        when: "Delete the application on the src switch"
        def delResponse = northbound.deleteFlowApps(flow.flowId, appName, new FlowDeleteAppDto(srcFlowEndpoint))

        then: "Response contains info about deleted application"
        with(delResponse) { response ->
            response.flowId == flow.flowId
            response.srcApps.endpointSwitch == flow.source.switchId
            response.srcApps.applications.empty
            response.dstApps.endpointSwitch == flow.destination.switchId
            !response.dstApps.applications.empty
        }

        and: "Application is really deleted"
         with(northbound.getFlowApps(flow.flowId)) { response ->
            response.flowId == flow.flowId
            response.srcApps.endpointSwitch == flow.source.switchId
            response.srcApps.applications.empty
            response.dstApps.endpointSwitch == flow.destination.switchId
            !response.dstApps.applications.empty
        }

        and: "Apps rules are deleted on the src switch only"
        northbound.getSwitchRules(srcSwitch.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }.empty
        northbound.getSwitchRules(dstSwitch.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }.size() == 2

        and: "Flow history contains information about deleting application"
        with(northbound.getFlowHistory(flow.flowId)) { it.last().action == "Flow Application Removed" }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "Apps rules are deleted on the dst switch"
        northbound.getSwitchRules(dstSwitch.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }.empty

        and: "Cleanup: Revert system to original state"
        [flow.source, flow.destination].each {
            northbound.updateSwitchProperties(it.switchId, initSwProps[it.switchId])
        }

        where:
        descr                            | typeOfFlow
        "vlan flow"                      | "randomFlow"
        "default flow"                   | "randomFlow"
        //TODO(andriidovhan) add test for QinQ
    }

//    @Unroll
    def "System allows to CRUD Application on a single #descr"() {
        given: "An active switch"
        def sw = topology.activeSwitches.last()

        and: "Switch property is configured for adding an application"
        def initSwProps = northbound.getSwitchProperties(sw.dpId)
        enableAppOnSwitch(sw.dpId, initSwProps)

        and: "A flow without application"
//        def flow = flowHelperV2."$typeOfFlow"(sw)
        def flow = flowHelperV2.singleSwitchFlow(sw)

        when: "Create a flow"
        flowHelperV2.addFlow(flow)

        then: "Flow created without application"
        with(northbound.getFlowApps(flow.flowId)) { response ->
            response.flowId == flow.flowId
            response.srcApps.endpointSwitch == flow.source.switchId
            response.srcApps.applications.empty
            response.dstApps.endpointSwitch == flow.destination.switchId
            response.dstApps.applications.empty
        }

        when: "Add application for the src flow endpoint"
        def appName = FlowApplication.TELESCOPE.toString().toLowerCase()
        def srcFlowEndpoint = new FlowEndpoint(flow.source.switchId, flow.source.portNumber, flow.source.vlanId)
        northbound.addFlowApps(flow.flowId, appName, new FlowAddAppDto(srcFlowEndpoint))

        then: "Application is really added"
        with(northbound.getFlowApps(flow.flowId)) { response ->
            response.flowId == flow.flowId
            response.srcApps.endpointSwitch == flow.source.switchId
            response.srcApps.applications.size() == 1
            response.srcApps.applications[0] == appName
            response.dstApps.endpointSwitch == flow.destination.switchId
            response.dstApps.applications.empty
        }

        and: "Application's rules are installed on the switch"
        def swAppRules = northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }
        swAppRules.size() == 2

        when:  "Add application for the dst flow endpoint)"
        def dstFlowEndpoint = new FlowEndpoint(flow.destination.switchId, flow.destination.portNumber,
                flow.destination.vlanId)
        northbound.addFlowApps(flow.flowId, appName, new FlowAddAppDto(dstFlowEndpoint))

        then: "Application is really updated"
        with(northbound.getFlowApps(flow.flowId)) { response ->
            response.flowId == flow.flowId
            response.srcApps.endpointSwitch == flow.source.switchId
            response.srcApps.applications.size() == 1
            response.srcApps.applications[0] == appName
            response.dstApps.endpointSwitch == flow.destination.switchId
            response.dstApps.applications.size() == 1
            response.dstApps.applications[0] == appName
        }

        and: "Extra two rules are installed for the dst flow endpoint because flow is single switch"
        northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }.size() == swAppRules.size()

        when: "Delete the application for the src flow endpoint"
        northbound.deleteFlowApps(flow.flowId, appName, new FlowDeleteAppDto(srcFlowEndpoint))

        then: "Application is really deleted for the src flow endpoint"
         with(northbound.getFlowApps(flow.flowId)) { response ->
            response.flowId == flow.flowId
            response.srcApps.endpointSwitch == flow.source.switchId
            response.srcApps.applications.empty
            response.dstApps.endpointSwitch == flow.destination.switchId
            !response.dstApps.applications.empty
        }

        and: "Apps rules are not deleted because app still exist for the dst flow endpoint"
        northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }.size() == 2

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "Apps rules are deleted"
        northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }.empty

        cleanup: "Cleanup: Revert system to original state"
        switchHelper.updateSwitchProperties(topology.switches.find { it.dpId == flow.source.switchId }, initSwProps)

        where:
        descr                     | typeOfFlow
        "switch flow"             | "singleSwitchFlow"
        "switch single port flow" | "singleSwitchSinglePortFlow"
    }

    @Ignore("it is not tested yet")
    def "System allows to install/delete Application's rules without deleting Application"() {
        given: "Two active traffGen switches"
        def allTraffGenSwitches = topology.activeTraffGens*.switchConnected
        assumeTrue("Unable to find two active traffGen switches", allTraffGenSwitches.size() >= 2)
        def (Switch srcSwitch, Switch dstSwitch) = allTraffGenSwitches
        def initSwProps = northbound.getSwitchProperties(srcSwitch.dpId)
        enableAppOnSwitch(srcSwitch.dpId, initSwProps)

        and: "A flow with Application on the src switch"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)
        def appName = FlowApplication.TELESCOPE.toString().toLowerCase()
        def srcFlowEndpoint = new FlowEndpoint(flow.source.switchId, flow.source.portNumber, flow.source.vlanId)
        northbound.addFlowApps(flow.flowId, appName, new FlowAddAppDto(srcFlowEndpoint))
        def srcSwAppRules
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            srcSwAppRules = northbound.getSwitchRules(srcSwitch.dpId).flowEntries.findAll {
                Cookie.isMaskedAsTelescope(it.cookie)
            }
            srcSwAppRules.size() == 2
        }

        when: "Update exclusion for the Application(delete action)"
        //via kafka
        def producer = new KafkaProducer(producerProps)
         producer.send(new ProducerRecord(appsNotificationTopic, flow.source.switchId.toString(), buildMessage(
            new CreateExclusion(flow.flowId,
            FlowApplication.TELESCOPE.toString(),
            new Exclusion("127.0.0.2", 1, "127.0.0.2", 2, "UDP", "IPv4"),
//            1000, new HashSet<FlowApplication>(), Metadata.builder().build()))
            1000))
         ))

        then: "Application's rule is deleted"
        Wrappers.wait(RULES_DELETION_TIME) {
            northbound.getSwitchRules(srcSwitch.dpId).flowEntries.findAll {
                Cookie.isMaskedAsTelescope(it.cookie)
            }.size() == 1
        }

        and: "Flow is valid and pingable"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
        with(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        and: "The src switch pass switch validation"
        with(northbound.validateSwitch(srcSwitch.dpId)) {
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
            switchHelper.verifyMeterSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
        }

        when: "Update exclusion for the Application(install action)"
        //via kafka
        producer.send()
        producer.close()

        then: "Application's rule is installed"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            northbound.getSwitchRules(srcSwitch.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }.size() == 2
            // check package count
        }

        and: "Flow is valid and pingable"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
        with(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        and: "The src switch pass switch validation"
        with(northbound.validateSwitch(srcSwitch.dpId)) {
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
            switchHelper.verifyMeterSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
        }

        when: "Pass traffic via flow"
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam)
            .buildBidirectionalExam(flowHelperV2.toV1(flow), 1000, 3)
        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        then: "Package counter is increased for app rules"
        // new srcSwAppRules ?
//        northbound.getSwitchRules(srcSwitch.dpId).flowEntries.findAll { it.cookie in srcSwAppRules*.cookie }.each {
//            assert it.packetCount > 0
//        }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "App rules are deleted"
        Wrappers.wait(RULES_DELETION_TIME) {
            northbound.getSwitchRules(srcSwitch.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }.empty
        }

        and: "Switch pass switch validation"
        and: "The src switch pass switch validation"
        with(northbound.validateSwitch(srcSwitch.dpId)) {
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
            switchHelper.verifyMeterSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
        }

        and: "Cleanup: Revert system to original state"
        switchHelper.updateSwitchProperties(srcSwitch, initSwProps)
    }

    @Ignore("it is not working yet, response - 500")
    @Tidy
    def "System doesn't allow to create Application on a flow if switch property isn't configured for that" () {
        given: "A switch which doesn't support application"
        def sw = topology.activeSwitches.first()
        with(northbound.getSwitchProperties(sw.dpId)) {
            !it.outboundTelescopePort
            !it.inboundTelescopePort
            !it.telescopeIngressVlan
            !it.telescopeEgressVlan
        }

        and: "A flow on the given switch"
        def flow = flowHelperV2.singleSwitchFlow(sw)
        flowHelperV2.addFlow(flow)

        when: "Try to create application on the flow"
        def appName = FlowApplication.TELESCOPE.toString().toLowerCase()
        def srcFlowEndpoint = new FlowEndpoint(flow.source.switchId, flow.source.portNumber, flow.source.vlanId)
        northbound.addFlowApps(flow.flowId, appName, new FlowAddAppDto(srcFlowEndpoint))

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage ==
                "Inbound telescope port for switch '$sw.dpId' is not set"

        and: "Application is not created"
        with(northbound.getFlowApps(flow.flowId)) { response ->
            response.flowId == flow.flowId
            response.srcApps.endpointSwitch == flow.source.switchId
            response.srcApps.applications.empty
            response.dstApps.endpointSwitch == flow.destination.switchId
            response.dstApps.applications.empty
        }

        and: "Switch pass switch validation"
        with(northbound.validateSwitch(sw.dpId)) {
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
            switchHelper.verifyMeterSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
        }
        northbound.getSwitchRules(sw.dpId).flowEntries.findAll { Cookie.isMaskedAsTelescope(it.cookie) }.empty

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Ignore("not tested")
    @Tidy
    def "System allows to create several flow with the same application on the same port"(){
         given: "Two neighboring switches"
        def swPair = topologyHelper.getNeighboringSwitchPair()

        and: "Application is configured in swProps on the src switch"
        def initScrSwProps = northbound.getSwitchProperties(swPair.src.dpId)
        enableAppOnSwitch(swPair.src.dpId, initScrSwProps)

        and: "A vlan flow with the application on the src switch"
        def vlanFlow = flowHelperV2.randomFlow(swPair)
        vlanFlow.allocateProtectedPath = true
        flowHelperV2.addFlow(vlanFlow)
        def appName = FlowApplication.TELESCOPE.toString().toLowerCase()
        def dstVlanFlowEndpoint = new FlowEndpoint(vlanFlow.source.switchId, vlanFlow.source.portNumber,
                vlanFlow.source.vlanId)
        northbound.addFlowApps(vlanFlow.flowId, appName, new FlowAddAppDto(dstVlanFlowEndpoint))
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert northbound.getSwitchRules(swPair.src.dpId).flowEntries.findAll {
                Cookie.isMaskedAsTelescope(it.cookie)
            }.size() == 2
        }

        and: "A default flow on the same switches"
        def defaultFlow = flowHelperV2.randomFlow(swPair)
        defaultFlow.source.vlanId = 0
        defaultFlow.destination.vlanId = 0
        flowHelperV2.addFlow(defaultFlow)
        def defaultFlowIsDeleted = false

        when: "Add the same application for the default flow as for the vlan flow"
        def dstDefaultFlowEndpoint = new FlowEndpoint(defaultFlow.source.switchId, defaultFlow.source.portNumber,
                defaultFlow.source.vlanId)
        northbound.addFlowApps(defaultFlow.flowId, appName, new FlowAddAppDto(dstDefaultFlowEndpoint))

        then: "Application is really added"
        with(northbound.getFlowApps(defaultFlow.flowId)) { response ->
            response.flowId == defaultFlow.flowId
            response.srcApps.endpointSwitch == defaultFlow.source.switchId
            response.srcApps.applications.size() == 1
            response.srcApps.applications[0] == appName
            response.dstApps.endpointSwitch == defaultFlow.destination.switchId
            response.dstApps.applications.empty
        }

        and: "Application rules are installed"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert northbound.getSwitchRules(swPair.src.dpId).flowEntries.findAll {
                Cookie.isMaskedAsTelescope(it.cookie)
            }.size() == 4
        }

        when: "Delete the default flow"
        flowHelperV2.deleteFlow(defaultFlow.flowId)
        defaultFlowIsDeleted = true

        then: "Application rules for the default flow are deleted"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            // ??? check match.metadata
            assert northbound.getSwitchRules(swPair.src.dpId).flowEntries.findAll {
                Cookie.isMaskedAsTelescope(it.cookie)
            }.size() == 2
        }

        and: "The src switch pass switch validation"
        with(northbound.validateSwitch(swPair.src.dpId)) { validation ->
            validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
            validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }

        cleanup:
        vlanFlow && flowHelperV2.deleteFlow(vlanFlow.flowId)
        !defaultFlowIsDeleted && flowHelperV2.deleteFlow(defaultFlow.flowId)
        switchHelper.updateSwitchProperties(swPair.src, initScrSwProps)
    }

    @Ignore("we don't know the correct behavior in this case")
    @Tidy
    def "System doesn't allow to disable application in switch properties when a flow with app exist"(){
        given: "An active switch"
        def sw = topology.activeSwitches.first()

        and: "Switch property is configured for adding an application"
        def initSwProps = northbound.getSwitchProperties(sw.dpId)
        enableAppOnSwitch(sw.dpId, initSwProps)

        and: "A flow with application"
        def flow = flowHelperV2.singleSwitchFlow(sw)
        flowHelperV2.addFlow(flow)
        def appName = FlowApplication.TELESCOPE.toString().toLowerCase()
        def srcFlowEndpoint = new FlowEndpoint(flow.destination.switchId, flow.destination.portNumber,
                flow.destination.vlanId)
        northbound.addFlowApps(flow.flowId, appName, new FlowAddAppDto(srcFlowEndpoint))

        when: "Try to delete configuration for app in switch properties"
        northbound.updateSwitchProperties(sw.dpId, initSwProps)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage ==
                "Can't delete....."

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        switchHelper.updateSwitchProperties(topology.switches.find { it.dpId == flow.source.switchId }, initSwProps)
    }

    //??? how to check
    @Ignore
    @Tidy
    def "System automatically recreates Application if flow is updated(port, sw)"() { // or flow is swapped
        given: "Three active switches"
        def allSwitches = topology.activeSwitches
        assumeTrue("Unable to find three active switches", allSwitches.size() > 2)
        def srcSwitch = allSwitches[0]
        def dstSwitch = allSwitches[1]

        and: "One of the given switches is configured for application"
        def initDstSwProps = northbound.getSwitchProperties(dstSwitch.dpId)
        enableAppOnSwitch(dstSwitch.dpId, initDstSwProps)

        and: "A flow on the given switches"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch, false)
        flowHelperV2.addFlow(flow)

        and: "Application for the flow on the dst switch"
        def appName = FlowApplication.TELESCOPE.toString().toLowerCase()
        def dstFlowEndpoint = new FlowEndpoint(flow.destination.switchId, flow.destination.portNumber,
                flow.destination.vlanId)
        northbound.addFlowApps(flow.flowId, appName, new FlowAddAppDto(dstFlowEndpoint))
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert northbound.getSwitchRules(dstSwitch.dpId).flowEntries.findAll {
                Cookie.isMaskedAsTelescope(it.cookie)
            }.size() == 2
        }

        when: "Update the port number and vlan on the destination flow endpoint"
        def newDstVlanId = 10 // this port is always available for a flow
        def newDstPortNumber = flow.destination.vlanId + 1
        flowHelperV2.updateFlow(flow.flowId, flow.tap {
            it.destination.portNumber = newDstPortNumber
            it.destination.vlanId = newDstVlanId
        })

        then: "Flow is really updated"
        with(northbound.getFlow(flow.flowId).destination) {
            it.vlanId == newDstVlanId
            it.portNumber == newDstPortNumber
        }

        then: "Rules for the application are updated too"

        when: "Select new dst switch and configure application in swProps on the switch"
        def newDstSwitch = allSwitches[2]
        def initNewDstSwProps = northbound.getSwitchProperties(newDstSwitch.dpId)
        enableAppOnSwitch(newDstSwitch.dpId, initNewDstSwProps)
        def newDstSwitchPropIsUpdated = true

        and: "Update the flow destination endpoint to the new dst switch"
        flowHelperV2.updateFlow(flow.flowId, flow.tap {
            it.destination.switchId = newDstSwitch.dpId
        })

        then: "Flow is updated"
        northbound.getFlow(flow.flowId).destination.switchDpId == newDstSwitch.dpId

        and: "Application rules are created on the new dst switch"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert northbound.getSwitchRules(newDstSwitch.dpId).flowEntries.findAll {
                Cookie.isMaskedAsTelescope(it.cookie)
            }.size() == 2
        }

        and: "Application rules are deleted on the old dst switch"
        Wrappers.wait(RULES_DELETION_TIME) {
            assert northbound.getSwitchRules(dstSwitch.dpId).flowEntries.findAll {
                Cookie.isMaskedAsTelescope(it.cookie)
            }.empty
        }

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        northbound.updateSwitchProperties(dstSwitch.dpId, initDstSwProps)
        newDstSwitchPropIsUpdated && northbound.updateSwitchProperties(newDstSwitch.dpId, initNewDstSwProps)
    }

    @Ignore
    @Tidy
    def "System doesn't allow to update destination flow endpoint to a switch if switch is not configured for application in swProps"() {
        given: "Three active switches"
        def allSwitches = topology.activeSwitches
        assumeTrue("Unable to find three active switches", allSwitches.size() > 2)
        def srcSwitch = allSwitches[0]
        def dstSwitch = allSwitches[1]

        and: "One of the given switches is configured for application"
        def initDstSwProps = northbound.getSwitchProperties(dstSwitch.dpId)
        enableAppOnSwitch(dstSwitch.dpId, initDstSwProps)

        and: "A flow on the given switches"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch, false)
        flowHelperV2.addFlow(flow)

        and: "Application for the flow on the dst switch"
        def appName = FlowApplication.TELESCOPE.toString().toLowerCase()
        def dstFlowEndpoint = new FlowEndpoint(flow.destination.switchId, flow.destination.portNumber,
                flow.destination.vlanId)
        northbound.addFlowApps(flow.flowId, appName, new FlowAddAppDto(dstFlowEndpoint))
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert northbound.getSwitchRules(dstSwitch.dpId).flowEntries.findAll {
                Cookie.isMaskedAsTelescope(it.cookie)
            }.size() == 2
        }

        when: "Select new destination switch which doesn't support application in swProps"
        def newDstSwitch = allSwitches[2]
        def initNewDstSwProps = northbound.getSwitchProperties(newDstSwitch.dpId)
        //make sure application is disabled in swProps
        northbound.updateSwitchProperties(newDstSwitch.dpId, initNewDstSwProps.jacksonCopy().tap {
            it.inboundTelescopePort = null
            it.outboundTelescopePort = null
            it.telescopeEgressVlan = null
            it.telescopeIngressVlan = null
        })
        def newDstSwitchPropIsUpdated = true

        and: "Update the flow destination endpoint to the new dst switch"
        flowHelperV2.updateFlow(flow.flowId, flow.tap {
            it.destination.switchId = newDstSwitch.dpId
        })

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage ==
                "Can't update....."

        and: "Applications rules are not deleted on the switch"
        assert northbound.getSwitchRules(dstSwitch.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }.size() == 2

        and: "Applications rules are not installed on the new dst switch"
        assert northbound.getSwitchRules(dstSwitch.dpId).flowEntries.findAll {
            Cookie.isMaskedAsTelescope(it.cookie)
        }.empty

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        northbound.updateSwitchProperties(dstSwitch.dpId, initDstSwProps)
        newDstSwitchPropIsUpdated && northbound.updateSwitchProperties(newDstSwitch.dpId, initNewDstSwProps)
    }

    @Unroll
    def "System returns human readable error when #data.descr application for a non-existing flow"() {
        when: "Make action from description on non-existing switch"
        data.operation()

        then: "Not Found error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        e.responseBodyAsString.to(MessageError).errorMessage == "Flow $NON_EXISTENT_FLOW_ID not found"

        where:
        data << [
                [descr    : "get",
                 operation: { getNorthbound().getFlowApps(NON_EXISTENT_FLOW_ID) }],
                [descr    : "add",
                 operation: {
                     getNorthbound().addFlowApps(NON_EXISTENT_FLOW_ID, FlowApplication.TELESCOPE.toString(),
                             new FlowAddAppDto(new FlowEndpoint(NON_EXISTENT_SWITCH_ID, 10, 302)))
                 }],
                [descr    : "delete",
                 operation: {
                     getNorthbound().deleteFlowApps(NON_EXISTENT_FLOW_ID, FlowApplication.TELESCOPE.toString(),
                             new FlowDeleteAppDto(new FlowEndpoint(NON_EXISTENT_SWITCH_ID, 10, 302)))
                 }],
        ]
    }

    def "System doesn't allow to configure application in switch properties for ISL port" () {
        given: "An isl"
        Isl isl = topology.islsForActiveSwitches.first()
        assumeTrue("Unable to find any active isl", isl as boolean)

        when: "Try configure switch properties using port which is occupied by the given ISL"
        def sw = isl.srcSwitch
        def initSwProps = northbound.getSwitchProperties(sw.dpId)
        northbound.updateSwitchProperties(sw.dpId, initSwProps.jacksonCopy().tap {
            it.inboundTelescopePort = isl.srcPort
        })

        then: "Human readable error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.BAD_REQUEST
        e.responseBodyAsString.to(MessageError).errorMessage ==
                "Inbound telescope port $isl.srcPort is conflicting with flow or isl on the switch $sw.dpId"


        and: "Switch properties isn't updated"
        northbound.getSwitchProperties(sw.dpId) == initSwProps
    }

    @Ignore // it is not working yet
    def "System doesn't allow to create a flow on a port which is occupied by inbound telescope port"() {
        given: "An active switch with telescope configuration"
        def sw = topology.activeSwitches.first()
        def initSwProps = northbound.getSwitchProperties(sw.dpId)
        def inboundTelescopePort = 44
        northbound.updateSwitchProperties(sw.dpId, initSwProps.jacksonCopy().tap {
            it.inboundTelescopePort = inboundTelescopePort
            it.outboundTelescopePort = 55
            it.telescopeEgressVlan = 5
            it.telescopeIngressVlan = 6
        })

        when: "Try to create a flow on the inbound telescope port"
        def flow = flowHelper.singleSwitchFlow(sw).tap {
            it.source.portNumber = inboundTelescopePort
        }
        northbound.addFlow(flow)

        then: "Human readable error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.BAD_REQUEST
        //fix message
        e.responseBodyAsString.to(MessageError).errorMessage ==
                "Flow source port $flow.source.portNumber is conflicting with inbound telescope port $isl.srcPort"

        cleanup: "Revert system to original state"
        northbound.updateSwitchProperties(sw.dpId, initSwProps)
    }

    //???
//    @Ignore // apps rules
    @Tidy
    def "System detects apps flow rules in the missing section"() { // or flow is swapped
        given: "Two active neighboring switches"
        def swPair = topologyHelper.getNeighboringSwitchPair()
        def initSrcSwProps = northbound.getSwitchProperties(swPair.src.dpId)
        enableAppOnSwitch(swPair.src.dpId, initSrcSwProps)

        and: "A flow with application on the src endpoint"
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)
        def appName = FlowApplication.TELESCOPE.toString().toLowerCase()
        def dstFlowEndpoint = new FlowEndpoint(flow.source.switchId, flow.source.portNumber, flow.source.vlanId)
        northbound.addFlowApps(flow.flowId, appName, new FlowAddAppDto(dstFlowEndpoint))

        when: "Delete apps rules on the src switch"
        def srcSwAppRules
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            srcSwAppRules = northbound.getSwitchRules(swPair.src.dpId).flowEntries.findAll {
                Cookie.isMaskedAsTelescope(it.cookie)
            }
            assert srcSwAppRules.size() == 2
        }
        srcSwAppRules*.cookie.each {
            northbound.deleteSwitchRules(swPair.src.dpId, it)
        }

        then: "System detects missing rules on the src switch"
        with(northbound.validateSwitch(swPair.src.dpId).rules) { validation ->
//            validation.missing.sort() == srcSwAppRules*.cookie.sort()
            validation.misconfigured.empty
//            validation.excess.empty
        }

        and: "Flow is valid"
//        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
//
//        and: "Flow is pingable"
//        with(northbound.pingFlow(flow.flowId, new PingInput())) {
//            it.forward.pingSuccess
//            it.reverse.pingSuccess
//        }

        when: "Synchronize the flow"
        with(northbound.synchronizeFlow(flow.flowId)) { !it.rerouted }

        then: "Missing rules are not reinstalled"  // ????
        with(northbound.validateSwitch(swPair.src.dpId).rules) { validation ->
            validation.missing.sort() == srcSwAppRules*.cookie.sort()
            validation.misconfigured.empty
            validation.excess.empty
        }

        when: "Synchronize the switch"
        northbound.synchronizeSwitch(swPair.src.dpId, false)

        then: "Missing rules are reinstalled"
        with(northbound.validateSwitch(swPair.src.dpId).rules) { validation ->
            validation.missing.empty
            validation.misconfigured.empty
            validation.excess.empty
            validation.proper.containsAll(srcSwAppRules*.cookie)
        }

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        northbound.updateSwitchProperties(swPair.src.dpId, initSrcSwProps)
    }

//    @Ignore("missing rules")
    @Tidy
    def "System doesn't allow to create the same application on both endpoints at the same time"() {
        given: "Two active switches"
        def swP = topologyHelper.getNeighboringSwitchPair()

        // Application is disabled by default
        Map<SwitchId, SwitchPropertiesDto> initSwProps = [swP.src, swP.dst].collectEntries {
            [(it.dpId): northbound.getSwitchProperties(it.dpId)]
        }
        [swP.src, swP.dst].each {
            enableAppOnSwitch(it.dpId, initSwProps[it.dpId])
        }

        and: "A flow"
        def flow = flowHelperV2.randomFlow(swP)
        flowHelperV2.addFlow(flow)

        when: "Add application for the flow on the dst switch"
        def appName = FlowApplication.TELESCOPE.toString().toLowerCase()
        def dstFlowEndpoint = new FlowEndpoint(flow.destination.switchId, flow.destination.portNumber,
                flow.destination.vlanId)
        northbound.addFlowApps(flow.flowId, appName, new FlowAddAppDto(dstFlowEndpoint))

        and: "Add application for the flow on the src switch"
        def srcFlowEndpoint = new FlowEndpoint(flow.source.switchId, flow.source.portNumber, flow.source.vlanId)
        northbound.addFlowApps(flow.flowId, appName, new FlowAddAppDto(srcFlowEndpoint))

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage ==
                "Telescope application is already enabled for the opposite endpoint for the flow ${flow.flowId}"

        and: "Application is not created on the src endpoint"
        with(northbound.getFlowApps(flow.flowId)) { response ->
            response.flowId == flow.flowId
            response.srcApps.applications.empty
            !response.dstApps.applications.empty
        }

        and: "Source switch passes switch validation"
        with(northbound.validateSwitch(swP.dst.dpId)) {
            switchHelper.verifyRuleSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
            switchHelper.verifyMeterSectionsAreEmpty(it, ["missing", "misconfigured", "excess"])
        }
        northbound.getSwitchRules(swP.dst.dpId).flowEntries.findAll { Cookie.isMaskedAsTelescope(it.cookie) }.empty

        cleanup:
        [swP.src, swP.dst].each {
            northbound.updateSwitchProperties(it.dpId, initSwProps[it.dpId])
        }
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    private void enableAppOnSwitch(SwitchId switchId, SwitchPropertiesDto swProps) {
        // inbound/outbound ports shouldn't be occupied by ISL/flow port
        northbound.updateSwitchProperties(switchId, swProps.jacksonCopy().tap {
            it.inboundTelescopePort = 33
            it.outboundTelescopePort = 44
            it.telescopeEgressVlan = 5
            it.telescopeIngressVlan = 6
        })
    }

    private static CommandAppMessage buildMessage(final CommandAppData data) {
        return new CommandAppMessage(System.currentTimeMillis(), UUID.randomUUID().toString(), data);
    }
}
