/*
 *
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
 *
*/

package org.apache.stratos.adc.mgt.utils;


import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.axis2.clustering.ClusteringAgent;
import org.apache.axis2.clustering.Member;
import org.apache.axis2.clustering.management.GroupManagementAgent;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.adc.mgt.client.CloudControllerServiceClient;
import org.apache.stratos.adc.mgt.dao.CartridgeSubscriptionInfo;
import org.apache.stratos.adc.mgt.dao.DataCartridge;
import org.apache.stratos.adc.mgt.dao.PortMapping;
import org.apache.stratos.adc.mgt.dns.DNSManager;
import org.apache.stratos.adc.mgt.dto.Cartridge;
import org.apache.stratos.adc.mgt.dto.Policy;
import org.apache.stratos.adc.mgt.dto.RepositoryInformation;
import org.apache.stratos.adc.mgt.dto.SubscriptionInfo;
import org.apache.stratos.adc.mgt.exception.ADCException;
import org.apache.stratos.adc.mgt.exception.DuplicateCartridgeAliasException;
import org.apache.stratos.adc.mgt.exception.InvalidCartridgeAliasException;
import org.apache.stratos.adc.mgt.exception.InvalidRepositoryException;
import org.apache.stratos.adc.mgt.exception.NotSubscribedException;
import org.apache.stratos.adc.mgt.exception.RepositoryCredentialsRequiredException;
import org.apache.stratos.adc.mgt.exception.RepositoryRequiredException;
import org.apache.stratos.adc.mgt.exception.RepositoryTransportException;
import org.apache.stratos.adc.mgt.exception.UnregisteredCartridgeException;
import org.apache.stratos.adc.mgt.internal.DataHolder;
import org.apache.stratos.adc.mgt.repository.Repository;
import org.apache.stratos.adc.mgt.service.RepositoryInfoBean;
import org.apache.stratos.adc.topology.mgt.service.TopologyManagementService;
import org.apache.stratos.cloud.controller.stub.CloudControllerServiceUnregisteredCartridgeExceptionException;
import org.apache.stratos.cloud.controller.util.xsd.CartridgeInfo;
import org.apache.stratos.cloud.controller.util.xsd.Properties;
import org.apache.stratos.cloud.controller.util.xsd.Property;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.storage.file.FileRepository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

/**
 * This class contains utility methods used by ApplicationManagementService.
 */
public class ApplicationManagementUtil {

    private static Log log = LogFactory.getLog(ApplicationManagementUtil.class);
    //private static volatile CloudControllerServiceClient serviceClient;

   
        

    protected static String getAppDeploymentDirPath(String cartridge, AxisConfiguration axisConfig) {
        return axisConfig.getRepository().getPath() + File.separator + cartridge;
    }

    public static CartridgeSubscriptionInfo createCartridgeSubscription(CartridgeInfo cartridgeInfo,
                                                                    Policy policy,
                                                                    String cartridgeType,
                                                                    String cartridgeName,
                                                                    int tenantId,
                                                                    String tenantDomain,
                                                                    Repository repository,
                                                                    String hostName,
                                                                    String clusterDomain,
                                                                    String clusterSubDomain,
                                                                    String mgtClusterDomain,
                                                                    String mgtClusterSubDomain,
                                                                    DataCartridge dataCartridge,
                                                                    String state,
                                                                    String subscribeKey) {

        CartridgeSubscriptionInfo cartridgeSubscriptionInfo = new CartridgeSubscriptionInfo();
        cartridgeSubscriptionInfo.setCartridge(cartridgeType);
        cartridgeSubscriptionInfo.setAlias(cartridgeName);
        cartridgeSubscriptionInfo.setClusterDomain(clusterDomain);
        cartridgeSubscriptionInfo.setClusterSubdomain(clusterSubDomain);
        cartridgeSubscriptionInfo.setMgtClusterDomain(mgtClusterDomain);
        cartridgeSubscriptionInfo.setMgtClusterSubDomain(mgtClusterSubDomain);
        cartridgeSubscriptionInfo.setHostName(hostName);
        cartridgeSubscriptionInfo.setPolicy(policy.getName());
        cartridgeSubscriptionInfo.setRepository(repository);
        cartridgeSubscriptionInfo.setPortMappings(createPortMappings(cartridgeInfo));
        cartridgeSubscriptionInfo.setProvider(cartridgeInfo.getProvider());
        cartridgeSubscriptionInfo.setDataCartridge(dataCartridge);
        cartridgeSubscriptionInfo.setTenantId(tenantId);
        cartridgeSubscriptionInfo.setTenantDomain(tenantDomain);
        cartridgeSubscriptionInfo.setBaseDirectory(cartridgeInfo.getBaseDir());
        //cartridgeSubscriptionInfo.setState("PENDING");
        cartridgeSubscriptionInfo.setState(state);
        cartridgeSubscriptionInfo.setSubscriptionKey(subscribeKey);
        return cartridgeSubscriptionInfo;
    }



