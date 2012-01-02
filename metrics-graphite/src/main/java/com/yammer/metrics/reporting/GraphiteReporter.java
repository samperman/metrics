package com.yammer.metrics.reporting;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.*;
import com.yammer.metrics.core.VirtualMachineMetrics.*;
import com.yammer.metrics.util.MetricPredicate;
import com.yammer.metrics.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.Thread.State;
import java.net.Socket;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import static com.yammer.metrics.core.VirtualMachineMetrics.*;


/**
 * A simple reporter which sends out application metrics to a <a href="http://graphite.wikidot.com/faq">Graphite</a>
 * server periodically.
 */
public class GraphiteReporter extends AbstractPollingReporter implements MetricsProcessor<Long> {
    private static final Logger LOG = LoggerFactory.getLogger(GraphiteReporter.class);
    private final String prefix;
    private final MetricPredicate predicate;
    private final Locale locale = Locale.US;
    private final Clock clock;
    private final SocketProvider socketProvider;
    private Writer writer;
    public boolean printVMMetrics = true;

    /**
     * Enables the graphite reporter to send data for the default metrics registry to graphite
     * server with the specified period.
     *
     * @param period the period between successive outputs
     * @param unit   the time unit of {@code period}
     * @param host   the host name of graphite server (carbon-cache agent)
     * @param port   the port number on which the graphite server is listening
     */
    public static void enable(long period, TimeUnit unit, String host, int port) {
        enable(Metrics.defaultRegistry(), period, unit, host, port);
    }

    /**
     * Enables the graphite reporter to send data for the given metrics registry to graphite server
     * with the specified period.
     *
     * @param metricsRegistry the metrics registry
     * @param period          the period between successive outputs
     * @param unit            the time unit of {@code period}
     * @param host            the host name of graphite server (carbon-cache agent)
     * @param port            the port number on which the graphite server is listening
     */
    public static void enable(MetricsRegistry metricsRegistry, long period, TimeUnit unit, String host, int port) {
        enable(metricsRegistry, period, unit, host, port, null);
    }

    /**
     * Enables the graphite reporter to send data to graphite server with the specified period.
     *
     * @param period the period between successive outputs
     * @param unit   the time unit of {@code period}
     * @param host   the host name of graphite server (carbon-cache agent)
     * @param port   the port number on which the graphite server is listening
     * @param prefix the string which is prepended to all metric names
     */
    public static void enable(long period, TimeUnit unit, String host, int port, String prefix) {
        enable(Metrics.defaultRegistry(), period, unit, host, port, prefix);
    }

    /**
     * Enables the graphite reporter to send data to graphite server with the specified period.
     *
     * @param metricsRegistry the metrics registry
     * @param period          the period between successive outputs
     * @param unit            the time unit of {@code period}
     * @param host            the host name of graphite server (carbon-cache agent)
     * @param port            the port number on which the graphite server is listening
     * @param prefix          the string which is prepended to all metric names
     */
    public static void enable(MetricsRegistry metricsRegistry, long period, TimeUnit unit, String host, int port, String prefix) {
        enable(metricsRegistry, period, unit, host, port, prefix, MetricPredicate.ALL);
    }

    /**
     * Enables the graphite reporter to send data to graphite server with the specified period.
     *
     * @param metricsRegistry the metrics registry
     * @param period          the period between successive outputs
     * @param unit            the time unit of {@code period}
     * @param host            the host name of graphite server (carbon-cache agent)
     * @param port            the port number on which the graphite server is listening
     * @param prefix          the string which is prepended to all metric names
     * @param predicate       filters metrics to be reported
     */
    public static void enable(MetricsRegistry metricsRegistry, long period, TimeUnit unit, String host, int port, String prefix, MetricPredicate predicate) {
        try {
            final GraphiteReporter reporter = new GraphiteReporter(metricsRegistry,
                                                                   prefix,
                                                                   predicate,
                                                                   new DefaultSocketProvider(host,
                                                                                             port),
                                                                   Clock.DEFAULT);
            reporter.start(period, unit);
        } catch (Exception e) {
            LOG.error("Error creating/starting Graphite reporter:", e);
        }
    }

