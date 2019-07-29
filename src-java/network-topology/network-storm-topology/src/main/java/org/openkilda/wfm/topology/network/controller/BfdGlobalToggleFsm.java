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

package org.openkilda.wfm.topology.network.controller;

import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.utils.AbstractBaseFsm;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.network.service.IBfdGlobalToggleCarrier;

import lombok.Builder;
import lombok.Value;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;

public class BfdGlobalToggleFsm
        extends AbstractBaseFsm<BfdGlobalToggleFsm,
                BfdGlobalToggleFsm.BfdGlobalToggleFsmState,
                BfdGlobalToggleFsm.BfdGlobalToggleFsmEvent,
                BfdGlobalToggleFsm.BfdGlobalToggleFsmContext> {

    private final IBfdGlobalToggleCarrier carrier;
    private final Endpoint endpoint;

    public static BfdGlobalToggleFsmFactory factory(PersistenceManager persistenceManager) {
        return new BfdGlobalToggleFsmFactory(persistenceManager);
    }

    // -- FSM actions --

    public void emitBfdKill(BfdGlobalToggleFsmState from, BfdGlobalToggleFsmState to, BfdGlobalToggleFsmEvent event,
                            BfdGlobalToggleFsmContext context) {
        log.info("BFD event KILL for {}", endpoint);
        carrier.filteredBfdKillNotification(endpoint);
    }

    public void emitBfdUp(BfdGlobalToggleFsmState from, BfdGlobalToggleFsmState to, BfdGlobalToggleFsmEvent event,
                          BfdGlobalToggleFsmContext context) {
        log.info("BFD event UP for {}", endpoint);
        carrier.filteredBfdUpNotification(endpoint);
    }

    public void emitBfdDown(BfdGlobalToggleFsmState from, BfdGlobalToggleFsmState to, BfdGlobalToggleFsmEvent event,
                            BfdGlobalToggleFsmContext context) {
        log.info("BFD event DOWN for {}", endpoint);
        carrier.filteredBfdDownNotification(endpoint);
    }

    // -- private/service methods --

    // -- service data types --

    public BfdGlobalToggleFsm(IBfdGlobalToggleCarrier carrier, Endpoint endpoint) {
        this.carrier = carrier;
        this.endpoint = endpoint;
    }

    public static class BfdGlobalToggleFsmFactory {
        private final FeatureTogglesRepository featureTogglesRepository;

        private final StateMachineBuilder<BfdGlobalToggleFsm, BfdGlobalToggleFsmState, BfdGlobalToggleFsmEvent,
                BfdGlobalToggleFsmContext> builder;

        BfdGlobalToggleFsmFactory(PersistenceManager persistenceManager) {
            featureTogglesRepository = persistenceManager.getRepositoryFactory().createFeatureTogglesRepository();

            builder = StateMachineBuilderFactory.create(
                    BfdGlobalToggleFsm.class, BfdGlobalToggleFsmState.class, BfdGlobalToggleFsmEvent.class,
                    BfdGlobalToggleFsmContext.class,
                    // extra parameters
                    IBfdGlobalToggleCarrier.class, Endpoint.class);

            // DOWN_ENABLED
            builder.transition()
                    .from(BfdGlobalToggleFsmState.DOWN_ENABLED).to(BfdGlobalToggleFsmState.DOWN_DISABLED)
                    .on(BfdGlobalToggleFsmEvent.DISABLE);
            builder.transition()
                    .from(BfdGlobalToggleFsmState.DOWN_ENABLED).to(BfdGlobalToggleFsmState.UP_ENABLED)
                    .on(BfdGlobalToggleFsmEvent.BFD_UP);

            // DOWN_DISABLED
            builder.transition()
                    .from(BfdGlobalToggleFsmState.DOWN_DISABLED).to(BfdGlobalToggleFsmState.DOWN_ENABLED)
                    .on(BfdGlobalToggleFsmEvent.ENABLE);
            builder.transition()
                    .from(BfdGlobalToggleFsmState.DOWN_DISABLED).to(BfdGlobalToggleFsmState.UP_DISABLED)
                    .on(BfdGlobalToggleFsmEvent.BFD_UP);

            // UP_ENABLED
            builder.transition()
                    .from(BfdGlobalToggleFsmState.UP_ENABLED).to(BfdGlobalToggleFsmState.UP_DISABLED)
                    .on(BfdGlobalToggleFsmEvent.DISABLE)
                    .callMethod("emitBfdKill");
            builder.transition()
                    .from(BfdGlobalToggleFsmState.UP_ENABLED).to(BfdGlobalToggleFsmState.DOWN_ENABLED)
                    .on(BfdGlobalToggleFsmEvent.BFD_DOWN)
                    .callMethod("emitBfdDown");
            builder.transition()
                    .from(BfdGlobalToggleFsmState.UP_ENABLED).to(BfdGlobalToggleFsmState.DOWN_ENABLED)
                    .on(BfdGlobalToggleFsmEvent.BFD_KILL)
                    .callMethod("emitBfdKill");
            builder.onEntry(BfdGlobalToggleFsmState.UP_ENABLED)
                    .callMethod("emitBfdUp");

            // UP_DISABLED
            builder.transition()
                    .from(BfdGlobalToggleFsmState.UP_DISABLED).to(BfdGlobalToggleFsmState.UP_ENABLED)
                    .on(BfdGlobalToggleFsmEvent.ENABLE);
            builder.transition()
                    .from(BfdGlobalToggleFsmState.UP_DISABLED).to(BfdGlobalToggleFsmState.DOWN_DISABLED)
                    .on(BfdGlobalToggleFsmEvent.BFD_DOWN);
        }

        public FsmExecutor<BfdGlobalToggleFsm, BfdGlobalToggleFsmState, BfdGlobalToggleFsmEvent,
                BfdGlobalToggleFsmContext> produceExecutor() {
            return new FsmExecutor<>(BfdGlobalToggleFsmEvent.NEXT);
        }

        /**
         * Determine initial state and create {@link BfdGlobalToggleFsm} instance.
         */
        public BfdGlobalToggleFsm produce(IBfdGlobalToggleCarrier carrier, Endpoint endpoint) {
            Boolean toggle = featureTogglesRepository.getOrDefault().getUseBfdForIslIntegrityCheck();
            if (toggle == null) {
                throw new IllegalStateException("Unable to identify initial BFD-global-toggle value (it is null at"
                                                        + " this moment)");
            }

            BfdGlobalToggleFsmState state;
            if (toggle) {
                state = BfdGlobalToggleFsmState.DOWN_ENABLED;
            } else {
                state = BfdGlobalToggleFsmState.DOWN_DISABLED;
            }

            return builder.newStateMachine(state, carrier, endpoint);
        }
    }

    @Value
    @Builder
    public static class BfdGlobalToggleFsmContext { }

    public enum BfdGlobalToggleFsmState {
        DOWN_DISABLED, DOWN_ENABLED,
        UP_DISABLED, UP_ENABLED
    }

    public enum BfdGlobalToggleFsmEvent {
        KILL, NEXT,

        ENABLE, DISABLE,
        BFD_UP, BFD_DOWN, BFD_KILL
    }
}
