/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.stratos.autoscaler.status.processor.cluster;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.autoscaler.context.AutoscalerContext;
import org.apache.stratos.autoscaler.context.cluster.ClusterInstanceContext;
import org.apache.stratos.autoscaler.context.partition.network.NetworkPartitionContext;
import org.apache.stratos.autoscaler.event.publisher.ClusterStatusEventPublisher;
import org.apache.stratos.autoscaler.monitor.cluster.ClusterMonitor;
import org.apache.stratos.autoscaler.status.processor.StatusProcessor;

/**
 * Cluster inactive checking processor
 */
public class ClusterStatusInactiveProcessor extends ClusterStatusProcessor {
    private static final Log log = LogFactory.getLog(ClusterStatusInactiveProcessor.class);
    private ClusterStatusProcessor nextProcessor;

    @Override
    public void setNext(StatusProcessor nextProcessor) {
        this.nextProcessor = (ClusterStatusProcessor) nextProcessor;
    }

    @Override
    public boolean process(String type, String clusterId, String instanceId) {
        boolean statusChanged;
        if (type == null || (ClusterStatusInactiveProcessor.class.getName().equals(type))) {
            statusChanged = doProcess(clusterId, instanceId);
            if (statusChanged) {
                return true;
            }

        } else {
            if (nextProcessor != null) {
                // ask the next processor to take care of the message.
                return nextProcessor.process(type, clusterId, instanceId);
            } else {

                log.warn(String.format("No possible state change found for [type] %s [cluster] %s " +
                        "[instance] %s", type, clusterId, instanceId));
            }
        }
        return false;
    }

    private boolean doProcess(String clusterId, String instanceId) {
        ClusterMonitor monitor = AutoscalerContext.getInstance().
                getClusterMonitor(clusterId);

        boolean clusterInactive;
        clusterInactive = getClusterInactive(instanceId, monitor);
        if (clusterInactive) {
            //if the monitor is dependent, temporarily pausing it
            if (monitor.hasStartupDependents()) {
                monitor.setHasFaultyMember(true);
            }
            if (log.isInfoEnabled()) {
                log.info("Publishing Cluster inactivate event for [application]: "
                        + monitor.getAppId() + " [cluster]: " + clusterId);
            }
            //send cluster In-Active event to cluster status topic
            ClusterStatusEventPublisher.sendClusterInactivateEvent(monitor.getAppId(),
                    monitor.getServiceId(), clusterId, instanceId);
        }
        return clusterInactive;
    }

    private boolean getClusterInactive(String instanceId, ClusterMonitor monitor) {
        boolean clusterInactive = false;
        for (NetworkPartitionContext clusterLevelNetworkPartitionContext :
                monitor.getAllNetworkPartitionCtxts().values()) {
            ClusterInstanceContext instanceContext =
                    (ClusterInstanceContext) clusterLevelNetworkPartitionContext.
                            getInstanceContext(instanceId);
            if (instanceContext != null) {
                if (instanceContext.getActiveMembers() < instanceContext.getMinInstanceCount()) {
                    clusterInactive = true;
                }
                break;
            }
        }
        return clusterInactive;
    }
}
