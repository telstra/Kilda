/* Copyright 2019 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.performancetests.helpers

import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.performancetests.model.CustomTopology
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.floodlight.model.Floodlight

import java.util.stream.IntStream

class TopologyBuilder {
    final List<Floodlight> controllers
    final int islandsCount
    final int regionsPerIsland
    final int switchesPerRegion

    TopologyBuilder(List<Floodlight> controllers, int islandsCount, int regionsPerIsland, int switchesPerRegion) {
        this.controllers = controllers.findAll { it.mode == RW }
        this.islandsCount = islandsCount
        this.regionsPerIsland = regionsPerIsland
        this.switchesPerRegion = switchesPerRegion
    }

    CustomTopology buildCircles() {
        def topo = new CustomTopology()
        List<Island> islands = new ArrayList<>()

        IntStream.rangeClosed(1, islandsCount).forEach({ i ->
            Island newIsland = new Island()

            IntStream.rangeClosed(1, regionsPerIsland).forEach({ r ->
                Region newRegion = new Region()

                IntStream.rangeClosed(1, switchesPerRegion).forEach({ s ->
                    Switch newSw = addSwitch(topo, i)

                    if (!newRegion.switches.isEmpty()) {
                        Switch prevSw = newRegion.switches.get(newRegion.switches.size() - 1)
                        topo.addIsl(prevSw, newSw)
                    }

                    newRegion.switches.add(newSw)
                })

                Switch first = newRegion.switches.get(0)
                Switch last = newRegion.switches.get(newRegion.switches.size() - 1)

                topo.addIsl(first, last)

                if (!newIsland.regions.isEmpty()) {
                    Region prevRegion = newIsland.regions.get(newIsland.regions.size() - 1)
                    Switch prevRegionSwitch = prevRegion.switches.get((int) (prevRegion.switches.size() / 2))
                    Switch newRegionSwitch = newRegion.switches.get(0)

                    topo.addIsl(prevRegionSwitch, newRegionSwitch)
                }

                newIsland.regions.add(newRegion)
            })

            Switch firstRegionSwitch = newIsland.regions.get(0).switches.get(0)
            Region lastRegion = newIsland.regions.get(newIsland.regions.size() - 1)
            Switch lastRegionSwitch = lastRegion.switches.get((int) (lastRegion.switches.size() / 2))

            topo.addIsl(firstRegionSwitch, lastRegionSwitch)

            islands.add(newIsland)
        })

        return topo
    }

    CustomTopology buildStars(int raysPerRegion) {
        def topo = new CustomTopology()
        List<Island> islands = new ArrayList<>()

        IntStream.rangeClosed(1, islandsCount).forEach({ i ->
            Island newIsland = new Island()

            IntStream.rangeClosed(1, regionsPerIsland).forEach({ r ->
                Region newRegion = new Region()

                Switch newRegionEyeSw = addSwitch(topo, i)
                newRegion.switches.add(newRegionEyeSw)

                int switchesPerRay = (int) (switchesPerRegion / raysPerRegion)
                IntStream.rangeClosed(1, raysPerRegion).forEach({ y ->
                    List<Switch> raySwitches = new ArrayList<>()

                    IntStream.rangeClosed(1, switchesPerRay).forEach({ s ->
                        Switch newSw = addSwitch(topo, i)
                        Switch prevSw = raySwitches.isEmpty() ? newRegionEyeSw : raySwitches.get(raySwitches.size() - 1)
                        topo.addIsl(prevSw, newSw)
                        raySwitches.add(newSw)
                    })

                    newRegion.switches.addAll(raySwitches)
                })

                if (!newIsland.regions.isEmpty()) {
                    Switch eyeRegionSwitch = newIsland.regions.get(0).switches.get(0)
                    topo.addIsl(eyeRegionSwitch, newRegionEyeSw)
                }

                newIsland.regions.add(newRegion)
            })

            islands.add(newIsland)
        })

        return topo
    }

    CustomTopology buildMeshes(int crossRegionLinks = 1) {
        def topo = new CustomTopology()
        List<Island> islands = new ArrayList<>()

        IntStream.rangeClosed(1, islandsCount).forEach({ i ->
            Island newIsland = new Island()

            IntStream.rangeClosed(1, regionsPerIsland).forEach({ r ->
                Region newRegion = new Region()

                IntStream.rangeClosed(1, switchesPerRegion).forEach({ s ->
                    Switch newSw = addSwitch(topo, i)

                    for (Switch prevSw : newRegion.switches) {
                        topo.addIsl(prevSw, newSw)
                    }

                    newRegion.switches.add(newSw)
                })

                for (Region prevRegion : newIsland.regions) {
                    // Add the cross region links
                    (1..crossRegionLinks).each {
                        Switch prevRegionSwitch = prevRegion.switches.get((int) ((prevRegion.switches.size() - 1) * it / crossRegionLinks))
                        Switch newRegionSwitch = newRegion.switches.get((int) ((newRegion.switches.size() - 1) * it / crossRegionLinks))

                        topo.addIsl(prevRegionSwitch, newRegionSwitch)
                    }
                }

                newIsland.regions.add(newRegion)
            })

            islands.add(newIsland)
        })

        return topo
    }

    CustomTopology buildTree(int branchesPerTreeKnot) {
        def topo = new CustomTopology()
        List<Island> islands = new ArrayList<>()

        IntStream.rangeClosed(1, islandsCount).forEach({ i ->
            Island newIsland = new Island()

            IntStream.rangeClosed(1, regionsPerIsland).forEach({ r ->
                Region newRegion = new Region()

                Switch newRegionEyeSwitch = addSwitch(topo, i)
                newRegion.switches.add(newRegionEyeSwitch)

                buildSubTree(topo, newRegion, newRegionEyeSwitch, i, branchesPerTreeKnot, switchesPerRegion - 1)

                if (!newIsland.regions.isEmpty()) {
                    List<Switch> lastRegionSwitches = newIsland.regions.get(newIsland.regions.size() - 1).switches
                    Switch lastRegionSwitch = lastRegionSwitches.get(lastRegionSwitches.size() - 1)

                    topo.addIsl(lastRegionSwitch, newRegionEyeSwitch)
                }

                newIsland.regions.add(newRegion)
            })

            islands.add(newIsland)
        })

        return topo
    }

    private void buildSubTree(CustomTopology topo, Region region, Switch knotSwitch, int island,
                              int branchesPerTreeKnot, int switchesLeft) {
        if (switchesLeft <= 0) {
            return
        }

        int switchesPerKnot = (int) (switchesLeft / branchesPerTreeKnot)

        for (int b = 1; b <= Math.min(switchesPerKnot, branchesPerTreeKnot); b++) {
            Switch newSw = addSwitch(topo, island)
            region.switches.add(newSw)

            topo.addIsl(knotSwitch, newSw)

            if (switchesPerKnot > 1) {
                buildSubTree(topo, region, newSw, island, branchesPerTreeKnot, switchesPerKnot - 1)
            }
        }
    }

    private Switch addSwitch(CustomTopology topo, int island) {
        def fl = controllers[island % controllers.size()]
        return topo.addCasualSwitch(fl.openflow, [fl.region])
    }

    private class Island {
        List<Region> regions = new ArrayList<>()
    }

    private class Region {
        List<Switch> switches = new ArrayList<>()
    }
}