    /**
     * Creates a new {@link GraphiteReporter}.
     *
     * @param host   is graphite server
     * @param port   is port on which graphite server is running
     * @param prefix is prepended to all names reported to graphite
     * @throws IOException if there is an error connecting to the Graphite server
     */
    public GraphiteReporter(String host, int port, String prefix) throws IOException {
        this(Metrics.defaultRegistry(), host, port, prefix);
    }

    /**
     * Creates a new {@link GraphiteReporter}.
     *
     * @param metricsRegistry the metrics registry
     * @param host            is graphite server
     * @param port            is port on which graphite server is running
     * @param prefix          is prepended to all names reported to graphite
     * @throws IOException if there is an error connecting to the Graphite server
     */
    public GraphiteReporter(MetricsRegistry metricsRegistry, String host, int port, String prefix) throws IOException {
        this(metricsRegistry,
             prefix,
             MetricPredicate.ALL,
             new DefaultSocketProvider(host, port),
             Clock.DEFAULT);
    }

    /**
     * Creates a new {@link GraphiteReporter}.
     *
     * @param metricsRegistry the metrics registry
     * @param prefix          is prepended to all names reported to graphite
     * @param predicate       filters metrics to be reported
     * @param socketProvider  a {@link SocketProvider} instance
     * @param clock           a {@link Clock} instance
     * @throws IOException if there is an error connecting to the Graphite server
     */
    public GraphiteReporter(MetricsRegistry metricsRegistry, String prefix, MetricPredicate predicate, SocketProvider socketProvider, Clock clock) throws IOException {
        super(metricsRegistry, "graphite-reporter");
        this.socketProvider = socketProvider;

        this.clock = clock;

        if (prefix != null) {
            // Pre-append the "." so that we don't need to make anything conditional later.
            this.prefix = prefix + ".";
        } else {
            this.prefix = "";
        }
        this.predicate = predicate;
    }

