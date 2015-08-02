/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.stratos.autoscaler.util;

import org.apache.axiom.om.OMElement;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.autoscaler.applications.ApplicationHolder;
import org.apache.stratos.autoscaler.applications.dependency.context.ApplicationChildContext;
import org.apache.stratos.autoscaler.applications.dependency.context.ClusterChildContext;
import org.apache.stratos.autoscaler.applications.dependency.context.GroupChildContext;
import org.apache.stratos.autoscaler.applications.pojo.ApplicationContext;
import org.apache.stratos.autoscaler.applications.pojo.CartridgeContext;
import org.apache.stratos.autoscaler.applications.pojo.ComponentContext;
import org.apache.stratos.autoscaler.applications.pojo.GroupContext;
import org.apache.stratos.autoscaler.context.AutoscalerContext;
import org.apache.stratos.autoscaler.context.InstanceContext;
import org.apache.stratos.autoscaler.context.cluster.ClusterInstanceContext;
import org.apache.stratos.autoscaler.context.partition.network.NetworkPartitionContext;
import org.apache.stratos.autoscaler.exception.AutoScalerException;
import org.apache.stratos.autoscaler.exception.application.*;
import org.apache.stratos.autoscaler.exception.policy.ApplicatioinPolicyNotExistsException;
import org.apache.stratos.autoscaler.exception.policy.PolicyValidationException;
import org.apache.stratos.autoscaler.monitor.Monitor;
import org.apache.stratos.autoscaler.monitor.MonitorFactory;
import org.apache.stratos.autoscaler.monitor.cluster.ClusterMonitor;
import org.apache.stratos.autoscaler.monitor.component.ApplicationMonitor;
import org.apache.stratos.autoscaler.monitor.component.GroupMonitor;
import org.apache.stratos.autoscaler.pojo.policy.PolicyManager;
import org.apache.stratos.autoscaler.pojo.policy.deployment.ApplicationPolicy;
import org.apache.stratos.autoscaler.pojo.policy.deployment.DeploymentPolicy;
import org.apache.stratos.autoscaler.registry.RegistryManager;
import org.apache.stratos.common.Properties;
import org.apache.stratos.common.Property;
import org.apache.stratos.common.client.CloudControllerServiceClient;
import org.apache.stratos.common.constants.StratosConstants;
import org.apache.stratos.common.partition.NetworkPartitionRef;
import org.apache.stratos.messaging.domain.application.Application;
import org.apache.stratos.messaging.domain.application.Applications;
import org.apache.stratos.messaging.domain.application.ClusterDataHolder;
import org.apache.stratos.messaging.domain.application.Group;
import org.apache.stratos.messaging.domain.topology.Service;
import org.apache.stratos.messaging.domain.topology.Topology;
import org.apache.stratos.messaging.message.receiver.topology.TopologyManager;

import javax.xml.namespace.QName;
import java.rmi.RemoteException;
import java.util.*;


/**
 * This class contains utility methods used by Autoscaler.
 */
public class AutoscalerUtil {

    private static final Log log = LogFactory.getLog(AutoscalerUtil.class);

    private AutoscalerUtil() {
    }

