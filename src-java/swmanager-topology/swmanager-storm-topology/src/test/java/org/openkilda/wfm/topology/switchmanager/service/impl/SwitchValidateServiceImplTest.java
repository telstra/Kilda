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

package org.openkilda.wfm.topology.switchmanager.service.impl;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.openkilda.model.SwitchProperties.DEFAULT_FLOW_ENCAPSULATION_TYPES;

import org.openkilda.config.provider.PropertiesBasedConfigurationProvider;
import org.openkilda.messaging.command.CommandData;
import org.openkilda.messaging.command.switches.SwitchValidateRequest;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.meter.MeterEntry;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.messaging.info.rule.SwitchExpectedDefaultFlowEntries;
import org.openkilda.messaging.info.rule.SwitchExpectedDefaultMeterEntries;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.messaging.info.switches.SwitchValidationResponse;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.SwitchNotFoundException;
import org.openkilda.wfm.topology.switchmanager.SwitchManagerTopologyConfig;
import org.openkilda.wfm.topology.switchmanager.model.ValidateMetersResult;
import org.openkilda.wfm.topology.switchmanager.model.ValidateRulesResult;
import org.openkilda.wfm.topology.switchmanager.model.ValidationResult;
import org.openkilda.wfm.topology.switchmanager.service.SwitchManagerCarrier;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.Optional;
import java.util.Properties;

@RunWith(MockitoJUnitRunner.class)
public class SwitchValidateServiceImplTest {

    private static SwitchId SWITCH_ID = new SwitchId(0x0000000000000001L);
    private static String KEY = "KEY";

    @Mock
    private PersistenceManager persistenceManager;

    @Mock
    private ValidationServiceImpl validationService;

    @Mock
    private SwitchManagerCarrier carrier;

    private SwitchValidateServiceImpl service;
    private SwitchValidateRequest request;

    private FlowEntry flowEntry;
    private MeterEntry meterEntry;

    @Before
    public void setUp() throws SwitchNotFoundException {
        PropertiesBasedConfigurationProvider configurationProvider =
                new PropertiesBasedConfigurationProvider(new Properties());
        SwitchManagerTopologyConfig topologyConfig =
                configurationProvider.getConfiguration(SwitchManagerTopologyConfig.class);
        when(carrier.getTopologyConfig()).thenReturn(topologyConfig);
        RepositoryFactory repositoryFactory = Mockito.mock(RepositoryFactory.class);
        FlowPathRepository flowPathRepository = Mockito.mock(FlowPathRepository.class);
        FlowRepository flowRepository = Mockito.mock(FlowRepository.class);
        SwitchRepository switchRepository = Mockito.mock(SwitchRepository.class);
        when(switchRepository.exists(any())).thenReturn(true);
        SwitchPropertiesRepository switchPropertiesRepository = mock(SwitchPropertiesRepository.class);
        when(switchPropertiesRepository.findBySwitchId(any(SwitchId.class))).thenAnswer((invocation) ->
                Optional.of(SwitchProperties.builder()
                        .switchObj(Switch.builder().switchId(invocation.getArgument(0)).build())
                        .multiTable(false)
                        .supportedTransitEncapsulation(DEFAULT_FLOW_ENCAPSULATION_TYPES)
                        .build()));
        when(repositoryFactory.createSwitchPropertiesRepository()).thenReturn(switchPropertiesRepository);
        IslRepository islRepository = Mockito.mock(IslRepository.class);
        when(islRepository.findBySrcSwitch(any(SwitchId.class))).thenAnswer((invocation) ->
                Collections.emptyList());
        when(repositoryFactory.createIslRepository()).thenReturn(islRepository);
        when(repositoryFactory.createFlowPathRepository()).thenReturn(flowPathRepository);
        when(repositoryFactory.createFlowRepository()).thenReturn(flowRepository);
        when(repositoryFactory.createSwitchRepository()).thenReturn(switchRepository);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);

        service = new SwitchValidateServiceImpl(carrier, persistenceManager);
        service.validationService = validationService;

