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

package org.openkilda.wfm.topology.nbworker.bolts;

import static java.lang.String.format;

import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.MessageException;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.event.DeactivateIslInfoData;
import org.openkilda.messaging.info.event.IslBfdPropertiesChangeNotification;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.nbtopology.request.BaseRequest;
import org.openkilda.messaging.nbtopology.request.BfdPropertiesReadRequest;
import org.openkilda.messaging.nbtopology.request.BfdPropertiesWriteRequest;
import org.openkilda.messaging.nbtopology.request.DeleteLinkRequest;
import org.openkilda.messaging.nbtopology.request.GetLinksRequest;
import org.openkilda.messaging.nbtopology.request.LinkPropsDrop;
import org.openkilda.messaging.nbtopology.request.LinkPropsGet;
import org.openkilda.messaging.nbtopology.request.LinkPropsPut;
import org.openkilda.messaging.nbtopology.request.UpdateLinkUnderMaintenanceRequest;
import org.openkilda.messaging.nbtopology.response.BfdPropertiesResponse;
import org.openkilda.messaging.nbtopology.response.LinkPropsData;
import org.openkilda.messaging.nbtopology.response.LinkPropsResponse;
import org.openkilda.model.FlowPath;
import org.openkilda.model.Isl;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.LinkProps;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.LinkPropsRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.error.IllegalIslStateException;
import org.openkilda.wfm.error.IslNotFoundException;
import org.openkilda.wfm.error.LinkPropsException;
import org.openkilda.wfm.share.mappers.IslMapper;
import org.openkilda.wfm.share.mappers.LinkPropsMapper;
import org.openkilda.wfm.share.metrics.TimedExecution;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.nbworker.StreamType;
import org.openkilda.wfm.topology.nbworker.services.FlowOperationsService;
import org.openkilda.wfm.topology.nbworker.services.ILinkOperationsServiceCarrier;
import org.openkilda.wfm.topology.nbworker.services.LinkOperationsService;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class LinkOperationsBolt extends PersistenceOperationsBolt implements ILinkOperationsServiceCarrier {
    private transient LinkOperationsService linkOperationsService;
    private transient FlowOperationsService flowOperationsService;

    private transient LinkPropsRepository linkPropsRepository;
    private transient IslRepository islRepository;

    public LinkOperationsBolt(PersistenceManager persistenceManager) {
        super(persistenceManager);

        enableMeterRegistry("kilda.link_operations", StreamType.TO_METRICS_BOLT.name());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init() {
        this.linkOperationsService = new LinkOperationsService(this, repositoryFactory, transactionManager);
        this.flowOperationsService = new FlowOperationsService(repositoryFactory, transactionManager);
        linkPropsRepository = repositoryFactory.createLinkPropsRepository();
        islRepository = repositoryFactory.createIslRepository();
    }

    @Override
    @SuppressWarnings("unchecked")
    List<InfoData> processRequest(Tuple tuple, BaseRequest request) {
        List<? extends InfoData> result = null;
        if (request instanceof GetLinksRequest) {
            result = getAllLinks((GetLinksRequest) request);
        } else if (request instanceof LinkPropsGet) {
            result = getLinkProps((LinkPropsGet) request);
        } else if (request instanceof LinkPropsPut) {
            result = Collections.singletonList(putLinkProps((LinkPropsPut) request));
        } else if (request instanceof LinkPropsDrop) {
            result = Collections.singletonList(dropLinkProps((LinkPropsDrop) request));
        } else if (request instanceof DeleteLinkRequest) {
            result = deleteLink((DeleteLinkRequest) request);
        } else if (request instanceof UpdateLinkUnderMaintenanceRequest) {
            result = updateLinkUnderMaintenanceFlag((UpdateLinkUnderMaintenanceRequest) request);
        } else if (request instanceof BfdPropertiesWriteRequest) {
            result = Collections.singletonList(bfdPropertiesWrite((BfdPropertiesWriteRequest) request));
        } else if (request instanceof BfdPropertiesReadRequest) {
            result = Collections.singletonList(bfdPropertiesRead((BfdPropertiesReadRequest) request));
        } else {
            unhandledInput(tuple);
        }

        return (List<InfoData>) result;
    }

    @TimedExecution("link_dump")
    private List<IslInfoData> getAllLinks(GetLinksRequest request) {
        Integer srcPort = request.getSource().getPortNumber();
        SwitchId srcSwitch = request.getSource().getDatapath();
        Integer dstPort = request.getDestination().getPortNumber();
        SwitchId dstSwitch = request.getDestination().getDatapath();

        return linkOperationsService.getAllIsls(srcSwitch, srcPort, dstSwitch, dstPort).stream()
                .map(IslMapper.INSTANCE::map)
                .collect(Collectors.toList());
    }

    private List<LinkPropsData> getLinkProps(LinkPropsGet request) {

        Integer srcPort = request.getSource().getPortNumber();
        SwitchId srcSwitch = request.getSource().getDatapath();
        Integer dstPort = request.getDestination().getPortNumber();
        SwitchId dstSwitch = request.getDestination().getDatapath();

        Collection<LinkProps> linkProps = linkPropsRepository.findByEndpoints(srcSwitch, srcPort, dstSwitch, dstPort);
        return linkProps.stream()
                .map(LinkPropsMapper.INSTANCE::map)
                .map(LinkPropsData::new)
                .collect(Collectors.toList());
    }

    private LinkPropsResponse putLinkProps(LinkPropsPut request) {
        try {
            LinkProps toSetForward = LinkPropsMapper.INSTANCE.map(request.getLinkProps());
            LinkProps toSetBackward = swapLinkProps(toSetForward);

            LinkProps result = createOrUpdateProps(toSetForward);
            createOrUpdateProps(toSetBackward);

            return new LinkPropsResponse(request, LinkPropsMapper.INSTANCE.map(result), null);
        } catch (LinkPropsException e) {
            log.error(e.getMessage(), e);

            return new LinkPropsResponse(request, null, e.getMessage());
        } catch (Exception e) {
            log.error("Unhandled exception in create or update linkprops operation.", e);

            return new LinkPropsResponse(request, null, e.getMessage());
        }
    }

    private LinkProps createOrUpdateProps(LinkProps linkPropsToSet) throws MessageException {

        return transactionManager.doInTransaction(() -> {
            Collection<LinkProps> existingLinkProps = linkPropsRepository.findByEndpoints(
                    linkPropsToSet.getSrcSwitchId(), linkPropsToSet.getSrcPort(),
                    linkPropsToSet.getDstSwitchId(), linkPropsToSet.getDstPort());

            LinkProps linkProps;
            if (!existingLinkProps.isEmpty()) {
                linkProps = existingLinkProps.iterator().next();
                if (linkPropsToSet.getCost() != null) {
                    linkProps.setCost(linkPropsToSet.getCost());
                }
                if (linkPropsToSet.getMaxBandwidth() != null) {
                    linkProps.setMaxBandwidth(linkPropsToSet.getMaxBandwidth());
                }
            } else {
                linkProps = new LinkProps(linkPropsToSet);
                linkPropsRepository.add(linkProps);
            }

            Optional<Isl> existingIsl = islRepository.findByEndpoints(
                    linkPropsToSet.getSrcSwitchId(), linkPropsToSet.getSrcPort(),
                    linkPropsToSet.getDstSwitchId(), linkPropsToSet.getDstPort());
            existingIsl.ifPresent(link -> {
                if (linkPropsToSet.getCost() != null) {
                    link.setCost(linkPropsToSet.getCost());
                }
                if (linkPropsToSet.getMaxBandwidth() != null) {
                    long currentMaxBandwidth = link.getMaxBandwidth();
                    long currentAvailableBandwidth = link.getAvailableBandwidth();
                    long availableBandwidth = linkPropsToSet.getMaxBandwidth()
                            - (currentMaxBandwidth - currentAvailableBandwidth);
                    link.setMaxBandwidth(linkPropsToSet.getMaxBandwidth());
                    if (availableBandwidth < 0) {
                        throw new LinkPropsException("Not enough available bandwidth for operation");
                    }
                    link.setAvailableBandwidth(availableBandwidth);
                }
            });

            return linkProps;
        });
    }

    private LinkProps swapLinkProps(LinkProps source) {
        LinkProps result = new LinkProps(source);
        result.setSrcSwitchId(source.getDstSwitchId());
        result.setSrcPort(source.getDstPort());
        result.setDstSwitchId(source.getSrcSwitchId());
        result.setDstPort(source.getSrcPort());
        return result;
    }

    private LinkPropsResponse dropLinkProps(LinkPropsDrop request) {

        LinkProps linkPropsToDrop = LinkProps.builder()
                .srcSwitchId(request.getPropsMask().getSource().getDatapath())
                .srcPort(request.getPropsMask().getSource().getPortNumber())
                .dstSwitchId(request.getPropsMask().getDest().getDatapath())
                .dstPort(request.getPropsMask().getDest().getPortNumber())
                .build();
        LinkProps reverseLinkPropsToDrop = swapLinkProps(linkPropsToDrop);

        try {
            LinkProps result = deleteLinkProps(linkPropsToDrop, reverseLinkPropsToDrop);

            return new LinkPropsResponse(request, LinkPropsMapper.INSTANCE.map(result), null);
        } catch (Exception e) {
            log.error("Unhandled exception in drop linkprops operation.", e);

            return new LinkPropsResponse(request, null, e.getMessage());
        }
    }

    private LinkProps deleteLinkProps(LinkProps fwdLinkProps, LinkProps rvsLinkProps) {

        List<LinkProps> linkProperties = Arrays.asList(rvsLinkProps, fwdLinkProps);

        return transactionManager.doInTransaction(() -> {
            LinkProps linkProps = null;
            for (LinkProps linkPropsToDrop : linkProperties) {
                Collection<LinkProps> existingLinkProps = linkPropsRepository.findByEndpoints(
                        linkPropsToDrop.getSrcSwitchId(), linkPropsToDrop.getSrcPort(),
                        linkPropsToDrop.getDstSwitchId(), linkPropsToDrop.getDstPort());

                if (!existingLinkProps.isEmpty()) {
                    linkProps = existingLinkProps.iterator().next();
                    linkPropsRepository.remove(linkProps);
                }

                Optional<Isl> existingIsl = islRepository.findByEndpoints(
                        linkPropsToDrop.getSrcSwitchId(), linkPropsToDrop.getSrcPort(),
                        linkPropsToDrop.getDstSwitchId(), linkPropsToDrop.getDstPort());
                long propsMaxBandwidth = (linkProps != null ? linkProps.getMaxBandwidth() : null) != null
                        ? linkProps.getMaxBandwidth() : 0L;
                existingIsl.ifPresent(link -> {
                    link.setCost(0);
                    link.setMaxBandwidth(link.getDefaultMaxBandwidth());
                    if (propsMaxBandwidth > 0) {
                        long availableBandwidth = link.getDefaultMaxBandwidth()
                                - (propsMaxBandwidth - link.getAvailableBandwidth());
                        link.setAvailableBandwidth(availableBandwidth);
                    }
                });

            }
            return linkProps;
        });
    }

    private List<IslInfoData> deleteLink(DeleteLinkRequest request) {
        try {
            Collection<Isl> operationsResult = linkOperationsService.deleteIsl(request.getSrcSwitch(),
                    request.getSrcPort(), request.getDstSwitch(), request.getDstPort(), request.isForce());
            List<IslInfoData> responseResult = operationsResult.stream()
                    .map(IslMapper.INSTANCE::map)
                    .collect(Collectors.toList());

            for (IslInfoData isl : responseResult) {
                DeactivateIslInfoData data = new DeactivateIslInfoData(isl.getSource(), isl.getDestination());
                getOutput().emit(StreamType.DISCO.toString(), getCurrentTuple(), new Values(data, getCorrelationId()));
            }

            return responseResult;
        } catch (IslNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "ISL was not found.");
        } catch (IllegalIslStateException e) {
            throw new MessageException(ErrorType.REQUEST_INVALID, e.getMessage(), "ISL is in illegal state.");
        }
    }

    private List<IslInfoData> updateLinkUnderMaintenanceFlag(UpdateLinkUnderMaintenanceRequest request) {
        SwitchId srcSwitch = request.getSource().getDatapath();
        Integer srcPort = request.getSource().getPortNumber();
        SwitchId dstSwitch = request.getDestination().getDatapath();
        Integer dstPort = request.getDestination().getPortNumber();
        boolean underMaintenance = request.isUnderMaintenance();
        boolean evacuate = request.isEvacuate();

        List<Isl> isl;
        try {
            isl = linkOperationsService.updateLinkUnderMaintenanceFlag(srcSwitch, srcPort,
                    dstSwitch, dstPort, underMaintenance);

            if (underMaintenance && evacuate) {
                Set<IslEndpoint> affectedIslEndpoints = new HashSet<>();
                affectedIslEndpoints.add(new IslEndpoint(srcSwitch, srcPort));
                affectedIslEndpoints.add(new IslEndpoint(dstSwitch, dstPort));
                String reason = format("evacuated due to link maintenance %s_%d - %s_%d",
                        srcSwitch, srcPort, dstSwitch, dstPort);
                Collection<FlowPath> targetPaths = flowOperationsService.getFlowPathsForLink(
                        srcSwitch, srcPort, dstSwitch, dstPort);

                for (FlowRerouteRequest reroute : flowOperationsService.makeRerouteRequests(
                        targetPaths, affectedIslEndpoints, reason)) {
                    CommandContext forkedContext = getCommandContext().fork(reroute.getFlowId());
                    getOutput().emit(StreamType.REROUTE.toString(), getCurrentTuple(),
                            new Values(reroute, forkedContext.getCorrelationId()));
                }
            }

        } catch (IslNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "ISL was not found.");
        }

        return isl.stream()
                .map(IslMapper.INSTANCE::map)
                .collect(Collectors.toList());
    }

    private BfdPropertiesResponse bfdPropertiesWrite(BfdPropertiesWriteRequest request) {
        Endpoint source = new Endpoint(request.getSource());
        Endpoint destination = new Endpoint(request.getDestination());
        try {
            return linkOperationsService.writeBfdProperties(source, destination, request.getProperties());
        } catch (IslNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "ISL was not found.");
        }
    }

    private BfdPropertiesResponse bfdPropertiesRead(BfdPropertiesReadRequest request) {
        Endpoint source = new Endpoint(request.getSource());
        Endpoint destination = new Endpoint(request.getDestination());
        try {
            return linkOperationsService.readBfdProperties(source, destination);
        } catch (IslNotFoundException e) {
            throw new MessageException(ErrorType.NOT_FOUND, e.getMessage(), "ISL was not found.");
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        super.declareOutputFields(declarer);
        declarer.declareStream(StreamType.REROUTE.toString(),
                new Fields(MessageEncoder.FIELD_ID_PAYLOAD, MessageEncoder.FIELD_ID_CONTEXT));
        declarer.declareStream(StreamType.DISCO.toString(),
                new Fields(MessageEncoder.FIELD_ID_PAYLOAD, MessageEncoder.FIELD_ID_CONTEXT));
    }

    @Override
    public void islBfdPropertiesChanged(Endpoint source, Endpoint destination) {
        IslBfdPropertiesChangeNotification notification = new IslBfdPropertiesChangeNotification(
                new IslEndpoint(source.getDatapath(), source.getPortNumber()),
                new IslEndpoint(destination.getDatapath(), destination.getPortNumber()));
        getOutput().emit(StreamType.DISCO.toString(), getCurrentTuple(), new Values(notification, getCorrelationId()));
    }
}