	private static List<PortMapping> createPortMappings(CartridgeInfo cartridgeInfo) {
        List<PortMapping> portMappings = new ArrayList<PortMapping>();

        if (cartridgeInfo.getPortMappings() != null) {
            for (org.apache.stratos.cloud.controller.util.xsd.PortMapping portMapping : cartridgeInfo.getPortMappings()) {
                PortMapping portMap = new PortMapping();
                portMap.setPrimaryPort(portMapping.getPort());
                portMap.setProxyPort(portMapping.getProxyPort());
                portMap.setType(portMapping.getProtocol());
                portMappings.add(portMap);
            }
        }
        return portMappings;
    }

    /*public static CloudControllerServiceClient getServiceClient() throws AxisFault {
        if (serviceClient == null) {
            synchronized (CloudControllerServiceClient.class) {
                if (serviceClient == null) {
                    serviceClient = new CloudControllerServiceClient(
                            System.getProperty(CartridgeConstants.AUTOSCALER_SERVICE_URL));
                }
            }
        }
        return serviceClient;
    }*/

    public static int getTenantId(ConfigurationContext configurationContext) {
        int tenantId = MultitenantUtils.getTenantId(configurationContext);
        if(log.isDebugEnabled()) {
            log.debug("Returning tenant ID : " + tenantId);
        }
        return tenantId;
    }

	public static void validateCartridgeAlias(String alias, String cartridgeType) throws InvalidCartridgeAliasException, DuplicateCartridgeAliasException, ADCException {
		// Do not use quotes in messages, current UI JavaScript does not work if there are quotes
		// TODO: Fix message display in UI
		String patternString = "([a-z0-9]+([-][a-z0-9])*)+";
        Pattern pattern = Pattern.compile(patternString);
        
        if (!pattern.matcher(alias).matches()) {
        	String msg = "The alias " + alias + " can contain only alpha-numeric lowercase characters. Please enter a valid alias.";
			log.error(msg);
			throw new InvalidCartridgeAliasException(msg, cartridgeType, alias);
        }
		
		boolean isAliasTaken = false;
		try {			
			isAliasTaken = PersistenceManager.isAliasAlreadyTaken(alias, cartridgeType);			
		} catch (Exception e) {
			String msg = "Exception : " + e.getMessage();
			log.error(msg, e);
			throw new ADCException("Error when checking alias is already taken", e);
		}
		
		if (isAliasTaken) {
			String msg = "The alias " + alias + " is already taken. Please try again with a different alias.";
			log.error(msg);
			throw new DuplicateCartridgeAliasException(msg, cartridgeType, alias);
		}
	}
	
