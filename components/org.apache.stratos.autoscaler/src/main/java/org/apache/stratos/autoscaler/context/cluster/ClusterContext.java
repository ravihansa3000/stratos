/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.stratos.autoscaler.context.cluster;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.autoscaler.applications.ApplicationHolder;
import org.apache.stratos.autoscaler.context.member.MemberStatsContext;
import org.apache.stratos.autoscaler.context.partition.ClusterLevelPartitionContext;
import org.apache.stratos.autoscaler.context.partition.network.NetworkPartitionContext;
import org.apache.stratos.autoscaler.exception.AutoScalerException;
import org.apache.stratos.autoscaler.exception.partition.PartitionValidationException;
import org.apache.stratos.autoscaler.exception.policy.PolicyValidationException;
import org.apache.stratos.autoscaler.pojo.policy.PolicyManager;
import org.apache.stratos.autoscaler.pojo.policy.autoscale.AutoscalePolicy;
import org.apache.stratos.autoscaler.pojo.policy.deployment.DeploymentPolicy;
import org.apache.stratos.autoscaler.util.AutoscalerObjectConverter;
import org.apache.stratos.autoscaler.util.AutoscalerUtil;
import org.apache.stratos.cloud.controller.stub.domain.MemberContext;
import org.apache.stratos.common.client.CloudControllerServiceClient;
import org.apache.stratos.common.partition.NetworkPartitionRef;
import org.apache.stratos.common.partition.PartitionRef;
import org.apache.stratos.messaging.domain.application.Application;
import org.apache.stratos.messaging.domain.application.ClusterDataHolder;
import org.apache.stratos.messaging.domain.instance.ClusterInstance;
import org.apache.stratos.messaging.domain.topology.Cluster;
import org.apache.stratos.messaging.domain.topology.Member;
import org.apache.stratos.messaging.domain.topology.MemberStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * It holds the runtime data of a VM cluster
 */
public class ClusterContext extends AbstractClusterContext {

    private static final long serialVersionUID = 17570842529682141L;

    private static final Log log = LogFactory.getLog(ClusterContext.class);

    // Map<NetworkpartitionId, Network Partition Context>
    protected Map<String, NetworkPartitionContext> networkPartitionCtxts;

    private String autoscalingPolicyId;

    private String deploymentPolicyId;

    public ClusterContext(String clusterId, String serviceId, String autoscalingPolicyId,
                          boolean hasScalingDependents, String deploymentPolicyId) {

        super(clusterId, serviceId);
        this.networkPartitionCtxts = new ConcurrentHashMap<String, NetworkPartitionContext>();
        this.autoscalingPolicyId = autoscalingPolicyId;
        this.deploymentPolicyId = deploymentPolicyId;
    }

    public Map<String, NetworkPartitionContext> getNetworkPartitionCtxts() {
        return networkPartitionCtxts;
    }

    public AutoscalePolicy getAutoscalePolicy() {
        return PolicyManager.getInstance().getAutoscalePolicy(autoscalingPolicyId);
    }

    public NetworkPartitionContext getNetworkPartitionCtxt(String networkPartitionId) {
        return networkPartitionCtxts.get(networkPartitionId);
    }

    public void setPartitionCtxt(Map<String, NetworkPartitionContext> partitionCtxt) {
        this.networkPartitionCtxts = partitionCtxt;
    }

    public boolean partitionCtxtAvailable(String partitionId) {
        return networkPartitionCtxts.containsKey(partitionId);
    }

    public void addNetworkPartitionCtxt(NetworkPartitionContext ctxt) {
        this.networkPartitionCtxts.put(ctxt.getId(), ctxt);
    }

    public NetworkPartitionContext getPartitionCtxt(String id) {
        return this.networkPartitionCtxts.get(id);
    }

    public NetworkPartitionContext getNetworkPartitionCtxt(Member member) {

        String networkPartitionId = member.getNetworkPartitionId();
        if (networkPartitionCtxts.containsKey(networkPartitionId)) {

            return networkPartitionCtxts.get(networkPartitionId);
        }
        return null;
    }