    @Override
    public void run() {
        Socket socket = null;
        try {
            socket = this.socketProvider.get();
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

            final long epoch = clock.time() / 1000;
            if (this.printVMMetrics) {
                printVmMetrics(epoch);
            }
            printRegularMetrics(epoch);
            writer.flush();
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error writing to Graphite", e);
            } else {
                LOG.warn("Error writing to Graphite: {}", e.getMessage());
            }
            if (writer != null) {
                try {
                    writer.flush();
                } catch (IOException e1) {
                    LOG.error("Error while flushing writer:", e1);
                }
            }
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    LOG.error("Error while closing socket:", e);
                }
            }
            writer = null;
        }
    }

    private void printRegularMetrics(final Long epoch) {
        for (Entry<String, Map<MetricName, Metric>> entry : Utils.sortAndFilterMetrics(
                metricsRegistry.allMetrics(),
                this.predicate).entrySet()) {
            for (Entry<MetricName, Metric> subEntry : entry.getValue().entrySet()) {
                final Metric metric = subEntry.getValue();
                if (metric != null) {
                    try {
                        metric.processWith(this, subEntry.getKey(), epoch);
                    } catch (Exception ignored) {
                        LOG.error("Error printing regular metrics:", ignored);
                    }
                }
            }
        }
    }

    private void sendInt(long timestamp, String name, String valueName, long value) {
        sendToGraphite(timestamp, name, valueName + " " + String.format(locale, "%d", value));
    }

    private void sendFloat(long timestamp, String name, String valueName, double value) {
        sendToGraphite(timestamp, name, valueName + " " + String.format(locale, "%2.2f", value));
    }

    private void sendObjToGraphite(long timestamp, String name, String valueName, Object value) {
        sendToGraphite(timestamp, name, valueName + " " + String.format(locale, "%s", value));
    }

    private void sendToGraphite(long timestamp, String name, String value) {
        try {
            if (!prefix.isEmpty()) {
                writer.write(prefix);
            }
            writer.write(sanitizeString(name));
            writer.write('.');
            writer.write(value);
            writer.write(' ');
            writer.write(Long.toString(timestamp));
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            LOG.error("Error sending to Graphite:", e);
        }
    }

    private String sanitizeName(MetricName name) {
        final StringBuilder sb = new StringBuilder()
                .append(name.getGroup())
                .append('.')
                .append(name.getType())
                .append('.');
        if (name.hasScope()) {
            sb.append(name.getScope())
              .append('.');
        }
        return sb.append(name.getName()).toString();
    }
    
    private String sanitizeString(String s) {
        return s.replace(' ', '-');
    }

    @Override
    public void processGauge(MetricName name, Gauge<?> gauge, Long epoch) throws IOException {
        sendObjToGraphite(epoch, sanitizeName(name), "value", gauge.value());
    }

    @Override
    public void processCounter(MetricName name, Counter counter, Long epoch) throws IOException {
        sendInt(epoch, sanitizeName(name), "count", counter.count());
    }

    @Override
    public void processMeter(MetricName name, Metered meter, Long epoch) throws IOException {
        final String sanitizedName = sanitizeName(name);
        sendInt(epoch, sanitizedName, "count", meter.count());
        sendFloat(epoch, sanitizedName, "meanRate", meter.meanRate());
        sendFloat(epoch, sanitizedName, "1MinuteRate", meter.oneMinuteRate());
        sendFloat(epoch, sanitizedName, "5MinuteRate", meter.fiveMinuteRate());
        sendFloat(epoch, sanitizedName, "15MinuteRate", meter.fifteenMinuteRate());
    }

    @Override
    public void processHistogram(MetricName name, Histogram histogram, Long epoch) throws IOException {
        final String sanitizedName = sanitizeName(name);
        sendSummarized(epoch, sanitizedName, histogram);
        sendPercentiled(epoch, sanitizedName, histogram);
    }

    @Override
    public void processTimer(MetricName name, Timer timer, Long epoch) throws IOException {
        processMeter(name, timer, epoch);
        final String sanitizedName = sanitizeName(name);
        sendSummarized(epoch, sanitizedName, timer);
        sendPercentiled(epoch, sanitizedName, timer);
    }

    private void sendSummarized(long epoch, String sanitizedName, Summarized metric) throws IOException {
        sendFloat(epoch, sanitizedName, "min", metric.min());
        sendFloat(epoch, sanitizedName, "max", metric.max());
        sendFloat(epoch, sanitizedName, "mean", metric.mean());
        sendFloat(epoch, sanitizedName, "stddev", metric.stdDev());
    }

    private void sendPercentiled(long epoch, String sanitizedName, Percentiled metric) throws IOException {
        final Double[] percentiles = metric.percentiles(0.5, 0.75, 0.95, 0.98, 0.99, 0.999);
        sendFloat(epoch, sanitizedName, "median", percentiles[0]);
        sendFloat(epoch, sanitizedName, "75percentile", percentiles[1]);
        sendFloat(epoch, sanitizedName, "95percentile", percentiles[2]);
        sendFloat(epoch, sanitizedName, "98percentile", percentiles[3]);
        sendFloat(epoch, sanitizedName, "99percentile", percentiles[4]);
        sendFloat(epoch, sanitizedName, "999percentile", percentiles[5]);
    }

    private void printVmMetrics(long epoch) {
        sendFloat(epoch, "jvm.memory", "heap_usage", heapUsage());
        sendFloat(epoch, "jvm.memory", "non_heap_usage", nonHeapUsage());
        for (Entry<String, Double> pool : memoryPoolUsage().entrySet()) {
            sendFloat(epoch, "jvm.memory.memory_pool_usages", pool.getKey(), pool.getValue());
        }

        sendInt(epoch, "jvm", "daemon_thread_count", daemonThreadCount());
        sendInt(epoch, "jvm", "thread_count", threadCount());
        sendInt(epoch, "jvm", "uptime", uptime());
        sendFloat(epoch, "jvm", "fd_usage", fileDescriptorUsage());

        for (Entry<State, Double> entry : threadStatePercentages().entrySet()) {
            sendFloat(epoch, "jvm.thread-states", entry.getKey().toString().toLowerCase(), entry.getValue());
        }

        for (Entry<String, GarbageCollector> entry : garbageCollectors().entrySet()) {
            final String name = "jvm.gc." + entry.getKey();
            sendInt(epoch, name, "time", entry.getValue().getTime(TimeUnit.MILLISECONDS));
            sendInt(epoch, name, "runs", entry.getValue().getRuns());
        }
    }

    private static class DefaultSocketProvider implements SocketProvider {

        private final String host;
        private final int port;

        public DefaultSocketProvider(String host, int port) {
            this.host = host;
            this.port = port;

        }

        @Override
        public Socket get() throws Exception {
            return new Socket(this.host, this.port);
        }

    }
}
