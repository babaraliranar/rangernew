/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.unixusersync.process;

import java.io.IOException;
import java.net.UnknownHostException;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.StringTokenizer;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

import javax.security.auth.Subject;
import jakarta.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.NewCookie;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.security.SecureClientLogin;
import org.apache.ranger.authorization.utils.JsonUtils;
import org.apache.ranger.ugsyncutil.model.GroupUserInfo;
import org.apache.ranger.ugsyncutil.model.UgsyncAuditInfo;
import org.apache.ranger.ugsyncutil.model.UsersGroupRoleAssignments;
import org.apache.ranger.ugsyncutil.model.XGroupInfo;
import org.apache.ranger.ugsyncutil.model.XUserInfo;
import org.apache.ranger.ugsyncutil.util.UgsyncCommonConstants;
import org.apache.ranger.unixusersync.config.UserGroupSyncConfig;
import org.apache.ranger.unixusersync.model.GetXGroupListResponse;
import org.apache.ranger.unixusersync.model.GetXUserListResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.ClientResponse;
import org.apache.ranger.usergroupsync.AbstractUserGroupSource;
import org.apache.ranger.usergroupsync.UserGroupSink;


public class PolicyMgrUserGroupBuilder extends AbstractUserGroupSource implements UserGroupSink {

	private static final Logger LOG = LoggerFactory.getLogger(PolicyMgrUserGroupBuilder.class);
	private static final String AUTHENTICATION_TYPE = "hadoop.security.authentication";
	private static final String AUTH_KERBEROS      = "kerberos";
	private static final String KERBEROS_PRINCIPAL = "ranger.usersync.kerberos.principal";
	private static final String KERBEROS_KEYTAB    = "ranger.usersync.kerberos.keytab";
	private static final String NAME_RULE       = "hadoop.security.auth_to_local";
	public static final String PM_USER_LIST_URI  = "/service/xusers/users/";				// GET
	private static final String PM_ADD_USERS_URI = "/service/xusers/ugsync/users";	// POST
	private static final String PM_ADD_GROUP_USER_LIST_URI = "/service/xusers/ugsync/groupusers";	// POST
	public static final String PM_GROUP_LIST_URI = "/service/xusers/groups/";				// GET
	private static final String PM_ADD_GROUPS_URI = "/service/xusers/ugsync/groups/";				// POST
	public static final String PM_GET_ALL_GROUP_USER_MAP_LIST_URI = "/service/xusers/ugsync/groupusers";		// GET
	private static final String PM_AUDIT_INFO_URI = "/service/xusers/ugsync/auditinfo/";				// POST
	public static final String PM_UPDATE_USERS_ROLES_URI  = "/service/xusers/users/roleassignments";	// PUT
	private static final String PM_UPDATE_DELETED_USERS_URI = "/service/xusers/ugsync/users/visibility";	// POST
	private static final String PM_UPDATE_DELETED_GROUPS_URI = "/service/xusers/ugsync/groups/visibility";	// POST
	private static final Pattern USER_OR_GROUP_NAME_VALIDATION_REGEX =
			Pattern.compile("^([A-Za-z0-9_]|[\u00C0-\u017F])([a-zA-Z0-9\\s,._\\-+/@= ]|[\u00C0-\u017F])+$", Pattern.CASE_INSENSITIVE);

	private static final String SOURCE_EXTERNAL ="1";
	private static final String STATUS_ENABLED = "1";
	private static final String ISVISIBLE = "1";
	private static final String ISHIDDEN = "0";

	private static String localHostname        = "unknown";
	private String        recordsToPullPerCall = "10";
	private boolean isMockRun = false;
	private String policyMgrBaseUrl;
	private Cookie sessionId=null;
	private boolean isValidRangerCookie=false;
	List<NewCookie> cookieList=new ArrayList<>();
	private boolean isStartupFlag;

	private volatile RangerUgSyncRESTClient ldapUgSyncClient;

	private Map<String, XUserInfo> userCache; // Key is user name as in ranger db
	private Map<String, XGroupInfo> groupCache; // Key is group name as in ranger db
	private Map<String, Set<String>> groupUsersCache; // Key is group name and value is set of user names (as stored in ranger DB)
	private Map<String, String> groupNameMap; // Key is group DN and value is group name as stored in ranger DB
	private Map<String, String> userNameMap; // Key is user DN and value is user name as stored in ranger DB

	private Map<String, XGroupInfo> deltaGroups;
	private Map<String, XUserInfo> deltaUsers;
	private Map<String, Set<String>> deltaGroupUsers;
	private Set<String> computeRolesForUsers;

	private Map<String, XGroupInfo> deletedGroups;
	private Map<String, XUserInfo> deletedUsers;

	private int noOfNewUsers;
	private int noOfNewGroups;
	private int noOfModifiedUsers;
	private int noOfModifiedGroups;
	private int noOfDeletedUsers = 0;
	private int noOfDeletedGroups = 0;

	private boolean userNameCaseConversionFlag;
	private boolean groupNameCaseConversionFlag;
	private boolean userNameLowerCaseFlag = false;
	private boolean groupNameLowerCaseFlag = false;
	private String currentSyncSource;
	private String ldapUrl;
	private boolean isUserSyncNameValidationEnabled = false;
	private boolean isSyncSourceValidationEnabled = false;

	private String authenticationType = null;
	String principal;
	String keytab;
	String policyMgrUserName;
	String nameRules;
	Map<String, String> userMap = new LinkedHashMap<>();
	Map<String, String> groupMap = new LinkedHashMap<>();
	Map<String, String> whiteListUserMap = new LinkedHashMap<>();
	Map<String, String> whiteListGroupMap = new LinkedHashMap<>();

	private boolean isRangerCookieEnabled;
	private String rangerCookieName;
	private static String errMsgForInactiveServer = "This userGroupSync server is not in active state. Cannot commit transaction!";
	static {
		try {
			localHostname = java.net.InetAddress.getLocalHost().getCanonicalHostName();
		} catch (UnknownHostException e) {
			localHostname = "unknown";
		}
	}

	public static void main(String[] args) throws Throwable {
		PolicyMgrUserGroupBuilder ugbuilder = new PolicyMgrUserGroupBuilder();
		ugbuilder.init();

	}

	public PolicyMgrUserGroupBuilder() {
		super();

		String userNameCaseConversion = config.getUserNameCaseConversion();

		if (UserGroupSyncConfig.UGSYNC_NONE_CASE_CONVERSION_VALUE.equalsIgnoreCase(userNameCaseConversion)) {
			userNameCaseConversionFlag = false;
		}
		else {
			userNameCaseConversionFlag = true;
			userNameLowerCaseFlag = UserGroupSyncConfig.UGSYNC_LOWER_CASE_CONVERSION_VALUE.equalsIgnoreCase(userNameCaseConversion);
		}

		String groupNameCaseConversion = config.getGroupNameCaseConversion();

		if (UserGroupSyncConfig.UGSYNC_NONE_CASE_CONVERSION_VALUE.equalsIgnoreCase(groupNameCaseConversion)) {
			groupNameCaseConversionFlag = false;
		}
		else {
			groupNameCaseConversionFlag = true;
			groupNameLowerCaseFlag = UserGroupSyncConfig.UGSYNC_LOWER_CASE_CONVERSION_VALUE.equalsIgnoreCase(groupNameCaseConversion);
		}
	}