    public void addInstanceContext(String instanceId, Cluster cluster, boolean hasScalingDependents,
                                   boolean groupScalingEnabledSubtree)
            throws PolicyValidationException, PartitionValidationException {
        NetworkPartitionContext networkPartitionContext = null;
        ClusterInstance clusterInstance = cluster.getInstanceContexts(instanceId);

        String deploymentPolicyName = AutoscalerUtil.getDeploymentPolicyIdByAlias(cluster.getAppId(),
                AutoscalerUtil.getAliasFromClusterId(clusterId));
        DeploymentPolicy deploymentPolicy = PolicyManager.getInstance().
                getDeploymentPolicy(deploymentPolicyName);

        if (networkPartitionCtxts.containsKey(clusterInstance.getNetworkPartitionId())) {
            networkPartitionContext = this.networkPartitionCtxts.get(
                    clusterInstance.getNetworkPartitionId());
        } else {

            NetworkPartitionRef[] networkPartitions = deploymentPolicy.getNetworkPartitionRefs();
            NetworkPartitionRef networkPartition = null;
            if (networkPartitions != null && networkPartitions.length != 0) {
                for (NetworkPartitionRef i : networkPartitions) {
                    if (i.getId().equals(clusterInstance.getNetworkPartitionId())) {
                        networkPartition = i;
                    }
                }
            }

            if (networkPartition == null) {
                //Parent should have the partition specified
                networkPartitionContext = new NetworkPartitionContext(
                        clusterInstance.getNetworkPartitionId());
            } else {
                networkPartitionContext = new NetworkPartitionContext(networkPartition.getId(),
                        networkPartition.getPartitionAlgo());
            }
        }

        if (clusterInstance.getPartitionId() != null) {
            //Need to add partition Context based on the given one from the parent
            networkPartitionContext = addPartition(clusterInstance, cluster,
                    networkPartitionContext, null, hasScalingDependents, groupScalingEnabledSubtree);
        } else {
            networkPartitionContext = parseDeploymentPolicy(clusterInstance, cluster,
                    networkPartitionContext, hasScalingDependents, groupScalingEnabledSubtree);
        }
        if (!networkPartitionCtxts.containsKey(clusterInstance.getNetworkPartitionId())) {
            this.networkPartitionCtxts.put(clusterInstance.getNetworkPartitionId(),
                    networkPartitionContext);
            if (log.isInfoEnabled()) {
                log.info(String.format("Cluster instance context has been added to network partition," +
                                " [application] %s [cluster] %s  [cluster-instance] %s " +
                                "[network partition] %s", cluster.getAppId(), cluster.getClusterId(),
                        clusterInstance.getInstanceId(),
                        clusterInstance.getNetworkPartitionId()));
            }
        }

    }

    private NetworkPartitionContext parseDeploymentPolicy(
            ClusterInstance clusterInstance,
            Cluster cluster,
            NetworkPartitionContext clusterLevelNetworkPartitionContext,
            boolean hasGroupScalingDependent, boolean groupScalingEnabledSubtree)
            throws PolicyValidationException, PartitionValidationException {

        String deploymentPolicyName = AutoscalerUtil.getDeploymentPolicyIdByAlias(cluster.getAppId(),
                AutoscalerUtil.getAliasFromClusterId(clusterId));
        DeploymentPolicy deploymentPolicy = PolicyManager.getInstance().
                getDeploymentPolicy(deploymentPolicyName);


        NetworkPartitionRef[] networkPartitions = deploymentPolicy
                .getNetworkPartitionRefs();
        PartitionRef[] partitions = null;
        if (networkPartitions != null && networkPartitions.length != 0) {
            for (NetworkPartitionRef networkPartitionRef : networkPartitions) {
                if (networkPartitionRef.getId().equals(
                        clusterLevelNetworkPartitionContext.getId())) {
                    partitions = networkPartitionRef.getPartitionRefs();
                }
            }
        }

        if (partitions == null) {
            String msg = "Partitions are null in deployment policy for [application] " +
                    cluster.getAppId() + " [cluster-alias] "
                    + AutoscalerUtil.getAliasFromClusterId(clusterId);
            log.error(msg);
            throw new PolicyValidationException(msg);
        }

        // Retrieving the ChildLevelNetworkPartition and create NP Context
        NetworkPartitionRef networkPartitionRef = null;
        if (networkPartitions != null && networkPartitions.length != 0) {
            for (NetworkPartitionRef networkPartition2 : networkPartitions) {
                if (networkPartition2.getId().equals(
                        clusterInstance.getNetworkPartitionId())) {
                    networkPartitionRef = networkPartition2;
                }
            }
        }

        // Fill cluster instance context with child level partitions
        if (networkPartitionRef != null) {
            for (PartitionRef partitionRef : networkPartitionRef
                    .getPartitionRefs()) {
                addPartition(clusterInstance, cluster,
                        clusterLevelNetworkPartitionContext, partitionRef,
                        hasGroupScalingDependent, groupScalingEnabledSubtree);
            }
        }

        return clusterLevelNetworkPartitionContext;
    }

