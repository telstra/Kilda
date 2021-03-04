package org.openkilda.functionaltests.spec.links

import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.fixture.TestFixture
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.model.SwitchId
import org.openkilda.northbound.dto.v1.links.LinkPropsDto
import org.openkilda.testing.Constants

import spock.lang.Shared
import spock.lang.Unroll

import java.util.concurrent.TimeUnit

class LinkPropertiesSpec extends HealthCheckSpecification {

    @Shared
    def propsDataForSearch = [
            new LinkPropsDto("00:00:00:00:00:00:00:01", 1, "00:00:00:00:00:00:00:02", 1, [:]),
            new LinkPropsDto("00:00:00:00:00:00:00:01", 2, "00:00:00:00:00:00:00:02", 1, [:]),
            new LinkPropsDto("00:00:00:00:00:00:00:01", 2, "00:00:00:00:00:00:00:02", 2, [:]),
            new LinkPropsDto("00:00:00:00:00:00:00:03", 3, "00:00:00:00:00:00:00:03", 3, [:]),
            new LinkPropsDto("00:00:00:00:00:00:00:02", 1, "00:00:00:00:00:00:00:01", 1, [:])
    ]

    def setupSpec() {
        //clear any existing properties before tests start
        def allLinkProps = northbound.getAllLinkProps()
        northbound.deleteLinkProps(allLinkProps)
        //make sure all costs are default
        database.resetCosts()
    }

    @Tidy
    def "Empty list is returned if there are no properties"() {
        expect: "Get link properties is empty for no properties"
        northbound.getAllLinkProps().empty
    }

    //TODO(ylobankov): Actually this is abnormal behavior and we should have an error. But this test is aligned
    // with the current system behavior to verify that system is not hanging. Need to rework the test when system
    // behavior is fixed.
    @Tidy
    def "Link props are created with empty values when sending an invalid link props key"() {
        when: "Send link property request with invalid character"
        def response = northbound.updateLinkProps([new LinkPropsDto("00:00:00:00:00:00:00:01", 1,
                "00:00:00:00:00:00:00:02", 1, ["`cost": "700"])])

        then: "Response states that operation succeeded"
        response.successes == 1

        and: "Link props are created but with empty values"
        def linkProps = northbound.getLinkProps(null, 1, null, 1)
        linkProps.size() == 2
        linkProps.each { assert it.props.isEmpty() }

        cleanup: "Delete created link props"
        northbound.deleteLinkProps(linkProps)
    }

    def "Unable to create link property with invalid switchId format"() {
        when: "Try creating link property with invalid switchId format"
        def linkProp = new LinkPropsDto("I'm invalid", 1, "00:00:00:00:00:00:00:02", 1, [:])
        def response = northbound.updateLinkProps([linkProp])

        then: "Response with error is received"
        response.failures == 1
        response.messages.first() == "Can not parse input string: \"${linkProp.srcSwitch}\""
    }

    @Unroll
    def "Unable to create link property with non-numeric value for #key"() {
        when: "Try creating link property with non-numeric values"
        def linkProp = new LinkPropsDto("00:00:00:00:00:00:00:01", 1, "00:00:00:00:00:00:00:02", 1, [(key): "1000L"])
        def response = northbound.updateLinkProps([linkProp])

        then: "Response with error is received"
        response.failures == 1
        response.messages.first() == "For input string: \"${linkProp.props[key]}\""

        where:
        key << ["cost", "max_bandwidth"]
    }

    @Tidy
    @Unroll
    @TestFixture(setup = "prepareLinkPropsForSearch", cleanup = "cleanLinkPropsAfterSearch")
    def "Searching for link props with #data.descr"() {
        when: "Get link properties with search query"
        def foundProps = northbound.getLinkProps(*data.params)

        then: "Returned props list match expected"
        foundProps.sort() == expected.sort()

        where:
        data << [
                [
                        descr : "src switch and src port (single result)",
                        params: [new SwitchId("00:00:00:00:00:00:00:01"), 1, null, null],
                ],
                [
                        descr : "src switch and src port (multiple results)",
                        params: [new SwitchId("00:00:00:00:00:00:00:01"), 2, null, null],
                ],
                [
                        descr : "dst switch and dst port (single result)",
                        params: [null, null, new SwitchId("00:00:00:00:00:00:00:02"), 2]
                ],
                [
                        descr : "dst switch and dst port (multiple results)",
                        params: [null, null, new SwitchId("00:00:00:00:00:00:00:02"), 1]
                ],
                [
                        descr : "src switch and src port (no results)",
                        params: [new SwitchId("00:00:00:00:00:00:00:03"), 2, null, null]
                ],
                [
                        descr : "dst switch and dst port (no results)",
                        params: [null, null, new SwitchId("00:00:00:00:00:00:00:03"), 2]
                ],
                [
                        descr : "src and dst switch (different switches)",
                        params: [new SwitchId("00:00:00:00:00:00:00:01"), null,
                                 new SwitchId("00:00:00:00:00:00:00:02"), null]
                ],
                [
                        descr : "src and dst switch (same switch)",
                        params: [new SwitchId("00:00:00:00:00:00:00:03"), null,
                                 new SwitchId("00:00:00:00:00:00:00:03"), null]
                ],
                [
                        descr : "src and dst port",
                        params: [null, 2, null, 1]
                ],
                [
                        descr : "full match",
                        params: [new SwitchId(propsDataForSearch[0].srcSwitch), propsDataForSearch[0].srcPort,
                                 new SwitchId(propsDataForSearch[0].dstSwitch), propsDataForSearch[0].dstPort]
                ]
        ]
        expected = propsDataForSearch.findAll {
            (!data.params[0] || it.srcSwitch == data.params[0].toString()) &&
                    (!data.params[1] || it.srcPort == data.params[1]) &&
                    (!data.params[2] || it.dstSwitch == data.params[2].toString()) &&
                    (!data.params[3] || it.dstPort == data.params[3])
        }
    }