	public static RepositoryInformation validateRepository(String repoURL, String repoUsername, String repoPassword,
			boolean privateRepo, boolean testConnection) throws RepositoryRequiredException, ADCException,
			RepositoryCredentialsRequiredException, InvalidRepositoryException, RepositoryTransportException {
		RepositoryInformation repositoryInformation = new RepositoryInformation();
		repositoryInformation.setRepoURL(repoURL);
		if (log.isDebugEnabled()) {
			log.debug("Validating Git Repository");
		}

		if (repoURL != null && repoURL.trim().length() > 0 && privateRepo) {
			if (log.isDebugEnabled()) {
				log.debug("External repo validation is a private repo: " + repoURL);
			}
			if (repoUsername == null || repoUsername.trim().length() == 0 || repoPassword == null
					|| repoPassword.trim().length() == 0) {
				throw new RepositoryCredentialsRequiredException(
						"Username and Password are required for private repository");
			}
		}

		if (!testConnection) {
			if (log.isDebugEnabled()) {
				log.debug("External repo validation is not enabled");
			}
			return repositoryInformation;
		}

		if (repoURL == null || repoURL.trim().length() == 0) {
			// This means, no repo to validate.
			return repositoryInformation;
		}
		
		if (log.isDebugEnabled()) {
			log.debug("External repo validation enabled");
		}

		// This assumes running on Linux.
		String parentDirName = "/tmp/" + UUID.randomUUID().toString();
		CredentialsProvider credentialsProvider = null;
		if (repoUsername != null && repoUsername.trim().length() > 0 && repoPassword != null
				&& repoPassword.trim().length() > 0) {
			if (log.isDebugEnabled()) {
				log.debug("External repo credentails are passed: " + repoUsername);
			}
			credentialsProvider = new UsernamePasswordCredentialsProvider(repoUsername, repoPassword.toCharArray());
		}

		// Initialize temp local file repo
		FileRepository localRepo = null;
		try {
			File f = new File(parentDirName + "/.git");
			localRepo = new FileRepository(f);
			if (log.isDebugEnabled()) {
				log.debug("Local File Repo: " + f.getAbsoluteFile());
			}
		} catch (IOException e) {
			throw new ADCException("Error creating local file repo", e);
		}

		Git git = new Git(localRepo);
		LsRemoteCommand cmd = git.lsRemote().setRemote(repoURL);
		if (credentialsProvider != null) {
			cmd.setCredentialsProvider(credentialsProvider);
		}
		try {
			Collection<Ref> collection = cmd.call();
			List<String> refNames = new ArrayList<String>();
			if (collection != null) {
				for (Ref ref : collection) {
					if (log.isDebugEnabled()) {
						log.debug(repoURL + ": " + ref.getName());
					}
					refNames.add(ref.getName());
				}
			}
			repositoryInformation.setRefName(refNames.toArray(new String[refNames.size()]));
		} catch (InvalidRemoteException e) {
			throw new InvalidRepositoryException("Provided repository url is not valid", e);
		} catch (TransportException e) {
			throw new RepositoryTransportException("Transport error when checking remote repository", e);
		} catch (GitAPIException e) {
			throw new ADCException("Git API error when checking remote repository", e);
		}
		return repositoryInformation;
	}

    public static String generatePassword() {

        final int PASSWORD_LENGTH = 8;
        StringBuffer sb = new StringBuffer();
        for (int x = 0; x < PASSWORD_LENGTH; x++) {
            sb.append((char) ((int) (Math.random() * 26) + 97));
        }
        return sb.toString();

    }

    public static Properties setRegisterServiceProperties(Policy policy, int tenantId, String alias) {
    	
    	DecimalFormat df = new DecimalFormat("##.##");
        df.setParseBigDecimal(true);

        Properties properties = new Properties();
        List<Property> allProperties = new ArrayList<Property>();
        // min_app_instances
        Property property = new Property();
        property.setName("min_app_instances");
        property.setValue(df.format(policy.getMinAppInstances()));
        allProperties.add(property);
        
        
     // max_app_instances
        property = new Property();
        property.setName("max_app_instances");
        property.setValue(df.format(policy.getMaxAppInstances()));
        allProperties.add(property);
        
        // max_requests_per_second
        property = new Property();
        property.setName("max_requests_per_second");
        property.setValue(df.format(policy.getMaxRequestsPerSecond()));
        allProperties.add(property);
        
        // alarming_upper_rate
        property = new Property();
        property.setName("alarming_upper_rate");
        property.setValue(df.format(policy.getAlarmingUpperRate()));
        allProperties.add(property);
        
     // alarming_lower_rate
        property = new Property();
        property.setName("alarming_lower_rate");
        property.setValue(df.format(policy.getAlarmingLowerRate()));
        allProperties.add(property);
        
        // scale_down_factor
        property = new Property();
        property.setName("scale_down_factor");
        property.setValue(df.format(policy.getScaleDownFactor()));
        allProperties.add(property);
        
     // rounds_to_average
        property = new Property();
        property.setName("rounds_to_average");
        property.setValue(df.format(policy.getRoundsToAverage()));
        allProperties.add(property);
        
       // tenant id
        property = new Property();
        property.setName("tenant_id");
        property.setValue(String.valueOf(tenantId));
        allProperties.add(property);
        
        // alias
        property = new Property();
        property.setName("alias");
        property.setValue(String.valueOf(alias));
        allProperties.add(property);
        
        properties.setProperties(allProperties.toArray(new Property[allProperties.size()]));
        return properties;
    }

