package org.openkilda.performancetests.helpers

import static org.openkilda.testing.Constants.WAIT_OFFSET
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.northbound.dto.v1.links.LinkParametersDto
import org.openkilda.performancetests.model.CustomTopology
import org.openkilda.testing.model.topology.TopologyDefinition
import org.openkilda.testing.service.floodlight.FloodlightsHelper
import org.openkilda.testing.service.labservice.LabService
import org.openkilda.testing.service.labservice.model.LabInstance
import org.openkilda.testing.service.northbound.NorthboundService
import org.openkilda.testing.tools.IslUtils

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component("performance")
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
            assert switches == activeSwitches
            assert switches.size() == topo.activeSwitches.size()

            //Isl discovery
            def isls = northbound.getAllLinks().findAll {
                it.source.switchId in topo.activeSwitches*.dpId ||
                        it.destination.switchId in topo.activeSwitches*.dpId
            }
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
    def purgeTopology(TopologyDefinition topo = null, LabInstance lab = null) {
        northbound.getAllFlows().each { northbound.deleteFlow(it.id) }
        Wrappers.wait(WAIT_OFFSET * 5) {
            assert northbound.getAllFlows().empty
        }

        if (lab) {
            labService.deleteLab(lab)
        } else {
            labService.flushLabs()
        }
        Wrappers.wait(WAIT_OFFSET + discoveryTimeout) {
            assert northbound.getAllSwitches().findAll { it.state == SwitchChangeType.ACTIVATED }.empty
            assert northbound.getAllLinks().findAll { it.state == IslChangeType.DISCOVERED }.empty
        }

        northbound.getAllLinks().unique {
            [it.source.switchId.toString(), it.source.portNo,
             it.destination.switchId.toString(), it.destination.portNo].sort()
        }.each { it ->
            northbound.deleteLink(new LinkParametersDto(it.source.switchId.toString(), it.source.portNo,
                    it.destination.switchId.toString(), it.destination.portNo))
        }
        Wrappers.wait(WAIT_OFFSET / 2) {
            assert northbound.getAllLinks().empty
        }

        northbound.getAllSwitches().each { northbound.deleteSwitch(it.switchId, false) }
        Wrappers.wait(WAIT_OFFSET / 2) {
            assert northbound.getAllSwitches().empty
        }
    }
}
