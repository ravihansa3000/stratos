/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.stratos.integration.tests.rest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Rest http response handler
 */
public class HttpResponseHandler implements ResponseHandler<HttpResponse> {
    private static final Log log = LogFactory.getLog(HttpResponseHandler.class);

    @Override
    public HttpResponse handleResponse(org.apache.http.HttpResponse response) throws ClientProtocolException,
            IOException {
        StatusLine statusLine = response.getStatusLine();
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            throw new ClientProtocolException("Response contains no content");
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(
                (response.getEntity().getContent())));

        String output;
        String result = "";

        while ((output = reader.readLine()) != null) {
            result += output;
        }

        HttpResponse httpResponse = new HttpResponse();
        httpResponse.setStatusCode(statusLine.getStatusCode());
        httpResponse.setContent(result);
        httpResponse.setReason(statusLine.getReasonPhrase());

        if (log.isDebugEnabled()) {
            log.debug("Extracted Http Response: " + httpResponse.toString());
        }

        return httpResponse;
    }
}
