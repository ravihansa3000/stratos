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
package org.apache.stratos.metadata.service.registry;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.common.concurrent.locks.ReadWriteLock;
import org.apache.stratos.metadata.service.MetadataTopologyEventReceiver;
import org.apache.stratos.metadata.service.ServiceHolder;
import org.apache.stratos.metadata.service.definition.Property;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import java.util.*;

/**
 * Carbon registry implementation
 */

public class MetadataApiRegistry implements DataStore {

    private static final String mainResource = "metadata/";
    private static Log log = LogFactory.getLog(MetadataApiRegistry.class);
    @Context
    HttpServletRequest httpServletRequest;
    private static final Map<String, ReadWriteLock> applicationIdToReadWriteLockMap = new HashMap<>();
    private MetadataTopologyEventReceiver metadataTopologyEventReceiver;

    public MetadataApiRegistry() {
        metadataTopologyEventReceiver = new MetadataTopologyEventReceiver();
        metadataTopologyEventReceiver.execute();
    }

    public List<Property> getApplicationProperties(String applicationName) throws RegistryException {
        Registry tempRegistry = getRegistry();
        String resourcePath = mainResource + applicationName;

        if (!tempRegistry.resourceExists(resourcePath)) {
            return null;
        }
        // We are using only super tenant registry to persist
        PrivilegedCarbonContext ctx = PrivilegedCarbonContext.getThreadLocalCarbonContext();
        ctx.setTenantId(MultitenantConstants.SUPER_TENANT_ID);
        ctx.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);

        Resource regResource = tempRegistry.get(resourcePath);
        ArrayList<Property> newProperties = new ArrayList<Property>();

