/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.stratos.common.statistics.publisher;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.databridge.agent.DataPublisher;
import org.wso2.carbon.databridge.commons.Event;
import org.wso2.carbon.databridge.commons.StreamDefinition;

import java.util.HashMap;
import java.util.List;

/**
 * Thrift statistics publisher.
 */
public class ThriftStatisticsPublisher implements StatisticsPublisher {

    private static final Log log = LogFactory.getLog(ThriftStatisticsPublisher.class);

    private StreamDefinition streamDefinition;
    private DataPublisher dataPublisher;
    private List<ThriftClientInfo> thriftClientInfoList;
    private boolean enabled = false;
    private String username;
    private String password;

    /**
     * Credential information stored inside thrift-client-config.xml file
     * is parsed and assigned into ip,port,username and password fields
     *
     * @param streamDefinition Thrift Event Stream Definition
     * @param thriftClientName Thrift Client Name
     */
    public ThriftStatisticsPublisher(StreamDefinition streamDefinition, String thriftClientName) {
        ThriftClientConfig thriftClientConfig = ThriftClientConfig.getInstance();
        this.thriftClientInfoList = thriftClientConfig.getThriftClientInfo(thriftClientName);
        this.streamDefinition = streamDefinition;

        if (isPublisherEnabled()) {
            this.enabled = true;
            init();
        }
    }

    private boolean isPublisherEnabled() {
        boolean publisherEnabled = false;
        for (ThriftClientInfo thriftClientInfo : thriftClientInfoList) {
            publisherEnabled = thriftClientInfo.isStatsPublisherEnabled();
            if (publisherEnabled) {
                break;
            }
        }
        return publisherEnabled;
    }

    private void init() {
        // Initialize load balancing data publisher
        try {
            dataPublisher = new DataPublisher(getReceiverURLSet(), username, password);
        } catch (Exception e) {
            throw new RuntimeException("Could not create data publisher.");
        }
    }

    private String getReceiverURLSet() {
        StringBuilder stringBuilder = new StringBuilder();
        for (ThriftClientInfo thriftClientInfo : thriftClientInfoList) {
            stringBuilder.append(buildUrl(thriftClientInfo));
            username = thriftClientInfo.getUsername();
            password = thriftClientInfo.getPassword();
        }
        return stringBuilder.toString();
    }

    private String buildUrl(ThriftClientInfo thriftClientInfo) {
        return new StringBuilder().append("tcp://").append(thriftClientInfo.getIp()).append(":")
                .append(thriftClientInfo.getPort()).toString();
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (this.enabled) {
            init();
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void publish(Object[] payload) {
        if (!isEnabled()) {
            throw new RuntimeException("Statistics publisher is not enabled");
        }

        Event event = new Event();
        event.setPayloadData(payload);
        event.setArbitraryDataMap(new HashMap<String, String>());
        event.setStreamId(streamDefinition.getStreamId());

        if (log.isDebugEnabled()) {
            log.debug(String.format("Publishing thrift event: [stream] %s [version] %s", streamDefinition.getName(),
                    streamDefinition.getVersion()));
        }

        if (!dataPublisher.tryPublish(event)) {
            throw new RuntimeException("Data publisher rejected event.");
        }
    }
}