        request = SwitchValidateRequest.builder().switchId(SWITCH_ID).processMeters(true).build();
        flowEntry = new FlowEntry(-1L, 0, 0, 0, 0, "", 0, 0, 0, 0, null, null, null);
        meterEntry = new MeterEntry(32, 10000, 10500, "OF_13", new String[]{"KBPS", "BURST", "STATS"});

        when(validationService.validateRules(any(), any(), any()))
                .thenReturn(new ValidateRulesResult(singletonList(flowEntry.getCookie()), emptyList(), emptyList(),
                        emptyList()));
        when(validationService.validateMeters(any(), any(), any()))
                .thenReturn(new ValidateMetersResult(emptyList(), emptyList(), emptyList(), emptyList()));
    }

    @Test
    public void smokeHandleRequest() {
        handleRequestAndInitDataReceive();
    }

    @Test
    public void receiveOnlyRules() {
        handleRequestAndInitDataReceive();

        service.handleFlowEntriesResponse(KEY, new SwitchFlowEntries(SWITCH_ID, singletonList(flowEntry)));

        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(validationService);
    }

    @Test
    public void receiveTaskTimeout() {
        handleRequestAndInitDataReceive();

        service.handleFlowEntriesResponse(KEY, new SwitchFlowEntries(SWITCH_ID, singletonList(flowEntry)));
        service.handleTaskTimeout(KEY);

        verify(carrier).response(eq(KEY), any(ErrorMessage.class));
        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(validationService);
    }

    @Test
    public void receiveTaskError() {
        handleRequestAndInitDataReceive();

        service.handleFlowEntriesResponse(KEY, new SwitchFlowEntries(SWITCH_ID, singletonList(flowEntry)));
        ErrorMessage errorMessage = getErrorMessage();
        service.handleTaskError(KEY, errorMessage);

        verify(carrier).cancelTimeoutCallback(eq(KEY));
        verify(carrier).response(eq(KEY), any(ErrorMessage.class));

        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(validationService);
    }

    @Test
    public void validationSuccess() throws SwitchNotFoundException {
        handleRequestAndInitDataReceive();
        handleDataReceiveAndValidate();

        verify(carrier).cancelTimeoutCallback(eq(KEY));
        ArgumentCaptor<InfoMessage> responseCaptor = ArgumentCaptor.forClass(InfoMessage.class);
        verify(carrier).response(eq(KEY), responseCaptor.capture());
        SwitchValidationResponse response = (SwitchValidationResponse) responseCaptor.getValue().getData();
        assertEquals(singletonList(flowEntry.getCookie()), response.getRules().getMissing());

        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(validationService);
    }

    @Test
    public void validationWithoutMetersSuccess() {
        verify(carrier, times(1)).getTopologyConfig();
        request = SwitchValidateRequest.builder().switchId(SWITCH_ID).build();

        service.handleSwitchValidateRequest(KEY, request);
        verify(carrier, times(2)).sendCommandToSpeaker(eq(KEY), any(CommandData.class));

        service.handleFlowEntriesResponse(KEY, new SwitchFlowEntries(SWITCH_ID, singletonList(flowEntry)));
        service.handleExpectedDefaultFlowEntriesResponse(KEY,
                new SwitchExpectedDefaultFlowEntries(SWITCH_ID, emptyList()));
        verify(validationService).validateRules(eq(SWITCH_ID), any(), any());

        verify(carrier).cancelTimeoutCallback(eq(KEY));
        ArgumentCaptor<InfoMessage> responseCaptor = ArgumentCaptor.forClass(InfoMessage.class);
        verify(carrier).response(eq(KEY), responseCaptor.capture());

        SwitchValidationResponse response = (SwitchValidationResponse) responseCaptor.getValue().getData();
        assertEquals(singletonList(flowEntry.getCookie()), response.getRules().getMissing());
        assertNull(response.getMeters());

        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(validationService);
    }

    @Test
    public void validationSuccessWithUnsupportedMeters() {
        handleRequestAndInitDataReceive();
        service.handleFlowEntriesResponse(KEY, new SwitchFlowEntries(SWITCH_ID, singletonList(flowEntry)));
        service.handleExpectedDefaultFlowEntriesResponse(KEY,
                new SwitchExpectedDefaultFlowEntries(SWITCH_ID, emptyList()));
        service.handleMetersUnsupportedResponse(KEY);
        service.handleExpectedDefaultMeterEntriesResponse(KEY,
                new SwitchExpectedDefaultMeterEntries(SWITCH_ID, emptyList()));

        verify(validationService).validateRules(eq(SWITCH_ID), any(), any());

        verify(carrier).cancelTimeoutCallback(eq(KEY));
        ArgumentCaptor<InfoMessage> responseCaptor = ArgumentCaptor.forClass(InfoMessage.class);
        verify(carrier).response(eq(KEY), responseCaptor.capture());

        SwitchValidationResponse response = (SwitchValidationResponse) responseCaptor.getValue().getData();
        assertEquals(singletonList(flowEntry.getCookie()), response.getRules().getMissing());
        assertNull(response.getMeters());

        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(validationService);
    }

    @Test
    public void exceptionWhileValidation() throws SwitchNotFoundException {
        handleRequestAndInitDataReceive();

        String errorMessage = "test error";
        when(validationService.validateMeters(any(), any(), any()))
                .thenThrow(new IllegalArgumentException(errorMessage));
        handleDataReceiveAndValidate();

        verify(carrier).cancelTimeoutCallback(eq(KEY));
        ArgumentCaptor<ErrorMessage> errorCaptor = ArgumentCaptor.forClass(ErrorMessage.class);
        verify(carrier).response(eq(KEY), errorCaptor.capture());
        assertEquals(errorMessage, errorCaptor.getValue().getData().getErrorMessage());

        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(validationService);
    }

    @Test
    public void doNothingWhenFsmNotFound() {
        verify(carrier, times(1)).getTopologyConfig();
        service.handleFlowEntriesResponse(KEY, new SwitchFlowEntries(SWITCH_ID, singletonList(flowEntry)));

        verifyZeroInteractions(carrier);
        verifyZeroInteractions(validationService);
    }

    @Test
    public void validationPerformSync() throws SwitchNotFoundException {
        request = SwitchValidateRequest.builder().switchId(SWITCH_ID).performSync(true).processMeters(true).build();

        handleRequestAndInitDataReceive();
        handleDataReceiveAndValidate();

        verify(carrier).runSwitchSync(eq(KEY), eq(request), any(ValidationResult.class));
        verifyNoMoreInteractions(carrier);
        verifyNoMoreInteractions(validationService);
    }


    private void handleRequestAndInitDataReceive() {
        verify(carrier, times(1)).getTopologyConfig();
        service.handleSwitchValidateRequest(KEY, request);

        verify(carrier, times(4)).sendCommandToSpeaker(eq(KEY), any(CommandData.class));
        verifyNoMoreInteractions(carrier);
    }

    private void handleDataReceiveAndValidate() throws SwitchNotFoundException {
        service.handleFlowEntriesResponse(KEY, new SwitchFlowEntries(SWITCH_ID, singletonList(flowEntry)));
        service.handleExpectedDefaultFlowEntriesResponse(KEY,
                new SwitchExpectedDefaultFlowEntries(SWITCH_ID, emptyList()));
        service.handleMeterEntriesResponse(KEY, new SwitchMeterEntries(SWITCH_ID, singletonList(meterEntry)));
        service.handleExpectedDefaultMeterEntriesResponse(KEY,
                new SwitchExpectedDefaultMeterEntries(SWITCH_ID, emptyList()));

        verify(validationService).validateRules(eq(SWITCH_ID), any(), any());
        verify(validationService).validateMeters(eq(SWITCH_ID), any(), any());
    }

    private ErrorMessage getErrorMessage() {
        return new ErrorMessage(new ErrorData(ErrorType.INTERNAL_ERROR, "message", "description"),
                System.currentTimeMillis(), KEY);
    }
}