    public static AutoscalerUtil getInstance() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        private static final AutoscalerUtil INSTANCE = new AutoscalerUtil();
    }

    public static Applications getApplications() {

        Applications applications;
        String[] appResourcePaths = RegistryManager.getInstance().getApplicationResourcePaths();
        if (appResourcePaths != null) {
            applications = new Applications();
            for (String appResourcePath : appResourcePaths) {
                applications.addApplication(getApplicationFromPath(appResourcePath));
            }

            return applications;
        }

        return null;
    }

    public static Application getApplication(String appId) {
        return getApplicationFromPath(AutoscalerConstants.AUTOSCALER_RESOURCE +
                AutoscalerConstants.APPLICATIONS_RESOURCE + "/" + appId);
    }

    public static void persistApplication(Application application) {
        RegistryManager.getInstance().persistApplication(application);
    }

    private static Application getApplicationFromPath(String appResourcePath) {
        return RegistryManager.getInstance().getApplicationByResourcePath(appResourcePath);
    }

    public static void removeApplication(String applicationId) {
        RegistryManager.getInstance().removeApplication(applicationId);
    }

    //TODO we need to make sure that application id or cartridge alias should not have "."
    public static String getAliasFromClusterId(String clusterId) {
        return StringUtils.substringBefore(StringUtils.substringAfter(clusterId, "."), ".");
    }

    public static boolean allClustersInitialized(Application application) {
        boolean allClustersInitialized = false;
        for (ClusterDataHolder holder : application.getClusterDataRecursively()) {
            TopologyManager.acquireReadLockForCluster(holder.getServiceType(),
                    holder.getClusterId());

            try {
                Topology topology = TopologyManager.getTopology();
                if (topology != null) {
                    Service service = topology.getService(holder.getServiceType());
                    if (service != null) {
                        //If one cluster of the application presents,
                        // then we can assume that all there clusters are there
                        // as we receive ApplicationClustersCreatedEvent with all the clusters.
                        if (service.clusterExists(holder.getClusterId())) {
                            allClustersInitialized = true;
                            return allClustersInitialized;
                        } else {
                            if (log.isWarnEnabled()) {
                                log.warn(String.format("Cluster not found in service: [service] %s [cluster] %s",
                                        holder.getServiceType(), holder.getClusterId()));
                            }
                            allClustersInitialized = false;
                        }
                    } else {
                        if (log.isWarnEnabled()) {
                            log.warn(String.format("Service not found in topology: [service] %s",
                                    holder.getServiceType()));
                        }
                    }
                } else {
                    if (log.isWarnEnabled()) {
                        log.warn("Topology not found in topology manager");
                    }
                }
            } finally {
                TopologyManager.releaseReadLockForCluster(holder.getServiceType(),
                        holder.getClusterId());
            }
        }
        return allClustersInitialized;
    }

    public static Properties getProperties(final OMElement elt) {

        Iterator<?> it = elt.getChildrenWithName(new QName(AutoscalerConstants.PROPERTY_ELEMENT));
        ArrayList<Property> propertyList = new ArrayList<Property>();

        while (it.hasNext()) {
            OMElement prop = (OMElement) it.next();

            if (prop.getAttribute(new QName(AutoscalerConstants.PROPERTY_NAME_ATTR)) == null ||
                    prop.getAttribute(new QName(AutoscalerConstants.PROPERTY_VALUE_ATTR)) == null) {

                String msg =
                        "Property element's, name and value attributes should be specified. "
                                + "Property: ";
                log.warn(msg + prop.toString());

            }

            String name =
                    prop.getAttribute(new QName(AutoscalerConstants.PROPERTY_NAME_ATTR))
                            .getAttributeValue();
            String value =
                    prop.getAttribute(new QName(AutoscalerConstants.PROPERTY_VALUE_ATTR))
                            .getAttributeValue();

            Property property = new Property();
            property.setName(name);
            property.setValue(value);
            propertyList.add(property);
        }

        if (propertyList.isEmpty()) {
            return null;
        }

        Property[] propertyArray = propertyList.toArray(new Property[propertyList.size()]);
        Properties properties = new Properties();
        properties.setProperties(propertyArray);
        return properties;
    }

    public static org.apache.stratos.cloud.controller.stub.Properties toStubProperties(
            org.apache.stratos.common.Properties properties) {
        org.apache.stratos.cloud.controller.stub.Properties stubProps = new org.apache.stratos.cloud.controller.stub.Properties();

        if (properties != null && properties.getProperties() != null) {
            for (Property property : properties.getProperties()) {
                if ((property != null) && (property.getValue() != null)) {
                    org.apache.stratos.cloud.controller.stub.Property newProperty = new org.apache.stratos.cloud.controller.stub.Property();
                    newProperty.setName(property.getName());
                    newProperty.setValue(property.getValue());
                    stubProps.addProperties(newProperty);
                }
            }

        }
        return stubProps;
    }

    public static org.apache.stratos.cloud.controller.stub.Properties toStubProperties(
            java.util.Properties properties) {
        org.apache.stratos.cloud.controller.stub.Properties stubProperties = new org.apache.stratos.cloud.controller.stub.Properties();
        if (properties != null) {
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                org.apache.stratos.cloud.controller.stub.Property newProperty = new org.apache.stratos.cloud.controller.stub.Property();
                newProperty.setName(entry.getKey().toString());
                newProperty.setValue(entry.getValue().toString());
                stubProperties.addProperties(newProperty);
            }
        }
        return stubProperties;
    }

    public static org.apache.stratos.common.Properties toCommonProperties(
            org.apache.stratos.cloud.controller.stub.Properties properties) {
        org.apache.stratos.common.Properties commonProps = new org.apache.stratos.common.Properties();

        if (properties != null && properties.getProperties() != null) {

            for (org.apache.stratos.cloud.controller.stub.Property property : properties.getProperties()) {
                if ((property != null) && (property.getValue() != null)) {
                    Property newProperty = new Property();
                    newProperty.setName(property.getName());
                    newProperty.setValue(property.getValue());
                    commonProps.addProperty(newProperty);
                }
            }

        }

        return commonProps;
    }

    public static org.apache.stratos.common.Properties toCommonProperties(
            org.apache.stratos.cloud.controller.stub.Property[] propertyArray) {

        org.apache.stratos.cloud.controller.stub.Properties properties = new org.apache.stratos.cloud.controller.stub.Properties();
        properties.setProperties(propertyArray);
        return toCommonProperties(properties);
    }

    public synchronized void startApplicationMonitor(String applicationId) {

        AutoscalerContext autoscalerContext = AutoscalerContext.getInstance();
        if (autoscalerContext.getAppMonitor(applicationId) == null) {
            autoscalerContext.addApplicationPendingMonitor(applicationId);
            ServiceReferenceHolder.getInstance().getExecutorService().submit(new ApplicationMonitorAdder(applicationId));

            log.info(String.format("Monitor scheduled: [application] %s ", applicationId));
        } else {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Application monitor thread already exists: " +
                        "[application] %s ", applicationId));
            }
        }
    }

    private class ApplicationMonitorAdder implements Runnable {
        private String applicationId;

        public ApplicationMonitorAdder(String applicationId) {
            this.applicationId = applicationId;
        }

        public void run() {
            long startTime = System.currentTimeMillis();
            long endTime = startTime;
            int retries = 5;
            boolean success = false;
            ApplicationMonitor applicationMonitor = null;
            while (!success && retries != 0) {

                try {
                    startTime = System.currentTimeMillis();
                    log.info("Starting monitor: [application] " + applicationId);
                    try {
                        applicationMonitor = MonitorFactory.getApplicationMonitor(applicationId);
                    } catch (PolicyValidationException e) {
                        String msg = "Monitor creation failed: [application] " + applicationId;
                        log.warn(msg, e);
                        retries--;
                    }
                    success = true;
                    endTime = System.currentTimeMillis();
                } catch (DependencyBuilderException e) {
                    String msg = "Monitor creation failed: [application] " + applicationId;
                    log.warn(msg, e);
                    retries--;
                } catch (TopologyInConsistentException e) {
                    String msg = "Monitor creation failed: [application] " + applicationId;
                    log.warn(msg, e);
                    retries--;
                }
            }

            if (applicationMonitor == null) {
                String msg = "Monitor creation failed, even after retrying for 5 times: "
                        + "[application] " + applicationId;
                log.error(msg);
                throw new RuntimeException(msg);
            }
            AutoscalerContext autoscalerContext = AutoscalerContext.getInstance();
            autoscalerContext.removeApplicationPendingMonitor(applicationId);
            autoscalerContext.removeAppMonitor(applicationId);
            autoscalerContext.addAppMonitor(applicationMonitor);

            long startupTime = ((endTime - startTime) / 1000);
            if (log.isInfoEnabled()) {
                log.info(String.format("Monitor started successfully: [application] %s [dependents] %s " +
                                "[startup-time] %d seconds", applicationMonitor.getId(),
                        applicationMonitor.getStartupDependencyTree(), startupTime));
            }
        }
    }

    public static Monitor.MonitorType findMonitorType(ApplicationChildContext context) {
        if (context instanceof GroupChildContext) {
            return Monitor.MonitorType.Group;
        } else if (context instanceof ClusterChildContext) {
            return Monitor.MonitorType.Cluster;
        } else {
            throw new RuntimeException("Unknown child context type: " + context.getClass().getName());
        }
    }

    public static String findTenantRange(int tenantId, String tenantPartitions) {
        if (StringUtils.isNotBlank(tenantPartitions)) {
            String[] tenantRanges = tenantPartitions.trim().split(",");
            if (tenantRanges != null) {
                for (String tenantRange : tenantRanges) {
                    if ((tenantRange != null) && (tenantRange.contains("-"))) {
                        String[] tenantValues = tenantRange.trim().split("-");
                        if ((tenantValues != null) && (tenantValues.length == 2)) {
                            if ((!tenantValues[0].equals("*")) && (!tenantValues[1].equals("*"))) {
                                int startValue = Integer.parseInt(tenantValues[0]);
                                int endValue = Integer.parseInt(tenantValues[1]);
                                if ((tenantId >= startValue) && (tenantId <= endValue)) {
                                    return tenantRange;
                                }
                            } else if ((!tenantValues[0].equals("*")) && (tenantValues[1].equals("*"))) {
                                int startValue = Integer.parseInt(tenantValues[0]);
                                if (tenantId >= startValue) {
                                    return tenantRange;
                                }
                            }
                        }
                    }
                }
            }
        }
        return "*";
    }

    /**
     * Get network partition ids referred in an application. Network partition ids are not referred directly.
     * Cartridge or cartridge group can refer deployment policy which has network partition references.
     *
     * @param applicationId the application id
     * @return list of network partition ids
     */
    public static List<String> getNetworkPartitionIdsReferedInApplication(String applicationId) {

        List<String> deploymentPolicyIdsReferedInApplication = getDeploymentPolicyIdsReferredInApplication(applicationId);
        if (deploymentPolicyIdsReferedInApplication == null) {
            return null;
        }

        List<String> networkPartitionIds = new ArrayList<String>();
        for (String deploymentPolicyId : deploymentPolicyIdsReferedInApplication) {
            try {
                DeploymentPolicy deploymentPolicy = PolicyManager.getInstance().getDeploymentPolicy(deploymentPolicyId);
                if (deploymentPolicy != null) {
                    for (NetworkPartitionRef networkPartition : deploymentPolicy.getNetworkPartitionRefs()) {
                        if (networkPartition != null) {
                            if (!networkPartitionIds.contains(networkPartition.getId())) {
                                networkPartitionIds.add(networkPartition.getId());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                String msg = String.format("Error while getting deployment policy from cloud controller [deployment-policy-id] %s ", deploymentPolicyId);
                log.error(msg, e);
                throw new AutoScalerException(msg, e);
            }
        }
        return networkPartitionIds;
    }

    /**
     * Get deployment policy ids referred in an application.
     *
     * @param applicationId the application id
     * @return list of deployment policy ids
     */
    public static List<String> getDeploymentPolicyIdsReferredInApplication(String applicationId) {

        if (applicationId == null || StringUtils.isBlank(applicationId)) {
            return null;
        }

        Application application = ApplicationHolder.getApplications().getApplication(applicationId);
        if (application == null) {
            return null;
        }

        Map<String, String> aliasToDeploymentPolicyIdMap = application.getAliasToDeploymentPolicyIdMap();
        if (aliasToDeploymentPolicyIdMap == null) {
            return null;
        }

        List<String> deploymentPolicyIds = new ArrayList<String>();

        for (Map.Entry<String, String> entry : aliasToDeploymentPolicyIdMap.entrySet()) {
            if (!deploymentPolicyIds.contains(entry.getValue())) {
                deploymentPolicyIds.add(entry.getValue());
            }
        }

        return deploymentPolicyIds;
    }

    /**
     * Get deployment policy id of an alias in the given application
     *
     * @param applicationId the application id
     * @param alias         the cartridge or cartridge-group alias
     * @return the deployment policy id if found, null otherwise
     */
    public static String getDeploymentPolicyIdByAlias(String applicationId, String alias) {

        if (alias == null || StringUtils.isBlank(alias)) {
            return null;
        }

        if (applicationId == null || StringUtils.isBlank(applicationId)) {
            return null;
        }

        Application application = ApplicationHolder.getApplications().getApplication(applicationId);
        if (application == null) {
            return null;
        }

        Map<String, String> aliasToDeploymentPolicyIdMap = application.getAliasToDeploymentPolicyIdMap();

        if (aliasToDeploymentPolicyIdMap == null) {
            return null;
        }

        return aliasToDeploymentPolicyIdMap.get(alias);
    }

    /**
     * Get alias to deployment policy id map in the given application.
     *
     * @param applicationContext the application context
     * @return alias to deployment policy map
     */
    public static Map<String, String> getAliasToDeploymentPolicyIdMapOfApplication(ApplicationContext applicationContext) {

        if (applicationContext == null) {
            return null;
        }

        ComponentContext componentContext = applicationContext.getComponents();
        if (componentContext == null) {
            return null;
        }

        Map<String, String> aliasToDeploymentPolicyIdMap = new HashMap<String, String>();

        CartridgeContext[] cartridgeContexts = componentContext.getCartridgeContexts();
        if (cartridgeContexts != null && cartridgeContexts.length != 0) {
            getAliasToDeployloymentPolicyIdMapFromChildCartridgeContexts(aliasToDeploymentPolicyIdMap, cartridgeContexts);
        }

        GroupContext[] groupContexts = componentContext.getGroupContexts();
        if (groupContexts != null && groupContexts.length != 0) {
            getAliasToDeployloymentPolicyIdMapFromChildGroupContexts(aliasToDeploymentPolicyIdMap, groupContexts);
        }

        return aliasToDeploymentPolicyIdMap;
    }

    private static void getAliasToDeployloymentPolicyIdMapFromChildCartridgeContexts(
            Map<String, String> aliasToDeploymentPolicyIdMap, CartridgeContext[] cartridgeContexts) {

        if (cartridgeContexts != null && cartridgeContexts.length != 0) {
            for (CartridgeContext cartridgeContext : cartridgeContexts) {
                if (cartridgeContext != null) {
                    aliasToDeploymentPolicyIdMap.put(
                            cartridgeContext.getSubscribableInfoContext().getAlias(),
                            cartridgeContext.getSubscribableInfoContext().getDeploymentPolicy());
                }
            }
        }
    }

    private static void getAliasToDeployloymentPolicyIdMapFromChildGroupContexts(
            Map<String, String> aliasToDeploymentPolicyIdMap, GroupContext[] groupContexts) {

        if (groupContexts != null && groupContexts.length != 0) {
            for (GroupContext groupContext : groupContexts) {
                if (groupContext != null) {
                    if (groupContext.getDeploymentPolicy() == null || groupContext.getDeploymentPolicy().isEmpty()) {
                        // if group does not have a deployment policy, children should have
                        getAliasToDeployloymentPolicyIdMapFromChildCartridgeContexts(aliasToDeploymentPolicyIdMap, groupContext.getCartridgeContexts());
                        getAliasToDeployloymentPolicyIdMapFromChildGroupContexts(aliasToDeploymentPolicyIdMap, groupContext.getGroupContexts());
                    } else {
                        // if group have a deployment policy, it is the same for all the children
                        String deploymentPolicyId = groupContext.getDeploymentPolicy();
                        aliasToDeploymentPolicyIdMap.put(groupContext.getAlias(), deploymentPolicyId);
                        if (groupContext.getCartridgeContexts() != null && groupContext.getCartridgeContexts().length != 0) {
                            setDeploymentPolicyIdToChildCartridgeContexts(aliasToDeploymentPolicyIdMap,
                                    deploymentPolicyId,
                                    groupContext.getCartridgeContexts());
                        }
                        if (groupContext.getGroupContexts() != null && groupContext.getGroupContexts().length != 0) {
                            setDeploymentPolicyIdToChildGroupContexts(aliasToDeploymentPolicyIdMap,
                                    deploymentPolicyId,
                                    groupContext.getGroupContexts());
                        }

                    }
                }
            }
        }
    }

    private static void setDeploymentPolicyIdToChildCartridgeContexts(
            Map<String, String> aliasToDeploymentPolicyIdMap, String deploymentPolicyId,
            CartridgeContext[] cartridgeContexts) {

        if (cartridgeContexts != null && cartridgeContexts.length != 0) {
            for (CartridgeContext cartridgeContext : cartridgeContexts) {
                if (cartridgeContext != null) {
                    aliasToDeploymentPolicyIdMap.put(cartridgeContext.getSubscribableInfoContext().getAlias(),
                            deploymentPolicyId);
                }
            }
        }
    }

    private static void setDeploymentPolicyIdToChildGroupContexts(
            Map<String, String> aliasToDeploymentPolicyIdMap, String deploymentPolicyId, GroupContext[] groupContexts) {

        if (groupContexts != null && groupContexts.length != 0) {
            for (GroupContext groupContext : groupContexts) {
                if (groupContext != null) {
                    if (groupContext.getCartridgeContexts() != null && groupContext.getCartridgeContexts().length != 0) {
                        setDeploymentPolicyIdToChildCartridgeContexts(aliasToDeploymentPolicyIdMap,
                                deploymentPolicyId,
                                groupContext.getCartridgeContexts());
                    }
                    if (groupContext.getGroupContexts() != null && groupContext.getGroupContexts().length != 0) {
                        setDeploymentPolicyIdToChildGroupContexts(aliasToDeploymentPolicyIdMap,
                                deploymentPolicyId,
                                groupContext.getGroupContexts());
                    }
                }
            }
        }
    }

    /**
     * Validates Application Policy against the given application.
     *
     * @param applicationPolicy the application policy to be validated
     * @throws InvalidApplicationPolicyException if application policy is not valid
     * @throws RemoteException                   is anything went wrong while communicating with CC to validate network partitions
     */
    public static void validateApplicationPolicy(ApplicationPolicy applicationPolicy)
            throws InvalidApplicationPolicyException, RemoteException {

        // application policy can't be null
        if (null == applicationPolicy) {
            String msg = "Invalid Application Policy: Application Policy is null";
            log.error(msg);
            throw new InvalidApplicationPolicyException(msg);
        }

        // application policy id can't be null
        if (applicationPolicy.getId() == null || StringUtils.isBlank(applicationPolicy.getId())) {
            String msg = "Invalid Application Policy: Application policy id null or empty";
            log.error(msg);
            throw new InvalidApplicationPolicyException(msg);
        }

        // network partition algorithm can't null or empty
        String algorithm = applicationPolicy.getAlgorithm();
        if (algorithm == null || StringUtils.isBlank(algorithm)) {
            String msg = "Invalid Application Policy: Network " +
                    "partition algorithm is null or empty";
            log.error(msg);
            throw new InvalidApplicationPolicyException(msg);
        }

        // network partition algorithm should be either one-after-another or all-at-once
        if (!algorithm.equals(StratosConstants.NETWORK_PARTITION_ONE_AFTER_ANOTHER_ALGORITHM_ID)
                && !algorithm.equals(StratosConstants.NETWORK_PARTITION_ALL_AT_ONCE_ALGORITHM_ID)) {
            String msg = String.format("Invalid Application Policy: " +
                            "Invalid network partition algorithm. "
                            + "It should be either %s or %s, but found %s",
                    StratosConstants.NETWORK_PARTITION_ONE_AFTER_ANOTHER_ALGORITHM_ID,
                    StratosConstants.NETWORK_PARTITION_ALL_AT_ONCE_ALGORITHM_ID, algorithm);
            log.error(msg);
            throw new InvalidApplicationPolicyException(msg);
        }

        // application policy should contain at least one network partition reference
        String[] networkPartitionIds = applicationPolicy.getNetworkPartitions();
        if (null == networkPartitionIds || networkPartitionIds.length == 0) {
            String msg = "Invalid Application Policy: "
                    + "Application Policy is not containing any network partition reference";
            log.error(msg);
            throw new InvalidApplicationPolicyException(msg);
        }

        // validating all network partition references
        for (String networkPartitionId : networkPartitionIds) {

            // network-partition-id can't be null or empty
            if (null == networkPartitionId || networkPartitionId.isEmpty()) {
                String msg = String.format("Invalid Application Policy: "
                        + "Invalid network-partition-id [network-partition-id] %s", networkPartitionId);
                log.error(msg);
                throw new InvalidApplicationPolicyException(msg);
            }

            // network partitions should be added already
            if (null == CloudControllerServiceClient.getInstance().
                    getNetworkPartition(networkPartitionId)) {
                String msg = String.format("Network partition not found: [network-partition-id]  %s in " +
                        "[application-policy-id] %s", networkPartitionId, applicationPolicy.getId());
                log.error(msg);
                throw new InvalidApplicationPolicyException(msg);
            }

        }

        // if networkPartitionGroups property is set, we need to validate that too
        Properties properties = applicationPolicy.getProperties();
        if (properties != null) {
            Property networkPartitionGroupsProperty = properties.
                    getProperty(StratosConstants.APPLICATION_POLICY_NETWORK_PARTITION_GROUPS);
            if (networkPartitionGroupsProperty != null) {
                String networkPartitionGroupsPropertyValue = networkPartitionGroupsProperty.getValue();
                if (networkPartitionGroupsPropertyValue != null) {
                    String[] networkPartitionGroups = networkPartitionGroupsPropertyValue.
                            split(StratosConstants.APPLICATION_POLICY_NETWORK_PARTITION_GROUPS_SPLITTER);
                    if (networkPartitionGroups != null) {
                        for (String networkPartitionIdsString : networkPartitionGroups) {
                            networkPartitionIds = networkPartitionIdsString.
                                    split(StratosConstants.APPLICATION_POLICY_NETWORK_PARTITIONS_SPLITTER);
                            if (networkPartitionIds != null) {
                                for (String networkPartitionId : networkPartitionIds) {
                                    // network-partition-id can't be null or empty
                                    if (null == networkPartitionId || networkPartitionId.isEmpty()) {
                                        String msg = String.format("Invalid Application Policy: "
                                                        + "Invalid network-partition-id [network-partition-id] %s",
                                                networkPartitionId);
                                        log.error(msg);
                                        throw new InvalidApplicationPolicyException(msg);
                                    }

                                    // network partitions should be added already
                                    if (null == CloudControllerServiceClient.getInstance().
                                            getNetworkPartition(networkPartitionId)) {
                                        String msg = String.format("Invalid Application Policy: "
                                                + "Network partition not found for " +
                                                "[network-partition-id] : %s", networkPartitionId);
                                        log.error(msg);
                                        throw new InvalidApplicationPolicyException(msg);
                                    }
                                }
                            }
                        }
                        // populating network partition groups in application policy
                        applicationPolicy.setNetworkPartitionGroups(networkPartitionGroups);
                    }
                }
            }
        }
    }


    /**
     * Validates an application policy against the application
     *
     * @param applicationId
     * @param applicationPolicyId
     * @throws ApplicatioinPolicyNotExistsException
     * @throws InvalidApplicationPolicyException
     */
    public static void validateApplicationPolicyAgainstApplication(String applicationId, String applicationPolicyId)
            throws ApplicatioinPolicyNotExistsException, InvalidApplicationPolicyException {

        ApplicationPolicy applicationPolicy = PolicyManager.getInstance().getApplicationPolicy(applicationPolicyId);
        if (applicationPolicy == null) {
            String msg = String.format("Application Policy not exists for [application-policy-id] %s", applicationPolicyId);
            log.error(msg);
            throw new ApplicatioinPolicyNotExistsException(msg);
        }

        String[] networkPartitionIds = applicationPolicy.getNetworkPartitions();

        for (String applicationPolicyNetworkPartitionerence : networkPartitionIds) {
            String networkPartitionId = applicationPolicyNetworkPartitionerence;
            // validate application policy against the given application
            if (!isAppUsingNetworkPartitionId(applicationId, networkPartitionId)) {
                String msg = String.format("Invalid Application Policy: "
                                + "Network partition [network-partition-id] %s is not used in application [application-id] %s. "
                                + "Hence application bursting will fail. Either remove %s from application policy or make all the cartridges available in %s",
                        networkPartitionId, applicationId, networkPartitionId, networkPartitionId);
                log.error(msg);
                throw new InvalidApplicationPolicyException(msg);
            }
        }
    }

    /**
     * Validate whether all the deployment policies used in the application are using the same network partitions of Application policy
     */
    private static boolean isAppUsingNetworkPartitionId(String applicationId, String networkPartitionId) {
        if (applicationId == null || StringUtils.isBlank(applicationId)
                || networkPartitionId == null || StringUtils.isBlank(networkPartitionId)) {
            return false;
        }
        List<String> deploymentPolicyIdsReferredInApplication = AutoscalerUtil.getDeploymentPolicyIdsReferredInApplication(applicationId);
        if (deploymentPolicyIdsReferredInApplication == null) {
            return false;
        }

        int referencesOfNetworkPartition = 0;
        for (String deploymentPolicyIDReferredInApp : deploymentPolicyIdsReferredInApplication) {
            try {
                DeploymentPolicy deploymentPolicyInApp = PolicyManager.getInstance().getDeploymentPolicy(deploymentPolicyIDReferredInApp);
                if (deploymentPolicyInApp != null) {
                    for (NetworkPartitionRef networkPartitionOfDeploymentPolicy : deploymentPolicyInApp.getNetworkPartitionRefs()) {
                        if (networkPartitionOfDeploymentPolicy != null) {
                            if (networkPartitionOfDeploymentPolicy != null && networkPartitionOfDeploymentPolicy.getId().
                                    equals(networkPartitionId)) {
                                referencesOfNetworkPartition++;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                String msg = String.format("Error while getting deployment policy from cloud controller [deployment-policy-id] %s ",
                        deploymentPolicyIDReferredInApp);
                log.error(msg, e);
                throw new AutoScalerException(msg, e);
            }
        }
        //If network-partition referred in application policy is found in all the deployment policies of application, return true
        if (deploymentPolicyIdsReferredInApplication.size() == referencesOfNetworkPartition) {
            return true;
        }
        return false;
    }

    public void updateApplicationsTopology(Application application)
            throws ApplicationDefinitionException {
        Application existingApplication = ApplicationHolder.getApplications().
                getApplication(application.getUniqueIdentifier());
        //Retrieve all the groups in order to update it
        Set<Group> existingGroups = existingApplication.getAllGroupsRecursively();

        //updating all the groups by traversing the existing application
        for (Group existingGroup : existingGroups) {
            Group newGroup = application.getGroupRecursively(existingGroup.getUniqueIdentifier());
            if (newGroup != null) {

                ApplicationMonitor applicationMonitor = AutoscalerContext.getInstance().
                        getAppMonitor(application.getUniqueIdentifier());

                if (applicationMonitor != null) {
                    //Finding the GroupMonitor based on ApplicationMonitor
                    GroupMonitor groupMonitor = (GroupMonitor) applicationMonitor.
                            findGroupMonitorWithId(existingGroup.getUniqueIdentifier());

                    if (groupMonitor != null) {
                        //Updating the GroupMonitor
                        for (NetworkPartitionContext networkPartitionContext : groupMonitor.
                                getNetworkPartitionContextsMap().values()) {
                            ((NetworkPartitionContext) networkPartitionContext).
                                    setMinInstanceCount(newGroup.getGroupMinInstances());
                            ((NetworkPartitionContext) networkPartitionContext).
                                    setMaxInstanceCount(newGroup.getGroupMaxInstances());
                        }
                    }
                }


                try {
                    ApplicationHolder.acquireWriteLock();
                    //update the min and max of Group instances
                    existingGroup.setGroupMinInstances(newGroup.getGroupMinInstances());
                    //TODO applications Topology update
                    existingGroup.setGroupMaxInstances(newGroup.getGroupMaxInstances());
                    //Persisting the existing application
                    ApplicationHolder.persistApplication(existingApplication);
                } finally {
                    ApplicationHolder.releaseWriteLock();
                }
            } else {
                String msg = "Application is inconsistent. Please check whether the updated " +
                        "application has same structure as existing application";
                log.error(msg);
                throw new ApplicationDefinitionException(msg);
            }

        }
    }

    public void updateClusterMonitor(Application application) throws ApplicationDefinitionException {
        Application existingApplication = ApplicationHolder.getApplications().
                getApplication(application.getUniqueIdentifier());

        Set<ClusterDataHolder> clusterDataHolders = application.getClusterDataRecursively();

        for (ClusterDataHolder clusterDataHolder : clusterDataHolders) {
            ClusterMonitor clusterMonitor = AutoscalerContext.getInstance().
                    getClusterMonitor(clusterDataHolder.getClusterId());
            if (clusterMonitor != null) {
                for (NetworkPartitionContext networkPartitionContext :
                        clusterMonitor.getNetworkPartitionCtxts()) {
                    for (InstanceContext instanceContext :
                            networkPartitionContext.getInstanceIdToInstanceContextMap().values()) {
                        //Updating the min and max instances of cluster instance context
                        ((ClusterInstanceContext) instanceContext).
                                setMinInstanceCount(clusterDataHolder.getMinInstances());
                        ((ClusterInstanceContext) instanceContext).
                                setMaxInstanceCount(clusterDataHolder.getMaxInstances());

                        try {
                            ApplicationHolder.acquireWriteLock();
                            //Updating the existing application
                            ClusterDataHolder existingClusterDataHolder = existingApplication.
                                    getClusterDataHolderRecursivelyByAlias(
                                            AutoscalerUtil.getAliasFromClusterId(
                                                    clusterDataHolder.getClusterId()));
                            //Updating the in-memory model
                            existingClusterDataHolder.setMinInstances(clusterDataHolder.
                                    getMinInstances());
                            existingClusterDataHolder.setMaxInstances(clusterDataHolder.
                                    getMaxInstances());
                            //Persisting the existing application
                            ApplicationHolder.persistApplication(existingApplication);
                        } finally {
                            ApplicationHolder.releaseWriteLock();
                        }
                    }
                }
            } else {
                String msg = "Application is inconsistent. Please check whether the updated " +
                        "application has same structure as existing application";
                log.error(msg);
                throw new ApplicationDefinitionException(msg);
            }
        }
    }

    public static void validateStartupOrders(String groupName, String[] startupOrders) throws InvalidServiceGroupException {

        if (startupOrders == null || startupOrders.length == 0) {
            if (log.isDebugEnabled()) {
                log.debug("No Startup Order defined for group " + groupName);
            }
            return;
        }

        for (String startupOrder : startupOrders) {
            // split comma separated startup orders
            String[] splittedStartupOrders = startupOrder.split(",");
            for (String splittedStartupOrder : splittedStartupOrders) {
                if (!splittedStartupOrder.trim().startsWith("cartridge.") && !splittedStartupOrder.trim().startsWith("group.")) {
                    // invalid startup order; should prefixed by either 'cartridge.' or 'group.'
                    throw new InvalidServiceGroupException("Invalid cartridge group: startup order [" + startupOrder +
                            "] for group " + groupName +
                            ", should prefixed by either 'cartridge.' or 'group.'");
                }
            }
        }
    }

    public static void validateScalingDependencies(String groupName, String[] scalingDependents) throws InvalidServiceGroupException {

        if (scalingDependents == null || scalingDependents.length == 0) {
            if (log.isDebugEnabled()) {
                log.debug("No Scaling Dependents defined for group " + groupName);
            }
            return;
        }

        for (String scalingDependent : scalingDependents) {
            // split comma separated scaling dependents
            String[] splittedDependents = scalingDependent.split(",");
            for (String splittedDependent : splittedDependents) {
                if (!splittedDependent.trim().startsWith("cartridge.") && !splittedDependent.trim().startsWith("group.")) {
                    // invalid startup order; should prefixed by either 'cartridge.' or 'group.'
                    throw new InvalidServiceGroupException("Invalid cartridge group: Scaling Dependency [" +
                            scalingDependent + "] for group " + groupName +
                            ", should prefixed by either 'cartridge.' or 'group.'");
                }
            }
        }
    }

    /**
     * Validate the Auto Scalar policy removal
     *
     * @param autoscalePolicyId Auto Scalar policy id boolean
     * @return
     */
    public static boolean removableAutoScalerPolicy(String autoscalePolicyId) {
        Collection<ApplicationContext> applicationContexts = AutoscalerContext.getInstance().
                getApplicationContexts();
        for (ApplicationContext applicationContext : applicationContexts) {
            if(applicationContext.getComponents().getCartridgeContexts() != null) {
                for(CartridgeContext cartridgeContext : applicationContext.getComponents().
                        getCartridgeContexts()) {
                    if(autoscalePolicyId.equals(cartridgeContext.getSubscribableInfoContext().
                            getAutoscalingPolicy())) {
                        return false;
                    }
                }
            }

            if(applicationContext.getComponents().getGroupContexts() != null) {
                return findAutoscalingPolicyInGroup(applicationContext.getComponents().getGroupContexts(),
                        autoscalePolicyId);
            }
        }
        return true;
    }

    public static boolean findAutoscalingPolicyInGroup(GroupContext[] groupContexts,
                                                String autoscalePolicyId) {
        for(GroupContext groupContext : groupContexts) {
            if(groupContext.getCartridgeContexts() != null) {
                for(CartridgeContext cartridgeContext : groupContext.getCartridgeContexts()) {
                    if(autoscalePolicyId.equals(cartridgeContext.getSubscribableInfoContext().
                            getAutoscalingPolicy())) {
                        return false;
                    }
                }

            }
            if(groupContext.getGroupContexts() != null) {
                return findAutoscalingPolicyInGroup(groupContext.getGroupContexts(),
                        autoscalePolicyId);
            }
        }
        return true;
    }


    /**
     * Validate the deployment policy removal
     *
     * @param deploymentPolicyId
     * @return
     */
    public static boolean removableDeploymentPolicy(String deploymentPolicyId) {
        boolean canRemove = true;
        Map<String, Application> applications = ApplicationHolder.getApplications().getApplications();
        for (Application application : applications.values()) {
            List<String> deploymentPolicyIdsReferredInApplication = AutoscalerUtil.
                    getDeploymentPolicyIdsReferredInApplication(application.getUniqueIdentifier());
            for (String deploymentPolicyIdInApp : deploymentPolicyIdsReferredInApplication) {
                if (deploymentPolicyId.equals(deploymentPolicyIdInApp)) {
                    canRemove = false;
                }
            }
        }
        return canRemove;
    }

    public static void readApplicationContextsFromRegistry() {
        String[] resourcePaths = RegistryManager.getInstance().getApplicationContextResourcePaths();
        if ((resourcePaths == null) || (resourcePaths.length == 0)) {
            return;
        }

        for (String resourcePath : resourcePaths) {
            ApplicationContext applicationContext = RegistryManager.getInstance().
                    getApplicationContextByResourcePath(resourcePath);
            AutoscalerContext.getInstance().addApplicationContext( applicationContext);
        }
    }

}
