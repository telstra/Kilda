package org.openkilda.performancetests.helpers

import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.performancetests.model.CustomTopology
import org.openkilda.testing.model.topology.TopologyDefinition
import org.openkilda.testing.service.floodlight.FloodlightsHelper
import org.openkilda.testing.service.labservice.LabService
import org.openkilda.testing.service.labservice.model.LabInstance
import org.openkilda.testing.service.northbound.NorthboundService
import org.openkilda.testing.tools.IslUtils

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

@Component("performance")
@Slf4j
class TopologyHelper extends org.openkilda.functionaltests.helpers.TopologyHelper {

    @Autowired
    NorthboundService northbound
    @Autowired
    LabService labService
    @Autowired
    FloodlightsHelper flHelper
    @Autowired
    IslUtils islUtils

    @Value('${discovery.timeout}')
    int discoveryTimeout

    /**
     * First connect all switches in a 'line'. Then start randomly adding hordes.
     * Requested amount of isls must be >= (switchesAmount - 1) in order to guarantee the initial 'line'
     */
    CustomTopology createRandomTopology(int switchesAmount, int islsAmount) {
        if (islsAmount < (switchesAmount - 1)) {
            throw new RuntimeException("Not enough ISLs were requested. islsAmount should be >= (switchesAmount - 1)")
        }
        def topo = new CustomTopology()
        switchesAmount.times { i ->
            def fls = flHelper.fls.findAll { it.mode == RW }
            def fl = fls[i % fls.size()]
            topo.addCasualSwitch(fl.openflow, [fl.region])
        }
        //form a line of switches
        def allSwitches = topo.getSwitches()
        for (def i = 0; i < allSwitches.size() - 1; i++) {
            topo.addIsl(allSwitches[i], allSwitches[i + 1])
        }
        //create links between random switches
        (islsAmount - topo.getIsls().size()).times {
            def src = topo.pickRandomSwitch()
            def dst = topo.pickRandomSwitch([src])
            topo.addIsl(src, dst)
        }

        createTopology(topo);
        return topo
    }

    def createTopology(CustomTopology topo) {
        labService.createLab(topo)
        Wrappers.wait(30 + topo.activeSwitches.size() * 3, 5) {
            verifyTopology(topo)
        }
        return topo
    }

    /**
     * Verify that current discovered topology matches the TopologyDefinition.
     */
    def verifyTopology(TopologyDefinition topo) {
        Wrappers.timedLoop(10) { //system should remain stable for 10s
            //Switches discovery
            def switches = northbound.getAllSwitches().findAll { it.switchId in topo.activeSwitches*.dpId }
            def activeSwitches = switches.findAll { it.state == SwitchChangeType.ACTIVATED }
            log.info("Active Switches " + activeSwitches.size())
            assert switches == activeSwitches
            assert switches.size() == topo.activeSwitches.size()

            //Isl discovery
            def isls = northbound.getAllLinks().findAll {
                it.source.switchId in topo.activeSwitches*.dpId ||
                        it.destination.switchId in topo.activeSwitches*.dpId
            }
            log.info("Isls " + isls.size())
            assert isls.size() == topo.isls.size() * 2
            //TODO(rtretiak): to verify actual src and dst of discovered ISLs
            isls.each {
                assert it.state == IslChangeType.DISCOVERED
                assert it.actualState == IslChangeType.DISCOVERED
            }
            sleep(500)
        }
    }

    /**
     * Delete the lab. Wait until topology is failed and delete all ISLs and all switches related to given
     * topology definition.
     */
    def purgeTopology(TopologyDefinition topo, LabInstance lab = null) {
        if (lab) {
            labService.deleteLab(lab)
        } else {
            labService.flushLabs()
        }
        TimeUnit.SECONDS.sleep(discoveryTimeout + 3)
        northbound.getAllLinks().findAll { it.state == IslChangeType.FAILED }.each {
            try {
                northbound.deleteLink(islUtils.toLinkParameters(it))
            } catch (HttpClientErrorException e) {
                if (e.statusCode == HttpStatus.NOT_FOUND) {
                    //fine, it's not present, do nothing
                } else {
                    throw e
                }
            }
        }
        def topoSwitches = northbound.getAllSwitches().findAll { it.state == SwitchChangeType.DEACTIVATED }
        int totalSwitches = topoSwitches.size()
        int i = 1;
        topoSwitches.each {
            log("Removing $i switch from $totalSwitches")
            i++
            northbound.deleteSwitch(it.switchId, true)
        }
    }

    def purgeTopology() {
        purgeTopology(readCurrentTopology())
    }

    static void log(String message) {
        SimpleDateFormat formatter= new SimpleDateFormat("yyyy-MM-dd 'at' HH:mm:ss z")
        Date date = new Date(System.currentTimeMillis())
        println formatter.format(date) + " " + message
    }
}
