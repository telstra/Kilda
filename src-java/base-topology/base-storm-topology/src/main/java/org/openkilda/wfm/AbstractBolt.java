/* Copyright 2018 Telstra Open Source
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

package org.openkilda.wfm;

import static org.openkilda.wfm.share.zk.ZooKeeperSpout.FIELD_ID_LIFECYCLE_EVENT;

import org.openkilda.bluegreen.LifecycleEvent;
import org.openkilda.bluegreen.Signal;
import org.openkilda.persistence.context.PersistenceContextRequired;
import org.openkilda.wfm.error.PipelineException;
import org.openkilda.wfm.share.zk.ZkStreams;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public abstract class AbstractBolt extends BaseRichBolt {
    public static final String FIELD_ID_CONTEXT = "context";

    protected transient Logger log = makeLog();

    protected boolean active = false;

    @Getter(AccessLevel.PROTECTED)
    private transient OutputCollector output;

    @Getter(AccessLevel.PROTECTED)
    private transient String componentId;

    @Getter(AccessLevel.PROTECTED)
    private transient Integer taskId;

    @Getter(AccessLevel.PROTECTED)
    private transient Tuple currentTuple;

    @Getter(AccessLevel.PROTECTED)
    @Setter(AccessLevel.PROTECTED)
    private transient CommandContext commandContext;

    private String lifeCycleEventSourceComponent;

    public AbstractBolt() {
        this(null);
    }

    public AbstractBolt(String lifeCycleEventSourceComponent) {
        this.lifeCycleEventSourceComponent = lifeCycleEventSourceComponent;
    }

    @Override
    @PersistenceContextRequired(requiresNew = true)
    public void execute(Tuple input) {
        if (log.isDebugEnabled()) {
            log.trace("{} input tuple from {}:{} [{}]",
                    getClass().getName(), input.getSourceComponent(), input.getSourceStreamId(),
                    formatTuplePayload(input));
        }
        try {
            currentTuple = input;
            commandContext = setupCommandContext();
            dispatch(input);
        } catch (Exception e) {
            wrapExceptionHandler(e);
        } finally {
            ack(input);
            currentTuple = null;
            commandContext = null;
        }
    }

    protected void emit(Tuple anchor, List<Object> payload) {
        log.debug("emit tuple into default stream: {}", payload);
        output.emit(anchor, payload);
    }

    protected void emit(String stream, Tuple anchor, List<Object> payload) {
        log.debug("emit tuple into \"{}\" stream: {}", stream, payload);
        output.emit(stream, anchor, payload);
    }

    protected void emit(String stream, List<Object> payload) {
        log.debug("emit tuple into \"{}\" stream: {}", stream, payload);
        output.emit(stream, payload);
    }

    protected void emitWithContext(String stream, Tuple input, Values payload) {
        payload.add(getCommandContext());
        log.debug("emit tuple into {} stream: {}", stream, payload);
        getOutput().emit(stream, input, payload);
    }

    protected void dispatch(Tuple input) throws Exception {
        if (input.getSourceComponent().equals(lifeCycleEventSourceComponent)) {
            LifecycleEvent event = (LifecycleEvent) input.getValueByField(FIELD_ID_LIFECYCLE_EVENT);
            log.info("Received lifecycle event {}", event);
            if (shouldHandleLifeCycleEvent(event.getSignal())) {
                handleLifeCycleEvent(event);
            }
        } else {
            handleInput(input);
        }
    }

    protected final boolean shouldHandleLifeCycleEvent(Signal signal) {
        if (Signal.START.equals(signal) && active) {
            log.info("Component is already in active state, skipping START signal");
            return false;
        }
        if (Signal.SHUTDOWN.equals(signal) && !active) {
            log.info("Component is already in inactive state, skipping SHUTDOWN signal");
            return false;
        }
        return true;
    }

    protected abstract void handleInput(Tuple input) throws Exception;

    protected final void handleLifeCycleEvent(LifecycleEvent event) {
        if (Signal.START.equals(event.getSignal())) {
            emit(ZkStreams.ZK.toString(), currentTuple, new Values(event, commandContext));
            try {
                activate();
            } finally {
                active = true;
            }
        } else if (Signal.SHUTDOWN.equals(event.getSignal())) {

            try {
                if (deactivate(event)) {
                    emit(ZkStreams.ZK.toString(), currentTuple, new Values(event, commandContext));
                }
            } finally {
                active = false;
            }
        } else {
            log.error("Unsupported signal received: {}", event.getSignal());
        }
    }

    protected void activate() {
        // no actions required
    }

    protected boolean deactivate(LifecycleEvent event) {
        return true;
    }

    protected void handleException(Exception e) throws Exception {
        throw e;
    }

    protected void ack(Tuple input) {
        log.trace("Ack tuple id {}", input.getMessageId());
        output.ack(input);
    }

    protected void unhandledInput(Tuple input) {
        log.error(
                "{} is unable to handle input tuple from \"{}\" stream \"{}\" [{}] - have topology being build"
                        + " correctly?",
                getClass().getName(), input.getSourceComponent(), input.getSourceStreamId(), formatTuplePayload(input));
    }

    private void wrapExceptionHandler(Exception e) {
        try {
            handleException(e);
        } catch (Exception ee) {
            log.error(String.format("Unhandled exception in %s", getClass().getName()), e);
        }
    }

    @Override
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        this.output = collector;
        this.taskId = context.getThisTaskId();
        this.componentId = String.format("%s:%d", context.getThisComponentId(), this.taskId);

        init();
    }

    protected void init() {
    }

    protected CommandContext setupCommandContext() {
        Tuple input = getCurrentTuple();
        CommandContext context;
        try {
            context = pullContext(input);
        } catch (PipelineException e) {
            context = new CommandContext().fork("trace-fail");

            log.warn("The command context is missing in input tuple received by {} on stream {}:{}, execution context"
                            + " can't  be traced. Create new command context for possible tracking of following"
                            + " processing [{}].",
                    getClass().getName(), input.getSourceComponent(), input.getSourceStreamId(),
                    formatTuplePayload(input), e);
        }

        return context;
    }

    protected CommandContext pullContext(Tuple input) throws PipelineException {
        CommandContext value;
        try {
            Object raw = input.getValueByField(FIELD_ID_CONTEXT);
            if (raw instanceof String) {
                value = new CommandContext((String) raw);
            } else {
                value = (CommandContext) raw;
            }
        } catch (IllegalArgumentException | ClassCastException e) {
            throw new PipelineException(this, input, FIELD_ID_CONTEXT, e.toString());
        }
        return value;
    }

    protected CommandContext forkContext(String... fork) {
        CommandContext context = commandContext;
        for (int idx = 0; idx < fork.length; idx++) {
            context = context.fork(fork[idx]);
        }
        return context;
    }

    protected <T> T pullValue(Tuple input, String field, Class<T> klass) throws PipelineException {
        T value;
        try {
            value = klass.cast(input.getValueByField(field));
        } catch (ClassCastException e) {
            throw new PipelineException(this, input, field, e.toString());
        }
        return value;
    }

    private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
        stream.defaultReadObject();

        log = makeLog();
    }

    private Logger makeLog() {
        return LoggerFactory.getLogger(getClass());
    }

    protected static String formatTuplePayload(Tuple input) {
        Iterator<String> fields = input.getFields().iterator();
        Iterator<Object> values = input.getValues().iterator();
        StringBuilder payload = new StringBuilder();
        boolean isFirst = true;
        while (fields.hasNext() || values.hasNext()) {
            if (!isFirst) {
                payload.append(", ");
            }
            isFirst = false;

            String name = fields.next();
            payload.append(name != null ? name : "(unknown)");
            payload.append(": ");
            Object v = values.next();
            payload.append(v != null ? v.toString() : "null");
        }

        return payload.toString();
    }
}