    private NetworkPartitionContext addPartition(
            ClusterInstance clusterInstance,
            Cluster cluster,
            NetworkPartitionContext clusterLevelNetworkPartitionContext,
            PartitionRef partition,
            boolean hasScalingDependents, boolean groupScalingEnabledSubtree)
            throws PolicyValidationException, PartitionValidationException {

        if (clusterLevelNetworkPartitionContext == null) {
            String msg = "Network Partition is null in deployment policy :  [application]" +
                    cluster.getAppId() + "[cluster-alias]: " +
                    clusterInstance.getAlias();
            log.error(msg);
            throw new PolicyValidationException(msg);
        }

        String nPartitionId = clusterLevelNetworkPartitionContext.getId();

        //Getting the associated  partition
        if (clusterInstance.getPartitionId() == null && partition == null) {
            String msg = "[Partition] " + clusterInstance.getPartitionId() + " for [application] " +
                    cluster.getAppId() + " [networkPartition] " +
                    clusterInstance.getNetworkPartitionId() + "is null " +
                    "in deployment policy: [cluster-alias]: " + clusterInstance.getAlias();
            log.error(msg);
            throw new PolicyValidationException(msg);
        }

        ClusterInstanceContext clusterInstanceContext =
                (ClusterInstanceContext) clusterLevelNetworkPartitionContext.
                        getInstanceContext(clusterInstance.getInstanceId());
        int maxInstances = 1;
        if (clusterInstanceContext == null) {
            int minInstances = 1;
            try {
                ApplicationHolder.acquireReadLock();
                Application application = ApplicationHolder.getApplications().
                        getApplication(cluster.getAppId());
                ClusterDataHolder dataHolder = application.
                        getClusterDataHolderRecursivelyByAlias(
                                AutoscalerUtil.getAliasFromClusterId(clusterId));
                minInstances = dataHolder.getMinInstances();
                maxInstances = dataHolder.getMaxInstances();
            } finally {
                ApplicationHolder.releaseReadLock();
            }
            clusterInstanceContext = new ClusterInstanceContext(clusterInstance.getInstanceId(),
                    clusterLevelNetworkPartitionContext.getPartitionAlgorithm(),
                    minInstances, maxInstances, nPartitionId, clusterId, hasScalingDependents,
                    groupScalingEnabledSubtree);
        }
        String partitionId;
        if (partition != null) {
            //use it own defined partition
            partitionId = partition.getId();
            maxInstances = partition.getPartitionMax();
        } else {
            //handling the partition given by the parent
            partitionId = clusterInstance.getPartitionId();
        }

        //Retrieving the actual partition from application
        PartitionRef[] partitions;
        try {

            partitions = AutoscalerObjectConverter.convertCCPartitionsToPartitions(
                    CloudControllerServiceClient.getInstance().
                            getNetworkPartition(nPartitionId).getPartitions());
        } catch (Exception e) {
            String msg = String.format("Error while getting network partitioin from cloud controller " +
                    ": [application] %s [network-partition-id] %s", cluster.getAppId(), nPartitionId);
            log.error(msg, e);
            throw new AutoScalerException(msg, e);
        }

        PartitionRef partition3 = null;
        if (partitions != null && partitions.length != 0) {
            for (PartitionRef partition2 : partitions) {
                if (partition2.getId().equals(partitionId)) {
                    partition3 = partition2;
                }
            }
        }

        //Creating cluster level partition context
        ClusterLevelPartitionContext clusterLevelPartitionContext = new ClusterLevelPartitionContext(
                partition3,
                clusterInstance.getNetworkPartitionId(), this.deploymentPolicyId);
        clusterLevelPartitionContext.setServiceName(cluster.getServiceName());
        clusterLevelPartitionContext.setProperties(cluster.getProperties());

        //add members to partition Context
        addMembersFromTopology(cluster, partition3, clusterLevelPartitionContext,
                clusterInstanceContext.getId());

        //adding it to the monitors context
        clusterInstanceContext.addPartitionCtxt(clusterLevelPartitionContext);
        if (log.isInfoEnabled()) {
            log.info(String.format("Partition context has been added: [application] %s  [cluster] %s " +
                            "[ClusterInstanceContext] %s [partition] %s", cluster.getAppId(),
                    cluster.getClusterId(), clusterInstanceContext.getId(),
                    clusterLevelPartitionContext.getPartitionId()));
        }

        clusterLevelNetworkPartitionContext.addInstanceContext(clusterInstanceContext);

        if (log.isInfoEnabled()) {
            log.info(String.format("Cluster Instance context has been added: [application] %s " +
                            "[cluster] %s [ClusterInstanceContext] %s", cluster.getAppId(),
                    cluster.getClusterId(), clusterInstanceContext.getId()));
        }

        return clusterLevelNetworkPartitionContext;
    }


