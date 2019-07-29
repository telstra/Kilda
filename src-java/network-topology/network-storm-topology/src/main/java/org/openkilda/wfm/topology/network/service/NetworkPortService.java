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

package org.openkilda.wfm.topology.network.service;

import static java.lang.String.format;
import static org.openkilda.messaging.error.ErrorType.NOT_FOUND;

import org.openkilda.messaging.error.MessageException;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.model.Isl;
import org.openkilda.model.PortProperties;
import org.openkilda.model.Switch;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.PortPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.network.NetworkTopologyDashboardLogger;
import org.openkilda.wfm.topology.network.controller.port.PortFsm;
import org.openkilda.wfm.topology.network.controller.port.PortFsm.PortFsmContext;
import org.openkilda.wfm.topology.network.controller.port.PortFsm.PortFsmEvent;
import org.openkilda.wfm.topology.network.controller.port.PortFsm.PortFsmState;
import org.openkilda.wfm.topology.network.controller.port.PortReportFsm;
import org.openkilda.wfm.topology.network.model.LinkStatus;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class NetworkPortService {
    private final PortFsm.PortFsmFactory controllerFactory;
    private final PortReportFsm.PortReportFsmFactory reportFactory;
    private final Map<Endpoint, PortFsm> controller = new HashMap<>();
    private final FsmExecutor<PortFsm, PortFsmState, PortFsmEvent, PortFsmContext> controllerExecutor;

    private final IPortCarrier carrier;
    private final TransactionManager transactionManager;
    private final PortPropertiesRepository portPropertiesRepository;
    private final SwitchRepository switchRepository;

    public NetworkPortService(IPortCarrier carrier, PersistenceManager persistenceManager) {
        this(carrier, persistenceManager, NetworkTopologyDashboardLogger.builder());
    }

    @VisibleForTesting
    NetworkPortService(IPortCarrier carrier, PersistenceManager persistenceManager,
                       NetworkTopologyDashboardLogger.Builder dashboardLoggerBuilder) {
        this.carrier = carrier;
        this.transactionManager = persistenceManager.getTransactionManager();
        this.portPropertiesRepository = persistenceManager.getRepositoryFactory().createPortPropertiesRepository();
        this.switchRepository = persistenceManager.getRepositoryFactory().createSwitchRepository();

        controllerFactory = PortFsm.factory(persistenceManager);
        controllerExecutor = controllerFactory.produceExecutor();

        reportFactory = PortReportFsm.factory(dashboardLoggerBuilder, carrier);
    }

    /**
     * .
     */
    public void setup(Endpoint endpoint, Isl history) {
        log.info("Port service receive setup request for {}", endpoint);
        // TODO: try to switch on atomic action i.e. port-setup + online|offline action in one event
        PortFsm portFsm = controllerFactory.produce(reportFactory, endpoint, history);
        controller.put(endpoint, portFsm);
    }

    /**
     * .
     */
    public void remove(Endpoint endpoint) {
        log.info("Port service receive remove request for {}", endpoint);
        PortFsm portFsm = controller.remove(endpoint);
        if (portFsm == null) {
            throw new IllegalStateException(format("Port FSM not found (%s).", endpoint));
        }
        PortFsmContext context = PortFsmContext.builder(carrier).build();
        controllerExecutor.fire(portFsm, PortFsmEvent.PORT_DEL, context);
    }

    /**
     * .
     */
    public void updateOnlineMode(Endpoint endpoint, boolean online) {
        PortFsm portFsm = locateController(endpoint);
        PortFsmEvent event;
        if (online) {
            event = PortFsmEvent.ONLINE;
        } else {
            event = PortFsmEvent.OFFLINE;
        }
        log.debug("Port service receive online status change for {}, new status is {}", endpoint, event);
        controllerExecutor.fire(portFsm, event, PortFsmContext.builder(carrier).build());
    }

    /**
     * .
     */
    public void updateLinkStatus(Endpoint endpoint, LinkStatus status) {
        log.debug("Port service receive link status update for {} new status is {}", endpoint, status);
        PortFsm portFsm = locateController(endpoint);
        PortFsmEvent event;
        switch (status) {
            case UP:
                event = PortFsmEvent.PORT_UP;
                break;
            case DOWN:
                event = PortFsmEvent.PORT_DOWN;
                break;
            default:
                throw new IllegalArgumentException(
                        format("Unsupported %s value %s", LinkStatus.class.getName(), status));
        }
        controllerExecutor.fire(portFsm, event, PortFsmContext.builder(carrier).build());
    }

    /**
     * Feed port FSM with discovery event from discovery poll subsystem.
     */
    public void discovery(Endpoint endpoint, IslInfoData speakerDiscoveryEvent) {
        log.debug("Port service receive discovery for {}: {}", endpoint, speakerDiscoveryEvent);

        PortFsm portFsm = locateController(endpoint);
        PortFsmContext context = PortFsmContext.builder(carrier)
                .speakerDiscoveryEvent(speakerDiscoveryEvent)
                .build();
        controllerExecutor.fire(portFsm, PortFsmEvent.DISCOVERY, context);
    }

    /**
     * Feed port FSM with fail event from discovery poll subsystem.
     */
    public void fail(Endpoint endpoint) {
        log.debug("Port service receive fail for {}", endpoint);

        PortFsm portFsm = locateController(endpoint);
        PortFsmContext context = PortFsmContext.builder(carrier).build();

        controllerExecutor.fire(portFsm, PortFsmEvent.FAIL, context);
    }

    /**
     * Update port properties.
     */
    public void updatePortProperties(Endpoint endpoint, boolean discoveryEnabled) {
        try {
            transactionManager.doInTransaction(() -> {
                PortProperties portProperties = savePortProperties(endpoint, discoveryEnabled);

                PortFsm portFsm = locateController(endpoint);
                PortFsmContext context = PortFsmContext.builder(carrier).build();

                PortFsmEvent event = discoveryEnabled ? PortFsmEvent.ENABLE_DISCOVERY : PortFsmEvent.DISABLE_DISCOVERY;
                controllerExecutor.fire(portFsm, event, context);

                carrier.notifyPortPropertiesChanged(portProperties);
            });
        } catch (PersistenceException e) {
            String message = format("Could not update port properties for '%s': %s", endpoint, e.getMessage());
            throw new MessageException(NOT_FOUND, message, "Persistence exception");
        } catch (IllegalStateException e) {
            // Rollback if port is not found. It's allowed to change port properties for already existing ports only.
            String message = format("Port not found: '%s'", e.getMessage());
            throw new MessageException(NOT_FOUND, message, "Port not found exception");
        }
    }

    // -- private --

    private PortFsm locateController(Endpoint endpoint) {
        PortFsm portFsm = controller.get(endpoint);
        if (portFsm == null) {
            throw new IllegalStateException(format("Port FSM not found (%s).", endpoint));
        }
        return portFsm;
    }

    private PortProperties savePortProperties(Endpoint endpoint, boolean discoveryEnabled) {
        Switch sw = switchRepository.findById(endpoint.getDatapath())
                .orElseThrow(() -> new PersistenceException(format("Switch %s not found.", endpoint.getDatapath())));
        PortProperties portProperties = portPropertiesRepository
                .getBySwitchIdAndPort(endpoint.getDatapath(), endpoint.getPortNumber())
                .orElseGet(() -> {
                    PortProperties newProps = PortProperties.builder()
                            .switchObj(sw)
                            .port(endpoint.getPortNumber())
                            .build();
                    portPropertiesRepository.add(newProps);
                    return newProps;
                });
        portProperties.setDiscoveryEnabled(discoveryEnabled);
        return portProperties;
    }
}