    def "Updating cost and max bandwidth via link props actually updates cost and max bandwidth on ISLs"() {
        given: "An active ISL"
        def isl = topology.islsForActiveSwitches.first()
        def initialMaxBandwidth = islUtils.getIslInfo(isl).get().maxBandwidth

        when: "Create link props of ISL to update cost and max bandwidth on the forward and reverse directions"
        def costValue = "12345"
        def maxBandwidthValue = "54321"
        def linkProps = [islUtils.toLinkProps(isl, ["cost": costValue, "max_bandwidth": maxBandwidthValue])]
        northbound.updateLinkProps(linkProps)
        assert northbound.getAllLinkProps().size() == 2

        then: "Cost on forward and reverse ISLs is really updated"
        database.getIslCost(isl) == costValue.toInteger()
        database.getIslCost(isl.reversed) == costValue.toInteger()

        and: "Max bandwidth on forward and reverse ISLs is really updated as well"
        def updatedLinks = northbound.getAllLinks()
        islUtils.getIslInfo(updatedLinks, isl).get().maxBandwidth == maxBandwidthValue.toInteger()
        islUtils.getIslInfo(updatedLinks, isl.reversed).get().maxBandwidth == maxBandwidthValue.toInteger()

        when: "Update link props on the forward direction of ISL to update cost one more time"
        def newCostValue = "345"
        northbound.updateLinkProps([islUtils.toLinkProps(isl, ["cost": newCostValue])])

        then: "Forward and reverse directions of the link props are really updated"
        northbound.getAllLinkProps().each {
            assert it.props.cost == newCostValue
        }

        and: "Cost on forward and reverse ISLs is really updated"
        database.getIslCost(isl) == newCostValue.toInteger()
        database.getIslCost(isl.reversed) == newCostValue.toInteger()

        when: "Delete link props"
        northbound.deleteLinkProps(linkProps)

        then: "Cost on ISLs is changed to the default value"
        database.getIslCost(isl) == Constants.DEFAULT_COST
        database.getIslCost(isl.reversed) == Constants.DEFAULT_COST

        and: "Max bandwidth on forward and reverse ISLs is changed to the initial value as well"
        def links = northbound.getAllLinks()
        islUtils.getIslInfo(links, isl).get().maxBandwidth == initialMaxBandwidth
        islUtils.getIslInfo(links, isl.reversed).get().maxBandwidth == initialMaxBandwidth
    }

    @Tags(SMOKE)
    def "Newly discovered link gets cost and max bandwidth from link props"() {
        given: "An active ISL"
        def isl = topology.islsForActiveSwitches.first()

        and: "Bring port down on the source switch"
        antiflap.portDown(isl.srcSwitch.dpId, isl.srcPort)
        TimeUnit.SECONDS.sleep(2) //receive any in-progress disco packets
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getLink(isl).actualState == IslChangeType.FAILED
            assert northbound.getLink(isl.reversed).actualState == IslChangeType.FAILED
        }

        and: "Delete the link"
        northbound.deleteLink(islUtils.toLinkParameters(isl))
        !islUtils.getIslInfo(isl)
        !islUtils.getIslInfo(isl.reversed)


        and: "Set cost and max bandwidth on the deleted link via link props"
        def costValue = "12345"
        def maxBandwidthValue = "54321"
        def linkProps = [islUtils.toLinkProps(isl, ["cost": costValue, "max_bandwidth": maxBandwidthValue])]
        northbound.updateLinkProps(linkProps)

        when: "Bring port up on the source switch to discover the deleted link"
        antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
        }

        then: "The discovered link gets cost from link props"
        database.getIslCost(isl) == costValue.toInteger()
        database.getIslCost(isl.reversed) == costValue.toInteger()

        and: "The discovered link gets max bandwidth from link props as well"
        def links = northbound.getAllLinks()
        islUtils.getIslInfo(links, isl).get().maxBandwidth == maxBandwidthValue.toInteger()
        islUtils.getIslInfo(links, isl.reversed).get().maxBandwidth == maxBandwidthValue.toInteger()

        and: "Delete link props"
        //DELETE /link/props deletes props for both directions, even if only one direction specified
        northbound.deleteLinkProps(linkProps)
        northbound.getAllLinkProps().empty
    }

    def prepareLinkPropsForSearch() {
        northbound.updateLinkProps(propsDataForSearch)
    }

    def cleanLinkPropsAfterSearch() {
        northbound.deleteLinkProps(propsDataForSearch)
        Wrappers.wait(WAIT_OFFSET / 2) { assert northbound.getAllLinkProps().empty }
    }
}
