package org.openkilda.functionaltests.spec.switches

import static groovyx.gpars.GParsPool.withPool
import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_ACTION
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_FAIL
import static org.openkilda.testing.Constants.NON_EXISTENT_SWITCH_ID
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.command.switches.DeleteRulesAction
import org.openkilda.messaging.command.switches.InstallRulesAction
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.northbound.dto.v2.switches.SwitchPatchDto

import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Unroll

class SwitchesSpec extends HealthCheckSpecification {
    @Tidy
    def "System is able to return a list of all switches"() {
        expect: "System can return list of all switches"
        !northbound.getAllSwitches().empty
    }

    @Tidy
    @Tags([SMOKE, SMOKE_SWITCHES])
    def "System is able to return a certain switch info by its id"() {
        when: "Request info about certain switch from Northbound"
        def sw = topology.activeSwitches[0]
        def response = northbound.getSwitch(sw.dpId)

        then: "Switch information is returned"
        response.switchId == sw.dpId
        !response.hostname.empty
        !response.address.empty
        !response.description.empty
        !response.ofVersion.empty
        !response.hardware.empty
        !response.software.empty
        !response.serialNumber.empty
        !response.manufacturer.empty
        response.state == SwitchChangeType.ACTIVATED
    }

    @Tidy
    def "Informative error is returned when requesting switch info with non-existing id"() {
        when: "Request info about non-existing switch from Northbound"
        northbound.getSwitch(NON_EXISTENT_SWITCH_ID)

        then: "Not Found error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        e.responseBodyAsString.to(MessageError).errorMessage == "Switch $NON_EXISTENT_SWITCH_ID not found."
    }

    @Tidy
    def "Systems allows to get a flow that goes through a switch"() {
        given: "Two active not neighboring switches with two diverse paths at least"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 3
        } ?: assumeTrue("No suiting switches found", false)

        and: "A protected flow"
        def protectedFlow = flowHelperV2.randomFlow(switchPair)
        protectedFlow.allocateProtectedPath = true
        flowHelperV2.addFlow(protectedFlow)

        and: "A single switch flow"
        def allowedPorts = topology.getAllowedPortsForSwitch(switchPair.src).findAll {
            it != protectedFlow.source.portNumber
        }
        def r = new Random()
        def singleFlow = flowHelperV2.singleSwitchFlow(switchPair.src)
        singleFlow.source.portNumber = allowedPorts[r.nextInt(allowedPorts.size())]
        singleFlow.destination.portNumber = allowedPorts[r.nextInt(allowedPorts.size())]
        flowHelperV2.addFlow(singleFlow)

        when: "Get all flows going through the involved switches"
        def flowPathInfo = northbound.getFlowPath(protectedFlow.flowId)
        def mainPath = pathHelper.convert(flowPathInfo)
        def protectedPath = pathHelper.convert(flowPathInfo.protectedPath)

        def mainSwitches = pathHelper.getInvolvedSwitches(mainPath)*.dpId
        def protectedSwitches = pathHelper.getInvolvedSwitches(protectedPath)*.dpId
        def involvedSwitchIds = (mainSwitches + protectedSwitches).unique()

        then: "The created flows are in the response list from the src switch"
        def switchFlowsResponseSrcSwitch = northbound.getSwitchFlows(switchPair.src.dpId)
        switchFlowsResponseSrcSwitch*.id.sort() == [protectedFlow.flowId, singleFlow.flowId].sort()

        and: "Only the protectedFlow is in the response list from the involved switch(except the src switch)"
        involvedSwitchIds.findAll { it != switchPair.src.dpId }.each { switchId ->
            def getSwitchFlowsResponse = northbound.getSwitchFlows(switchId)
            assert getSwitchFlowsResponse.size() == 1
            assert getSwitchFlowsResponse[0].id == protectedFlow.flowId
        }

        when: "Get all flows going through the src switch based on the port of the main path"
        def getSwitchFlowsResponse1 = northbound.getSwitchFlows(switchPair.src.dpId, mainPath[0].portNo)

        then: "Only the protected flow is in the response list"
        getSwitchFlowsResponse1.size() == 1
        getSwitchFlowsResponse1[0].id == protectedFlow.flowId

        when: "Get all flows going through the src switch based on the port of the protected path"
        def getSwitchFlowsResponse2 = northbound.getSwitchFlows(switchPair.src.dpId, protectedPath[0].portNo)

        then: "Only the protected flow is in the response list"
        getSwitchFlowsResponse2.size() == 1
        getSwitchFlowsResponse2[0].id == protectedFlow.flowId

        when: "Get all flows going through the src switch based on the dstPort of the single switch flow"
        def getSwitchFlowsResponse3 = northbound.getSwitchFlows(switchPair.src.dpId, singleFlow.destination.portNumber)

        then: "Only the single switch flow is in the response list"
        getSwitchFlowsResponse3.size() == 1
        getSwitchFlowsResponse3[0].id == singleFlow.flowId

        when: "Get all flows going through the dst switch based on the dstPort of the protected flow"
        def getSwitchFlowsResponse4 = northbound.getSwitchFlows(switchPair.dst.dpId,
                protectedFlow.destination.portNumber)

