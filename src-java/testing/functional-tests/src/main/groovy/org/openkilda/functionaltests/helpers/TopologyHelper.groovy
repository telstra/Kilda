package org.openkilda.functionaltests.helpers

import org.openkilda.functionaltests.helpers.model.SwitchPair
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.model.SwitchId
import org.openkilda.testing.model.topology.TopologyDefinition
import org.openkilda.testing.model.topology.TopologyDefinition.Isl
import org.openkilda.testing.model.topology.TopologyDefinition.Status
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.model.topology.TopologyDefinition.TraffGenConfig
import org.openkilda.testing.service.database.Database
import org.openkilda.testing.service.northbound.NorthboundService

import com.fasterxml.jackson.databind.ObjectMapper
import groovy.transform.Memoized
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
@Slf4j
class TopologyHelper {
    @Autowired
    NorthboundService northbound
    @Autowired
    TopologyDefinition topology
    @Autowired
    Database database

    @Value('#{\'${floodlight.regions}\'.split(\',\')}')
    private List<String> regions;

    /**
     * Get a switch pair of random switches.
     *
     * @param forceDifferent whether to exclude the picked src switch when looking for dst switch
     */
    Tuple2<Switch, Switch> getRandomSwitchPair(boolean forceDifferent = true) {
        def randomSwitch = { List<Switch> switches ->
            switches[new Random().nextInt(switches.size())]
        }
        def src = randomSwitch(topology.activeSwitches)
        def dst = randomSwitch(forceDifferent ? topology.activeSwitches - src : topology.activeSwitches)
        return new Tuple2(src, dst)
    }

    SwitchPair getSingleSwitchPair() {
        return SwitchPair.singleSwitchInstance(topology.activeSwitches.first())
    }

    List<SwitchPair> getAllSingleSwitchPairs() {
        return topology.activeSwitches.collect { SwitchPair.singleSwitchInstance(it) }
    }

    SwitchPair getNeighboringSwitchPair() {
        getSwitchPairs().find {
            it.paths.min { it.size() }?.size() == 2
        }
    }

    SwitchPair getNotNeighboringSwitchPair() {
        getSwitchPairs().find {
            it.paths.min { it.size() }?.size() > 2
        }
    }

    List<SwitchPair> getAllNeighboringSwitchPairs() {
        getSwitchPairs().findAll {
            it.paths.min { it.size() }?.size() == 2
        }
    }

    List<SwitchPair> getAllNotNeighboringSwitchPairs() {
        getSwitchPairs().findAll {
            it.paths.min { it.size() }?.size() > 2
        }
    }

    def traffgenEnabled = { SwitchPair swPair ->
        def tgSwitches = topology.activeTraffGens*.switchConnected
        swPair.src in tgSwitches && swPair.dst in tgSwitches
    }

    List<SwitchPair> getSwitchPairs() {
        //get deep copy
        def mapper = new ObjectMapper()
        return mapper.readValue(mapper.writeValueAsString(getSwitchPairsCached()), SwitchPair[]).toList()
    }

    Switch findSwitch(SwitchId swId) {
        topology.switches.find { it.dpId == swId }
    }

    TopologyDefinition readCurrentTopology() {
        def switches = northbound.getAllSwitches()
        def links = northbound.getAllLinks()
        def i = 0
        def topoSwitches = switches.collect {
            i++
            //TODO(rtretiak): properly discover the switch's region instead of just picking first
            new Switch("ofsw$i", it.switchId, it.ofVersion, switchStateToStatus(it.state), regions.first(), [],
                    null, null, null)
        }
        def topoLinks = links.collect { link ->
            new Isl(topoSwitches.find { it.dpId == link.source.switchId }, link.source.portNo,
                    topoSwitches.find { it.dpId == link.destination.switchId }, link.destination.portNo,
                    link.maxBandwidth, null)
        }.unique { a, b -> a == b || a == b.reversed ? 0 : 1 }

        return new TopologyDefinition(topoSwitches, topoLinks, [], TraffGenConfig.defaultConfig())
    }

    private static Status switchStateToStatus(SwitchChangeType state) {
        switch (state) {
            case SwitchChangeType.ACTIVATED:
                return Status.Active
            default:
                return Status.Inactive
        }
    }

    @Memoized
    private List<SwitchPair> getSwitchPairsCached() {
        return [topology.activeSwitches, topology.activeSwitches].combinations()
                .findAll { src, dst -> src != dst } //non-single-switch
                .unique { it.sort() } //no reversed versions of same flows
                .collect { Switch src, Switch dst ->
                    new SwitchPair(src: src, dst: dst, paths: database.getPaths(src.dpId, dst.dpId)*.path)
                }
    }
}