    private static String convertRepoURL(String gitURL) {
        String convertedHttpURL = null;
        if (gitURL != null && gitURL.startsWith("git@")) {
            StringBuilder httpRepoUrl = new StringBuilder();
            httpRepoUrl.append("http://");
            String[] urls = gitURL.split(":");
            String[] hostNameArray = urls[0].split("@");
            String hostName = hostNameArray[1];
            httpRepoUrl.append(hostName).append("/").append(urls[1]);
            convertedHttpURL = httpRepoUrl.toString();
        } else if (gitURL != null && gitURL.startsWith("http")) {
            convertedHttpURL = gitURL;
        }
        return convertedHttpURL;
    }

    public static void addDNSEntry(String alias, String cartridgeType) {
        new DNSManager().addNewSubDomain(alias + "." + cartridgeType, System.getProperty(CartridgeConstants.ELB_IP));
    }

    public static SubscriptionInfo createSubscriptionResponse(CartridgeSubscriptionInfo cartridgeSubscriptionInfo, Repository repository) {
    	SubscriptionInfo subscriptionInfo = new SubscriptionInfo();
    	
        if (repository != null && repository.getUrl() != null) {
        	subscriptionInfo.setRepositoryURL(convertRepoURL(repository.getUrl()));
        }
        
        subscriptionInfo.setHostname(cartridgeSubscriptionInfo.getHostName());
        
        return subscriptionInfo;
    }

    private static String getDynamicClusterDomain(String cartridgeType, String cartName,
                                                  CartridgeInfo cartridgeInfo) {
        return cartName + "." + cartridgeInfo.getHostName() + "." + cartridgeType +
                ".domain";
    }

    private static Repository manageRepository(String repoURL, String repoUserName, String repoUserPassword,
                                               String cartName, CartridgeInfo cartridgeInfo, String username,
                                               String tenantDomain)
            throws Exception {

        Repository repository = new Repository();
        if (repoURL != null && repoURL.trim().length() > 0) {
            log.info("External REPO URL is provided as [" + repoURL +
                    "]. Therefore not creating a new repo.");
            //repository.setRepoName(repoURL.substring(0, repoURL.length()-4)); // remove .git part
            repository.setUrl(repoURL);
            repository.setUserName(repoUserName);
            repository.setPassword(repoUserPassword);
        } else {

            //log.info("External REPO URL is not provided. Therefore creating a new repo. Adding to Executor");
            log.info("External git repo url not provided for tenant "
                    + tenantDomain + ", creating an git internal repository");

            // for internal repos  internal git server username and password is used.
            repository.setUserName(System.getProperty(CartridgeConstants.INTERNAL_GIT_USERNAME));
            repository.setPassword(System.getProperty(CartridgeConstants.INTERNAL_GIT_PASSWORD));
            /*repoCreationExecutor.execute(new RepositoryCreator(new RepositoryInfoBean(repoURL,
                    cartName,
                    tenantDomain,
                    repository.getRepoUserName(),
                    repository.getRepoUserPassword(),
                    cartridgeInfo.getDeploymentDirs(),
                    cartridgeInfo)));*/
            new RepositoryCreator(new RepositoryInfoBean(repoURL,
                    cartName,
                    tenantDomain,
                    repository.getUserName(),
                    repository.getPassword(),
                    cartridgeInfo.getDeploymentDirs(),
                    cartridgeInfo)).createInternalRepository();
            String repoName = tenantDomain + "/" + cartName;
            repository.setUrl("https://" + System.getProperty(CartridgeConstants.GIT_HOST_NAME) + ":8443/git/" + repoName);

        }
        return repository;
    }

