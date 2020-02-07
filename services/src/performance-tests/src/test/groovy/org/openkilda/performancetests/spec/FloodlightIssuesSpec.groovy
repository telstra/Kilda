package org.openkilda.performancetests.spec

import org.openkilda.functionaltests.helpers.Dice
import org.openkilda.functionaltests.helpers.Dice.Face
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.performancetests.BaseSpecification
import org.openkilda.testing.model.topology.TopologyDefinition

import org.springframework.beans.factory.annotation.Value
import spock.lang.Shared

import java.util.concurrent.TimeUnit

class FloodlightIssuesSpec extends BaseSpecification {

    @Value('${floodlight.alive.timeout}')
    int floodlightAliveTimeout

    @Shared
    TopologyDefinition topology
    @Shared
    def r = new Random()

    @Override
    def setupOnce() {
        northbound.getAllFlows().each { northboundV2.deleteFlow(it.id) }
        topoHelper.purgeTopology()
    }

    def "System is having constant issues with Floodlight connection during a long time"() {
        when:
        def dice = new Dice([
                new Face(name: "Floodlight reboot", chance: 10, event: lockKeeper.&restartFloodlight()),
                new Face(name: "Idle", chance: 90, event: { TimeUnit.SECONDS.sleep(3) })
        ])
        def testDuration = 7 * 60 * 60 //7 hours

        Wrappers.timedLoop(testDuration) {
            dice.roll()
            TimeUnit.SECONDS.sleep(1)
        }

        then:""
    }

    def floodlightKafkaBlink(int downtimeSeconds = floodlightAliveTimeout + 1, boolean async = true) {
        lockKeeper.knockoutFloodlight()
        def revive = {
            TimeUnit.SECONDS.sleep(downtimeSeconds)
            lockKeeper.reviveFloodlight()
        }
        async ? new Thread(revive).start() : revive()
    }
}
