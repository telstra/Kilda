package org.openkilda.functionaltests

import static org.openkilda.model.MeterId.MAX_SYSTEM_RULE_METER_ID
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.exception.IslNotFoundException
import org.openkilda.functionaltests.extension.healthcheck.HealthCheck
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.testing.model.topology.TopologyDefinition.Status
import org.openkilda.testing.tools.SoftAssertions

class HealthCheckSpecification extends BaseSpecification {

    @HealthCheck
    def "Kilda is UP and topology is clean"() {
        expect: "Kilda's health check request is successful"
        northbound.getHealthCheck().components["kafka"] == "operational"

        and: "All switches and links are active. No flows and link props are present"
        def links = null
        verifyAll {
            Wrappers.wait(WAIT_OFFSET) {
                links = northbound.getAllLinks()
                assert northbound.activeSwitches.size() == topology.switches.findAll { it.status != Status.Inactive }.size()
                assert links.findAll { it.state != IslChangeType.DISCOVERED }.empty
            }
            def topoLinks = topology.islsForActiveSwitches.collectMany { isl ->
                [islUtils.getIslInfo(links, isl).orElseThrow { new IslNotFoundException(isl.toString()) },
                 islUtils.getIslInfo(links, isl.reversed).orElseThrow {
                     new IslNotFoundException(isl.reversed.toString())
                 }]
            }
            def missingLinks = links.findAll { it.state == IslChangeType.DISCOVERED } - topoLinks
            assert missingLinks.empty, "These links are missing in topology.yaml"
            northbound.allFlows.empty
            northbound.allLinkProps.empty
        }

        and: "Link bandwidths and speeds are equal. No excess and missing switch rules are present"
        verifyAll {
            links.findAll { it.availableBandwidth != it.speed }.empty
            topology.activeSwitches.each { sw ->
                def rules = northbound.validateSwitchRules(sw.dpId)
                assert rules.excessRules.empty, sw
                assert rules.missingRules.empty, sw
            }

            topology.activeSwitches.findAll {
                !it.virtual && it.ofVersion != "OF_12" && !northbound.getAllMeters(it.dpId).meterEntries.findAll {
                    it.meterId > MAX_SYSTEM_RULE_METER_ID
                }.isEmpty()
            }.empty
        }

//        and: "Every switch is connected to the expected region"
//        def regionVerifications = new SoftAssertions()
//        flHelper.fls.forEach { fl ->
//            def expectedSwitchIds = topology.activeSwitches.findAll { fl.region in it.regions }*.dpId
//            if (!expectedSwitchIds.empty) {
//                regionVerifications.checkSucceeds {
//                    assert fl.floodlightService.switches*.switchId.sort() == expectedSwitchIds.sort()
//                }
//            }
//        }
//        regionVerifications.verify()
    }
}
