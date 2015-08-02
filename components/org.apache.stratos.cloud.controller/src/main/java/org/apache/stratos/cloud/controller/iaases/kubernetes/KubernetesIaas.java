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

package org.apache.stratos.cloud.controller.iaases.kubernetes;

import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.cloud.controller.context.CloudControllerContext;
import org.apache.stratos.cloud.controller.domain.*;
import org.apache.stratos.cloud.controller.domain.kubernetes.KubernetesCluster;
import org.apache.stratos.cloud.controller.domain.kubernetes.KubernetesClusterContext;
import org.apache.stratos.cloud.controller.domain.kubernetes.KubernetesHost;
import org.apache.stratos.cloud.controller.domain.kubernetes.PortRange;
import org.apache.stratos.cloud.controller.exception.*;
import org.apache.stratos.cloud.controller.iaases.Iaas;
import org.apache.stratos.cloud.controller.iaases.PartitionValidator;
import org.apache.stratos.cloud.controller.util.CloudControllerUtil;
import org.apache.stratos.common.Property;
import org.apache.stratos.common.constants.StratosConstants;
import org.apache.stratos.common.domain.NameValuePair;
import org.apache.stratos.kubernetes.client.KubernetesApiClient;
import org.apache.stratos.kubernetes.client.KubernetesConstants;
import org.apache.stratos.kubernetes.client.exceptions.KubernetesClientException;
import org.apache.stratos.messaging.domain.topology.KubernetesService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.Lock;

/**
 * Kubernetes IaaS implementation.
 */
public class KubernetesIaas extends Iaas {

    private static final Log log = LogFactory.getLog(KubernetesIaas.class);

    private static final long DEFAULT_POD_ACTIVATION_TIMEOUT = 60000; // 1 min
    private static final String PAYLOAD_PARAMETER_SEPARATOR = ",";
    private static final String PAYLOAD_PARAMETER_NAME_VALUE_SEPARATOR = "=";
    private static final String PAYLOAD_PARAMETER_PREFIX = "payload_parameter.";
    private static final String PORT_MAPPINGS = "PORT_MAPPINGS";
    private static final String KUBERNETES_CONTAINER_CPU = "KUBERNETES_CONTAINER_CPU";
    private static final String KUBERNETES_CONTAINER_MEMORY = "KUBERNETES_CONTAINER_MEMORY";
    private static final String KUBERNETES_SERVICE_SESSION_AFFINITY = "KUBERNETES_SERVICE_SESSION_AFFINITY";
    private static final String KUBERNETES_CONTAINER_CPU_DEFAULT = "kubernetes.container.cpu.default";
    private static final String KUBERNETES_CONTAINER_MEMORY_DEFAULT = "kubernetes.container.memory.default";

    private PartitionValidator partitionValidator;
    private List<NameValuePair> payload;
    private Long podActivationTimeout;

    public KubernetesIaas(IaasProvider iaasProvider) {
        super(iaasProvider);
        partitionValidator = new KubernetesPartitionValidator();
        payload = new ArrayList<NameValuePair>();

        podActivationTimeout = Long.getLong("stratos.pod.activation.timeout");
        if (podActivationTimeout == null) {
            podActivationTimeout = DEFAULT_POD_ACTIVATION_TIMEOUT;
            if (log.isInfoEnabled()) {
                log.info("Pod activation timeout was set: " + podActivationTimeout);
            }
        }
    }

    @Override
    public void initialize() {
    }

