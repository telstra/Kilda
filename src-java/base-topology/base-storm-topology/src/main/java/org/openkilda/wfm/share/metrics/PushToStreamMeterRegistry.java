/* Copyright 2020 Telstra Open Source
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

package org.openkilda.wfm.share.metrics;

import org.openkilda.messaging.Utils;
import org.openkilda.messaging.info.Datapoint;
import org.openkilda.messaging.info.DatapointEntries;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.cumulative.CumulativeCounter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.internal.DefaultGauge;
import io.micrometer.core.instrument.internal.DefaultLongTaskTimer;
import io.micrometer.core.instrument.internal.DefaultMeter;
import io.micrometer.core.lang.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.apache.storm.task.OutputCollector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
public class PushToStreamMeterRegistry extends MeterRegistry {
    public PushToStreamMeterRegistry(String namePrefix) {
        this(namePrefix, Clock.SYSTEM);
    }

    public PushToStreamMeterRegistry(String namePrefix, Clock clock) {
        super(clock);
        config().namingConvention(new SanitizedTagsNamingConvention(namePrefix));
    }

    @Override
    protected Counter newCounter(Meter.Id id) {
        return new CumulativeCounter(id);
    }

    @Override
    protected DistributionSummary newDistributionSummary(Meter.Id id,
                                                         DistributionStatisticConfig distributionStatisticConfig,
                                                         double scale) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    protected Timer newTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig,
                             PauseDetector pauseDetector) {
        return new ResettableTimer(id, clock, distributionStatisticConfig, pauseDetector, getBaseTimeUnit(), false);
    }

    @Override
    protected <T> Gauge newGauge(Meter.Id id, @Nullable T obj, ToDoubleFunction<T> valueFunction) {
        return new DefaultGauge<>(id, obj, valueFunction);
    }

    @Override
    protected LongTaskTimer newLongTaskTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig) {
        return new DefaultLongTaskTimer(id, clock, getBaseTimeUnit(), distributionStatisticConfig, false);
    }

    @Override
    protected <T> FunctionTimer newFunctionTimer(Meter.Id id, T obj, ToLongFunction<T> countFunction,
                                                 ToDoubleFunction<T> totalTimeFunction,
                                                 TimeUnit totalTimeFunctionUnit) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    protected <T> FunctionCounter newFunctionCounter(Meter.Id id, T obj, ToDoubleFunction<T> countFunction) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    protected Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements) {
        return new DefaultMeter(id, type, measurements);
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.NANOSECONDS;
    }

    @Override
    protected DistributionStatisticConfig defaultHistogramConfig() {
        return DistributionStatisticConfig.NONE;
    }

    /**
     * Push the current values of registered meters to the specified stream.
     */
    public void pushMeters(OutputCollector collector, String streamId) {
        try {
            List<Datapoint> datapoints = new ArrayList<>();
            for (Meter meter : getMeters()) {
                datapoints.addAll(meter.match(
                        this::writeGauge,
                        this::writeCounter,
                        this::writeTimer,
                        this::writeSummary,
                        this::writeLongTaskTimer,
                        this::writeTimeGauge,
                        this::writeFunctionCounter,
                        this::writeFunctionTimer,
                        this::writeCustomMeter));
            }
            String payload = Utils.MAPPER.writeValueAsString(new DatapointEntries(datapoints));
            log.debug("Pushing datapoint(s) {}", payload);
            collector.emit(streamId, Collections.singletonList(payload));
        } catch (Exception ex) {
            log.warn("Failed to send metrics", ex);
        }
    }

    private List<Datapoint> writeGauge(Gauge gauge) {
        double value = gauge.value();
        if (Double.isFinite(value)) {
            return Collections.singletonList(buildDatapoint(gauge.getId(), config().clock().wallTime(), value));
        }
        return Collections.emptyList();
    }

    private List<Datapoint> writeCounter(Counter counter) {
        return Collections.singletonList(buildDatapoint(counter.getId(), config().clock().wallTime(), counter.count()));
    }

    private List<Datapoint> writeTimer(Timer timer) {
        long wallTime = config().clock().wallTime();

        ResettableTimer resettableTimer = (ResettableTimer) timer;

        List<Datapoint> metrics = new ArrayList<>();
        if (resettableTimer.isSet()) {
            metrics.add(buildDatapointWithSuffix(timer.getId(), "count", wallTime, timer.count()));
            metrics.add(buildDatapointWithSuffix(timer.getId(), "sum", wallTime, timer.totalTime(getBaseTimeUnit())));
            metrics.add(buildDatapointWithSuffix(timer.getId(), "max", wallTime, timer.max(getBaseTimeUnit())));
            metrics.add(buildDatapointWithSuffix(timer.getId(), "mean", wallTime, timer.mean(getBaseTimeUnit())));

            resettableTimer.reset();
        }
        return metrics;
    }

    private List<Datapoint> writeSummary(DistributionSummary summary) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    private List<Datapoint> writeLongTaskTimer(LongTaskTimer timer) {
        long wallTime = config().clock().wallTime();

        List<Datapoint> metrics = new ArrayList<>();
        metrics.add(buildDatapointWithSuffix(timer.getId(), "active.count", wallTime, timer.activeTasks()));
        metrics.add(buildDatapointWithSuffix(timer.getId(), "duration.sum", wallTime,
                timer.duration(getBaseTimeUnit())));
        metrics.add(buildDatapointWithSuffix(timer.getId(), "max", wallTime, timer.max(getBaseTimeUnit())));
        metrics.add(buildDatapointWithSuffix(timer.getId(), "mean", wallTime, timer.mean(getBaseTimeUnit())));
        return metrics;
    }

    private List<Datapoint> writeTimeGauge(TimeGauge timeGauge) {
        double value = timeGauge.value(getBaseTimeUnit());
        if (Double.isFinite(value)) {
            return Collections.singletonList(buildDatapoint(timeGauge.getId(), config().clock().wallTime(), value));
        }
        return Collections.emptyList();
    }

    private List<Datapoint> writeFunctionCounter(FunctionCounter counter) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    private List<Datapoint> writeFunctionTimer(FunctionTimer timer) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    private List<Datapoint> writeCustomMeter(Meter meter) {
        long wallTime = config().clock().wallTime();

        List<Tag> tags = getConventionTags(meter.getId());

        return StreamSupport.stream(meter.measure().spliterator(), false)
                .map(ms -> {
                    String name = getConventionName(meter.getId());

                    switch (ms.getStatistic()) {
                        case TOTAL:
                        case TOTAL_TIME:
                            name += ".sum";
                            break;
                        case MAX:
                            name += ".max";
                            break;
                        case ACTIVE_TASKS:
                            name += ".active.count";
                            break;
                        case DURATION:
                            name += ".duration.sum";
                            break;
                        default:
                            throw new IllegalStateException("Unknown statistic " + ms.getStatistic());
                    }

                    Datapoint datapoint = new Datapoint();
                    datapoint.setMetric(name);
                    datapoint.setTime(wallTime);
                    // Double type is not supported by {@link org.apache.storm.opentsdb.OpenTsdbMetricDatapoint}
                    datapoint.setValue((long) ms.getValue());

                    Tags localTags = Tags.concat(tags, "statistics", ms.getStatistic().toString());
                    Map<String, String> resultTags = new HashMap<>(localTags.stream()
                            .collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
                    if (resultTags.isEmpty()) {
                        //FIXME: opentsdb rejects datapoints without tags.
                        resultTags.put("dummy", "dummy");
                    }
                    datapoint.setTags(resultTags);
                    return datapoint;
                })
                .collect(Collectors.toList());
    }

    private Datapoint buildDatapoint(Meter.Id id, long wallTime, double value) {
        return buildDatapointWithSuffix(id, "", wallTime, value);
    }

    private Datapoint buildDatapointWithSuffix(Meter.Id id, String suffix, long wallTime, double value) {
        // usually tagKeys and metricNames naming rules are the same
        // but we can't call getConventionName again after adding suffix
        Datapoint datapoint = new Datapoint();
        datapoint.setMetric(suffix.isEmpty() ? getConventionName(id)
                : config().namingConvention().tagKey(getConventionName(id) + "." + suffix));
        datapoint.setTime(wallTime);
        // Double type is not supported by {@link org.apache.storm.opentsdb.OpenTsdbMetricDatapoint}
        datapoint.setValue((long) value);
        Map<String, String> tags = new HashMap<>(getConventionTags(id).stream()
                .collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
        if (tags.isEmpty()) {
            //FIXME: opentsdb rejects datapoints without tags.
            tags.put("dummy", "dummy");
        }
        datapoint.setTags(tags);
        return datapoint;
    }

    static class SanitizedTagsNamingConvention implements NamingConvention {
        private static final String SEPARATOR = "_";
        private static final Pattern tagValueChars = Pattern.compile("[^a-zA-Z0-9_]");

        private final String namePrefix;

        public SanitizedTagsNamingConvention(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public String name(String name, Meter.Type type, @Nullable String baseUnit) {
            return namePrefix + "." + NamingConvention.dot.name(name, type, baseUnit);
        }

        @Override
        public String tagValue(String value) {
            String conventionValue = NamingConvention.dot.tagValue(value);
            return tagValueChars.matcher(conventionValue).replaceAll(SEPARATOR);
        }
    }
}