	synchronized public void init() throws Throwable {
		isUserSyncNameValidationEnabled = config.isUserSyncNameValidationEnabled();
		isSyncSourceValidationEnabled = config.isSyncSourceValidationEnabled();
		recordsToPullPerCall = config.getMaxRecordsPerAPICall();
		policyMgrBaseUrl = config.getPolicyManagerBaseURL();
		isMockRun = config.isMockRunEnabled();
		isRangerCookieEnabled = config.isUserSyncRangerCookieEnabled();
		rangerCookieName = config.getRangerAdminCookieName();
		groupNameMap = new HashMap<>();
		userNameMap = new HashMap<>();
		userCache = new HashMap<>();
		groupCache = new HashMap<>();
		groupUsersCache = new HashMap<>();
		isStartupFlag = true;
		ldapUrl = null;
		currentSyncSource = config.getCurrentSyncSource();
		if (StringUtils.equalsIgnoreCase(currentSyncSource, "LDAP/AD")) {
			ldapUrl = config.getLdapUrl();
		}

		sessionId=null;
		String keyStoreFile =  config.getSSLKeyStorePath();
		String trustStoreFile = config.getSSLTrustStorePath();
		String keyStoreFilepwd = config.getSSLKeyStorePathPassword();
		String trustStoreFilepwd = config.getSSLTrustStorePathPassword();
		String keyStoreType = config.getSSLKeyStoreType();
		String trustStoreType = config.getSSLTrustStoreType();
		authenticationType = config.getProperty(AUTHENTICATION_TYPE,"simple");
		try {
			principal = SecureClientLogin.getPrincipal(config.getProperty(KERBEROS_PRINCIPAL, ""), localHostname);
		} catch (IOException ignored) {
			// do nothing
		}
		keytab = config.getProperty(KERBEROS_KEYTAB, "");
		policyMgrUserName = config.getPolicyMgrUserName();
		nameRules = config.getProperty(NAME_RULE,"DEFAULT");
		ldapUgSyncClient = new RangerUgSyncRESTClient(policyMgrBaseUrl, keyStoreFile, keyStoreFilepwd, keyStoreType,
				trustStoreFile, trustStoreFilepwd, trustStoreType, authenticationType, principal, keytab,
				policyMgrUserName, config.getPolicyMgrPassword());

		String userGroupRoles = config.getGroupRoleRules();
		if (userGroupRoles != null && !userGroupRoles.isEmpty()) {
			getRoleForUserGroups(userGroupRoles, userMap, groupMap);
		}
		String whiteListUserRoles = config.getWhileListUserRoleRules();
		if (whiteListUserRoles != null && !whiteListUserRoles.isEmpty()) {
			getRoleForUserGroups(whiteListUserRoles, whiteListUserMap, whiteListGroupMap);
		}
		String policyMgrUserRole  = whiteListUserMap.get(policyMgrUserName);
		if (!StringUtils.equalsIgnoreCase(policyMgrUserRole, "ROLE_SYS_ADMIN")) {
			whiteListUserMap.put(policyMgrUserName, "ROLE_SYS_ADMIN");
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("Entries in group role assignments: " + groupMap);
			LOG.debug("Entries in whitelist group role assignments: " + whiteListGroupMap);
		}

		buildUserGroupInfo();

		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("PolicyMgrUserGroupBuilderOld.init()==> policyMgrBaseUrl: %s, KeyStore File: %s, TrustStore File: %s, Authentication Type: %s", policyMgrBaseUrl, keyStoreFile, trustStoreFile, authenticationType));
		}

	}

	@Override
	public void postUserGroupAuditInfo(UgsyncAuditInfo ugsyncAuditInfo) throws Throwable {
		ugsyncAuditInfo.setNoOfNewUsers(Integer.toUnsignedLong(noOfNewUsers));
		ugsyncAuditInfo.setNoOfNewGroups(Integer.toUnsignedLong(noOfNewGroups));
		ugsyncAuditInfo.setNoOfModifiedUsers(Integer.toUnsignedLong(noOfModifiedUsers));
		ugsyncAuditInfo.setNoOfModifiedGroups(Integer.toUnsignedLong(noOfModifiedGroups));
		int noOfCachedUsers = userCache.size();
		int noOfCachedGroups = groupCache.size();
		switch (ugsyncAuditInfo.getSyncSource()) {
			case "LDAP/AD":
				ugsyncAuditInfo.getLdapSyncSourceInfo().setTotalUsersSynced(noOfCachedUsers);
				ugsyncAuditInfo.getLdapSyncSourceInfo().setTotalGroupsSynced(noOfCachedGroups);
				ugsyncAuditInfo.getLdapSyncSourceInfo().setTotalUsersDeleted(noOfDeletedUsers);
				ugsyncAuditInfo.getLdapSyncSourceInfo().setTotalGroupsDeleted(noOfDeletedGroups);
				break;
			case "Unix":
				ugsyncAuditInfo.getUnixSyncSourceInfo().setTotalUsersSynced(noOfCachedUsers);
				ugsyncAuditInfo.getUnixSyncSourceInfo().setTotalGroupsSynced(noOfCachedGroups);
				ugsyncAuditInfo.getUnixSyncSourceInfo().setTotalUsersDeleted(noOfDeletedUsers);
				ugsyncAuditInfo.getUnixSyncSourceInfo().setTotalGroupsDeleted(noOfDeletedGroups);
				break;
			case "File" :
				ugsyncAuditInfo.getFileSyncSourceInfo().setTotalUsersSynced(noOfCachedUsers);
				ugsyncAuditInfo.getFileSyncSourceInfo().setTotalGroupsSynced(noOfCachedGroups);
				ugsyncAuditInfo.getFileSyncSourceInfo().setTotalUsersDeleted(noOfDeletedUsers);
				ugsyncAuditInfo.getFileSyncSourceInfo().setTotalGroupsDeleted(noOfDeletedGroups);
				break;
			default:
				break;
		}

		if (!isMockRun) {
			checkStatus();
			addUserGroupAuditInfo(ugsyncAuditInfo);
		}

	}

	@Override
	public void addOrUpdateUsersGroups(Map<String, Map<String, String>> sourceGroups,
									   Map<String, Map<String, String>> sourceUsers,
									   Map<String, Set<String>> sourceGroupUsers,
									   boolean computeDeletes) throws Throwable {
		checkStatus();

		noOfNewUsers = 0;
		noOfNewGroups = 0;
		noOfModifiedUsers = 0;
		noOfModifiedGroups = 0;
		computeRolesForUsers = new HashSet<>();

		if (!isStartupFlag && computeDeletes) {
			LOG.info("Computing deleted users/groups");
			if (LOG.isDebugEnabled()) {
				LOG.debug("Computing deleted users/groups");
			}
			if (MapUtils.isNotEmpty(sourceGroups)) {
				updateDeletedGroups(sourceGroups);
			}
			if (MapUtils.isNotEmpty(sourceUsers)) {
				updateDeletedUsers(sourceUsers);
			}

			if (MapUtils.isNotEmpty(deletedGroups)) {
				groupCache.putAll(deletedGroups);
			}

			if (MapUtils.isNotEmpty(deletedUsers)) {
				userCache.putAll(deletedUsers);
			}
		}

		if (MapUtils.isNotEmpty(sourceGroups)) {
			addOrUpdateGroups(sourceGroups);
		}
		if (MapUtils.isNotEmpty(sourceUsers)) {
			addOrUpdateUsers(sourceUsers);
		}

		if (MapUtils.isNotEmpty(sourceGroupUsers)) {
			addOrUpdateGroupUsers(sourceGroupUsers);
		}

		if (isStartupFlag) {
			// This is to handle any config changes for role assignments that might impact existing users in ranger db
			if (MapUtils.isNotEmpty(whiteListUserMap)) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("adding " + whiteListUserMap.keySet() + " for computing roles during startup");
				}
				computeRolesForUsers.addAll(whiteListUserMap.keySet()); // Add all the user defined in the whitelist role assignment rules
			}
			if (MapUtils.isNotEmpty(whiteListGroupMap)) {
				for (String groupName : whiteListGroupMap.keySet()) {
					Set<String> groupUsers = null;
					if (CollectionUtils.isNotEmpty(groupUsersCache.get(groupName))) {
						groupUsers = new HashSet<>(groupUsersCache.get(groupName));
					} else if (CollectionUtils.isNotEmpty(deltaGroupUsers.get(groupName))) {
						groupUsers = new HashSet<>(deltaGroupUsers.get(groupName));
					}
					if (groupUsers != null) {
						if (LOG.isDebugEnabled()) {
							LOG.debug("adding " + groupUsers + " from " + groupName + " for computing roles during startup");
						}
						computeRolesForUsers.addAll(groupUsers);
					}
				}
			}

			if (MapUtils.isNotEmpty(userMap)) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("adding " + userMap.keySet() + " for computing roles during startup");
				}
				computeRolesForUsers.addAll(userMap.keySet()); // Add all the user defined in the role assignment rules
			}
			if (MapUtils.isNotEmpty(groupMap)) {
				for (String groupName : groupMap.keySet()) {
					Set<String> groupUsers = null;
					if (CollectionUtils.isNotEmpty(groupUsersCache.get(groupName))) {
						groupUsers = new HashSet<>(groupUsersCache.get(groupName));
					} else if (CollectionUtils.isNotEmpty(deltaGroupUsers.get(groupName))) {
						groupUsers = new HashSet<>(deltaGroupUsers.get(groupName));
					}
					if (groupUsers != null) {
						if (LOG.isDebugEnabled()) {
							LOG.debug("adding " + groupUsers + " from " + groupName + " for computing roles during startup");
						}
						computeRolesForUsers.addAll(groupUsers);
					}
				}
			}
		}

		if (CollectionUtils.isNotEmpty(computeRolesForUsers)) {
			updateUserRoles();
		}
		isStartupFlag = false;

		if (LOG.isDebugEnabled()){
			LOG.debug("Update cache");
		}
		if (MapUtils.isNotEmpty(deltaGroups)) {
			groupCache.putAll(deltaGroups);
		}
		if (MapUtils.isNotEmpty(deltaUsers)) {
			userCache.putAll(deltaUsers);
		}
		if (MapUtils.isNotEmpty(deltaGroupUsers)) {
			groupUsersCache.putAll(deltaGroupUsers);
		}
	}

	private void buildUserGroupInfo() throws Throwable {
		if(LOG.isDebugEnabled() && authenticationType != null && AUTH_KERBEROS.equalsIgnoreCase(authenticationType) && SecureClientLogin.isKerberosCredentialExists(principal, keytab)) {
			LOG.debug(String.format("==> Kerberos Environment : Principal is %s and Keytab is %s", principal, keytab));
		}
		if (authenticationType != null && AUTH_KERBEROS.equalsIgnoreCase(authenticationType) && SecureClientLogin.isKerberosCredentialExists(principal, keytab)) {
			LOG.info(String.format("Using principal: %s and keytab: %s", principal, keytab));
			Subject sub = SecureClientLogin.loginUserFromKeytab(principal, keytab, nameRules);
			Boolean isInitDone = Subject.doAs(sub, new PrivilegedAction<Boolean>() {
				@Override
				public Boolean run() {
					try {
						buildGroupList();
						buildUserList();
						buildGroupUserLinkList();
					} catch (Throwable e) {
						LOG.error("Failed to build Users and Groups from Ranger admin : ", e);
						return false;
					}
					return true;
				}
			});
			if (isInitDone.booleanValue() == false) {
				String msg = ("Failed to build Users and Groups from Ranger admin");
				LOG.error(msg);
				throw new Exception(msg);
			}
		} else {
			buildGroupList();
			buildUserList();
			buildGroupUserLinkList();
		}
	}

	private void buildGroupList() throws Throwable {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.buildGroupList()");
		}
		int totalCount = 100;
		int retrievedCount = 0;

		while (retrievedCount < totalCount) {
			String response = null;
			ClientResponse clientResp = null;

			Map<String, String> queryParams = new HashMap<>();
			queryParams.put("pageSize", recordsToPullPerCall);
			queryParams.put("startIndex", String.valueOf(retrievedCount));

			if (isRangerCookieEnabled) {
				response = cookieBasedGetEntity(PM_GROUP_LIST_URI, retrievedCount);
			} else {
				try {
					clientResp = ldapUgSyncClient.get(PM_GROUP_LIST_URI, queryParams);
					if (clientResp != null) {
						response = clientResp.getEntity(String.class);
					}
				} catch (Exception e) {
					LOG.error("Failed to get groups from Ranger, Error is : " + e.getMessage());
					throw e;
				}
			}
			if (LOG.isDebugEnabled()) {
				LOG.debug(String.format("REST response from %s : %s", PM_GROUP_LIST_URI, response));
			}
			GetXGroupListResponse groupList = JsonUtils.jsonToObject(response, GetXGroupListResponse.class);

			totalCount = groupList.getTotalCount();

			if (groupList.getXgroupInfoList() != null) {
				for (XGroupInfo g : groupList.getXgroupInfoList()) {
					if (LOG.isDebugEnabled()) {
						LOG.debug(String.format("GROUP:  Id: %s, Name: %s, Description: %s", g.getId(), g.getName(), g.getDescription()));
					}
					if(null != g.getOtherAttributes()) {
						g.setOtherAttrsMap(JsonUtils.jsonToObject(g.getOtherAttributes(), Map.class));
					}
					groupCache.put(g.getName(), g);
				}
				retrievedCount = groupCache.size();
			}
			LOG.info("PolicyMgrUserGroupBuilder.buildGroupList(): No. of groups retrieved from ranger admin " + retrievedCount);
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.buildGroupList()");
		}
	}

	private void buildUserList() throws Throwable {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.buildUserList()");
		}
		int totalCount = 100;
		int retrievedCount = 0;

		while (retrievedCount < totalCount) {
			String response = null;
			ClientResponse clientResp = null;

			Map<String, String> queryParams = new HashMap<>();
			queryParams.put("pageSize", recordsToPullPerCall);
			queryParams.put("startIndex", String.valueOf(retrievedCount));

			if (isRangerCookieEnabled) {
				response = cookieBasedGetEntity(PM_USER_LIST_URI, retrievedCount);
			} else {
				try {
					clientResp = ldapUgSyncClient.get(PM_USER_LIST_URI, queryParams);
					if (clientResp != null) {
						response = clientResp.getEntity(String.class);
					}
				} catch (Exception e) {
					LOG.error("Failed to get users from Ranger admin, Error is : "+e.getMessage());
					throw e;
				}
			}
			if (LOG.isDebugEnabled()) {
				LOG.debug(String.format("REST response from %s : %s", PM_USER_LIST_URI, response));
			}
			GetXUserListResponse userList = JsonUtils.jsonToObject(response, GetXUserListResponse.class);
			totalCount = userList.getTotalCount();

			if (userList.getXuserInfoList() != null) {
				for (XUserInfo u : userList.getXuserInfoList()) {
					if (LOG.isDebugEnabled()) {
						LOG.debug(String.format("USER: Id: %s, Name: %s, Description: %s", u.getId(), u.getName(), u.getDescription()));
					}
					if(null != u.getOtherAttributes()) {
						u.setOtherAttrsMap(JsonUtils.jsonToObject(u.getOtherAttributes(), Map.class));
					}
					userCache.put(u.getName(), u);
				}
				retrievedCount = userCache.size();
			}
			LOG.info("PolicyMgrUserGroupBuilder.buildUserList(): No. of users retrieved from ranger admin = " + retrievedCount);
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.buildUserList()");
		}
	}

	private void buildGroupUserLinkList() throws Throwable {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.buildGroupUserLinkList()");
		}

		String response = null;
		ClientResponse clientResp = null;

		if (isRangerCookieEnabled) {
			response = cookieBasedGetEntity(PM_GET_ALL_GROUP_USER_MAP_LIST_URI, 0);
		} else {
			try {
				clientResp = ldapUgSyncClient.get(PM_GET_ALL_GROUP_USER_MAP_LIST_URI, null);
				if (clientResp != null) {
					response = clientResp.getEntity(String.class);
				}
			} catch (Exception e) {
				LOG.error("Failed to get response, group user mappings from Ranger admin. Error is : " + e.getMessage());
				throw e;
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("REST response from %s : %s", PM_GET_ALL_GROUP_USER_MAP_LIST_URI, response));
		}

		groupUsersCache = JsonUtils.jsonToObject(response, Map.class);
		if (MapUtils.isEmpty(groupUsersCache)) {
			groupUsersCache = new HashMap<>();
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("Group User List : " + groupUsersCache.values());
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.buildGroupUserLinkList()");
		}
	}

	private void addOrUpdateUsers(Map<String, Map<String, String>> sourceUsers) throws Throwable {
		computeUserDelta(sourceUsers);
		if (MapUtils.isNotEmpty(deltaUsers)) {
			if (addOrUpdateDeltaUsers() == 0) {
				String msg = "Failed to addorUpdate users to ranger admin";
				LOG.error(msg);
				throw new Exception(msg);
			}
		}
	}

	private void addOrUpdateGroups(Map<String, Map<String, String>> sourceGroups) throws Throwable {
		computeGroupDelta(sourceGroups);
		if (MapUtils.isNotEmpty(deltaGroups)) {
			if (addOrUpdateDeltaGroups() == 0) {
				String msg = "Failed to addorUpdate groups to ranger admin";
				LOG.error(msg);
				throw new Exception(msg);
			}
		}
	}

	private void addOrUpdateGroupUsers(Map<String, Set<String>> sourceGroupUsers) throws Throwable {
		List<GroupUserInfo> groupUserInfoList = computeGroupUsersDelta(sourceGroupUsers);
		if (CollectionUtils.isNotEmpty(groupUserInfoList)) {
			noOfModifiedGroups += groupUserInfoList.size();
			if (addOrUpdateDeltaGroupUsers(groupUserInfoList) == 0) {
				String msg = "Failed to addorUpdate group memberships to ranger admin";
				LOG.error(msg);
				throw new Exception(msg);
			}
		}
	}

	private void updateUserRoles() throws Throwable {
		UsersGroupRoleAssignments ugRoleAssignments = new UsersGroupRoleAssignments();
		List<String> allUsers = new ArrayList<>(computeRolesForUsers);

		ugRoleAssignments.setUsers(allUsers);
		ugRoleAssignments.setGroupRoleAssignments(groupMap);
		ugRoleAssignments.setUserRoleAssignments(userMap);
		ugRoleAssignments.setWhiteListUserRoleAssignments(whiteListUserMap);
		ugRoleAssignments.setWhiteListGroupRoleAssignments(whiteListGroupMap);
		ugRoleAssignments.setReset(isStartupFlag);
		String updatedUsers = updateRoles(ugRoleAssignments);
		if (updatedUsers == null) {
			String msg = "Unable to update roles for " + allUsers;
			LOG.error(msg);
			throw new Exception(msg);
		}
	}

	private <T> T setOtherAttributes(T uginfo, String syncSource, Map<String, String> otherAttrsMap, String otherAttributes) {

		if (uginfo instanceof XUserInfo){
			XUserInfo xUserInfo = ((XUserInfo) uginfo);
			xUserInfo.setSyncSource(syncSource);
			xUserInfo.setOtherAttrsMap(otherAttrsMap);
			xUserInfo.setOtherAttributes(otherAttributes);
			return ((T) xUserInfo);
		} else if (uginfo instanceof XGroupInfo ){
			XGroupInfo xGroupInfo = ((XGroupInfo) uginfo);
			xGroupInfo.setSyncSource(syncSource);
			xGroupInfo.setOtherAttrsMap(otherAttrsMap);
			xGroupInfo.setOtherAttributes(otherAttributes);
			return ((T) xGroupInfo);
		} else {
			return null;
		}
	}

	private void computeGroupDelta(Map<String, Map<String, String>> sourceGroups) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("PolicyMgrUserGroupBuilder.computeGroupDelta(" + sourceGroups.keySet() + ")");
		}
		deltaGroups = new HashMap<>();
		// Check if the group exists in cache. If not, mark as new group.
		// else check if other attributes are updated and mark as updated group
		for (String groupDN : sourceGroups.keySet()) {
			Map<String, String> newGroupAttrs = sourceGroups.get(groupDN);
			String newGroupAttrsStr = JsonUtils.objectToJson(newGroupAttrs);
			String groupName                  = groupNameMap.get(groupDN);
			if (StringUtils.isEmpty(groupName)) {
				groupName = groupNameTransform(newGroupAttrs.get(UgsyncCommonConstants.ORIGINAL_NAME).trim());
			}

			if (!isValidString(groupName)) {
				LOG.warn("Ignoring invalid group " + groupName + " Full name = " + groupDN);
				continue;
			}

			if (!groupCache.containsKey(groupName)) {
				XGroupInfo newGroup = addXGroupInfo(groupName, newGroupAttrs, newGroupAttrsStr);
				deltaGroups.put(groupName, newGroup);
				noOfNewGroups++;
				groupNameMap.put(groupDN, groupName);
			} else {
				XGroupInfo curGroup                = groupCache.get(groupName);
				String curSyncSource               = curGroup.getSyncSource();
				String curGroupAttrsStr            = curGroup.getOtherAttributes();
				Map<String, String> curGroupAttrs  = curGroup.getOtherAttrsMap();
				String curGroupDN                  = MapUtils.isEmpty(curGroupAttrs) ? groupName : curGroupAttrs.get(UgsyncCommonConstants.FULL_NAME);
				String newSyncSource               = newGroupAttrs.get(UgsyncCommonConstants.SYNC_SOURCE);

				if (isStartupFlag && !isSyncSourceValidationEnabled && (!StringUtils.equalsIgnoreCase(curSyncSource, newSyncSource))) {
					if (LOG.isDebugEnabled()) {
						LOG.debug("[" + groupName + "]: SyncSource updated to " + newSyncSource + ", previous value: " + curSyncSource);
					}
					curGroup = setOtherAttributes(curGroup, newSyncSource, newGroupAttrs, newGroupAttrsStr);
					deltaGroups.put(groupName, curGroup);
					noOfModifiedGroups++;
					groupNameMap.put(groupDN, groupName);
				} else {
					if (MapUtils.isNotEmpty(curGroupAttrs) && !StringUtils.equalsIgnoreCase(groupDN, curGroupDN)) { // skip update
						if (LOG.isDebugEnabled()) {
							LOG.debug("[" + groupName + "]: SyncSource update skipped, current group DN = " + curGroupDN + " new user DN  = " + groupDN );
						}

						if (StringUtils.equalsIgnoreCase(curGroupAttrsStr, newGroupAttrsStr)) {
							groupNameMap.put(groupDN, groupName);
						}
						continue;
					}

					if (StringUtils.isEmpty(curSyncSource) || (!StringUtils.equalsIgnoreCase(curGroupAttrsStr, newGroupAttrsStr)
							&& StringUtils.equalsIgnoreCase(curSyncSource, newSyncSource))) { // update
						if (LOG.isDebugEnabled()) {
							if (StringUtils.isEmpty(curSyncSource)) {
								LOG.debug("[" + groupName + "]: SyncSource updated to " + newSyncSource + ", previously empty");
							} else {
								LOG.debug("[" + groupName + "]: Other Attributes updated!");
							}
						}
						curGroup = setOtherAttributes(curGroup, newSyncSource, newGroupAttrs, newGroupAttrsStr);
						deltaGroups.put(groupName, curGroup);
						noOfModifiedGroups++;
						groupNameMap.put(groupDN, groupName);

					} else if (LOG.isDebugEnabled()) {
						if (!StringUtils.equalsIgnoreCase(curSyncSource, newSyncSource)) {
							LOG.debug("[" + groupName + "]: Different sync source exists, update skipped!");
						} else {
							LOG.debug("[" + groupName + "]: No change, update skipped!");
						}
					}

					if (StringUtils.equalsIgnoreCase(curGroupAttrsStr, newGroupAttrsStr)) {
						groupNameMap.put(groupDN, groupName);
					}
				}
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.computeGroupDelta(" + deltaGroups.keySet() + ")");
		}
	}

	private void computeUserDelta(Map<String, Map<String, String>> sourceUsers) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.computeUserDelta(" + sourceUsers.keySet() + ")");
		}
		deltaUsers = new HashMap<>();
		// Check if the user exists in cache. If not, mark as new user.
		// else check if other attributes are updated and mark as updated user
		for (Map.Entry<String, Map<String, String>> sourceUser : sourceUsers.entrySet()) {
			String userDN = sourceUser.getKey();
			Map<String, String> newUserAttrs = sourceUser.getValue();
			String newUserAttrsStr = JsonUtils.objectToJson(newUserAttrs);
			String userName                  = userNameMap.get(userDN);
			if (StringUtils.isEmpty(userName)) {
				userName = userNameTransform(newUserAttrs.get(UgsyncCommonConstants.ORIGINAL_NAME).trim());
			}

			if (!isValidString(userName)) {
				LOG.warn("Ignoring invalid user " + userName + " Full name = " + userDN);
				continue;
			}

			if (!userCache.containsKey(userName)) {
				XUserInfo newUser = addXUserInfo(userName, newUserAttrs, newUserAttrsStr);
				deltaUsers.put(userName, newUser);
				noOfNewUsers++;
				userNameMap.put(userDN, userName);
			} else {
				// no updates allowed for rangerusersync and admin
				if (StringUtils.equalsIgnoreCase(policyMgrUserName, userName) || StringUtils.equalsIgnoreCase("admin", userName)) {
					if (LOG.isDebugEnabled()) {
						LOG.debug("[" + userName + "]: SyncSource update skipped!");
					}
					continue;
				}

				XUserInfo curUser                = userCache.get(userName);
				String curSyncSource             = curUser.getSyncSource();
				String curUserAttrsStr           = curUser.getOtherAttributes();
				Map<String, String> curUserAttrs = curUser.getOtherAttrsMap();
				String curUserDN                 = MapUtils.isEmpty(curUserAttrs) ? userName : curUserAttrs.get(UgsyncCommonConstants.FULL_NAME);
				String newSyncSource             = newUserAttrs.get(UgsyncCommonConstants.SYNC_SOURCE);
				if (isStartupFlag && !isSyncSourceValidationEnabled && (!StringUtils.equalsIgnoreCase(curSyncSource, newSyncSource))) {
					if (LOG.isDebugEnabled()) {
						LOG.debug("[" + userName + "]: SyncSource updated to " + newSyncSource + ", previous value: " + curSyncSource);
					}
					curUser = setOtherAttributes(curUser, newSyncSource, newUserAttrs, newUserAttrsStr);
					curUser.setUserSource(SOURCE_EXTERNAL);
					deltaUsers.put(userName, curUser);
					noOfModifiedGroups++;
					userNameMap.put(userDN, userName);
				} else {
					if (MapUtils.isNotEmpty(curUserAttrs) && !StringUtils.equalsIgnoreCase(userDN, curUserDN)) { // skip update
						if (LOG.isDebugEnabled()) { // Same username with different DN already exists
							LOG.debug("[" + userName + "]: SyncSource update skipped, current user DN = " + curUserDN + " new user DN  = " + userDN );
						}

						if (StringUtils.equalsIgnoreCase(curUserAttrsStr, newUserAttrsStr)) {
							userNameMap.put(userDN, userName);
						}
						continue;
					}

					if (StringUtils.isEmpty(curSyncSource) || (!StringUtils.equalsIgnoreCase(curUserAttrsStr, newUserAttrsStr)
							&& StringUtils.equalsIgnoreCase(curSyncSource, newSyncSource))) { // update
						if (LOG.isDebugEnabled()) {
							if (StringUtils.isEmpty(curSyncSource)) {
								LOG.debug("[" + userName + "]: SyncSource updated to " + newSyncSource + ", previously empty");
							} else {
								LOG.debug("[" + userName + "]: Other Attributes updated!");
							}
						}
						curUser = setOtherAttributes(curUser, newSyncSource, newUserAttrs, newUserAttrsStr);
						curUser.setUserSource(SOURCE_EXTERNAL);
						deltaUsers.put(userName, curUser);
						noOfModifiedUsers++;
						userNameMap.put(userDN, userName);
					} else if (LOG.isDebugEnabled()) {
						if (!StringUtils.equalsIgnoreCase(curSyncSource, newSyncSource)) {
							LOG.debug("[" + userName + "]: Different sync source exists, update skipped!");
						} else {
							LOG.debug("[" + userName + "]: No change, update skipped!");
						}
					}

					if (StringUtils.equalsIgnoreCase(curUserAttrsStr, newUserAttrsStr)) {
						userNameMap.put(userDN, userName);
					}
				}
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.computeUserDelta(" + deltaUsers.keySet() + ")");
		}
	}

	private List<GroupUserInfo> computeGroupUsersDelta(Map<String, Set<String>> sourceGroupUsers) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.computeGroupUsersDelta(" + sourceGroupUsers.keySet() + ")");
		}
		deltaGroupUsers = new HashMap<>();
		List<GroupUserInfo> deltaGroupUserInfoList = new ArrayList<>();
		for (String groupDN : sourceGroupUsers.keySet()) {
			String groupName = groupNameMap.get(groupDN);
			if (StringUtils.isEmpty(groupName)) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Ignoring group membership update for " + groupDN);
				}
				continue;
			}
			Set<String> oldUsers = new HashSet<>();
			Set<String> newUsers = new HashSet<>();
			Set<String> addUsers = new HashSet<>();
			Set<String> delUsers = new HashSet<>();
			if (CollectionUtils.isNotEmpty(groupUsersCache.get(groupName))) {
				oldUsers = new HashSet<>(groupUsersCache.get(groupName));
			}

			for (String userDN : sourceGroupUsers.get(groupDN)) {
				String userName = userNameMap.get(userDN);
				if (!StringUtils.isEmpty(userName)) {
					newUsers.add(userName);
					if (CollectionUtils.isEmpty(oldUsers) || !oldUsers.contains(userName)) {
						addUsers.add(userName);
					}
				}
			}

			if (CollectionUtils.isNotEmpty(oldUsers)) {
				for (String userName : oldUsers) {
					if (CollectionUtils.isEmpty(newUsers)|| !newUsers.contains(userName)) {
						delUsers.add(userName);
					}
				}
			}

			if (CollectionUtils.isNotEmpty(addUsers) || CollectionUtils.isNotEmpty(delUsers)) {
				GroupUserInfo groupUserInfo = new GroupUserInfo();
				groupUserInfo.setGroupName(groupName);
				if (CollectionUtils.isNotEmpty(addUsers)) {
					groupUserInfo.setAddUsers(addUsers);
					if (groupMap.containsKey(groupName) || whiteListGroupMap.containsKey(groupName)) {
						// Add users to the computeRole list only if there is a rule defined for the group.
						computeRolesForUsers.addAll(addUsers);
					}
				}
				if (CollectionUtils.isNotEmpty(delUsers)) {
					groupUserInfo.setDelUsers(delUsers);
					if (groupMap.containsKey(groupName)|| whiteListGroupMap.containsKey(groupName)) {
						// Add users to the computeRole list only if there is a rule defined for the group.
						computeRolesForUsers.addAll(delUsers);
					}
				}
				deltaGroupUserInfoList.add(groupUserInfo);
				deltaGroupUsers.put(groupName, newUsers);
			}
		}
		if (CollectionUtils.isNotEmpty(deltaGroupUserInfoList)) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("<== PolicyMgrUserGroupBuilder.computeGroupUsersDelta(" + deltaGroupUserInfoList + ")");
			}
		} else {
			if (LOG.isDebugEnabled()) {
				LOG.debug("<== PolicyMgrUserGroupBuilder.computeGroupUsersDelta(0)");
			}
		}

		return deltaGroupUserInfoList;
	}

	private XUserInfo addXUserInfo(String aUserName, Map<String, String> otherAttrsMap, String otherAttributes) {
		XUserInfo xuserInfo = new XUserInfo();
		xuserInfo.setName(aUserName);
		xuserInfo.setFirstName(aUserName);
		xuserInfo.setDescription(aUserName + " - add from Unix box");
		xuserInfo.setUserSource(SOURCE_EXTERNAL);
		xuserInfo.setStatus(STATUS_ENABLED);
		xuserInfo.setIsVisible(ISVISIBLE);
		List<String> roleList = new ArrayList<String>();
		if (userMap.containsKey(aUserName)) {
			roleList.add(userMap.get(aUserName));
		}else{
			roleList.add("ROLE_USER");
		}
		xuserInfo.setUserRoleList(roleList);
		xuserInfo.setOtherAttributes(otherAttributes);
		xuserInfo.setSyncSource(otherAttrsMap.get(UgsyncCommonConstants.SYNC_SOURCE));
		xuserInfo.setOtherAttrsMap(otherAttrsMap);
		return xuserInfo;
	}


	private XGroupInfo addXGroupInfo(String aGroupName, Map<String, String> otherAttrsMap, String otherAttributes) {

		XGroupInfo addGroup = new XGroupInfo();

		addGroup.setName(aGroupName);

		addGroup.setDescription(aGroupName + " - add from Unix box");

		addGroup.setGroupType("1");
		addGroup.setIsVisible(ISVISIBLE);
		addGroup.setGroupSource(SOURCE_EXTERNAL);
		addGroup.setOtherAttributes(otherAttributes);
		addGroup.setSyncSource(otherAttrsMap.get(UgsyncCommonConstants.SYNC_SOURCE));
		addGroup.setOtherAttrsMap(otherAttrsMap);

		return addGroup;
	}

	private int addOrUpdateDeltaUsers() throws Throwable{
		if (LOG.isDebugEnabled()) {
			LOG.debug("PolicyMgrUserGroupBuilder.addOrUpdateDeltaUsers(" + deltaUsers.keySet() + ")");
		}
		int ret = 0;

		GetXUserListResponse xUserList = new GetXUserListResponse();
		xUserList.setTotalCount(deltaUsers.size());
		xUserList.setXuserInfoList(new ArrayList<>(deltaUsers.values()));

		if (authenticationType != null
				&& AUTH_KERBEROS.equalsIgnoreCase(authenticationType)
				&& SecureClientLogin.isKerberosCredentialExists(principal,
				keytab)) {
			try {
				Subject sub = SecureClientLogin.loginUserFromKeytab(principal, keytab, nameRules);
				final GetXUserListResponse xUserListFinal = xUserList;
				ret = Subject.doAs(sub, new PrivilegedAction<Integer>() {
					@Override
					public Integer run() {
						try {
							return getUsers(xUserListFinal);
						} catch (Throwable e) {
							LOG.error("Failed to add or update Users : ", e);
						}
						return 0;
					}
				});
			} catch (Exception e) {
				LOG.error("Failed to add or update Users : " , e);
				throw e;
			}
		} else {
			ret = getUsers(xUserList);
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("PolicyMgrUserGroupBuilder.addOrUpdateDeltaUsers(" + deltaUsers.keySet() + ")");
		}
		return ret;
	}


	private int getUsers(GetXUserListResponse xUserList) throws Throwable{
		if(LOG.isDebugEnabled()){
			LOG.debug("==> PolicyMgrUserGroupBuilder.getUsers()");
		}
		int ret = 0;
		int totalCount = xUserList.getTotalCount();
		int uploadedCount = 0;
		int pageSize = Integer.parseInt(recordsToPullPerCall);
		while (uploadedCount < totalCount) {
			checkStatus();
			GetXUserListResponse pagedXUserList = new GetXUserListResponse();
			int pagedXUserListLen = uploadedCount+pageSize;
			pagedXUserList.setXuserInfoList(xUserList.getXuserInfoList().subList(uploadedCount,
					pagedXUserListLen>totalCount?totalCount:pagedXUserListLen));
			pagedXUserList.setTotalCount(pageSize);
			if (pagedXUserList.getXuserInfoList().isEmpty()) {
				LOG.info("PolicyMgrUserGroupBuilder.getUsers() done updating users");
				return 1;
			}

			String response = getDataFromLdap(PM_ADD_USERS_URI, pagedXUserList);

			if (StringUtils.isNotEmpty(response)) {
				try {
					ret = Integer.valueOf(response);
					uploadedCount += pageSize;
				} catch (NumberFormatException e) {
					LOG.error("Failed to addOrUpdateUsers " + uploadedCount, e);
					throw e;
				}
			} else {
				LOG.error("Failed to addOrUpdateUsers " + uploadedCount );
				throw new Exception("Failed to addOrUpdateUsers" + uploadedCount);
			}
			LOG.info(String.format("API returned: %s, No. of users uploaded to ranger admin = %s", ret, (uploadedCount>totalCount?totalCount:uploadedCount)));		}

		if(LOG.isDebugEnabled()){
			LOG.debug("<== PolicyMgrUserGroupBuilder.getUsers()");
		}
		return ret;
	}

	private String getDataFromLdap(String uri, Object pagedList) throws Exception {
		String response = null;
		if (isRangerCookieEnabled) {
			response = cookieBasedUploadEntity(pagedList, uri);
		} else {
			try {
				ClientResponse clientRes = ldapUgSyncClient.post(uri, null, pagedList);
				if (clientRes != null) {
					response = clientRes.getEntity(String.class);
				}
			} catch (Throwable t) {
				LOG.error("Failed to get response, Error is : ", t);
				throw t;
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("REST response from %s : %s", uri, response));
		}
		return response;
	}

	private int addOrUpdateDeltaGroups() throws Throwable{
		if (LOG.isDebugEnabled()) {
			LOG.debug("PolicyMgrUserGroupBuilder.addOrUpdateDeltaGroups(" + deltaGroups.keySet() + ")");
		}
		int ret = 0;

		GetXGroupListResponse xGroupList = new GetXGroupListResponse();
		xGroupList.setTotalCount(deltaGroups.size());
		xGroupList.setXgroupInfoList(new ArrayList<>(deltaGroups.values()));

		if (authenticationType != null
				&& AUTH_KERBEROS.equalsIgnoreCase(authenticationType)
				&& SecureClientLogin.isKerberosCredentialExists(principal,
				keytab)) {
			try {
				Subject sub = SecureClientLogin.loginUserFromKeytab(principal, keytab, nameRules);
				final GetXGroupListResponse xGroupListFinal = xGroupList;
				ret = Subject.doAs(sub, new PrivilegedAction<Integer>() {
					@Override
					public Integer run() {
						try {
							return getGroups(xGroupListFinal);
						} catch (Throwable e) {
							LOG.error("Failed to add or update groups : ", e);
						}
						return 0;
					}
				});
			} catch (Exception e) {
				LOG.error("Failed to add or update groups : " , e);
				throw e;
			}
		} else {
			ret = getGroups(xGroupList);
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("PolicyMgrUserGroupBuilder.addOrUpdateDeltaGroups(" + deltaGroups.keySet() + ")");
		}
		return ret;
	}


	private int getGroups(GetXGroupListResponse xGroupList) throws Throwable{
		if(LOG.isDebugEnabled()){
			LOG.debug("==> PolicyMgrUserGroupBuilder.getGroups()");
		}

		int ret = 0;
		int totalCount = xGroupList.getTotalCount();
		int uploadedCount = 0;
		int pageSize = Integer.parseInt(recordsToPullPerCall);
		while (uploadedCount < totalCount) {
			checkStatus();
			GetXGroupListResponse pagedXGroupList = new GetXGroupListResponse();
			int pagedXGroupListLen = uploadedCount+pageSize;
			pagedXGroupList.setXgroupInfoList(xGroupList.getXgroupInfoList().subList(uploadedCount,
					pagedXGroupListLen>totalCount?totalCount:pagedXGroupListLen));
			pagedXGroupList.setTotalCount(pageSize);

			String response = getDataFromLdap(PM_ADD_GROUPS_URI, pagedXGroupList);
			if (StringUtils.isNotEmpty(response)) {
				try {
					ret = Integer.valueOf(response);
					uploadedCount += pageSize;
				} catch (NumberFormatException e) {
					LOG.error("Failed to addOrUpdateGroups " + uploadedCount, e );
					throw e;
				}
			} else {
				LOG.error("Failed to addOrUpdateGroups " + uploadedCount );
				throw new Exception("Failed to addOrUpdateGroups " + uploadedCount);
			}
			LOG.info(String.format("API returned: %s, No. of groups uploaded to ranger admin = %s", ret, (uploadedCount>totalCount?totalCount:uploadedCount)));		}

		if(LOG.isDebugEnabled()){
			LOG.debug("<== PolicyMgrUserGroupBuilder.getGroups()");
		}
		return ret;
	}

	private int addOrUpdateDeltaGroupUsers(List<GroupUserInfo> groupUserInfoList) throws Throwable{
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.addOrUpdateDeltaGroupUsers(" + groupUserInfoList + ")");
		}
		int ret = 0;

		if (authenticationType != null
				&& AUTH_KERBEROS.equalsIgnoreCase(authenticationType)
				&& SecureClientLogin.isKerberosCredentialExists(principal,
				keytab)) {
			try {
				Subject sub = SecureClientLogin.loginUserFromKeytab(principal, keytab, nameRules);
				final List<GroupUserInfo> groupUserInfoListFinal = groupUserInfoList;
				ret = Subject.doAs(sub, new PrivilegedAction<Integer>() {
					@Override
					public Integer run() {
						try {
							return getGroupUsers(groupUserInfoListFinal);
						} catch (Throwable e) {
							LOG.error("Failed to add or update group memberships : ", e);
						}
						return 0;
					}
				});
			} catch (Exception e) {
				LOG.error("Failed to add or update group memberships : " , e);
				throw e;
			}
		} else {
			ret = getGroupUsers(groupUserInfoList);
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.addOrUpdateDeltaGroupUsers(" + groupUserInfoList + ")");
		}
		return ret;
	}


	private int getGroupUsers(List<GroupUserInfo> groupUserInfoList) throws Throwable{
		if(LOG.isDebugEnabled()){
			LOG.debug("==> PolicyMgrUserGroupBuilder.getGroupUsers()");
		}
		int ret = 0;
		int totalCount = groupUserInfoList.size();
		int uploadedCount = 0;
		int pageSize = Integer.parseInt(recordsToPullPerCall);
		while (uploadedCount < totalCount) {
			checkStatus();

			int pagedGroupUserInfoListLen = uploadedCount+pageSize;
			List<GroupUserInfo> pagedGroupUserInfoList = groupUserInfoList.subList(uploadedCount,
					pagedGroupUserInfoListLen>totalCount?totalCount:pagedGroupUserInfoListLen);

			String response = getDataFromLdap(PM_ADD_GROUP_USER_LIST_URI, pagedGroupUserInfoList);
			if (StringUtils.isNotEmpty(response)) {
				try {
					ret = Integer.valueOf(response);
					uploadedCount += pageSize;
				} catch (NumberFormatException e) {
					LOG.error("Failed to addOrUpdateGroupUsers " + uploadedCount, e );
					throw e;
				}
			} else {
				LOG.error("Failed to addOrUpdateGroupUsers " + uploadedCount );
				throw new Exception("Failed to addOrUpdateGroupUsers " + uploadedCount);
			}

			LOG.info(String.format("API returned: %s, No. of group memberships uploaded to ranger admin = %s", ret, (uploadedCount>totalCount?totalCount:uploadedCount)));		}

		if(LOG.isDebugEnabled()){
			LOG.debug("<== PolicyMgrUserGroupBuilder.getGroupUsers()");
		}
		return ret;
	}
	private String updateRoles(UsersGroupRoleAssignments ugRoleAssignments) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.updateUserRole(" + ugRoleAssignments.getUsers() + ")");
		}

		if (authenticationType != null && AUTH_KERBEROS.equalsIgnoreCase(authenticationType) && SecureClientLogin.isKerberosCredentialExists(principal, keytab)){
			try {
				Subject sub = SecureClientLogin.loginUserFromKeytab(principal, keytab, nameRules);
				final UsersGroupRoleAssignments result = ugRoleAssignments;
				return Subject.doAs(sub, (PrivilegedAction<String>) () -> {
					try {
						return updateUsersRoles(result);
					} catch (Exception e) {
						LOG.error("Failed to add User Group Info: ", e);
						return null;
					}
				});
			} catch (Exception e) {
				LOG.error("Failed to Authenticate Using given Principal and Keytab : " , e);
			}
			return null;
		} else {
			return updateUsersRoles(ugRoleAssignments);
		}
	}

	private String updateUsersRoles(UsersGroupRoleAssignments ugRoleAssignments) {
		if(LOG.isDebugEnabled()){
			LOG.debug("==> PolicyMgrUserGroupBuilder.updateUserRoles(" + ugRoleAssignments.getUsers() + ")");
		}
		String response = null;
		try {
			int totalCount = ugRoleAssignments.getUsers().size();
			int uploadedCount = 0;
			int pageSize = Integer.parseInt(recordsToPullPerCall);
			while (uploadedCount < totalCount) {
				checkStatus();
				int pagedUgRoleAssignmentsListLen = uploadedCount + pageSize;
				UsersGroupRoleAssignments pagedUgRoleAssignmentsList = new UsersGroupRoleAssignments();
				pagedUgRoleAssignmentsList.setUsers(ugRoleAssignments.getUsers().subList(uploadedCount,
						pagedUgRoleAssignmentsListLen > totalCount ? totalCount : pagedUgRoleAssignmentsListLen));
				pagedUgRoleAssignmentsList.setGroupRoleAssignments(ugRoleAssignments.getGroupRoleAssignments());
				pagedUgRoleAssignmentsList.setUserRoleAssignments(ugRoleAssignments.getUserRoleAssignments());
				pagedUgRoleAssignmentsList.setWhiteListGroupRoleAssignments(ugRoleAssignments.getWhiteListGroupRoleAssignments());
				pagedUgRoleAssignmentsList.setWhiteListUserRoleAssignments(ugRoleAssignments.getWhiteListUserRoleAssignments());
				pagedUgRoleAssignmentsList.setReset(ugRoleAssignments.isReset());
				if ((uploadedCount + pageSize) >= totalCount) { // this is the last iteration of the loop
					pagedUgRoleAssignmentsList.setLastPage(true);
				}
				ClientResponse clientRes;
				String url = PM_UPDATE_USERS_ROLES_URI;

				if (LOG.isDebugEnabled()) {
					String jsonString = JsonUtils.objectToJson(pagedUgRoleAssignmentsList);
					LOG.debug(String.format("Paged RoleAssignments Request to %s: %s", url, jsonString));
				}

				if (isRangerCookieEnabled) {
					response = cookieBasedUploadEntity(pagedUgRoleAssignmentsList, url);
				} else {
					try {
						clientRes = ldapUgSyncClient.post(url, null, ugRoleAssignments);
						if (clientRes != null) {
							response = clientRes.getEntity(String.class);
						}
					} catch (Throwable t) {
						LOG.error("Failed to get response: ", t);
					}
				}

				if (LOG.isDebugEnabled()) {
					LOG.debug(String.format("REST response from %s : %s", url, response));
				}

				if (response == null){
					throw new RuntimeException("Failed to get a REST response!");
				}

				uploadedCount += pageSize;
			}

		} catch (Exception e) {
			LOG.error("Unable to update roles for: " + ugRoleAssignments.getUsers(), e);
			response = null;
		}

		if(LOG.isDebugEnabled()){
			LOG.debug("<== PolicyMgrUserGroupBuilder.updateUserRoles(" + response + ")");
		}
		return response;
	}

	private void addUserGroupAuditInfo(UgsyncAuditInfo auditInfo) throws Throwable {
		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> PolicyMgrUserGroupBuilder.addUserGroupAuditInfo(%s, %s, %s, %s, %s)", auditInfo.getNoOfNewUsers(), auditInfo.getNoOfNewGroups(), auditInfo.getNoOfModifiedUsers(), auditInfo.getNoOfModifiedGroups(), auditInfo.getSyncSource()));
		}

		if (authenticationType != null
				&& AUTH_KERBEROS.equalsIgnoreCase(authenticationType)
				&& SecureClientLogin.isKerberosCredentialExists(principal,
				keytab)) {
			try {
				Subject sub = SecureClientLogin.loginUserFromKeytab(principal, keytab, nameRules);
				final UgsyncAuditInfo auditInfoFinal = auditInfo;
				Subject.doAs(sub, new PrivilegedAction<Void>() {
					@Override
					public Void run() {
						try {
							getUserGroupAuditInfo(auditInfoFinal);
						} catch (Throwable e) {
							LOG.error("Failed to add User : ", e);
						}
						return null;
					}
				});
			} catch (Exception e) {
				LOG.error("Failed to Authenticate Using given Principal and Keytab : " , e);
			}
		} else {
			getUserGroupAuditInfo(auditInfo);
		}
	}


	private void getUserGroupAuditInfo(UgsyncAuditInfo userInfo) throws Throwable{
		if(LOG.isDebugEnabled()){
			LOG.debug("==> PolicyMgrUserGroupBuilder.getUserGroupAuditInfo()");
		}
		checkStatus();
		String response = null;
		ClientResponse clientRes = null;

		if(isRangerCookieEnabled){
			response = cookieBasedUploadEntity(userInfo, PM_AUDIT_INFO_URI);
		}
		else {
			try {
				clientRes = ldapUgSyncClient.post(PM_AUDIT_INFO_URI, null, userInfo);
				if (clientRes != null) {
					response = clientRes.getEntity(String.class);
				}
			}
			catch(Throwable t){
				LOG.error("Failed to get response, Error is : ", t);
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("REST response from %s : %s", PM_AUDIT_INFO_URI, response));
		}
		JsonUtils.jsonToObject(response, UgsyncAuditInfo.class);

		if(LOG.isDebugEnabled()){
			LOG.debug("AuditInfo Creation successful ");
			LOG.debug("<== PolicyMgrUserGroupBuilder.getUserGroupAuditInfo()");
		}
	}

	private String cookieBasedUploadEntity(Object obj, String apiURL ) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.cookieBasedUploadEntity()");
		}
		String response = null;
		if (sessionId != null && isValidRangerCookie) {
			response = tryUploadEntityWithCookie(obj, apiURL);
		}
		else{
			response = tryUploadEntityWithCred(obj, apiURL);
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.cookieBasedUploadEntity()");
		}
		return response;
	}

	private String cookieBasedGetEntity(String apiURL ,int retrievedCount) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.cookieBasedGetEntity()");
		}
		String response = null;
		if (sessionId != null && isValidRangerCookie) {
			response = tryGetEntityWithCookie(apiURL,retrievedCount);
		}
		else{
			response = tryGetEntityWithCred(apiURL,retrievedCount);
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.cookieBasedGetEntity()");
		}
		return response;
	}

	private String tryUploadEntityWithCookie(Object obj, String apiURL) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.tryUploadEntityWithCookie()");
		}
		String response = null;
		ClientResponse clientResp = null;
		try {
			clientResp = ldapUgSyncClient.post(apiURL, null, obj, sessionId);
		}
		catch(Throwable t){
			LOG.error("Failed to get response, Error is : ", t);
		}
		if (clientResp != null) {
			if (!(clientResp.toString().contains(apiURL))) {
				clientResp.setStatus(HttpServletResponse.SC_NOT_FOUND);
				sessionId = null;
				isValidRangerCookie = false;
			} else if (clientResp.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
				sessionId = null;
				isValidRangerCookie = false;
			} else if (clientResp.getStatus() == HttpServletResponse.SC_NO_CONTENT || clientResp.getStatus() == HttpServletResponse.SC_OK) {
				List<NewCookie> respCookieList = clientResp.getCookies();
				for (NewCookie cookie : respCookieList) {
					if (cookie.getName().equalsIgnoreCase(rangerCookieName)) {
						if (!(sessionId.getValue().equalsIgnoreCase(cookie.toCookie().getValue()))) {
							sessionId = cookie.toCookie();
						}
						isValidRangerCookie = true;
						break;
					}
				}
			}

			if (clientResp.getStatus() != HttpServletResponse.SC_OK	&& clientResp.getStatus() != HttpServletResponse.SC_NO_CONTENT
					&& clientResp.getStatus() != HttpServletResponse.SC_BAD_REQUEST) {
				sessionId = null;
				isValidRangerCookie = false;
			}
			clientResp.bufferEntity();
			response = clientResp.getEntity(String.class);
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.tryUploadEntityWithCookie()");
		}
		return response;
	}


	private String tryUploadEntityWithCred(Object obj, String apiURL){
		if(LOG.isDebugEnabled()){
			LOG.debug("==> PolicyMgrUserGroupBuilder.tryUploadEntityInfoWithCred()");
		}
		String response = null;
		ClientResponse clientResp = null;

		if ( LOG.isDebugEnabled() ) {
			String jsonString = JsonUtils.objectToJson(obj);
			LOG.debug(String.format("User Group Mapping: %s", jsonString));
		}

		try {
			clientResp = ldapUgSyncClient.post(apiURL, null, obj);
		} catch(Throwable t) {
			LOG.error("Failed to get response, Error is : ", t);
		}
		if (clientResp != null) {
			if (!(clientResp.toString().contains(apiURL))) {
				clientResp.setStatus(HttpServletResponse.SC_NOT_FOUND);
			} else if (clientResp.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
				LOG.warn("Credentials response from ranger is 401.");
			} else if (clientResp.getStatus() == HttpServletResponse.SC_OK || clientResp.getStatus() == HttpServletResponse.SC_NO_CONTENT) {
				cookieList = clientResp.getCookies();
				for (NewCookie cookie : cookieList) {
					if (cookie.getName().equalsIgnoreCase(rangerCookieName)) {
						sessionId = cookie.toCookie();
						isValidRangerCookie = true;
						LOG.info("valid cookie saved ");
						break;
					}
				}
			}
			if (clientResp.getStatus() != HttpServletResponse.SC_OK && clientResp.getStatus() != HttpServletResponse.SC_NO_CONTENT
					&& clientResp.getStatus() != HttpServletResponse.SC_BAD_REQUEST) {
				sessionId = null;
				isValidRangerCookie = false;
			}
			clientResp.bufferEntity();
			response = clientResp.getEntity(String.class);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.tryUploadEntityInfoWithCred()");
		}
		return response;
	}

	private String tryGetEntityWithCred(String apiURL, int retrievedCount) {
		if(LOG.isDebugEnabled()){
			LOG.debug("==> PolicyMgrUserGroupBuilder.tryGetEntityWithCred()");
		}
		String response = null;
		ClientResponse clientResp = null;

		Map<String, String> queryParams = new HashMap<String, String>();
		queryParams.put("pageSize", recordsToPullPerCall);
		queryParams.put("startIndex", String.valueOf(retrievedCount));
		try{
			clientResp = ldapUgSyncClient.get(apiURL, queryParams);
		}
		catch(Throwable t){
			LOG.error("Failed to get response, Error is : ", t);
		}
		if (clientResp != null) {
			if (!(clientResp.toString().contains(apiURL))) {
				clientResp.setStatus(HttpServletResponse.SC_NOT_FOUND);
			} else if (clientResp.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
				LOG.warn("Credentials response from ranger is 401.");
			} else if (clientResp.getStatus() == HttpServletResponse.SC_OK || clientResp.getStatus() == HttpServletResponse.SC_NO_CONTENT) {
				cookieList = clientResp.getCookies();
				for (NewCookie cookie : cookieList) {
					if (cookie.getName().equalsIgnoreCase(rangerCookieName)) {
						sessionId = cookie.toCookie();
						isValidRangerCookie = true;
						LOG.info("valid cookie saved ");
						break;
					}
				}
			}
			if (clientResp.getStatus() != HttpServletResponse.SC_OK && clientResp.getStatus() != HttpServletResponse.SC_NO_CONTENT
					&& clientResp.getStatus() != HttpServletResponse.SC_BAD_REQUEST) {
				sessionId = null;
				isValidRangerCookie = false;
			}
			clientResp.bufferEntity();
			response = clientResp.getEntity(String.class);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.tryGetEntityWithCred()");
		}
		return response;
	}


	private String tryGetEntityWithCookie(String apiURL, int retrievedCount) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.tryGetEntityWithCookie()");
		}
		String response = null;
		ClientResponse clientResp = null;

		Map<String, String> queryParams = new HashMap<String, String>();
		queryParams.put("pageSize", recordsToPullPerCall);
		queryParams.put("startIndex", String.valueOf(retrievedCount));
		try {
			clientResp = ldapUgSyncClient.get(apiURL, queryParams, sessionId);
		}
		catch(Throwable t){
			LOG.error("Failed to get response, Error is : ", t);
		}
		if (clientResp != null) {
			if (!(clientResp.toString().contains(apiURL))) {
				clientResp.setStatus(HttpServletResponse.SC_NOT_FOUND);
				sessionId = null;
				isValidRangerCookie = false;
			} else if (clientResp.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
				sessionId = null;
				isValidRangerCookie = false;
			} else if (clientResp.getStatus() == HttpServletResponse.SC_NO_CONTENT || clientResp.getStatus() == HttpServletResponse.SC_OK) {
				List<NewCookie> respCookieList = clientResp.getCookies();
				for (NewCookie cookie : respCookieList) {
					if (cookie.getName().equalsIgnoreCase(rangerCookieName)) {
						if (!(sessionId.getValue().equalsIgnoreCase(cookie.toCookie().getValue()))) {
							sessionId = cookie.toCookie();
						}
						isValidRangerCookie = true;
						break;
					}
				}
			}

			if (clientResp.getStatus() != HttpServletResponse.SC_OK	&& clientResp.getStatus() != HttpServletResponse.SC_NO_CONTENT
					&& clientResp.getStatus() != HttpServletResponse.SC_BAD_REQUEST) {
				sessionId = null;
				isValidRangerCookie = false;
			}
			clientResp.bufferEntity();
			response = clientResp.getEntity(String.class);
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.tryGetEntityWithCookie()");
		}
		return response;
	}

	private void getRoleForUserGroups(String userGroupRolesData, Map<String, String> userMap, Map<String, String> groupMap) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==>> getRoleForUserGroups(" + userGroupRolesData + ")");
		}
		Map<String, String> reverseGroupMap = new LinkedHashMap<>();
		String roleDelimiter = config.getRoleDelimiter();
		String userGroupDelimiter = config.getUserGroupDelimiter();
		String userNameDelimiter = config.getUserGroupNameDelimiter();
		roleDelimiter = StringUtils.isEmpty(roleDelimiter) ? "&" : roleDelimiter;
		userGroupDelimiter = StringUtils.isEmpty(userGroupDelimiter) ? ":" : userGroupDelimiter;
		userNameDelimiter = StringUtils.isEmpty(userNameDelimiter) ? "," : userNameDelimiter;
		StringTokenizer str = new StringTokenizer(userGroupRolesData,
				roleDelimiter);
		int flag = 0;
		String userGroupCheck = null;
		String roleName = null;
		while (str.hasMoreTokens()) {
			flag = 0;
			String tokens = str.nextToken();
			if (StringUtils.isNotEmpty(tokens)) {
				StringTokenizer userGroupRoles = new StringTokenizer(tokens,
						userGroupDelimiter);
				if (userGroupRoles != null) {
					while (userGroupRoles.hasMoreElements()) {
						String userGroupRolesTokens = userGroupRoles
								.nextToken();
						if (StringUtils.isNotEmpty(userGroupRolesTokens)) {
							flag++;
							switch (flag) {
								case 1:
									roleName = userGroupRolesTokens;
									break;
								case 2:
									userGroupCheck = userGroupRolesTokens;
									break;
								case 3:
									StringTokenizer userGroupNames = new StringTokenizer(
											userGroupRolesTokens, userNameDelimiter);
									if (userGroupNames != null) {
										while (userGroupNames.hasMoreElements()) {
											String userGroup = userGroupNames
													.nextToken();
											if (StringUtils.isNotEmpty(userGroup)) {
												if (userGroupCheck.trim().equalsIgnoreCase("u")) {
													userMap.put(userGroup.trim(), roleName.trim());
												} else if (userGroupCheck.trim().equalsIgnoreCase("g")) {
													reverseGroupMap.put(userGroup.trim(),
															roleName.trim());
												}
											}
										}
									}
									break;
								default:
									userMap.clear();
									reverseGroupMap.clear();
									break;
							}
						}
					}
				}
			}
		}
		// Reversing the order of group keys so that the last role specified in the configuration rules is applied when a user belongs to multiple groups with different roles
		if (MapUtils.isNotEmpty(reverseGroupMap)) {
			List<String> groupNames = new ArrayList<>(reverseGroupMap.keySet());
			Collections.reverse(groupNames);
			groupNames.forEach(group -> groupMap.put(group, reverseGroupMap.get(group)));
		}
	}

	protected String userNameTransform(String userName) {
		if (userNameCaseConversionFlag) {
			if (userNameLowerCaseFlag) {
				userName = userName.toLowerCase();
			}
			else {
				userName = userName.toUpperCase();
			}
		}

		if (userNameRegExInst != null) {
			userName = userNameRegExInst.transform(userName);
		}

		return userName;
	}

	protected String groupNameTransform(String groupName) {
		if (groupNameCaseConversionFlag) {
			if (groupNameLowerCaseFlag) {
				groupName = groupName.toLowerCase();
			}
			else {
				groupName = groupName.toUpperCase();
			}
		}

		if (groupNameRegExInst != null) {
			groupName = groupNameRegExInst.transform(groupName);
		}

		return groupName;
	}

	protected boolean isValidString(final String name) {
		if (StringUtils.isBlank(name)) {
			return false;
		}
		if (isUserSyncNameValidationEnabled) {
			return USER_OR_GROUP_NAME_VALIDATION_REGEX.matcher(name).matches();
		}
		return true;
	}

	private void updateDeletedGroups(Map<String, Map<String, String>> sourceGroups) throws Throwable {
		computeDeletedGroups(sourceGroups);
		if (MapUtils.isNotEmpty(deletedGroups)) {
			if (updateDeletedGroups() == 0) {
				String msg = "Failed to update deleted groups to ranger admin";
				LOG.error(msg);
				throw new Exception(msg);
			}
		}
		LOG.info("No. of groups marked for delete = " + deletedGroups.size());
		noOfDeletedGroups += deletedGroups.size();
	}

	private void computeDeletedGroups(Map<String, Map<String, String>> sourceGroups) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("PolicyMgrUserGroupBuilder.computeDeletedGroups(" + sourceGroups.keySet() + ")");
		}
		deletedGroups = new HashMap<>();
		// Check if the group from cache exists in the sourceGroups. If not, mark as deleted group.
		for (XGroupInfo groupInfo : groupCache.values()) {
			Map<String, String> groupOtherAttrs = groupInfo.getOtherAttrsMap();
			String groupDN = groupOtherAttrs != null ? groupOtherAttrs.get(UgsyncCommonConstants.FULL_NAME) : null;
			if (StringUtils.isNotEmpty(groupDN) && !sourceGroups.containsKey(groupDN)
					&& StringUtils.equalsIgnoreCase(groupOtherAttrs.get(UgsyncCommonConstants.SYNC_SOURCE), currentSyncSource)
					&& StringUtils.equalsIgnoreCase(groupOtherAttrs.get(UgsyncCommonConstants.LDAP_URL), ldapUrl)) {
				if (ISHIDDEN.equals(groupInfo.getIsVisible())) {
				groupInfo.setIsVisible(ISHIDDEN);
				deletedGroups.put(groupInfo.getName(), groupInfo);
				} else {
					LOG.info("group " + groupInfo.getName() + " already marked for delete ");
				}
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.computeDeletedGroups(" + deletedGroups + ")");
		}
	}

	private int updateDeletedGroups() throws Throwable{
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.updateDeletedGroups(" + deletedGroups + ")");
		}
		int ret = 0;

		if (authenticationType != null
				&& AUTH_KERBEROS.equalsIgnoreCase(authenticationType)
				&& SecureClientLogin.isKerberosCredentialExists(principal,
				keytab)) {
			try {
				Subject sub = SecureClientLogin.loginUserFromKeytab(principal, keytab, nameRules);
				ret = Subject.doAs(sub, new PrivilegedAction<Integer>() {
					@Override
					public Integer run() {
						try {
							return getDeletedGroups();
						} catch (Throwable e) {
							LOG.error("Failed to add or update deleted groups : ", e);
						}
						return 0;
					}
				});
			} catch (Exception e) {
				LOG.error("Failed to add or update deleted groups : " , e);
				throw e;
			}
		} else {
			ret = getDeletedGroups();
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.updateDeletedGroups(" + deletedGroups + ")");
		}
		return ret;
	}


	private int getDeletedGroups() throws Throwable{
		if(LOG.isDebugEnabled()){
			LOG.debug("==> PolicyMgrUserGroupBuilder.getDeletedGroups()");
		}
		checkStatus();
		int ret = 0;
		String response = null;
		ClientResponse clientRes = null;

		if(isRangerCookieEnabled){
			response = cookieBasedUploadEntity(deletedGroups.keySet(), PM_UPDATE_DELETED_GROUPS_URI);
		}
		else {
			try {
				clientRes = ldapUgSyncClient.post(PM_UPDATE_DELETED_GROUPS_URI, null, deletedGroups.keySet());
				if (clientRes != null) {
					response = clientRes.getEntity(String.class);
				}
			}
			catch(Throwable t){
				LOG.error("Failed to get response, Error is : ", t);
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("REST response from %s : %s", PM_UPDATE_DELETED_GROUPS_URI, response));
		}
		if (response != null) {
			try {
				ret = Integer.valueOf(response);
			} catch (NumberFormatException e) {
				LOG.error("Failed to update deleted groups", e );
				throw e;
			}
		} else {
			LOG.error("Failed to update deleted groups ");
			throw new Exception("Failed to update deleted groups ");
		}

		if(LOG.isDebugEnabled()){
			LOG.debug("<== PolicyMgrUserGroupBuilder.getDeletedGroups()" + ret);
		}

		return ret;
	}


	private void updateDeletedUsers(Map<String, Map<String, String>> sourceUsers) throws Throwable {
		computeDeletedUsers(sourceUsers);
		if (MapUtils.isNotEmpty(deletedUsers)) {
			if (updateDeletedUsers() == 0) {
				String msg = "Failed to update deleted users to ranger admin";
				LOG.error(msg);
				throw new Exception(msg);
			}
		}
		LOG.info("No. of users marked for delete = " + deletedUsers.size());
		noOfDeletedUsers += deletedUsers.size();
	}

	private void computeDeletedUsers(Map<String, Map<String, String>> sourceUsers) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("PolicyMgrUserGroupBuilder.computeDeletedUsers(" + sourceUsers.keySet() + ")");
		}
		deletedUsers = new HashMap<>();
		// Check if the group from cache exists in the sourceGroups. If not, mark as deleted group.
		for (XUserInfo userInfo : userCache.values()) {
			Map<String, String> userOtherAttrs = userInfo.getOtherAttrsMap();
			String userDN = userOtherAttrs != null ? userOtherAttrs.get(UgsyncCommonConstants.FULL_NAME) : null;
			if (StringUtils.isNotEmpty(userDN) && !sourceUsers.containsKey(userDN)
					&& StringUtils.equalsIgnoreCase(userOtherAttrs.get(UgsyncCommonConstants.SYNC_SOURCE), currentSyncSource)
					&& StringUtils.equalsIgnoreCase(userOtherAttrs.get(UgsyncCommonConstants.LDAP_URL), ldapUrl)) {
				if (userInfo.getIsVisible() != ISHIDDEN) {
				userInfo.setIsVisible(ISHIDDEN);
				deletedUsers.put(userInfo.getName(), userInfo);
				} else {
					LOG.info("user " + userInfo.getName() + " already marked for delete ");
				}
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.computeDeletedUsers(" + deletedUsers + ")");
		}
	}

	private int updateDeletedUsers() throws Throwable{
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> PolicyMgrUserGroupBuilder.updateDeletedUsers(" + deletedUsers + ")");
		}
		int ret = 0;

		if (authenticationType != null
				&& AUTH_KERBEROS.equalsIgnoreCase(authenticationType)
				&& SecureClientLogin.isKerberosCredentialExists(principal,
				keytab)) {
			try {
				Subject sub = SecureClientLogin.loginUserFromKeytab(principal, keytab, nameRules);
				ret = Subject.doAs(sub, new PrivilegedAction<Integer>() {
					@Override
					public Integer run() {
						try {
							return getDeletedUsers();
						} catch (Throwable e) {
							LOG.error("Failed to add or update deleted users : ", e);
						}
						return 0;
					}
				});
			} catch (Exception e) {
				LOG.error("Failed to add or update deleted users : " , e);
				throw e;
			}
		} else {
			ret = getDeletedUsers();
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== PolicyMgrUserGroupBuilder.updateDeletedUsers(" + deletedUsers + ")");
		}
		return ret;
	}


	private int getDeletedUsers() throws Throwable{
		if(LOG.isDebugEnabled()){
			LOG.debug("==> PolicyMgrUserGroupBuilder.getDeletedUsers()");
		}
		checkStatus();
		int ret = 0;
		String response = null;
		ClientResponse clientRes = null;

		if(isRangerCookieEnabled){
			response = cookieBasedUploadEntity(deletedUsers.keySet(), PM_UPDATE_DELETED_USERS_URI);
		}
		else {
			try {
				clientRes = ldapUgSyncClient.post(PM_UPDATE_DELETED_USERS_URI, null, deletedUsers.keySet());
				if (clientRes != null) {
					response = clientRes.getEntity(String.class);
				}
			}
			catch(Throwable t){
				LOG.error("Failed to get response, Error is : ", t);
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("REST response from %s : %s", PM_UPDATE_DELETED_USERS_URI, response));
		}
		if (response != null) {
			try {
				ret = Integer.valueOf(response);
			} catch (NumberFormatException e) {
				LOG.error("Failed to update deleted users", e );
				throw e;
			}
		} else {
			LOG.error("Failed to update deleted users ");
			throw new Exception("Failed to update deleted users ");
		}

		if(LOG.isDebugEnabled()){
			LOG.debug("<== PolicyMgrUserGroupBuilder.getDeletedUsers()" + ret);
		}

		return ret;
	}

	//Only for testing purpose
	protected void setUserSyncNameValidationEnabled(String isNameValidationEnabled) {
		config.setProperty(UserGroupSyncConfig.UGSYNC_NAME_VALIDATION_ENABLED, isNameValidationEnabled);
		this.isUserSyncNameValidationEnabled = config.isUserSyncNameValidationEnabled();
	}

	// This will throw RuntimeException if Server is not Active
	private void checkStatus() throws Exception {
		if(!UserGroupSyncConfig.isUgsyncServiceActive()) {
			LOG.error(errMsgForInactiveServer);
			throw new RuntimeException(errMsgForInactiveServer);
		}
	}
}
