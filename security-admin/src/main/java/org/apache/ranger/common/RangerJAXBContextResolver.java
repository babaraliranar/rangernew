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

 package org.apache.ranger.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;


/**
 *
 *
 */
@Provider
public class RangerJAXBContextResolver implements ContextResolver<ObjectMapper> {

    private final ObjectMapper jsonMapper;

    private Class<?>[] types = {
	org.apache.ranger.view.VXAuthSessionList.class,
	org.apache.ranger.view.VXResponse.class,
	org.apache.ranger.view.VXStringList.class,
	org.apache.ranger.view.VXPortalUserList.class,
	org.apache.ranger.view.VXAssetList.class,
	org.apache.ranger.view.VXResourceList.class,
	org.apache.ranger.view.VXCredentialStoreList.class,
	org.apache.ranger.view.VXGroupList.class,
	org.apache.ranger.view.VXUserList.class,
	org.apache.ranger.view.VXGroupUserList.class,
	org.apache.ranger.view.VXGroupGroupList.class,
	org.apache.ranger.view.VXPermMapList.class,
	org.apache.ranger.view.VXAuditMapList.class,
	org.apache.ranger.view.VXPolicyExportAuditList.class,
	org.apache.ranger.view.VXAccessAuditList.class
    };

    public RangerJAXBContextResolver() throws Exception {
		jsonMapper = new ObjectMapper();
		jsonMapper.registerModule(new JaxbAnnotationModule());
    }

    @Override
    public ObjectMapper getContext(Class<?> objectType) {
	// return context;
	for (Class<?> type : types) {
	    if (type.getName().equals(objectType.getName())) {
		return this.jsonMapper;
	    }
	}
	return null;
    }
}

