/*
 *  Copyright 2009-2016 Weibo, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.networknt.consul;

import com.networknt.common.SecretConstants;
import com.networknt.config.Config;
import com.networknt.consul.client.ConsulClient;
import com.networknt.registry.NotifyListener;
import com.networknt.registry.URL;
import com.networknt.registry.URLParamType;
import com.networknt.registry.support.AbstractRegistry;
import com.networknt.status.Status;
import com.networknt.utility.ConcurrentHashSet;
import com.networknt.utility.Constants;
import com.networknt.utility.ModuleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ConsulRegistry extends AbstractRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ConsulRegistry.class);
    private static final String CONFIG_PROPERTY_MISSING = "ERR10057";

    private ConsulClient client;
    private ConsulHeartbeatManager heartbeatManager;
    private int lookupInterval;

    // service local cache. key: serviceName, value: <service url list>
    private ConcurrentHashMap<String, List<URL>> serviceCache = new ConcurrentHashMap<String, List<URL>>();
    // keep all the subscribe urls, so that it won't double subscribe.
    private static Set<URL> subscribedSet = new ConcurrentHashSet<>();
    // record lookup service thread, ensure each serviceName start only one thread, <serviceName, lastConsulIndexId>
    private ConcurrentHashMap<String, Long> lookupServices = new ConcurrentHashMap<String, Long>();

    // TODO: 2016/6/17 clientUrl support multiple listener
    // record subscribers service callback listeners, listener was called when corresponding service changes
    private ConcurrentHashMap<String, ConcurrentHashMap<URL, NotifyListener>> notifyListeners = new ConcurrentHashMap<>();
    private ThreadPoolExecutor notifyExecutor;

    static String MASK_KEY_CONSUL_TOKEN = "consulToken";

    public ConsulRegistry(URL url, ConsulClient client) {
        super(url);
        this.client = client;
        if(getConsulConfig().ttlCheck) {
            heartbeatManager = new ConsulHeartbeatManager(client, getConsulToken());
            heartbeatManager.start();
        }
        lookupInterval = getUrl().getIntParameter(URLParamType.registrySessionTimeout.getName(), ConsulConstants.DEFAULT_LOOKUP_INTERVAL);

        ArrayBlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<Runnable>(20000);
        notifyExecutor = new ThreadPoolExecutor(10, 30, 30 * 1000, TimeUnit.MILLISECONDS, workQueue);
        logger.info("ConsulRegistry init finish.");
        ModuleRegistry.registerModule(ConsulRegistry.class.getName(), Config.getInstance().getJsonMapConfigNoCache(ConsulConfig.CONFIG_NAME), List.of(MASK_KEY_CONSUL_TOKEN));
    }

    public ConcurrentHashMap<String, ConcurrentHashMap<URL, NotifyListener>> getNotifyListeners() {
        return notifyListeners;
    }

    @Override
    protected void doRegister(URL url) {
        ConsulService service = ConsulUtils.buildService(url);
        client.registerService(service, getConsulToken());
        if(getConsulConfig().ttlCheck) heartbeatManager.addHeartbeatServcieId(service.getId());
    }

    @Override
    protected void doUnregister(URL url) {
        ConsulService service = ConsulUtils.buildService(url);
        client.unregisterService(service.getId(), getConsulToken());
        if(getConsulConfig().ttlCheck) heartbeatManager.removeHeartbeatServiceId(service.getId());
    }

    @Override
    protected void doAvailable(URL url) {
        if (url == null) {
            if(getConsulConfig().ttlCheck) heartbeatManager.setHeartbeatOpen(true);
        } else {
            throw new UnsupportedOperationException("Command consul registry not support available by urls yet");
        }
    }

    @Override
    protected void doUnavailable(URL url) {
        if (url == null) {
            if(getConsulConfig().ttlCheck) heartbeatManager.setHeartbeatOpen(false);
        } else {
            throw new UnsupportedOperationException("Command consul registry not support unavailable by urls yet");
        }
    }

    /**
     * Override the method in <code>com.networknt.registry.support.commandCommandFailbackRegistry</code>
     * to skip calling the <code>com.networknt.registry.support.commandCommandFailbackRegistry#doDiscover()</code> and
     * <code>com.networknt.registry.support.commandCommandFailbackRegistry#notify()</code>
     * @param url The subscribed service URL
     * @param listener  The listener to be notified when service registration changed.
     */
    @Override
    protected void doSubscribe(URL url, final NotifyListener listener) {
        // you only need to subscribe once.
        if(!subscribedSet.contains(url)) {
            addNotifyListener(url, listener);
            startListenerThreadIfNewService(url);
            subscribedSet.add(url);
        }
    }

    @Override
    protected void doUnsubscribe(URL url, NotifyListener listener) {
        ConcurrentHashMap<URL, NotifyListener> listeners = notifyListeners.get(ConsulUtils.getUrlClusterInfo(url));
        if (listeners != null) {
            synchronized (listeners) {
                listeners.remove(url);
            }
        }
    }

    @Override
    protected List<URL> doDiscover(URL url) {
        String serviceName = url.getPath();
        String tag = url.getParameter(Constants.TAG_ENVIRONMENT);
        String protocol = url.getProtocol();
        if(logger.isTraceEnabled()) logger.trace("protocol = " + protocol + " serviceName = " + serviceName + " tag = " + tag);
        List<URL> urls = serviceCache.get(serviceName);
        if (urls == null || urls .isEmpty()) {
            synchronized (serviceName.intern()) {
                urls = serviceCache.get(serviceName);
                if (urls == null || urls .isEmpty()) {
                    ConcurrentHashMap<String, List<URL>> serviceUrls = lookupServiceUpdate(protocol, serviceName, false);
                    updateServiceCache(serviceName, serviceUrls, false);
                    urls = serviceCache.get(serviceName);
                }
            }
        }
        return urls;
    }

    /**
     * if new service registered, start a new lookup thread
     * each serviceName start a lookup thread to discover service
     *
     * @param url
     */
    private void startListenerThreadIfNewService(URL url) {
        String serviceName = url.getPath();
        String protocol = url.getProtocol();
        if (!lookupServices.containsKey(serviceName)) {
            Long value = lookupServices.putIfAbsent(serviceName, 0L);
            if (value == null) {
                ServiceLookupThread lookupThread = new ServiceLookupThread(protocol, serviceName);
                lookupThread.setDaemon(true);
                lookupThread.start();
            }
        }
    }

    private void addNotifyListener(URL url, NotifyListener listener) {
        String service = ConsulUtils.getUrlClusterInfo(url);
        ConcurrentHashMap<URL, NotifyListener> map = notifyListeners.get(service);
        if (map == null) {
            notifyListeners.putIfAbsent(service, new ConcurrentHashMap<>());
            map = notifyListeners.get(service);
        }
        synchronized (map) {
            map.put(url, listener);
        }
    }

    private ConcurrentHashMap<String, List<URL>> lookupServiceUpdate(String protocol, String serviceName) {
        return lookupServiceUpdate(protocol, serviceName, true);
    }

    private ConcurrentHashMap<String, List<URL>> lookupServiceUpdate(String protocol, String serviceName, boolean isBlockQuery) {
        Long lastConsulIndexId = 0L;
        if (isBlockQuery) {
            lastConsulIndexId = lookupServices.get(serviceName) == null ? 0L : lookupServices.get(serviceName);
        }

        if(logger.isTraceEnabled()) logger.trace("serviceName = " + serviceName + " lastConsulIndexId = " + lastConsulIndexId);
        ConsulResponse<List<ConsulService>> response = lookupConsulService(serviceName, lastConsulIndexId);
        if(logger.isTraceEnabled()) {
            try {
                logger.trace("response = " + Config.getInstance().getMapper().writeValueAsString(response));
            } catch (Exception e) {}
        }
        ConcurrentHashMap<String, List<URL>> serviceUrls = new ConcurrentHashMap<>();
        if (response != null) {
            List<ConsulService> services = response.getValue();
            if(logger.isDebugEnabled()) try {logger.debug("services = " + Config.getInstance().getMapper().writeValueAsString(services));} catch (Exception e) {}
            if (services != null && !services.isEmpty()
                    && response.getConsulIndex() > lastConsulIndexId) {
                for (ConsulService service : services) {
                    try {
                        URL url = ConsulUtils.buildUrl(protocol, service);
                        List<URL> urlList = serviceUrls.get(serviceName);
                        if (urlList == null) {
                            urlList = new ArrayList<>();
                            serviceUrls.put(serviceName, urlList);
                        }
                        if(logger.isTraceEnabled()) logger.trace("lookupServiceUpdate url = " + url);
                        urlList.add(url);
                    } catch (Exception e) {
                        logger.error("convert consul service to url fail! service:" + service, e);
                    }
                }
                lookupServices.put(serviceName, response.getConsulIndex());
                return serviceUrls;
            } else if (response.getConsulIndex() < lastConsulIndexId) {
                logger.info(serviceName + "  lastIndex:" + lastConsulIndexId + "; response consul Index:" + response.getConsulIndex());
                lookupServices.put(serviceName, 0L);
            } else {
                logger.info(serviceName + " no need update, lastIndex:" + lastConsulIndexId);
            }
        } else {
            logger.info("Consul UNRESPONSIVE - Service {}'s local Service Registry Cache potentially out of date", serviceName);
        }
        return serviceUrls;
    }

    /**
     * directly fetch consul service data.
     *
     * @param serviceName
     * @return ConsulResponse or null
     */
    private ConsulResponse<List<ConsulService>> lookupConsulService(String serviceName, Long lastConsulIndexId) {
        ConsulResponse<List<ConsulService>> response = client.lookupHealthService(serviceName, null, lastConsulIndexId, getConsulToken());
        return response;
    }

    /**
     * update service cache of the serviceName.
     * update local cache when service list changed,
     * if need notify, notify service
     *
     * @param serviceName
     * @param serviceUrls
     * @param needNotify
     */
    private void updateServiceCache(String serviceName, ConcurrentHashMap<String, List<URL>> serviceUrls, boolean needNotify) {
        if (serviceUrls != null && !serviceUrls.isEmpty()) {
            List<URL> cachedUrls = serviceCache.get(serviceName);
            List<URL> newUrls = serviceUrls.get(serviceName);
            try {
                logger.trace("serviceUrls = {}", Config.getInstance().getMapper().writeValueAsString(serviceUrls));
            } catch(Exception e) {
            }
            boolean change = true;
            if (ConsulUtils.isSame(newUrls, cachedUrls)) {
                change = false;
            } else {
                serviceCache.put(serviceName, newUrls);
            }
            if (change && needNotify) {
                notifyExecutor.execute(new NotifyService(serviceName, newUrls));
                logger.info("light service notify-service: " + serviceName);
                StringBuilder sb = new StringBuilder();
                for (URL url : newUrls) {
                    sb.append(url.getUri()).append(";");
                }
                logger.info("consul notify urls:" + sb.toString());
            }
        }
    }

    private class ServiceLookupThread extends Thread {
       private String protocol;
       private String serviceName;

        public ServiceLookupThread(String protocol, String serviceName) {
            this.protocol = protocol;
            this.serviceName = serviceName;
        }

        @Override
        public void run() {
            logger.info("start service lookup thread. lookup interval: " + lookupInterval + "ms, service: " + serviceName);
            while (true) {
                try {
                    sleep(lookupInterval);
                    ConcurrentHashMap<String, List<URL>> serviceUrls = lookupServiceUpdate(protocol, serviceName);
                    updateServiceCache(serviceName, serviceUrls, true);
                } catch (Throwable e) {
                    logger.error("service lookup thread fail!", e);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }

    private class NotifyService implements Runnable {
        private String service;
        private List<URL> urls;

        public NotifyService(String service, List<URL> urls) {
            this.service = service;
            this.urls = urls;
        }

        @Override
        public void run() {
            ConcurrentHashMap<URL, NotifyListener> listeners = notifyListeners.get(service);
            if (listeners != null) {
                synchronized (listeners) {
                    for (Map.Entry<URL, NotifyListener> entry : listeners.entrySet()) {
                        NotifyListener listener = entry.getValue();
                        listener.notify(getUrl(), urls);
                    }
                }
            } else {
                logger.debug("need not notify service:" + service);
            }
        }
    }

    private ConsulConfig getConsulConfig(){
        return (ConsulConfig)Config.getInstance().getJsonObjectConfig(ConsulConstants.CONFIG_NAME, ConsulConfig.class);
    }

    private String getConsulToken() {
        ConsulConfig consulConfig = getConsulConfig();
        String token = consulConfig.getConsulToken();
        if(token == null) {
            logger.error(new Status(CONFIG_PROPERTY_MISSING, SecretConstants.CONSUL_TOKEN, "consul.yml").toString());
        }
        return token;
    }

}
