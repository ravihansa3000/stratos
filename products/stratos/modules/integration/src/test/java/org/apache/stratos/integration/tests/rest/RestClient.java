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


import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;

import java.net.URI;

/**
 * Rest client to handle rest requests
 */
public class RestClient {

    private DefaultHttpClient httpClient;

    public RestClient() {
        PoolingClientConnectionManager cm = new PoolingClientConnectionManager();
        // Increase max total connection to 200
        cm.setMaxTotal(200);
        // Increase default max connection per route to 50
        cm.setDefaultMaxPerRoute(50);

        httpClient = new DefaultHttpClient(cm);
        httpClient = (DefaultHttpClient) WebClientWrapper.wrapClient(httpClient);
    }

    /**
     * Handle http post request. Return String
     *
     * @param resourcePath    This should be REST endpoint
     * @param jsonParamString The json string which should be executed from the post request
     * @return The HttpResponse
     * @throws Exception if any errors occur when executing the request
     */
    public HttpResponse doPost(URI resourcePath, String jsonParamString) throws Exception {
        HttpPost postRequest = null;
        try {
            postRequest = new HttpPost(resourcePath);
            StringEntity input = new StringEntity(jsonParamString);
            input.setContentType("application/json");
            postRequest.setEntity(input);

            String userPass = "admin" + ":" + "admin";
            String basicAuth = "Basic " + javax.xml.bind.DatatypeConverter.printBase64Binary(userPass.getBytes("UTF-8"));
            postRequest.addHeader("Authorization", basicAuth);

            return httpClient.execute(postRequest, new HttpResponseHandler());
        } finally {
            releaseConnection(postRequest);
        }
    }

    /**
     * Handle http get request. Return String
     *
     * @param resourcePath This should be REST endpoint
     * @return The HttpResponse
     * @throws org.apache.http.client.ClientProtocolException and IOException
     *                                                        if any errors occur when executing the request
     */
    public HttpResponse doGet(URI resourcePath) throws Exception {
        HttpGet getRequest = null;
        try {
            getRequest = new HttpGet(resourcePath);
            getRequest.addHeader("Content-Type", "application/json");
            String userPass = "admin" + ":" + "admin";
            String basicAuth = "Basic " + javax.xml.bind.DatatypeConverter.printBase64Binary(userPass.getBytes("UTF-8"));
            getRequest.addHeader("Authorization", basicAuth);

            return httpClient.execute(getRequest, new HttpResponseHandler());
        } finally {
            releaseConnection(getRequest);
        }
    }

    public HttpResponse doDelete(URI resourcePath) throws Exception {
        HttpDelete httpDelete = null;
        try {
            httpDelete = new HttpDelete(resourcePath);
            httpDelete.addHeader("Content-Type", "application/json");
            String userPass = "admin" + ":" + "admin";
            String basicAuth = "Basic " + javax.xml.bind.DatatypeConverter.printBase64Binary(userPass.getBytes("UTF-8"));
            httpDelete.addHeader("Authorization", basicAuth);
            return httpClient.execute(httpDelete, new HttpResponseHandler());
        } finally {
            releaseConnection(httpDelete);
        }
    }

    public HttpResponse doPut(URI resourcePath, String jsonParamString) throws Exception {

        HttpPut putRequest = null;
        try {
            putRequest = new HttpPut(resourcePath);

            StringEntity input = new StringEntity(jsonParamString);
            input.setContentType("application/json");
            putRequest.setEntity(input);
            String userPass = "admin" + ":" + "admin";
            String basicAuth = "Basic " + javax.xml.bind.DatatypeConverter.printBase64Binary(userPass.getBytes("UTF-8"));
            putRequest.addHeader("Authorization", basicAuth);
            return httpClient.execute(putRequest, new HttpResponseHandler());
        } finally {
            releaseConnection(putRequest);
        }
    }

    private void releaseConnection(HttpRequestBase request) {
        if (request != null) {
            request.releaseConnection();
        }
    }
}
