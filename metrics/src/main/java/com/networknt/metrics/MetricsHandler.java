/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.metrics;

import com.networknt.config.Config;
import com.networknt.handler.Handler;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.httpstring.AttachmentConstants;
import com.networknt.server.Server;
import com.networknt.utility.Constants;
import com.networknt.utility.ModuleRegistry;
import com.networknt.utility.Util;
import io.dropwizard.metrics.Clock;
import io.dropwizard.metrics.MetricFilter;
import io.dropwizard.metrics.MetricName;
import io.dropwizard.metrics.MetricRegistry;
import io.dropwizard.metrics.influxdb.InfluxDbHttpSender;
import io.dropwizard.metrics.influxdb.InfluxDbReporter;
import io.dropwizard.metrics.influxdb.InfluxDbSender;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Metrics middleware handler can be plugged into the request/response chain to
 * capture metrics information for all services. It is based on the dropwizard
 * metric but customized to capture statistic info for a period of time and then
 * reset all the data in order to capture the next period. The capture period can
 * be configured in metrics.yml and normally should be 5 minutes, 10 minutes or
 * 20 minutes depending on the load of the service.
 *
 * @author Steve Hu
 */
public class MetricsHandler implements MiddlewareHandler {
    public static final String CONFIG_NAME = "metrics";
    public static MetricsConfig config = (MetricsConfig)Config.getInstance().getJsonObjectConfig(CONFIG_NAME, MetricsConfig.class);
    static final MetricRegistry registry = new MetricRegistry();
    static final Logger logger = LoggerFactory.getLogger(MetricsHandler.class);

    // this is the indicator to start the reporter and construct the common tags. It cannot be static as
    // the currentPort and currentAddress are not available during the handler initialization.
    private boolean firstTime = true;
    private volatile HttpHandler next;
    Map<String, String> commonTags = new HashMap<>();

    static String MASK_KEY_INFLUX_DB_PASS= "influxdbPass";

    public MetricsHandler() {

    }

    @Override
    public HttpHandler getNext() {
        return this.next;
    }

