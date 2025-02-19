/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.server.master.registry;

import static org.apache.dolphinscheduler.common.Constants.REGISTRY_DOLPHINSCHEDULER_MASTERS;
import static org.apache.dolphinscheduler.common.Constants.REGISTRY_DOLPHINSCHEDULER_NODE;
import static org.apache.dolphinscheduler.common.Constants.SLEEP_TIME_MILLIS;

import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.IStoppable;
import org.apache.dolphinscheduler.common.enums.NodeType;
import org.apache.dolphinscheduler.common.thread.ThreadUtils;
import org.apache.dolphinscheduler.common.utils.NetUtils;
import org.apache.dolphinscheduler.registry.api.RegistryException;
import org.apache.dolphinscheduler.remote.utils.NamedThreadFactory;
import org.apache.dolphinscheduler.server.master.config.MasterConfig;
import org.apache.dolphinscheduler.server.master.service.FailoverService;
import org.apache.dolphinscheduler.server.registry.HeartBeatTask;
import org.apache.dolphinscheduler.service.registry.RegistryClient;

import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.collect.Sets;

/**
 * <p>DolphinScheduler master register client, used to connect to registry and hand the registry events.
 * <p>When the Master node startup, it will register in registry center. And schedule a {@link HeartBeatTask} to update its metadata in registry.
 */
@Component
public class MasterRegistryClient {

    /**
     * logger
     */
    private static final Logger logger = LoggerFactory.getLogger(MasterRegistryClient.class);

    /**
     * failover service
     */
    @Autowired
    private FailoverService failoverService;

    @Autowired
    private RegistryClient registryClient;

    /**
     * master config
     */
    @Autowired
    private MasterConfig masterConfig;

    /**
     * heartbeat executor
     */
    private ScheduledExecutorService heartBeatExecutor;

    /**
     * master startup time, ms
     */
    private long startupTime;
    private String masterAddress;

    public void init() {
        this.masterAddress = NetUtils.getAddr(masterConfig.getListenPort());
        this.startupTime = System.currentTimeMillis();
        this.heartBeatExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("HeartBeatExecutor"));
    }

    public void start() {
        try {
            // master registry
            registry();
            registryClient.addConnectionStateListener(new MasterConnectionStateListener(getCurrentNodePath(), registryClient));
            registryClient.subscribe(REGISTRY_DOLPHINSCHEDULER_NODE, new MasterRegistryDataListener());
        } catch (Exception e) {
            logger.error("master start up exception", e);
            throw new RegistryException("Master registry client start up error", e);
        }
    }

    public void setRegistryStoppable(IStoppable stoppable) {
        registryClient.setStoppable(stoppable);
    }

    public void closeRegistry() {
        // TODO unsubscribe MasterRegistryDataListener
        deregister();
    }

    /**
     * remove master node path
     *
     * @param path node path
     * @param nodeType node type
     * @param failover is failover
     */
    public void removeMasterNodePath(String path, NodeType nodeType, boolean failover) {
        logger.info("{} node deleted : {}", nodeType, path);

        if (StringUtils.isEmpty(path)) {
            logger.error("server down error: empty path: {}, nodeType:{}", path, nodeType);
            return;
        }

        String serverHost = registryClient.getHostByEventDataPath(path);
        if (StringUtils.isEmpty(serverHost)) {
            logger.error("server down error: unknown path: {}, nodeType:{}", path, nodeType);
            return;
        }

        try {
            if (!registryClient.exists(path)) {
                logger.info("path: {} not exists", path);
                // handle dead server
                registryClient.handleDeadServer(Collections.singleton(path), nodeType, Constants.ADD_OP);
            }

            //failover server
            if (failover) {
                failoverService.failoverServerWhenDown(serverHost, nodeType);
            }
        } catch (Exception e) {
            logger.error("{} server failover failed, host:{}", nodeType, serverHost, e);
        }
    }

    /**
     * remove worker node path
     *
     * @param path     node path
     * @param nodeType node type
     * @param failover is failover
     */
    public void removeWorkerNodePath(String path, NodeType nodeType, boolean failover) {
        logger.info("{} node deleted : {}", nodeType, path);
        try {
            String serverHost = null;
            if (!StringUtils.isEmpty(path)) {
                serverHost = registryClient.getHostByEventDataPath(path);
                if (StringUtils.isEmpty(serverHost)) {
                    logger.error("server down error: unknown path: {}", path);
                    return;
                }
                if (!registryClient.exists(path)) {
                    logger.info("path: {} not exists", path);
                    // handle dead server
                    registryClient.handleDeadServer(Collections.singleton(path), nodeType, Constants.ADD_OP);
                }
            }
            //failover server
            if (failover) {
                failoverService.failoverServerWhenDown(serverHost, nodeType);
            }
        } catch (Exception e) {
            logger.error("{} server failover failed", nodeType, e);
        }
    }

    /**
     * Registry the current master server itself to registry.
     */
    void registry() {
        logger.info("master node : {} registering to registry center...", masterAddress);
        String localNodePath = getCurrentNodePath();
        int masterHeartbeatInterval = masterConfig.getHeartbeatInterval();
        HeartBeatTask heartBeatTask = new HeartBeatTask(startupTime,
                masterConfig.getMaxCpuLoadAvg(),
                masterConfig.getReservedMemory(),
                Sets.newHashSet(localNodePath),
                Constants.MASTER_TYPE,
                registryClient);

        // remove before persist
        registryClient.remove(localNodePath);
        registryClient.persistEphemeral(localNodePath, heartBeatTask.getHeartBeatInfo());

        while (!registryClient.checkNodeExists(NetUtils.getHost(), NodeType.MASTER)) {
            logger.warn("The current master server node:{} cannot find in registry....", NetUtils.getHost());
            ThreadUtils.sleep(SLEEP_TIME_MILLIS);
        }

        // sleep 1s, waiting master failover remove
        ThreadUtils.sleep(SLEEP_TIME_MILLIS);

        // delete dead server
        registryClient.handleDeadServer(Collections.singleton(localNodePath), NodeType.MASTER, Constants.DELETE_OP);

        this.heartBeatExecutor.scheduleAtFixedRate(heartBeatTask, 0L, masterHeartbeatInterval, TimeUnit.SECONDS);
        logger.info("master node : {} registry to ZK successfully with heartBeatInterval : {}s", masterAddress, masterHeartbeatInterval);

    }

    public void deregister() {
        try {
            String address = getLocalAddress();
            String localNodePath = getCurrentNodePath();
            registryClient.remove(localNodePath);
            logger.info("master node : {} unRegistry to register center.", address);
            heartBeatExecutor.shutdown();
            logger.info("heartbeat executor shutdown");
            registryClient.close();
        } catch (Exception e) {
            logger.error("remove registry path exception ", e);
        }
    }

    /**
     * get master path
     */
    private String getCurrentNodePath() {
        String address = getLocalAddress();
        return REGISTRY_DOLPHINSCHEDULER_MASTERS + "/" + address;
    }

    /**
     * get local address
     */
    private String getLocalAddress() {
        return NetUtils.getAddr(masterConfig.getListenPort());
    }

}
