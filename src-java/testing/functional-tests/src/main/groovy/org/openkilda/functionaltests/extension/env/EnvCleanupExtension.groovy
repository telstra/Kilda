package org.openkilda.functionaltests.extension.env

import static org.openkilda.model.MeterId.MAX_SYSTEM_RULE_METER_ID

import org.openkilda.functionaltests.exception.IslNotFoundException
import org.openkilda.functionaltests.extension.spring.SpringContextListener
import org.openkilda.functionaltests.extension.spring.SpringContextNotifier
import org.openkilda.messaging.command.switches.DeleteRulesAction
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.IslInfoData
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.northbound.dto.v1.links.LinkParametersDto
import org.openkilda.northbound.dto.v1.switches.SwitchDto
import org.openkilda.testing.model.topology.TopologyDefinition
import org.openkilda.testing.service.database.Database
import org.openkilda.testing.service.lockkeeper.LockKeeperService
import org.openkilda.testing.service.northbound.NorthboundService
import org.openkilda.testing.tools.IslUtils

import groovy.util.logging.Slf4j
import org.spockframework.runtime.extension.AbstractGlobalExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value

@Slf4j
abstract class EnvCleanupExtension extends AbstractGlobalExtension implements SpringContextListener {

    @Autowired
    TopologyDefinition topology

    @Autowired
    NorthboundService northbound

    @Autowired
    Database database

    @Autowired
    IslUtils islUtils

    @Autowired
    LockKeeperService lockKeeper

    @Value('${spring.profiles.active}')
    String profile

    @Override
    void start() {
        SpringContextNotifier.addListener(this)
    }

    def deleteAllFlows() {
        log.info("Deleting all flows")
        northbound.deleteAllFlows()
    }

    def unsetLinkMaintenance(List<IslInfoData> links) {
        def maintenanceLinks = northbound.getAllLinks().findAll { it.underMaintenance }
        if (maintenanceLinks) {
            log.info("Unset maintenance mode for affected links: $maintenanceLinks")
            maintenanceLinks.each {
                northbound.setLinkMaintenance(islUtils.toLinkUnderMaintenance(it, false, false))
            }
        }
    }

    def reviveFailedLinks(List<IslInfoData> links) {
        def failedLinks = links.findAll { it.state == IslChangeType.FAILED }
        if (failedLinks) {
            log.info("Bring ports up for failed links: $failedLinks")
            failedLinks.each {
                try {
                    northbound.portUp(it.source.switchId, it.source.portNo)
                    northbound.portUp(it.destination.switchId, it.destination.portNo)
                } catch (Throwable t) {
                    // Switch may be disconnected. Ignore for now, healthcheck test will report problems if any.
                    // This situation may require manual intervention
                }
            }
        }
    }

    def deleteLinkProps() {
        def linkProps = northbound.getAllLinkProps()
        if (linkProps) {
            log.info("Deleting all link props: $linkProps")
            northbound.deleteLinkProps(linkProps)
        }
    }

    def resetCosts() {
        log.info("Resetting all link costs")
        database.resetCosts()
    }

    def resetBandwidth(List<IslInfoData> links) {
        def topoIsls = topology.isls.collectMany { [it, it.reversed] }
        links.each { link ->
            if (link.availableBandwidth != link.speed || link.maxBandwidth != link.speed
                    || link.defaultMaxBandwidth != link.speed) {
                def isl = topoIsls.find {
                    it.srcSwitch.dpId == link.source.switchId && it.srcPort == link.source.portNo &&
                            it.dstSwitch.dpId == link.destination.switchId && it.dstPort == link.destination.portNo
                }
                if (!isl) {
                    throw new IslNotFoundException("Wasn't able to find isl: $link")
                }
                log.info("Resetting available bandwidth on ISL: $isl")
                database.resetIslBandwidth(isl)
            }
        }
    }

    def unsetSwitchMaintenance(List<SwitchDto> switches) {
        def maintenanceSwitches = switches.findAll { it.underMaintenance }
        if (maintenanceSwitches) {
            log.info("Unset maintenance mode from all affected switches: $maintenanceSwitches")
            maintenanceSwitches.each {
                northbound.setSwitchMaintenance(it.switchId, false, false)
            }
        }
    }

    def removeFlowRules(List<SwitchDto> switches) {
        log.info("Remove non-default rules from all switches")
        switches.each {
            northbound.deleteSwitchRules(it.switchId, DeleteRulesAction.IGNORE_DEFAULTS)
        }
    }

    def removeExcessMeters(List<SwitchDto> switches) {
        log.info("Remove excess meters from switches")
        switches.each { sw ->
            if (!sw.description.contains("OF_12")) {
                northbound.getAllMeters(sw.switchId).meterEntries.each { meter ->
                    if (meter.meterId > MAX_SYSTEM_RULE_METER_ID) {
                        northbound.deleteMeter(sw.switchId, meter.meterId)
                    }
                }
            }
        }
    }

    def resetAswRules() {
        def requiredAswRules = topology.isls.collectMany {
            if (it.aswitch?.inPort && it.aswitch?.outPort) {
                return [it.aswitch, it.aswitch.reversed]
            }
            return []
        }
        def excessAswRules = lockKeeper.getAllFlows().findAll { !(it in requiredAswRules) }
        if (excessAswRules) {
            log.info("Removing excess A-switch rules: $excessAswRules")
            lockKeeper.removeFlows(excessAswRules)
        }
        def missingAswRules = requiredAswRules - lockKeeper.getAllFlows()
        if (missingAswRules) {
            log.info("Adding missing A-switch rules: $missingAswRules")
            lockKeeper.addFlows(missingAswRules)
        }
    }

    def deleteInactiveIsls(List<IslInfoData> allLinks) {
        def inactiveLinks = allLinks.findAll { it.state != IslChangeType.DISCOVERED }

        if(inactiveLinks) {
            log.info("Removing inactive ISLs: $inactiveLinks")
            inactiveLinks.unique { [it.source, it.destination].sort() }.each {
                northbound.deleteLink(new LinkParametersDto(it.source.switchId.toString(), it.source.portNo,
                        it.destination.switchId.toString(), it.destination.portNo))
            }
        }
    }

    def deleteInactiveSwitches(List<SwitchDto> allSwitches) {
        def inactiveSwitches = allSwitches.findAll { it.state == SwitchChangeType.DEACTIVATED }
        if(inactiveSwitches) {
            log.info("Removing inactive switches: $inactiveSwitches")
            inactiveSwitches.each {
                northbound.deleteSwitch(it.switchId, false)
            }
        }
    }
}