        then: "Only the protected flow is in the response list"
        getSwitchFlowsResponse4.size() == 1
        getSwitchFlowsResponse4[0].id == protectedFlow.flowId

        when: "Create default flow on the same switches"
        def defaultFlow = flowHelperV2.randomFlow(switchPair)
        defaultFlow.source.vlanId = 0
        defaultFlow.destination.vlanId = 0
        flowHelperV2.addFlow(defaultFlow)

        and: "Get all flows going through the src switch"
        def getSwitchFlowsResponse5 = northbound.getSwitchFlows(switchPair.src.dpId)

        then: "The created flows are in the response list"
        getSwitchFlowsResponse5.size() == 3
        getSwitchFlowsResponse5*.id.sort() == [protectedFlow.flowId, singleFlow.flowId, defaultFlow.flowId].sort()

        when: "Bring down all ports on src switch to make flow DOWN"
        def doPortDowns = true //helper var for cleanup
        def portsToDown = topology.getBusyPortsForSwitch(switchPair.src)
        withPool {
            portsToDown.eachParallel { // https://github.com/telstra/open-kilda/issues/4014
                antiflap.portDown(switchPair.src.dpId, it)
            }
        }
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == portsToDown.size() * 2
        }

        and: "Get all flows going through the src switch"
        Wrappers.wait(WAIT_OFFSET * 2) {
            assert northboundV2.getFlowStatus(protectedFlow.flowId).status == FlowState.DOWN
            assert northbound.getFlowHistory(protectedFlow.flowId).last().payload.find { it.action == REROUTE_FAIL }
            assert northboundV2.getFlowStatus(defaultFlow.flowId).status == FlowState.DOWN
            def defaultFlowHistory = northbound.getFlowHistory(defaultFlow.flowId).findAll { it.action == REROUTE_ACTION }
            assert defaultFlowHistory.last().payload.find { it.action == REROUTE_FAIL }
            assert defaultFlowHistory.find { it.taskId =~ /.+ : retry #1/ }
        }
        def getSwitchFlowsResponse6 = northbound.getSwitchFlows(switchPair.src.dpId)

        then: "The created flows are in the response list"
        getSwitchFlowsResponse6*.id.sort() == [protectedFlow.flowId, singleFlow.flowId, defaultFlow.flowId].sort()

        cleanup: "Delete the flows"
        [protectedFlow, singleFlow, defaultFlow].each { flowHelperV2.deleteFlow(it.flowId) }
        doPortDowns && portsToDown.each { antiflap.portUp(switchPair.src.dpId, it) }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()
    }

    @Tidy
    def "Informative error is returned when requesting all flows going through non-existing switch"() {
        when: "Get all flows going through non-existing switch"
        northbound.getSwitchFlows(NON_EXISTENT_SWITCH_ID)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        exc.responseBodyAsString.to(MessageError).errorMessage == "Switch $NON_EXISTENT_SWITCH_ID not found."
    }

    @Tidy
    def "Systems allows to get all flows that goes through a DEACTIVATED switch"() {
        given: "Two active not neighboring switches"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().first() ?:
                assumeTrue("No suiting switches found", false)

        and: "A simple flow"
        def simpleFlow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(simpleFlow)

        and: "A single switch flow"
        def singleFlow = flowHelperV2.singleSwitchFlow(switchPair.src)
        flowHelperV2.addFlow(singleFlow)

        when: "Deactivate the src switch"
        def switchToDisconnect = topology.switches.find { it.dpId == switchPair.src.dpId }
        def blockData = switchHelper.knockoutSwitch(switchToDisconnect, RW)

        and: "Get all flows going through the deactivated src switch"
        def switchFlowsResponseSrcSwitch = northbound.getSwitchFlows(switchPair.src.dpId)

        then: "The created flows are in the response list from the deactivated src switch"
        switchFlowsResponseSrcSwitch*.id.sort() == [simpleFlow.flowId, singleFlow.flowId].sort()

        cleanup: "Revive the src switch and delete the flows"
        [simpleFlow, singleFlow].each { flowHelperV2.deleteFlow(it.flowId) }
        switchHelper.reviveSwitch(switchToDisconnect, blockData)
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "System returns human readable error when requesting switch ports from non-existing switch"() {
        when: "Request all ports info from non-existing switch"
        northbound.getPorts(NON_EXISTENT_SWITCH_ID)

        then: "Not Found error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        e.responseBodyAsString.to(MessageError).errorMessage == "Switch $NON_EXISTENT_SWITCH_ID not found"
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "System returns human readable error when requesting switch rules from non-existing switch"() {
        when: "Request all rules from non-existing switch"
        northbound.getSwitchRules(NON_EXISTENT_SWITCH_ID)

        then: "Not Found error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        e.responseBodyAsString.to(MessageError).errorMessage == "Switch $NON_EXISTENT_SWITCH_ID not found"
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "System returns human readable error when installing switch rules on non-existing switch"() {
        when: "Install switch rules on non-existing switch"
        northbound.installSwitchRules(NON_EXISTENT_SWITCH_ID, InstallRulesAction.INSTALL_DEFAULTS)

        then: "Not Found error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        e.responseBodyAsString.to(MessageError).errorMessage == "Switch $NON_EXISTENT_SWITCH_ID not found"
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "System returns human readable error when deleting switch rules on non-existing switch"() {
        when: "Delete switch rules on non-existing switch"
        northbound.deleteSwitchRules(NON_EXISTENT_SWITCH_ID, DeleteRulesAction.DROP_ALL_ADD_DEFAULTS)

        then: "Not Found error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        e.responseBodyAsString.to(MessageError).errorMessage == "Switch $NON_EXISTENT_SWITCH_ID not found"
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "System returns human readable error when setting under maintenance non-existing switch"() {
        when: "set under maintenance non-existing switch"
        northbound.setSwitchMaintenance(NON_EXISTENT_SWITCH_ID, true, true)

        then: "Not Found error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        e.responseBodyAsString.to(MessageError).errorMessage == "Switch $NON_EXISTENT_SWITCH_ID not found."
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "System returns human readable error when requesting all meters from non-existing switch"() {
        when: "Request all meters from non-existing switch"
        northbound.getAllMeters(NON_EXISTENT_SWITCH_ID)

        then: "Not Found error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        e.responseBodyAsString.to(MessageError).errorMessage == "Switch $NON_EXISTENT_SWITCH_ID not found"
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "System returns human readable error when deleting meter on non-existing switch"() {
        when: "Delete meter on non-existing switch"
        northbound.deleteMeter(NON_EXISTENT_SWITCH_ID, 33)

        then: "Not Found error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        e.responseBodyAsString.to(MessageError).errorMessage == "Switch $NON_EXISTENT_SWITCH_ID not found"
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "System returns human readable error when configuring port on non-existing switch"() {
        when: "Configure port on non-existing switch"
        northbound.portUp(NON_EXISTENT_SWITCH_ID, 33)

        then: "Not Found error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        e.responseBodyAsString.to(MessageError).errorMessage == "Switch $NON_EXISTENT_SWITCH_ID not found"
    }

    @Unroll
    def "System returns human readable error when #data.descr non-existing switch"() {
        when: "Make action from description on non-existing switch"
        data.operation()

        then: "Not Found error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        e.responseBodyAsString.to(MessageError).errorMessage == "Switch '$NON_EXISTENT_SWITCH_ID' not found"

        where:
        data << [
                [descr    : "synchronizing rules on",
                 operation: { getNorthbound().synchronizeSwitchRules(NON_EXISTENT_SWITCH_ID) }],
                [descr    : "synchronizing",
                 operation: { getNorthbound().synchronizeSwitch(NON_EXISTENT_SWITCH_ID, true) }],
                [descr    : "validating rules on",
                 operation: { getNorthbound().validateSwitchRules(NON_EXISTENT_SWITCH_ID) }],
                [descr    : "validating",
                 operation: { getNorthbound().validateSwitch(NON_EXISTENT_SWITCH_ID) }]
        ]
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    @Unroll
    def "Able to partially update switch a 'location.#data.field' field"() {
        given: "A switch"
        def sw = topology.activeSwitches.first()
        def initConf = northbound.getSwitch(sw.dpId)

        when: "Request a switch partial update for a #data.field field"
        SwitchPatchDto updateRequest = [location: [(data.field): data.newValue]] as SwitchPatchDto
        def response = northboundV2.partialSwitchUpdate(sw.dpId, updateRequest)

        then: "Update response reflects the changes"
        response.location."$data.field" == data.newValue

        and: "Changes actually took place"
        northbound.getSwitch(sw.dpId).location."$data.field" == data.newValue

        cleanup:
        northboundV2.partialSwitchUpdate(sw.dpId, [location: [
                latitude: initConf.location.latitude ?: 0,
                longitude: initConf.location.longitude ?: 0,
                city: initConf.location.city ?: "",
                country: initConf.location.country ?: "",
                street: initConf.location.street ?: ""
        ]] as SwitchPatchDto)

        where:
        data << [
                [
                        field   : "latitude",
                        newValue: 654
                ],
                [
                        field   : "longitude",
                        newValue: 456
                ],
                [
                        field   : "street",
                        newValue: "testStreet"
                ],
                [
                        field   : "city",
                        newValue: "testCity"
                ],
                [
                        field   : "country",
                        newValue: "testCountry"
                ]
        ]
    }

    @Tidy
    def "Able to partially update switch a 'pop' field"() {
        given: "A switch"
        def sw = topology.activeSwitches.first()
        def initConf = northbound.getSwitch(sw.dpId)

        when: "Request a switch partial update for a 'pop' field"
        def newPopValue = "test_POP"
        def response = northboundV2.partialSwitchUpdate(sw.dpId, new SwitchPatchDto().tap { it.pop = newPopValue })

        then: "Update response reflects the changes"
        response.pop == newPopValue

        and: "Changes actually took place"
        northbound.getSwitch(sw.dpId).pop == newPopValue

        cleanup:
        northboundV2.partialSwitchUpdate(sw.dpId, new SwitchPatchDto().tap { it.pop = initConf.pop ?: "" })
    }
}