    /**
     * Set dynamic payload which needs to be passed to the containers as environment variables.
     *
     * @param payloadByteArray
     */
    @Override
    public void setDynamicPayload(byte[] payloadByteArray) {
        // Clear existing payload parameters
        payload.clear();

        if (payloadByteArray != null) {
            String payloadString = new String(payloadByteArray);
            String[] parameterArray = payloadString.split(PAYLOAD_PARAMETER_SEPARATOR);
            if (parameterArray != null) {
                for (String parameter : parameterArray) {
                    if (parameter != null) {
                        String[] nameValueArray = parameter.split(PAYLOAD_PARAMETER_NAME_VALUE_SEPARATOR);
                        if ((nameValueArray != null) && (nameValueArray.length == 2)) {
                            NameValuePair nameValuePair = new NameValuePair(nameValueArray[0], nameValueArray[1]);
                            payload.add(nameValuePair);
                        }
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("Dynamic payload is set: " + payload.toString());
                }
            }
        }
    }

    @Override
    public MemberContext startInstance(MemberContext memberContext, byte[] payload) throws CartridgeNotFoundException {
        setDynamicPayload(payload);
        return startContainer(memberContext);
    }

    @Override
    public PartitionValidator getPartitionValidator() {
        return partitionValidator;
    }

    @Override
    public void terminateInstance(MemberContext memberContext) throws InvalidCartridgeTypeException,
            InvalidMemberException, MemberTerminationFailedException {
        terminateContainer(memberContext.getMemberId());
    }

    /**
     * Starts a container via kubernetes for the given member context.
     *
     * @param memberContext
     * @return
     * @throws CartridgeNotFoundException
     */
    public MemberContext startContainer(MemberContext memberContext)
            throws CartridgeNotFoundException {
        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireMemberContextWriteLock();

            handleNullObject(memberContext, "member context is null");
            log.info(String.format("Starting container: [application] %s [cartridge] %s [member] %s",
                    memberContext.getApplicationId(), memberContext.getCartridgeType(),
                    memberContext.getMemberId()));

            // Validate cluster id
            String clusterId = memberContext.getClusterId();
            String memberId = memberContext.getMemberId();
            handleNullObject(clusterId, "cluster id is null in member context");

            // Validate cluster context
            ClusterContext clusterContext = CloudControllerContext.getInstance().getClusterContext(clusterId);
            handleNullObject(clusterContext, String.format("Cluster context not found: [application] %s [cartridge] %s " +
                    "[cluster] %s", memberContext.getApplicationId(), memberContext.getCartridgeType(), clusterId));

            // Validate partition
            Partition partition = memberContext.getPartition();
            handleNullObject(partition, String.format("partition not found in member context: [application] %s " +
                            "[cartridge] %s [member] %s", memberContext.getApplicationId(), memberContext.getCartridgeType(),
                    memberContext.getMemberId()));

            // Validate cartridge
            String cartridgeType = clusterContext.getCartridgeType();
            Cartridge cartridge = CloudControllerContext.getInstance().getCartridge(cartridgeType);
            if (cartridge == null) {
                String msg = String.format("Cartridge not found: [application] %s [cartridge] %s",
                        memberContext.getApplicationId(), memberContext.getCartridgeType());
                log.error(msg);
                throw new CartridgeNotFoundException(msg);
            }

            String kubernetesClusterId = partition.getKubernetesClusterId();
            clusterContext.setKubernetesClusterId(kubernetesClusterId);
            KubernetesCluster kubernetesCluster = CloudControllerContext.getInstance().
                    getKubernetesCluster(kubernetesClusterId);
            handleNullObject(kubernetesCluster, "kubernetes cluster not found: " +
                    "[kubernetes-cluster] " + kubernetesClusterId + " [cluster] " + clusterId +
                    " [member] " + memberId);

            // Prepare kubernetes context
            String kubernetesMasterIp = kubernetesCluster.getKubernetesMaster().getPrivateIPAddress();
            PortRange kubernetesPortRange = kubernetesCluster.getPortRange();
            String kubernetesMasterPort = CloudControllerUtil.getProperty(
                    kubernetesCluster.getKubernetesMaster().getProperties(), StratosConstants.KUBERNETES_MASTER_PORT,
                    StratosConstants.KUBERNETES_MASTER_DEFAULT_PORT);

            // Add kubernetes cluster payload parameters to payload
            if ((kubernetesCluster.getProperties() != null) &&
                    (kubernetesCluster.getProperties().getProperties() != null)) {
                for (Property property : kubernetesCluster.getProperties().getProperties()) {
                    if (property != null) {
                        if (property.getName().startsWith(PAYLOAD_PARAMETER_PREFIX)) {
                            String name = property.getName().replace(PAYLOAD_PARAMETER_PREFIX, "");
                            payload.add(new NameValuePair(name, property.getValue()));
                        }
                    }
                }
            }

            KubernetesClusterContext kubernetesClusterContext = getKubernetesClusterContext(kubernetesClusterId,
                    kubernetesMasterIp, kubernetesMasterPort, kubernetesPortRange.getUpper(),
                    kubernetesPortRange.getLower());

            // Generate kubernetes service ports and update port mappings in cartridge
            generateKubernetesServicePorts(clusterContext.getApplicationId(), clusterContext.getClusterId(),
                    kubernetesClusterContext, cartridge);

            // Create kubernetes services for port mappings
            KubernetesApiClient kubernetesApi = kubernetesClusterContext.getKubApi();
            createKubernetesServices(kubernetesApi, clusterContext, kubernetesCluster, kubernetesClusterContext);

            // Create pod
            createPod(clusterContext, memberContext, kubernetesApi, kubernetesClusterContext);

            // Wait for pod status to be changed to running
            Pod pod = waitForPodToBeActivated(memberContext, kubernetesApi);

            // Update member context
            updateMemberContext(memberContext, pod, kubernetesCluster);

            log.info(String.format("Container started successfully: [application] %s [cartridge] %s [member] %s " +
                            "[pod] %s [cpu] %d [memory] %d MB",
                    memberContext.getApplicationId(), memberContext.getCartridgeType(),
                    memberContext.getMemberId(), memberContext.getKubernetesPodId(),
                    memberContext.getInstanceMetadata().getCpu(), memberContext.getInstanceMetadata().getRam()));
            return memberContext;
        } catch (Exception e) {
            String msg = String.format("Could not start container: [application] %s [cartridge] %s [member] %s",
                    memberContext.getApplicationId(), memberContext.getCartridgeType(),
                    memberContext.getMemberId());
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
    }

    private void updateMemberContext(MemberContext memberContext, Pod pod, KubernetesCluster kubernetesCluster) {

        String memberPrivateIPAddress = pod.getStatus().getPodIP();
        String podHostIPAddress = pod.getStatus().getHostIP();
        String memberPublicIPAddress = podHostIPAddress;
        String kubernetesHostPublicIP = findKubernetesHostPublicIPAddress(kubernetesCluster, podHostIPAddress);

        if (StringUtils.isNotBlank(kubernetesHostPublicIP)) {
            memberPublicIPAddress = kubernetesHostPublicIP;
            if (log.isInfoEnabled()) {
                log.info(String.format("Member public IP address set to kubernetes host public IP address:" +
                        "[pod-host-ip] %s [kubernetes-host-public-ip] %s", podHostIPAddress, kubernetesHostPublicIP));
            }
        }

        memberContext.setInstanceId(pod.getMetadata().getName());
        memberContext.setDefaultPrivateIP(memberPrivateIPAddress);
        memberContext.setPrivateIPs(new String[]{memberPrivateIPAddress});
        memberContext.setDefaultPublicIP(memberPublicIPAddress);
        memberContext.setPublicIPs(new String[]{memberPublicIPAddress});
        memberContext.setInitTime(memberContext.getInitTime());
        memberContext.setProperties(memberContext.getProperties());
    }

    private String findKubernetesHostPublicIPAddress(KubernetesCluster kubernetesCluster, String podHostIP) {
        if ((kubernetesCluster != null) && (StringUtils.isNotBlank(podHostIP))) {
            for (KubernetesHost kubernetesHost : kubernetesCluster.getKubernetesHosts()) {
                if (kubernetesHost != null) {
                    if (podHostIP.equals(kubernetesHost.getPrivateIPAddress())) {
                        return kubernetesHost.getPublicIPAddress();
                    }
                }
            }
        }
        return null;
    }

    private Pod waitForPodToBeActivated(MemberContext memberContext, KubernetesApiClient kubernetesApi)
            throws KubernetesClientException, InterruptedException {

        Pod pod;
        boolean podCreated = false;
        boolean podRunning = false;
        long startTime = System.currentTimeMillis();

        while (!podRunning) {
            pod = kubernetesApi.getPod(memberContext.getKubernetesPodId());
            if (pod != null) {
                podCreated = true;
                if (pod.getStatus().getPhase().equals(KubernetesConstants.POD_STATUS_RUNNING)) {
                    log.info(String.format("Pod status changed to running: [application] %s [cartridge] %s [member] %s " +
                                    "[pod] %s", memberContext.getApplicationId(), memberContext.getCartridgeType(),
                            memberContext.getMemberId(), pod.getMetadata().getName()));
                    return pod;
                } else {
                    log.info(String.format("Waiting pod status to be changed to running: [application] %s " +
                                    "[cartridge] %s [member] %s [pod] %s", memberContext.getApplicationId(),
                            memberContext.getCartridgeType(), memberContext.getMemberId(), pod.getMetadata().getName()));
                }
            } else {
                log.info(String.format("Waiting for pod to be created: [application] %s " +
                                "[cartridge] %s [member] %s [pod] %s", memberContext.getApplicationId(),
                        memberContext.getCartridgeType(), memberContext.getMemberId(),
                        memberContext.getKubernetesPodId()));
            }

            if ((System.currentTimeMillis() - startTime) > podActivationTimeout) {
                break;
            }
            Thread.sleep(5000);
        }

        String message;
        if (podCreated) {
            // Pod created but status did not change to running
            message = String.format("Pod status did not change to running within %d sec: " +
                            "[application] %s [cartridge] %s [member] %s [pod] %s",
                    (podActivationTimeout.intValue() / 1000),
                    memberContext.getApplicationId(), memberContext.getCartridgeType(), memberContext.getMemberId(),
                    memberContext.getKubernetesPodId());
            log.error(message);
        } else {
            // Pod did not create
            message = String.format("Pod did not create within %d sec: " +
                            "[application] %s [cartridge] %s [member] %s [pod] %s",
                    (podActivationTimeout.intValue() / 1000),
                    memberContext.getApplicationId(), memberContext.getCartridgeType(), memberContext.getMemberId(),
                    memberContext.getKubernetesPodId());
            log.error(message);
        }

        throw new RuntimeException(message);
    }

    /**
     * Create new pod and pass environment variables.
     *
     * @param memberContext
     * @param kubernetesApi
     * @param kubernetesClusterContext
     * @throws KubernetesClientException
     */
    private void createPod(ClusterContext clusterContext, MemberContext memberContext,
                           KubernetesApiClient kubernetesApi, KubernetesClusterContext kubernetesClusterContext)
            throws KubernetesClientException {

        String applicationId = memberContext.getApplicationId();
        String cartridgeType = memberContext.getCartridgeType();
        String clusterId = memberContext.getClusterId();
        String memberId = memberContext.getMemberId();

        if (log.isInfoEnabled()) {
            log.info(String.format("Creating kubernetes pod: [application] %s [cartridge] %s [member] %s",
                    applicationId, cartridgeType, memberId));
        }

        Partition partition = memberContext.getPartition();
        if (partition == null) {
            String message = String.format("Partition not found in member context: [application] %s [cartridge] %s " +
                            "[member] %s ", applicationId, cartridgeType,
                    memberId);
            log.error(message);
            throw new RuntimeException(message);
        }

        Cartridge cartridge = CloudControllerContext.getInstance().getCartridge(cartridgeType);
        if (cartridge == null) {
            String message = "Could not find cartridge: [cartridge] " + cartridgeType;
            log.error(message);
            throw new RuntimeException(message);
        }

        // Set default values to zero to avoid cpu and memory restrictions
        int cpu = Integer.getInteger(KUBERNETES_CONTAINER_CPU_DEFAULT, 0);
        int memory = Integer.getInteger(KUBERNETES_CONTAINER_MEMORY_DEFAULT, 0);
        Property cpuProperty = cartridge.getProperties().getProperty(KUBERNETES_CONTAINER_CPU);
        if (cpuProperty != null) {
            cpu = Integer.parseInt(cpuProperty.getValue());
        }
        Property memoryProperty = cartridge.getProperties().getProperty(KUBERNETES_CONTAINER_MEMORY);
        if (memoryProperty != null) {
            memory = Integer.parseInt(memoryProperty.getValue());
        }

        IaasProvider iaasProvider = CloudControllerContext.getInstance().getIaasProviderOfPartition(cartridge.getType(), partition.getId());
        if (iaasProvider == null) {
            String message = "Could not find iaas provider: [partition] " + partition.getId();
            log.error(message);
            throw new RuntimeException(message);
        }

        // Add dynamic payload to the member context
        memberContext.setDynamicPayload(payload.toArray(new NameValuePair[payload.size()]));

        // Create pod
        long podSeqNo = kubernetesClusterContext.getPodSeqNo().incrementAndGet();
        String podId = "pod" + "-" + podSeqNo;
        String podLabel = KubernetesIaasUtil.fixSpecialCharacters(clusterId);
        String dockerImage = iaasProvider.getImage();
        List<EnvVar> environmentVariables = KubernetesIaasUtil.prepareEnvironmentVariables(
                clusterContext, memberContext);

        List<ContainerPort> ports = KubernetesIaasUtil.convertPortMappings(Arrays.asList(cartridge.getPortMappings()));

        log.info(String.format("Starting pod: [application] %s [cartridge] %s [member] %s " +
                        "[cpu] %d [memory] %d MB",
                memberContext.getApplicationId(), memberContext.getCartridgeType(),
                memberContext.getMemberId(), cpu, memory));

        kubernetesApi.createPod(podId, podLabel, dockerImage, cpu, memory, ports, environmentVariables);

        log.info(String.format("Pod started successfully: [application] %s [cartridge] %s [member] %s " +
                        "[pod] %s [cpu] %d [memory] %d MB",
                memberContext.getApplicationId(), memberContext.getCartridgeType(),
                memberContext.getMemberId(), podId, cpu, memory));

        // Add pod id to member context
        memberContext.setKubernetesPodId(podId);
        memberContext.setKubernetesPodLabel(podLabel);

        // Create instance metadata
        InstanceMetadata instanceMetadata = new InstanceMetadata();
        instanceMetadata.setImageId(dockerImage);
        instanceMetadata.setCpu(cpu);
        instanceMetadata.setRam(memory);
        memberContext.setInstanceMetadata(instanceMetadata);

        // Persist cloud controller context
        CloudControllerContext.getInstance().persist();
    }

    /**
     * Creates and returns proxy services for the cluster.
     *
     * @param kubernetesApi
     * @param clusterContext
     * @param kubernetesCluster
     * @param kubernetesClusterContext
     * @throws KubernetesClientException
     */
    private void createKubernetesServices(KubernetesApiClient kubernetesApi, ClusterContext clusterContext,
                                          KubernetesCluster kubernetesCluster,
                                          KubernetesClusterContext kubernetesClusterContext)
            throws KubernetesClientException {

        String clusterId = clusterContext.getClusterId();
        String cartridgeType = clusterContext.getCartridgeType();

        Cartridge cartridge = CloudControllerContext.getInstance().getCartridge(cartridgeType);
        if (cartridge == null) {
            String message = "Could not create kubernetes services, cartridge not found: [cartridge] " +
                    cartridgeType;
            log.error(message);
            throw new RuntimeException(message);
        }

        String sessionAffinity = null;
        Property sessionAffinityProperty = cartridge.getProperties().getProperty(KUBERNETES_SERVICE_SESSION_AFFINITY);
        if (sessionAffinityProperty != null) {
            sessionAffinity = sessionAffinityProperty.getValue();
        }

        List<KubernetesService> kubernetesServices = clusterContext.getKubernetesServices();
        if (kubernetesServices == null) {
            kubernetesServices = new ArrayList<KubernetesService>();
        }

        // Prepare minion public IP addresses
        List<String> minionPrivateIPList = new ArrayList<String>();
        List<String> minionPublicIPList = new ArrayList<String>();
        KubernetesHost[] kubernetesHosts = kubernetesCluster.getKubernetesHosts();
        if ((kubernetesHosts == null) || (kubernetesHosts.length == 0) || (kubernetesHosts[0] == null)) {
            throw new RuntimeException("Hosts not found in kubernetes cluster: [cluster] "
                    + kubernetesCluster.getClusterId());
        }
        for (KubernetesHost host : kubernetesHosts) {
            if (host != null) {
                minionPrivateIPList.add(host.getPrivateIPAddress());
                minionPublicIPList.add(host.getPublicIPAddress());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug(String.format("Minion private IPs: %s", minionPrivateIPList));
        }

        Collection<ClusterPortMapping> clusterPortMappings = CloudControllerContext.getInstance()
                .getClusterPortMappings(clusterContext.getApplicationId(), clusterId);

        if (clusterPortMappings != null) {
            for (ClusterPortMapping clusterPortMapping : clusterPortMappings) {

                // Skip if already created
                int containerPort = clusterPortMapping.getPort();
                if (kubernetesServiceExist(kubernetesServices, containerPort)) {
                    continue;
                }

                // Find next service sequence no
                long serviceSeqNo = kubernetesClusterContext.getServiceSeqNo().incrementAndGet();
                String serviceId = KubernetesIaasUtil.fixSpecialCharacters("service" + "-" + (serviceSeqNo));
                String serviceLabel = KubernetesIaasUtil.fixSpecialCharacters(clusterId);

                if (log.isInfoEnabled()) {
                    log.info(String.format("Creating kubernetes service: [cluster] %s [service] %s " +
                                    "[protocol] %s [service-port] %d [container-port] %s", clusterId,
                            serviceId, clusterPortMapping.getProtocol(),
                            clusterPortMapping.getKubernetesServicePort(), containerPort));
                }

                // Create kubernetes service for port mapping
                int nodePort = clusterPortMapping.getKubernetesServicePort();
                String containerPortName = KubernetesIaasUtil.preparePortNameFromPortMapping(clusterPortMapping);

                try {
                    // Services need to use minions private IP addresses for creating iptable rules
                    kubernetesApi.createService(serviceId, serviceLabel, nodePort, containerPortName,
                            containerPort, sessionAffinity);
                } finally {
                    // Persist kubernetes service sequence no
                    CloudControllerContext.getInstance().persist();
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignore) {
                }

                Service service = kubernetesApi.getService(serviceId);

                KubernetesService kubernetesService = new KubernetesService();
                kubernetesService.setId(service.getMetadata().getName());
                kubernetesService.setPortalIP(service.getSpec().getClusterIP());
                // Expose minions public IP addresses as they need to be accessed by external networks
                String[] minionPublicIPArray = minionPublicIPList.toArray(new String[minionPublicIPList.size()]);
                kubernetesService.setPublicIPs(minionPublicIPArray);
                kubernetesService.setProtocol(clusterPortMapping.getProtocol());
                kubernetesService.setPort(service.getSpec().getPorts().get(0).getNodePort());
                kubernetesService.setContainerPort(containerPort);
                kubernetesServices.add(kubernetesService);

                if (log.isInfoEnabled()) {
                    log.info(String.format("Kubernetes service successfully created: [cluster] %s [service] %s " +
                                    "[protocol] %s [node-port] %d [container-port] %s", clusterId,
                            serviceId, clusterPortMapping.getProtocol(), nodePort, containerPort));
                }
            }
        }

        // Add kubernetes services to cluster context and persist
        clusterContext.setKubernetesServices(kubernetesServices);
        CloudControllerContext.getInstance().persist();
    }

    private boolean kubernetesServiceExist(List<KubernetesService> kubernetesServices, int port) {
        for (KubernetesService kubernetesService : kubernetesServices) {
            if (kubernetesService.getContainerPort() == port) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generate kubernetes service ports for cluster.
     *
     * @param kubernetesClusterContext
     * @param clusterId
     * @param cartridge
     */
    private void generateKubernetesServicePorts(String applicationId, String clusterId,
                                                KubernetesClusterContext kubernetesClusterContext,
                                                Cartridge cartridge) {

        synchronized (KubernetesIaas.class) {
            if (cartridge != null) {

                StringBuilder portMappingStrBuilder = new StringBuilder();
                for (PortMapping portMapping : Arrays.asList(cartridge.getPortMappings())) {

                    int nextServicePort = kubernetesClusterContext.getNextServicePort();
                    if (nextServicePort == -1) {
                        throw new RuntimeException(String.format("Could not generate service port: [cluster-id] %s " +
                                "[port] %d", clusterId, portMapping.getPort()));
                    }

                    Collection<ClusterPortMapping> clusterPortMappings =
                            CloudControllerContext.getInstance().getClusterPortMappings(applicationId, clusterId);
                    if(clusterPortMappings == null) {
                        throw new CloudControllerException(String.format("Cluster port mappings not found: " +
                                "[application-id] %s [cluster-id] %s", applicationId, clusterId));
                    }

                    ClusterPortMapping clusterPortMapping = findClusterPortMapping(clusterPortMappings, portMapping);
                    if(clusterPortMappings == null) {
                        throw new CloudControllerException(String.format("Cluster port mapping not found: " +
                                "[application-id] %s [cluster-id] %s [transport] %s", applicationId, clusterId,
                                portMapping.getName()));
                    }

                    clusterPortMapping.setKubernetesServicePort(nextServicePort);

                    // Add port mappings to payload
                    if (portMappingStrBuilder.toString().length() > 0) {
                        portMappingStrBuilder.append(";");
                    }
                    portMappingStrBuilder.append(String.format("NAME:%s|PROTOCOL:%s|PORT:%d|PROXY_PORT:%d",
                            clusterPortMapping.getName(), clusterPortMapping.getProtocol(),
                            clusterPortMapping.getKubernetesServicePort(), clusterPortMapping.getProxyPort()));

                    if (log.isInfoEnabled()) {
                        log.info(String.format("Kubernetes service port generated: [application-id] %s " +
                                        "[cluster-id] %s [port] %d [service-port] %d",
                                applicationId, clusterId, clusterPortMapping.getPort(),
                                clusterPortMapping.getKubernetesServicePort()));
                    }
                }

                NameValuePair nameValuePair = new NameValuePair(PORT_MAPPINGS, portMappingStrBuilder.toString());
                payload.add(nameValuePair);

                // Persist service ports added to cluster port mappings
                CloudControllerContext.getInstance().persist();
            }
        }
    }


    /**
     * Find cluster port mapping that corresponds to cartridge port mapping.
     * @param clusterPortMappings
     * @param portMapping
     * @return
     */
    private ClusterPortMapping findClusterPortMapping(Collection<ClusterPortMapping> clusterPortMappings, PortMapping portMapping) {
        for(ClusterPortMapping clusterPortMapping : clusterPortMappings) {
            if(clusterPortMapping.getName().equals(portMapping.getName())) {
                return clusterPortMapping;
            }
        }
        return null;
    }

    /**
     * Terminate all the containers belong to a cluster by cluster id.
     *
     * @param clusterId
     * @return
     * @throws InvalidClusterException
     */
    public MemberContext[] terminateContainers(String clusterId)
            throws InvalidClusterException {
        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireMemberContextWriteLock();

            ClusterContext clusterContext = CloudControllerContext.getInstance().getClusterContext(clusterId);
            handleNullObject(clusterContext, "Could not terminate containers, cluster not found: [cluster-id] "
                    + clusterId);

            String kubernetesClusterId = clusterContext.getKubernetesClusterId();
            handleNullObject(kubernetesClusterId, "Could not terminate containers, kubernetes cluster id not found: " +
                    "[cluster-id] " + clusterId);

            KubernetesClusterContext kubClusterContext = CloudControllerContext.getInstance().
                    getKubernetesClusterContext(kubernetesClusterId);
            handleNullObject(kubClusterContext, "Could not terminate containers, kubernetes cluster not found: " +
                    "[kubernetes-cluster-id] " + kubernetesClusterId);

            KubernetesApiClient kubApi = kubClusterContext.getKubApi();

            // Remove kubernetes services
            List<KubernetesService> kubernetesServices = clusterContext.getKubernetesServices();
            if (kubernetesServices != null) {
                for (KubernetesService kubernetesService : kubernetesServices) {
                    try {
                        kubApi.deleteService(kubernetesService.getId());
                        int allocatedPort = kubernetesService.getPort();
                        kubClusterContext.deallocatePort(allocatedPort);
                    } catch (KubernetesClientException e) {
                        log.error("Could not remove kubernetes service: [cluster-id] " + clusterId, e);
                    }
                }
            }

            List<MemberContext> memberContextsRemoved = new ArrayList<MemberContext>();
            List<MemberContext> memberContexts = CloudControllerContext.getInstance().getMemberContextsOfClusterId(clusterId);
            if (memberContexts != null) {
                for (MemberContext memberContext : memberContexts) {
                    try {
                        MemberContext memberContextRemoved = terminateContainer(memberContext.getMemberId());
                        memberContextsRemoved.add(memberContextRemoved);
                    } catch (MemberTerminationFailedException e) {
                        String message = "Could not terminate container: [member-id] " + memberContext.getMemberId();
                        log.error(message);
                    }
                }
            }

            // Persist changes
            CloudControllerContext.getInstance().persist();
            return memberContextsRemoved.toArray(new MemberContext[memberContextsRemoved.size()]);
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
    }

    /**
     * Terminate a container by member id
     *
     * @param memberId
     * @return
     * @throws MemberTerminationFailedException
     */
    public MemberContext terminateContainer(String memberId) throws MemberTerminationFailedException {
        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireMemberContextWriteLock();
            handleNullObject(memberId, "Could not terminate container, member id is null");

            MemberContext memberContext = CloudControllerContext.getInstance().getMemberContextOfMemberId(memberId);
            handleNullObject(memberContext, "Could not terminate container, member context not found: [member-id] " + memberId);

            String clusterId = memberContext.getClusterId();
            handleNullObject(clusterId, "Could not terminate container, cluster id is null: [member-id] " + memberId);

            ClusterContext clusterContext = CloudControllerContext.getInstance().getClusterContext(clusterId);
            handleNullObject(clusterContext, String.format("Could not terminate container, cluster context not found: " +
                    "[cluster-id] %s [member-id] %s", clusterId, memberId));

            String kubernetesClusterId = clusterContext.getKubernetesClusterId();
            handleNullObject(kubernetesClusterId, String.format("Could not terminate container, kubernetes cluster " +
                    "context id is null: [cluster-id] %s [member-id] %s", clusterId, memberId));

            KubernetesClusterContext kubernetesClusterContext = CloudControllerContext.getInstance().getKubernetesClusterContext(kubernetesClusterId);
            handleNullObject(kubernetesClusterContext, String.format("Could not terminate container, kubernetes cluster " +
                    "context not found: [cluster-id] %s [member-id] %s", clusterId, memberId));
            KubernetesApiClient kubApi = kubernetesClusterContext.getKubApi();

            try {
                log.info(String.format("Removing kubernetes pod: [application] %s [cartridge] %s [member] %s [pod] %s",
                        memberContext.getApplicationId(), memberContext.getCartridgeType(), memberContext.getMemberId(),
                        memberContext.getKubernetesPodId()));

                // Remove pod
                kubApi.deletePod(memberContext.getKubernetesPodId());
                // Persist changes
                CloudControllerContext.getInstance().persist();

                log.info(String.format("Kubernetes pod removed successfully: [application] %s [cartridge] %s " +
                                "[member] %s [pod] %s",
                        memberContext.getApplicationId(), memberContext.getCartridgeType(), memberContext.getMemberId(),
                        memberContext.getKubernetesPodId()));
            } catch (KubernetesClientException ignore) {
                // we can't do nothing here
                log.warn(String.format("Could not delete pod: [pod-id] %s", memberContext.getKubernetesPodId()));
            }
            return memberContext;
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
    }

    /**
     * Get kubernetes cluster context
     *
     * @param kubernetesClusterId
     * @param kubernetesMasterIp
     * @param kubernetesMasterPort
     * @param upperPort
     * @param lowerPort
     * @return
     */
    private KubernetesClusterContext getKubernetesClusterContext(String kubernetesClusterId, String kubernetesMasterIp,
                                                                 String kubernetesMasterPort, int upperPort, int lowerPort) {

        KubernetesClusterContext kubernetesClusterContext = CloudControllerContext.getInstance().
                getKubernetesClusterContext(kubernetesClusterId);
        if (kubernetesClusterContext != null) {
            return kubernetesClusterContext;
        }

        kubernetesClusterContext = new KubernetesClusterContext(kubernetesClusterId, kubernetesMasterIp,
                kubernetesMasterPort, lowerPort, upperPort);
        CloudControllerContext.getInstance().addKubernetesClusterContext(kubernetesClusterContext);
        return kubernetesClusterContext;
    }

    private String readProperty(String property, org.apache.stratos.common.Properties properties, String object) {
        String propVal = CloudControllerUtil.getProperty(properties, property);
        handleNullObject(propVal, "Property validation failed. Could not find property: '" + property + " in " + object);
        return propVal;

    }

    private void handleNullObject(Object obj, String errorMsg) {
        if (obj == null) {
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
    }

    @Override
    public void releaseAddress(String ip) {

    }

    @Override
    public boolean isValidRegion(String region) throws InvalidRegionException {
        // No regions in kubernetes cluster
        return true;
    }

    @Override
    public boolean isValidZone(String region, String zone) throws InvalidZoneException, InvalidRegionException {
        // No zones in kubernetes cluster
        return true;
    }

    @Override
    public boolean isValidHost(String zone, String host) throws InvalidHostException {
        // No zones in kubernetes cluster
        return true;
    }

    @Override
    public String createVolume(int sizeGB, String snapshotId) {
        throw new NotImplementedException();
    }

    @Override
    public String attachVolume(String instanceId, String volumeId, String deviceName) {
        throw new NotImplementedException();
    }

    @Override
    public void detachVolume(String instanceId, String volumeId) {
        throw new NotImplementedException();
    }

    @Override
    public void deleteVolume(String volumeId) {
        throw new NotImplementedException();
    }

    @Override
    public String getIaasDevice(String device) {
        throw new NotImplementedException();
    }

    @Override
    public void allocateIpAddresses(String clusterId, MemberContext memberContext, Partition partition) {
    }

    /**
     * Remove kubernetes services if available for application cluster.
     * @param applicationId
     * @param clusterId
     */
    public static void removeKubernetesServices(String applicationId, String clusterId) {

        ClusterContext clusterContext =
                CloudControllerContext.getInstance().getClusterContext(clusterId);
        if(clusterContext != null) {
            String kubernetesClusterId = clusterContext.getKubernetesClusterId();
            if(org.apache.commons.lang3.StringUtils.isNotBlank(kubernetesClusterId)) {
                KubernetesClusterContext kubernetesClusterContext =
                        CloudControllerContext.getInstance().getKubernetesClusterContext(kubernetesClusterId);
                if(kubernetesClusterContext != null) {
                    KubernetesApiClient kubernetesApiClient = kubernetesClusterContext.getKubApi();
                    for (KubernetesService kubernetesService : clusterContext.getKubernetesServices()) {
                        log.info(String.format("Deleting kubernetes service: [application-id] %s " +
                                "[service-id] %s", applicationId, kubernetesService.getId()));
                        try {
                            kubernetesApiClient.deleteService(kubernetesService.getId());
                        } catch (KubernetesClientException e) {
                            log.error(String.format("Could not delete kubernetes service: [application-id] %s " +
                                    "[service-id] %s", applicationId, kubernetesService.getId()));
                        }
                    }
                }
            }
        }
    }
}