        Properties props = regResource.getProperties();
        Enumeration<?> x = props.propertyNames();
        while (x.hasMoreElements()) {
            String key = (String) x.nextElement();
            List<String> values = regResource.getPropertyValues(key);
            Property property = new Property();
            property.setKey(key);
            String[] valueArr = new String[values.size()];
            property.setValues(values.toArray(valueArr));

            newProperties.add(property);
        }
        return newProperties;
    }

    /**
     * Get Properties of clustor
     *
     * @param applicationName
     * @param clusterId
     * @return
     * @throws RegistryException
     */
    public List<Property> getClusterProperties(String applicationName, String clusterId) throws RegistryException {
        Registry tempRegistry = getRegistry();
        String resourcePath = mainResource + applicationName + "/" + clusterId;

        if (!tempRegistry.resourceExists(resourcePath)) {
            return null;
        }

        // We are using only super tenant registry to persist
        PrivilegedCarbonContext ctx = PrivilegedCarbonContext.getThreadLocalCarbonContext();
        ctx.setTenantId(MultitenantConstants.SUPER_TENANT_ID);
        ctx.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);

        Resource regResource = tempRegistry.get(resourcePath);

        ArrayList<Property> newProperties = new ArrayList<Property>();

        Properties props = regResource.getProperties();
        Enumeration<?> x = props.propertyNames();
        while (x.hasMoreElements()) {
            String key = (String) x.nextElement();
            List<String> values = regResource.getPropertyValues(key);
            Property property = new Property();
            property.setKey(key);
            String[] valueArr = new String[values.size()];
            property.setValues(values.toArray(valueArr));

            newProperties.add(property);
        }

        return newProperties;
    }

    public void addPropertyToApplication(String applicationId, Property property) throws RegistryException {
        Registry registry = getRegistry();
        String resourcePath = mainResource + applicationId;
        acquireWriteLock(applicationId);
        try {
            // We are using only super tenant registry to persist
            PrivilegedCarbonContext ctx = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            ctx.setTenantId(MultitenantConstants.SUPER_TENANT_ID);
            ctx.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
            Resource nodeResource = null;
            if (registry.resourceExists(resourcePath)) {
                nodeResource = registry.get(resourcePath);
            } else {
                nodeResource = registry.newCollection();
                if (log.isDebugEnabled()) {
                    log.debug("Registry resource created for application: " + applicationId);
                }
            }

            boolean updated = false;
            for (String value : property.getValues()) {
                if (!propertyValueExist(nodeResource, property.getKey(), value)) {
                    updated = true;
                    if (log.isDebugEnabled()) {
                        log.debug(String.format("Registry property is added: [resource-path] %s "
                                        + "[Property Name] %s [Property Value] %s", resourcePath, property.getKey(),
                                value));
                    }
                    nodeResource.addProperty(property.getKey(), value);
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug(String.format("Property value already exist property=%s value=%s", property.getKey(),
                                value));
                    }
                }
            }
            if (updated) {
                registry.put(resourcePath, nodeResource);
                if (log.isDebugEnabled()) {
                    log.debug(String.format(
                            "Registry property is persisted: [resource-path] %s [Property Name] %s [Property Values] "
                                    + "%s", resourcePath, property.getKey(), Arrays.asList(property.getValues())));
                }
            }
        } catch (Exception e) {
            String msg = "Failed to persist properties in registry: " + resourcePath;
            log.error(msg, e);
            throw new RegistryException(msg, e);
        } finally {
            releaseWriteLock(applicationId);
        }
    }

    private boolean propertyValueExist(Resource nodeResource, String key, String value) {
        List<String> properties = nodeResource.getPropertyValues(key);
        return properties != null && properties.contains(value);

    }

    public boolean removePropertyValueFromApplication(String applicationId, String propertyName, String valueToRemove)
            throws RegistryException {
        Registry registry = getRegistry();
        String resourcePath = mainResource + applicationId;
        acquireWriteLock(applicationId);
        try {
            // We are using only super tenant registry to persist
            PrivilegedCarbonContext ctx = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            ctx.setTenantId(MultitenantConstants.SUPER_TENANT_ID);
            ctx.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
            Resource nodeResource;
            if (registry.resourceExists(resourcePath)) {
                nodeResource = registry.get(resourcePath);
            } else {
                log.warn(String.format("Registry [resource] %s not found ", resourcePath));
                return false;
            }
            nodeResource.removePropertyValue(propertyName, valueToRemove);
            registry.put(resourcePath, nodeResource);
            log.info(String.format("Application %s property %s value %s is removed from metadata ", applicationId,
                    propertyName, valueToRemove));
            return true;
        } catch (Exception e) {
            throw new RegistryException("Could not remove registry resource: [resource-path] " + resourcePath, e);
        } finally {
            releaseWriteLock(applicationId);
        }
    }

    /**
     * Add property to cluster
     *
     * @param applicationId
     * @param clusterId
     * @param property
     * @throws RegistryException
     */
    public void addPropertyToCluster(String applicationId, String clusterId, Property property)
            throws RegistryException {
        Registry registry = getRegistry();
        String resourcePath = mainResource + applicationId + "/" + clusterId;
        acquireWriteLock(applicationId);
        try {
            // We are using only super tenant registry to persist
            PrivilegedCarbonContext ctx = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            ctx.setTenantId(MultitenantConstants.SUPER_TENANT_ID);
            ctx.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
            Resource nodeResource = null;
            if (registry.resourceExists(resourcePath)) {
                nodeResource = registry.get(resourcePath);
            } else {
                nodeResource = registry.newResource();
                if (log.isDebugEnabled()) {
                    log.debug("Registry resource created for cluster" + clusterId);
                }
            }
            nodeResource.setProperty(property.getKey(), Arrays.asList(property.getValues()));
            registry.put(resourcePath, nodeResource);
            log.info(String.format(
                    "Registry property is persisted: [resource-path] %s [Property Name] %s [Property Values] %s",
                    resourcePath, property.getKey(), Arrays.asList(property.getValues())));
        } finally {
            releaseWriteLock(applicationId);
        }
    }

    private UserRegistry getRegistry() throws RegistryException {
        return ServiceHolder.getRegistryService().getGovernanceSystemRegistry();
    }

    /**
     * Delete the resource identified by the applicationId, if exist.
     *
     * @param applicationId ID of the application.
     * @return True if resource exist and able to delete, else false.
     * @throws RegistryException
     */
    public boolean deleteApplicationProperties(String applicationId) throws RegistryException {
        if (StringUtils.isBlank(applicationId)) {
            throw new IllegalArgumentException("Application ID can not be null");
        }
        Registry registry = getRegistry();
        String resourcePath = mainResource + applicationId;
        acquireWriteLock(applicationId);
        try {
            // We are using only super tenant registry to persist
            PrivilegedCarbonContext ctx = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            ctx.setTenantId(MultitenantConstants.SUPER_TENANT_ID);
            ctx.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
            if (registry.resourceExists(resourcePath)) {
                registry.delete(resourcePath);
                log.info(String.format("Application [application-id ] properties removed from registry %s",
                        applicationId));
            }
            return true;
        } catch (Exception e) {
            throw new RegistryException("Could not remove registry resource: [resource-path] " + resourcePath, e);
        } finally {
            releaseWriteLock(applicationId);
        }
    }

    public boolean removePropertyFromApplication(String applicationId, String propertyName)
            throws org.wso2.carbon.registry.api.RegistryException {
        Registry registry = getRegistry();
        String resourcePath = mainResource + applicationId;
        acquireWriteLock(applicationId);
        Resource nodeResource;
        try {
            // We are using only super tenant registry to persist
            PrivilegedCarbonContext ctx = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            ctx.setTenantId(MultitenantConstants.SUPER_TENANT_ID);
            ctx.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
            if (registry.resourceExists(resourcePath)) {
                nodeResource = registry.get(resourcePath);
                if (nodeResource.getProperty(propertyName) == null) {
                    log.info(String.format("[application-id] %s does not have a property [property-name] %s ",
                            applicationId, propertyName));
                    return false;
                } else {
                    nodeResource.removeProperty(propertyName);
                    registry.put(resourcePath, nodeResource);
                }
            } else {
                log.error("Registry resource not not found at " + resourcePath);
                return false;
            }

            log.info(String.format("Application [application-id] %s property [property-name] %s removed from Registry ",
                    applicationId, propertyName));
            return true;
        } finally {
            releaseWriteLock(applicationId);
        }
    }

    public void acquireReadLock(String applicationId) {
        if (applicationIdToReadWriteLockMap.get(applicationId) == null) {
            throw new RuntimeException(
                    String.format("Invalid application [application-id] %s not found. Failed to acquire read lock.",
                            applicationId));
        } else {
            applicationIdToReadWriteLockMap.get(applicationId).acquireReadLock();
        }
    }

    public void acquireWriteLock(String applicationId) {
        if (applicationIdToReadWriteLockMap.get(applicationId) == null) {
            throw new RuntimeException(
                    String.format("Invalid application [application-id] %s not found. Failed to acquire write lock.",
                            applicationId));
        } else {
            applicationIdToReadWriteLockMap.get(applicationId).acquireWriteLock();
        }
    }

    public void releaseReadLock(String applicationId) {
        if (applicationIdToReadWriteLockMap.get(applicationId) == null) {
            throw new RuntimeException(
                    String.format("Invalid application [application-id] %s not found. Failed to release read lock.",
                            applicationId));
        } else {
            applicationIdToReadWriteLockMap.get(applicationId).releaseReadLock();
        }
    }

    public void releaseWriteLock(String applicationId) {
        if (applicationIdToReadWriteLockMap.get(applicationId) == null) {
            throw new RuntimeException(
                    String.format("Invalid application [application-id] %s not found. Failed to release write lock.",
                            applicationId));
        } else {
            applicationIdToReadWriteLockMap.get(applicationId).releaseWriteLock();
        }
    }

    public static Map<String, ReadWriteLock> getApplicationIdToReadWriteLockMap() {
        return applicationIdToReadWriteLockMap;
    }

    public void stopTopologyReceiver() {
        metadataTopologyEventReceiver.terminate();
    }
}
