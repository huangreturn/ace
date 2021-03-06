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
package org.apache.ace.client.rest;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ace.client.repository.RepositoryObject;
import org.apache.ace.client.repository.SessionFactory;
import org.apache.ace.client.repository.stateful.StatefulGatewayObject;
import org.apache.felix.dm.Component;
import org.apache.felix.dm.DependencyManager;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;

/**
 * Servlet that offers a REST client API.
 */
public class RESTClientServlet extends HttpServlet implements ManagedService {
    private static final long serialVersionUID = 5210711248294238039L;
    /** Alias that redirects to the latest version automatically. */
    private static final String LATEST_FOLDER = "latest";
    /** Name of the folder where working copies are kept. */
    private static final String WORK_FOLDER = "work";
    /** URL of the repository to talk to. */
    private static final String KEY_REPOSITORY_URL = "repository.url";
    /** URL of the OBR to talk to. */
    private static final String KEY_OBR_URL = "obr.url";
    /** Name of the customer. */
    private static final String KEY_CUSTOMER_NAME = "customer.name";
    /** Name of the store repository. */
    private static final String KEY_STORE_REPOSITORY_NAME = "store.repository.name";
    /** Name of the license repository. */
    private static final String KEY_LICENSE_REPOSITORY_NAME = "license.repository.name";
    /** Name of the deployment repository. */
    private static final String KEY_DEPLOYMENT_REPOSITORY_NAME = "deployment.repository.name";
    /** Name of the user to log in as. */
    private static final String KEY_USER_NAME = "user.name";

    private static long m_sessionID = 1;

    private volatile DependencyManager m_dm;
    private volatile SessionFactory m_sessionFactory;

    private final Map<String, Workspace> m_workspaces = new HashMap<String, Workspace>();
    private final Map<String, Component> m_workspaceComponents = new HashMap<String, Component>();
    private Gson m_gson;
    private String m_repositoryURL;
    private String m_obrURL;
    private String m_customerName;
    private String m_storeRepositoryName;
    private String m_licenseRepositoryName;
    private String m_deploymentRepositoryName;
    private String m_serverUser;
    