    @Override
    public MiddlewareHandler setNext(final HttpHandler next) {
        Handlers.handlerNotNull(next);
        this.next = next;
        return this;
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        if(logger.isDebugEnabled()) logger.debug("MetricsHandler.handleRequest starts.");
        if(firstTime) {
            commonTags.put("api", Server.getServerConfig().getServiceId());
            commonTags.put("env", Server.getServerConfig().getEnvironment());
            commonTags.put("addr", Server.currentAddress);
            commonTags.put("port", "" + (Server.getServerConfig().isEnableHttps() ? Server.currentHttpsPort : Server.currentHttpPort));
            InetAddress inetAddress = Util.getInetAddress();
            commonTags.put("host", inetAddress == null ? "unknown" : inetAddress.getHostName()); // will be container id if in docker.
            if(logger.isDebugEnabled()) {
                logger.debug(commonTags.toString());
            }
            try {
                InfluxDbSender influxDb =
                        new InfluxDbHttpSender(config.influxdbProtocol, config.influxdbHost, config.influxdbPort,
                                config.influxdbName, config.influxdbUser, config.influxdbPass);
                InfluxDbReporter reporter = InfluxDbReporter
                        .forRegistry(registry)
                        .convertRatesTo(TimeUnit.SECONDS)
                        .convertDurationsTo(TimeUnit.MILLISECONDS)
                        .filter(MetricFilter.ALL)
                        .build(influxDb);
                reporter.start(config.getReportInMinutes(), TimeUnit.MINUTES);
                if (config.enableJVMMonitor) {
                    createJVMMetricsReporter(influxDb);
                }

                logger.info("metrics is enabled and reporter is started");
            } catch (Exception e) {
                // if there are any exception, chances are influxdb is not available.
                logger.error("metrics is failed to connect to the influxdb", e);
            }
            // reset the flag so that this block will only be called once.
            firstTime = false;
        }

        long startTime = Clock.defaultClock().getTick();
        exchange.addExchangeCompleteListener((exchange1, nextListener) -> {
            Map<String, Object> auditInfo = exchange1.getAttachment(AttachmentConstants.AUDIT_INFO);
            if(auditInfo != null) {
                Map<String, String> tags = new HashMap<>();
                tags.put("endpoint", (String)auditInfo.get(Constants.ENDPOINT_STRING));
                tags.put("clientId", auditInfo.get(Constants.CLIENT_ID_STRING) != null ? (String)auditInfo.get(Constants.CLIENT_ID_STRING) : "unknown");
                tags.put("scopeClientId", auditInfo.get(Constants.SCOPE_CLIENT_ID_STRING) != null ? (String)auditInfo.get(Constants.SCOPE_CLIENT_ID_STRING) : "unknown");
                tags.put("callerId", auditInfo.get(Constants.CALLER_ID_STRING) != null ? (String)auditInfo.get(Constants.CALLER_ID_STRING) : "unknown");
                long time = Clock.defaultClock().getTick() - startTime;
                MetricName metricName = new MetricName("response_time");
                metricName = metricName.tagged(commonTags);
                metricName = metricName.tagged(tags);
                registry.getOrAdd(metricName, MetricRegistry.MetricBuilder.TIMERS).update(time, TimeUnit.NANOSECONDS);
                incCounterForStatusCode(exchange1.getStatusCode(), commonTags, tags);
            }
            nextListener.proceed();
        });
        if(logger.isDebugEnabled()) logger.debug("MetricsHandler.handleRequest ends.");
        Handler.next(exchange, next);
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public void register() {
        ModuleRegistry.registerModule(MetricsHandler.class.getName(), Config.getInstance().getJsonMapConfigNoCache(CONFIG_NAME), List.of(MASK_KEY_INFLUX_DB_PASS));
    }

    @Override
    public void reload() {
        config = (MetricsConfig)Config.getInstance().getJsonObjectConfig(CONFIG_NAME, MetricsConfig.class);
    }

    private void incCounterForStatusCode(int statusCode, Map<String, String> commonTags, Map<String, String> tags) {
        MetricName metricName = new MetricName("request").tagged(commonTags).tagged(tags);
        registry.getOrAdd(metricName, MetricRegistry.MetricBuilder.COUNTERS).inc();
        if(statusCode >= 200 && statusCode < 400) {
            metricName = new MetricName("success").tagged(commonTags).tagged(tags);
            registry.getOrAdd(metricName, MetricRegistry.MetricBuilder.COUNTERS).inc();
        } else if(statusCode == 401 || statusCode == 403) {
            metricName = new MetricName("auth_error").tagged(commonTags).tagged(tags);
            registry.getOrAdd(metricName, MetricRegistry.MetricBuilder.COUNTERS).inc();
        } else if(statusCode >= 400 && statusCode < 500) {
            metricName = new MetricName("request_error").tagged(commonTags).tagged(tags);
            registry.getOrAdd(metricName, MetricRegistry.MetricBuilder.COUNTERS).inc();
        } else if(statusCode >= 500) {
            metricName = new MetricName("server_error").tagged(commonTags).tagged(tags);
            registry.getOrAdd(metricName, MetricRegistry.MetricBuilder.COUNTERS).inc();
        }
    }

    private void createJVMMetricsReporter(final InfluxDbSender influxDb) {
        JVMMetricsInfluxDbReporter jvmReporter = new JVMMetricsInfluxDbReporter(new MetricRegistry(), influxDb, "jvmInfluxDb-reporter",
                MetricFilter.ALL, TimeUnit.SECONDS, TimeUnit.MILLISECONDS, commonTags);
        jvmReporter.start(config.getReportInMinutes(), TimeUnit.MINUTES);
    }
}
