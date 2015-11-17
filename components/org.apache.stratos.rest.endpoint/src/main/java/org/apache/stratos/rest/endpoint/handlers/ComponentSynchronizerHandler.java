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

package org.apache.stratos.rest.endpoint.handlers;

import org.apache.cxf.jaxrs.ext.RequestHandler;
import org.apache.cxf.jaxrs.model.ClassResourceInfo;
import org.apache.cxf.message.Message;
import org.apache.stratos.common.beans.ResponseMessageBean;
import org.apache.stratos.manager.context.StratosManagerContext;

import javax.ws.rs.core.Response;

/**
 * Component synchronizer handler for enabling rest api once stratos manager component becomes active.
 */
public class ComponentSynchronizerHandler implements RequestHandler {

    public Response handleRequest(Message message, ClassResourceInfo classResourceInfo) {
        if (!StratosManagerContext.getInstance().isActivated()) {
            ResponseMessageBean responseBean = new ResponseMessageBean();
            responseBean.setMessage("Stratos manager component is not active");
            return Response.status(Response.Status.NOT_ACCEPTABLE).entity(responseBean).build();
        }
        return null;
    }
}