    public static Cartridge getCartridgeInfo(String alias, String tenantDomain) throws ADCException, NotSubscribedException {
        log.info("Alias: " + alias);
        if (alias == null) {
            String msg = "Provided alias is empty";
            log.error(msg);
            throw new ADCException("Alias you provided is empty.");
        }

        CartridgeSubscriptionInfo sub;
        try {
            sub = PersistenceManager.getSubscription(tenantDomain, alias);
        } catch (Exception e) {
            String message = "Cannot get subscription info for " + tenantDomain + " and alias " + alias;
            log.error(message, e);
            throw new ADCException(message, e);
        }
        if (sub == null) {
            String msg = "Info request for not subscribed cartridge";
            log.error(msg);
            throw new NotSubscribedException("You have not subscribed for " + alias, alias);
        }

        log.info("Cluster domain : " + sub.getClusterDomain() + " cartridge: " + sub.getCartridge());
        
        CartridgeInfo cartridgeInfo = null;
        try {
            cartridgeInfo = CloudControllerServiceClient.getServiceClient().getCartridgeInfo(sub.getCartridge());
        } catch (Exception e) {
            throw new ADCException("Cannot get cartridge info: " + sub.getCartridge(), e);
        }

        TopologyManagementService topologyMgtService = DataHolder.getTopologyMgtService();

        String[] ips =
                topologyMgtService.getActiveIPs(sub.getCartridge(),
                        sub.getClusterDomain(),
                        sub.getClusterSubdomain());
        return populateCartridgeInfo(cartridgeInfo, sub, ips, tenantDomain);
    }

    public static void registerService(String cartridgeType, String domain, String subDomain,
                                        StringBuilder payload, String tenantRange, String hostName, Properties properties)
            throws ADCException, UnregisteredCartridgeException {
        log.info("Register service..");
        try {
            CloudControllerServiceClient.getServiceClient().register(domain, cartridgeType, payload.toString(), tenantRange,
                    hostName, properties, "economyPolicy");
        } catch (CloudControllerServiceUnregisteredCartridgeExceptionException e) {
            String msg = "Exception is occurred in register service operation. Reason :" + e.getMessage();
            log.error(msg, e);
            throw new UnregisteredCartridgeException("Not a registered cartridge " + cartridgeType, cartridgeType, e);
        } catch (RemoteException e) {
        	log.error("Remote Error", e);
        	throw new ADCException("An error occurred in subscribing process");
        }
    }