    private void addMembersFromTopology(Cluster cluster,
                                        PartitionRef partition,
                                        ClusterLevelPartitionContext clusterLevelPartitionContext,
                                        String ClusterInstanceId) {
        for (Member member : cluster.getMembers()) {
            String memberId = member.getMemberId();
            if (member.getPartitionId().equalsIgnoreCase(partition.getId()) &&
                    member.getClusterInstanceId().equals(ClusterInstanceId)) {
                MemberContext memberContext = new MemberContext();
                memberContext.setClusterId(member.getClusterId());
                memberContext.setMemberId(memberId);
                memberContext.setInitTime(member.getInitTime());
                memberContext.setPartition(AutoscalerObjectConverter.convertPartitionToCCPartition(partition));
                memberContext.setProperties(AutoscalerUtil.toStubProperties(member.getProperties()));

                if (MemberStatus.Active.equals(member.getStatus()) || MemberStatus.Suspended.equals(member.getStatus())) {
                    clusterLevelPartitionContext.addActiveMember(memberContext);
                    if (log.isDebugEnabled()) {
                        String msg = String.format("Active or suspended member read from topology and added " +
                                        "to active member list: [application] %s [cluster] %s " +
                                        "[clusterInstanceContext] %s [partitionContext] %s [member-id] %s",
                                cluster.getAppId(), cluster.getClusterId(), ClusterInstanceId,
                                clusterLevelPartitionContext.getPartitionId(), member.toString());
                        log.debug(msg);
                    }

                    clusterLevelPartitionContext.addMemberStatsContext(new MemberStatsContext(memberId));
                    if (log.isInfoEnabled()) {
                        log.info(String.format("Member stat context has been added: [application] %s " +
                                        "[cluster] %s [clusterInstanceContext] %s [partitionContext] %s [member-id] %s",
                                cluster.getAppId(), cluster.getClusterId(), ClusterInstanceId,
                                clusterLevelPartitionContext.getPartitionId(), memberId));
                    }
                } else if (MemberStatus.Created.equals(member.getStatus())
                        || MemberStatus.Starting.equals(member.getStatus())) {
                    clusterLevelPartitionContext.addPendingMember(memberContext);
                    if (log.isDebugEnabled()) {
                        String msg = String.format("Created or starting member read from topology and added to " +
                                        "pending member list: [application] %s [cluster] %s " +
                                        "[clusterInstanceContext] %s [partitionContext] %s [member-id] %s",
                                cluster.getAppId(), cluster.getClusterId(), ClusterInstanceId,
                                clusterLevelPartitionContext.getPartitionId(), member.toString());
                        log.debug(msg);
                    }
                } else if (MemberStatus.In_Maintenance.equals(member.getStatus())) {
                    clusterLevelPartitionContext.addTerminationPendingMember(memberContext);
                    if (log.isDebugEnabled()) {
                        String msg = String.format("In maintenance member is read from topology " +
                                        "and added to termination pending member list: [application] %s [cluster] %s " +
                                        "[clusterInstanceContext] %s [partitionContext] %s [member-id] %s",
                                cluster.getAppId(), cluster.getClusterId(), ClusterInstanceId,
                                clusterLevelPartitionContext.getPartitionId(), member.toString());
                        log.debug(msg);
                    }
                } else if (MemberStatus.ReadyToShutDown.equals(member.getStatus())) {
                    clusterLevelPartitionContext.addObsoleteMember(memberContext);
                    if (log.isDebugEnabled()) {
                        String msg = String.format("Ready to shutdown member is read from topology " +
                                        "and added to obsolete member list: [application] %s [cluster] %s " +
                                        "[clusterInstanceContext] %s [partitionContext] %s [member-id] %s",
                                cluster.getAppId(), cluster.getClusterId(), ClusterInstanceId,
                                clusterLevelPartitionContext.getPartitionId(), member.toString());
                        log.debug(msg);
                    }
                } else if (MemberStatus.Terminated.equals(member.getStatus())) {
                    if (log.isDebugEnabled()) {
                        String msg = String.format("Terminated member is read from topology ignored without adding to " +
                                        "Autoscaler contexts: [application] %s [cluster] %s [clusterInstanceContext] %s" +
                                        " [partitionContext] %s [member-id] %s",
                                cluster.getAppId(), cluster.getClusterId(), ClusterInstanceId,
                                clusterLevelPartitionContext.getPartitionId(), member.toString());
                        log.debug(msg);
                    }
                }
            }
        }
    }
}