    public RESTClientServlet() {
        m_gson = (new GsonBuilder())
            .registerTypeHierarchyAdapter(RepositoryObject.class, new RepositoryObjectSerializer())
            .create();
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String[] pathElements = getPathElements(req);
        if (pathElements == null || pathElements.length == 0) {
            // TODO return a list of versions
            resp.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED, "Not implemented: list of versions");
            return;
        }
        else {
            if (pathElements.length == 1) {
                if (LATEST_FOLDER.equals(pathElements[0])) {
                    // TODO redirect to latest version
                    // resp.sendRedirect("notImplemented" /* to latest version */);
                    resp.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED, "Not implemented: redirect to latest version");
                    return;
                }
            }
            else if (pathElements.length == 3) {
                if (WORK_FOLDER.equals(pathElements[0])) {
                    Workspace workspace = getWorkspace(pathElements[1]);
                    if (workspace != null) {
                        // TODO add a feature to filter the list that is returned (query, paging, ...)
                        List<RepositoryObject> objects = workspace.getRepositoryObjects(pathElements[2]);
                        JsonArray result = new JsonArray();
                        for (RepositoryObject ro : objects) {
                            String identity = workspace.getRepositoryObjectIdentity(ro);
                            if (identity != null) {
                                result.add(new JsonPrimitive(URLEncoder.encode(identity, "UTF-8")));
                            }
                        }
                        resp.getWriter().println(m_gson.toJson(result));
                        return;
                    }
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Could not find workspace: " + pathElements[1]);
                    return;
                }
            }
            else if (pathElements.length == 4) {
                if (WORK_FOLDER.equals(pathElements[0])) {
                    Workspace workspace = getWorkspace(pathElements[1]);
                    if (workspace != null) {
                        String entityType = pathElements[2];
                        String entityId = pathElements[3];
                        RepositoryObject repositoryObject = workspace.getRepositoryObject(entityType, entityId);
                        if (repositoryObject == null) {
                            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Repository object of type " + entityType + " and identity " + entityId + " not found.");
                            return;
                        }
                        
                        resp.getWriter().println(m_gson.toJson(repositoryObject));
                        return;
                    }
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Could not find workspace: " + pathElements[1]);
                    return;
                }
            }
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String[] pathElements = getPathElements(req);
        if (pathElements != null) {
            if (pathElements.length == 1) {
                if (WORK_FOLDER.equals(pathElements[0])) {
                    // TODO get data from post body (if no data, assume latest??) -> for now always assume latest
                    String sessionID;
                    Workspace workspace;
                    Component component;
                    synchronized (m_workspaces) {
                        sessionID = "rest-" + m_sessionID++;
                        workspace = new Workspace(sessionID, m_repositoryURL, m_obrURL, m_customerName, m_storeRepositoryName, m_licenseRepositoryName, m_deploymentRepositoryName, m_serverUser);
                        m_workspaces.put(sessionID, workspace);
                        component = m_dm.createComponent().setImplementation(workspace);
                        m_workspaceComponents.put(sessionID, component);
                    }
                    m_sessionFactory.createSession(sessionID);
                    m_dm.add(component);
                    resp.sendRedirect(buildPathFromElements(WORK_FOLDER, sessionID));
                    return;
                }
            }
            else if (pathElements.length == 2) {
                if (WORK_FOLDER.equals(pathElements[0])) {
                    Workspace workspace = getWorkspace(pathElements[1]);
                    if (workspace != null) {
                        try {
                            workspace.commit();
                            return;
                        }
                        catch (Exception e) {
                            e.printStackTrace();
                            resp.sendError(HttpServletResponse.SC_CONFLICT, "Commit failed: " + e.getMessage());
                            return;
                        }
                    }
                    else {
                        // return error
                        System.out.println("Failed...");
                    }
                }
            }
            else if (pathElements.length == 3) {
                if (WORK_FOLDER.equals(pathElements[0])) {
                    Workspace workspace = getWorkspace(pathElements[1]);
                    if (workspace != null) {
                        try {
                            RepositoryValueObject data = m_gson.fromJson(req.getReader(), RepositoryValueObject.class);
                            RepositoryObject object = workspace.addRepositoryObject(pathElements[2], data.attributes, data.tags);
                            String identity = workspace.getRepositoryObjectIdentity(object);
                            if (identity != null) {
                                resp.sendRedirect(buildPathFromElements(WORK_FOLDER, pathElements[1], pathElements[2], identity));
                            }
                            else {
                                // TODO decide what to do here, if this can happen at all
                            }
                            return;
                        }
                        catch (IllegalArgumentException e) {
                            e.printStackTrace();
                        }
                    }
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Could not add entity of type " + pathElements[2]);
                    return;
                }
            }
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        return;
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String[] pathElements = getPathElements(req);
        if (pathElements != null) {
            if (pathElements.length == 4) {
                if (pathElements[0].equals(WORK_FOLDER)) {
                    Workspace workspace = getWorkspace(pathElements[1]);
                    if (workspace != null) {
                        try {
                            RepositoryValueObject data = m_gson.fromJson(req.getReader(), RepositoryValueObject.class);
                            RepositoryObject object = workspace.getRepositoryObject(pathElements[2], pathElements[3]);
                            workspace.updateObjectWithData(pathElements[2], pathElements[2], data);
                            resp.sendRedirect(buildPathFromElements(WORK_FOLDER, pathElements[1], pathElements[2], pathElements[3]));
                            return;
                        }
                        catch (IllegalArgumentException e) {
                            e.printStackTrace();
                        }
                    }
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Could not add entity of type " + pathElements[2]);
                    return;
                }
            }
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        return;
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String[] pathElements = getPathElements(req);
        if (pathElements != null) {
            if (pathElements.length == 2) {
                if (WORK_FOLDER.equals(pathElements[0])) {
                    String id = pathElements[1];
                    Workspace workspace;
                    Component component;
                    synchronized (m_workspaces) {
                        workspace = m_workspaces.remove(id);
                        component = m_workspaceComponents.remove(id);
                    }
                    if (workspace != null && component != null) {
                        // TODO delete the work area
                        m_dm.remove(component);
                        m_sessionFactory.destroySession(id);
                        return;
                    }
                    else {
                        resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Could not delete work area.");
                        return;
                    }
                }
            }
            else if (pathElements.length == 4) {
                if (WORK_FOLDER.equals(pathElements[0])) {
                    String id = pathElements[1];
                    String entityType = pathElements[2];
                    String entityId = pathElements[3];
                    
                    Workspace workspace;
                    Component component;
                    synchronized (m_workspaces) {
                        workspace = m_workspaces.get(id);
                    }
                    if (workspace != null) {
                        workspace.deleteRepositoryObject(entityType, entityId);
                        return;
                    }
                    else {
                        resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Could not find work area.");
                        return;
                    }
                }
            }
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        return;
    }

    private Workspace getWorkspace(String id) {
        Workspace workspace;
        synchronized (m_workspaces) {
            workspace = m_workspaces.get(id);
        }
        return workspace;
    }
    
    /**
     * Builds a URL path from the supplied elements. Each individual element is URL encoded.
     * 
     * @param elements the elements
     * @return the URL path
     */
    String buildPathFromElements(String... elements) {
        StringBuilder result = new StringBuilder();
        for (String element : elements) {
            if (result.length() > 0) {
                result.append('/');
            }
            try {
                result.append(URLEncoder.encode(element, "UTF-8").replaceAll("\\+", "%20"));
            }
            catch (UnsupportedEncodingException e) {} // ignored on purpose, any JVM must support UTF-8
        }
        return result.toString();
    }

    /**
     * Returns the separate path parts from the request, and URL decodes them.
     * 
     * @param req the request
     * @return the separate path parts
     */
    String[] getPathElements(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null) {
            return new String[0];
        }
        if (path.startsWith("/") && path.length() > 1) {
            path = path.substring(1);
        }
        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        String[] pathElements = path.split("/");
        try {
            for (int i = 0; i < pathElements.length; i++) {
                pathElements[i] = URLDecoder.decode(pathElements[i].replaceAll("%20", "\\+"), "UTF-8");
            }
        }
        catch (UnsupportedEncodingException e) {}
        return pathElements;
    }

    public void updated(Dictionary properties) throws ConfigurationException {
        // Note that configuration changes are only applied to new work areas, started after the
        // configuration was changed. No attempt is done to "fix" existing work areas, although we
        // might consider flushing/invalidating them.
        m_repositoryURL = getProperty(properties, KEY_REPOSITORY_URL, "http://localhost:8080/repository");
        m_obrURL = getProperty(properties, KEY_OBR_URL, "http://localhost:8080/obr");
        m_customerName = getProperty(properties, KEY_CUSTOMER_NAME, "apache");
        m_storeRepositoryName = getProperty(properties, KEY_STORE_REPOSITORY_NAME, "shop");
        m_licenseRepositoryName = getProperty(properties, KEY_LICENSE_REPOSITORY_NAME, "gateway");
        m_deploymentRepositoryName = getProperty(properties, KEY_DEPLOYMENT_REPOSITORY_NAME, "deployment");
        m_serverUser = getProperty(properties, KEY_USER_NAME, "d");
    }
    
    String getProperty(Dictionary properties, String key, String defaultValue) {
        if (properties != null) {
            Object value = properties.get(key);
            if (value != null && value instanceof String) {
                return (String) value;
            }
        }
        return defaultValue;
    }
}