    public static Cartridge populateCartridgeInfo(CartridgeInfo cartridgeInfo, CartridgeSubscriptionInfo sub, String[] ips, String tenantDomain) throws ADCException {
    	Cartridge cartridge = new Cartridge();
        if (ips != null && ips.length > 0) {
			if (log.isDebugEnabled()) {
				log.debug("Found IPs: " + Arrays.toString(ips) + " for " + cartridgeInfo.getType() + ", "
						+ sub.getAlias());
			}
			if (CartridgeConstants.DATA_CARTRIDGE_PROVIDER.equals(sub.getProvider())) {
				// FIXME Temporary fix for SPI-301
				cartridge.setIp(ips[ips.length - 1]);
				if (sub.getDataCartridge() != null) {
					if (log.isDebugEnabled()) {
						log.debug("Data Cartridge Info: " + sub.getDataCartridge().getUserName());
					}
					cartridge.setPassword(sub.getDataCartridge().getPassword());
					cartridge.setDbUserName(sub.getDataCartridge().getUserName());
				}
			}
			if (cartridgeInfo.getMultiTenant()) { // TODO refactor logic for carbon cartridges.
				cartridge.setStatus(CartridgeConstants.ACTIVE);
				cartridge.setActiveInstances(ips.length);
			} else {
				Map<String, String> instanceIpMap;
				try {
					instanceIpMap = PersistenceManager.getCartridgeInstanceInfo(ips, sub.getClusterDomain(),
							sub.getClusterSubdomain());
					cartridge.setActiveInstances(ips.length);
					cartridge.setStatus(CartridgeConstants.ACTIVE);
				} catch (Exception e) {
					throw new ADCException("Error checking cartridge status");
				}

                if(cartridgeInfo.getProvider().equalsIgnoreCase(CartridgeConstants.PROVIDER_NAME_WSO2)) {
                    cartridge.setActiveInstances(ips.length);
                    cartridge.setStatus(CartridgeConstants.ACTIVE);
                    /*List<Member> members = getMemberInstances(sub.getClusterDomain(), sub.getClusterSubdomain());
                    if(members != null) {
                        int activeInstancesCount = 0;
                        for (Member member : members) {
                            if (member != null && member.isActive() && member.getDomain().equals(sub.getClusterDomain())) {
                                cartridge.setStatus(CartridgeConstants.ACTIVE);
                                activeInstancesCount ++;
                            }
                        }
                        cartridge.setActiveInstances(activeInstancesCount);
                    }*/
                }
			}
        } else {
            log.warn("IPs have not returned through Topology Management for " + sub.getAlias());
            cartridge.setStatus(CartridgeConstants.SUBSCRIBED);
        }
        cartridge.setDisplayName(cartridgeInfo.getDisplayName());
        cartridge.setDescription(cartridgeInfo.getDescription());
        cartridge.setVersion(cartridgeInfo.getVersion());
        cartridge.setMultiTenant(cartridgeInfo.getMultiTenant());

        List<String> accessURLs = new ArrayList<String>();

        if (cartridgeInfo.getPortMappings() != null) {
            for (org.apache.stratos.cloud.controller.util.xsd.PortMapping portMapping : cartridgeInfo
                    .getPortMappings()) {
                if (portMapping != null) {
					try {
						int port = Integer.parseInt(portMapping.getProxyPort());
	                	String protocol = portMapping.getProtocol();

	                	if ("http".equals(protocol) && port == 80) {
	                	    port = -1;
	                	} else if ("https".equals(protocol) && port == 443) {
	                	    port = -1;
	                	}
	                	
	                	String file = "";
	                	if (CartridgeConstants.PROVIDER_NAME_WSO2.equals(cartridgeInfo.getProvider()) && !cartridgeInfo.getMultiTenant()) {
	                		// Carbon Private Jet Cartridges
	                		file = "/t/" + tenantDomain;
	                	}
	                	
	                	URL serverURL = new URL(protocol, sub.getHostName(), port, file);
						accessURLs.add(serverURL.toExternalForm());
					} catch (MalformedURLException e) {
						if (log.isErrorEnabled()) {
							log.error("Error getting access URL for " + cartridgeInfo.getType(), e);
						}
					} catch (NumberFormatException e) {
						if (log.isErrorEnabled()) {
							log.error("Error getting port of access URL for " + cartridgeInfo.getType(), e);
						}
					}
                }
            }
        }

        cartridge.setAccessURLs(accessURLs.toArray(new String[accessURLs.size()]));

        cartridge.setCartridgeAlias(sub.getAlias());
        cartridge.setCartridgeType(sub.getCartridge());
        cartridge.setHostName(sub.getHostName());
        cartridge.setPolicy(sub.getPolicy());
        Policy policy = PolicyHolder.getInstance().getPolicy(sub.getPolicy());
        if (policy != null) {
        	cartridge.setPolicyDescription(policy.getDescription());
        }
        cartridge.setProvider(sub.getProvider());
        cartridge.setMappedDomain(sub.getMappedDomain());

        if (sub.getRepository() != null) {
            cartridge.setRepoURL(convertRepoURL(sub.getRepository().getUrl()));
        }
        return cartridge;
    }

    private static List<Member> getMemberInstances(String domain, String subDomain) {

        ClusteringAgent clusteringAgent = DataHolder.getServerConfigContext()
                .getAxisConfiguration().getClusteringAgent();
        GroupManagementAgent groupMgtAgent = clusteringAgent.getGroupManagementAgent(domain, subDomain);

        if (groupMgtAgent == null) {
            log.warn("Group Management Agent not found for domain : " + domain +
                    ", sub domain : " + subDomain);
            return null;
        }

        List<Member> members = groupMgtAgent.getMembers();
        if (members == null || members.isEmpty()) {
            return null;
        }

        return members;
    }

    private static String checkCartridgeStatus(Map<String, String> instanceIpMap) {
        if (instanceIpMap.values().contains(CartridgeConstants.ACTIVE)) {
            return CartridgeConstants.ACTIVE;
        } else
            return CartridgeConstants.NOT_READY;
    }
}
