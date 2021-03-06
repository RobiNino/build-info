/*
 * Copyright (C) 2010 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.build.extractor.clientConfiguration.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.jfrog.build.api.dependency.BuildPatternArtifacts;
import org.jfrog.build.api.dependency.BuildPatternArtifactsRequest;
import org.jfrog.build.api.dependency.PatternResultFileSet;
import org.jfrog.build.api.dependency.PropertySearchResult;
import org.jfrog.build.api.repository.RepositoryConfig;
import org.jfrog.build.api.search.AqlSearchResult;
import org.jfrog.build.api.util.Log;
import org.jfrog.build.client.ArtifactoryHttpClient;
import org.jfrog.build.client.PreemptiveHttpClient;
import org.jfrog.build.extractor.clientConfiguration.util.DeploymentUrlUtils;
import org.jfrog.build.extractor.clientConfiguration.util.JsonSerializer;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

/**
 * Artifactory client to perform artifacts and build dependencies related tasks.
 *
 * @author Noam Y. Tenne
 */
public class ArtifactoryDependenciesClient extends ArtifactoryBaseClient {
    private static final String LATEST = "LATEST";
    private static final String LAST_RELEASE = "LAST_RELEASE";

    public ArtifactoryDependenciesClient(String artifactoryUrl, String username, String password, String accessToken, Log logger) {
        super(artifactoryUrl, username, password, accessToken, logger);
    }

    public ArtifactoryDependenciesClient(String artifactoryUrl, String username, String password, Log logger) {
        this(artifactoryUrl, username, password, StringUtils.EMPTY, logger);
    }

    public ArtifactoryDependenciesClient(String artifactoryUrl, ArtifactoryHttpClient httpClient, Log logger) {
        super(artifactoryUrl, httpClient, logger);
    }

    /**
     * Retrieves list of {@link org.jfrog.build.api.dependency.BuildPatternArtifacts} for build dependencies specified.
     *
     * @param requests build dependencies to retrieve outputs for.
     * @return build outputs for dependencies specified.
     */
    public List<BuildPatternArtifacts> retrievePatternArtifacts(List<BuildPatternArtifactsRequest> requests)
            throws IOException {
        final String json = new JsonSerializer<List<BuildPatternArtifactsRequest>>().toJSON(requests);
        final HttpPost post = new HttpPost(artifactoryUrl + "/api/build/patternArtifacts");
        StringEntity stringEntity = new StringEntity(json);
        stringEntity.setContentType("application/vnd.org.jfrog.artifactory+json");
        post.setEntity(stringEntity);
        try (CloseableHttpResponse response = httpClient.getHttpClient().execute(post);
             InputStream responseStream = getResponseStream(response, "Failed to retrieve build artifacts report")) {
            return readJsonResponse(responseStream, new TypeReference<List<BuildPatternArtifacts>>() {
            }, false);
        }

    }

    public PatternResultFileSet searchArtifactsByPattern(String pattern) throws IOException {
        String url = artifactoryUrl + "/api/search/pattern?pattern=" + pattern;
        try (CloseableHttpResponse response = httpClient.getHttpClient().execute(new HttpGet(url));
             InputStream responseStream = getResponseStream(response, "Failed to search artifact by the pattern '" + pattern + "'")) {
            return readJsonResponse(responseStream, new TypeReference<PatternResultFileSet>() {
            }, false);
        }
    }

    public PropertySearchResult searchArtifactsByProperties(String properties) throws IOException {
        String replacedProperties = StringUtils.replaceEach(properties, new String[]{";", "+"}, new String[]{"&", ""});
        String url = artifactoryUrl + "/api/search/prop?" + replacedProperties;
        try (CloseableHttpResponse response = httpClient.getHttpClient().execute(new HttpGet(url));
             InputStream responseStream = getResponseStream(response, "Failed to search artifact by the properties '" + properties + "'")) {
            return readJsonResponse(responseStream, new TypeReference<PropertySearchResult>() {
            }, false);
        }
    }

    public AqlSearchResult searchArtifactsByAql(String aql) throws IOException {
        String url = artifactoryUrl + "/api/search/aql";
        HttpPost httpPost = new HttpPost(url);
        StringEntity entity = new StringEntity(aql);
        httpPost.setEntity(entity);
        try (CloseableHttpResponse response = httpClient.getHttpClient().execute(httpPost);
             InputStream responseStream = getResponseStream(response, "Failed to search artifact by the aql '" + aql + "'")) {
            return readJsonResponse(responseStream, new TypeReference<AqlSearchResult>() {
            }, true);
        }
    }

    public Properties getNpmAuth() throws IOException {
        String url = artifactoryUrl + "/api/npm/auth";
        HttpGet httpGet = new HttpGet(url);
        try (CloseableHttpResponse response = httpClient.getHttpClient().execute(httpGet);
             InputStream responseStream = getResponseStream(response, "npm Auth request failed")) {
            Properties properties = new Properties();
            properties.load(responseStream);
            return properties;
        }
    }

    private InputStream getResponseStream(HttpResponse response, String errorMessage) throws IOException {
        if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                return null;
            }
            return entity.getContent();
        }
        throw new IOException(errorMessage + ": " + response.getStatusLine());
    }

    /**
     * Reads HTTP response and converts it to object of the type specified.
     *
     * @param responseStream response to read
     * @param valueType      response object type
     * @param <T>            response object type
     * @return response object converted from HTTP Json reponse to the type specified.
     * @throws java.io.IOException if reading or converting response fails.
     */
    private <T> T readJsonResponse(InputStream responseStream, TypeReference<T> valueType, boolean ignoreMissingFields)
            throws IOException {
        JsonParser parser = httpClient.createJsonParser(responseStream);
        if (ignoreMissingFields) {
            ((ObjectMapper) parser.getCodec()).configure(FAIL_ON_UNKNOWN_PROPERTIES, false);
        }
        return parser.readValueAs(valueType);
    }

    public CloseableHttpResponse downloadArtifact(String downloadUrl) throws IOException {
        return executeDownload(downloadUrl, false, null);
    }

    public CloseableHttpResponse downloadArtifact(String downloadUrl, Map<String, String> headers) throws IOException {
        return executeDownload(downloadUrl, false, headers);
    }

    public CloseableHttpResponse getArtifactMetadata(String artifactUrl) throws IOException {
        return executeDownload(artifactUrl, true, null);
    }

    public boolean isArtifactoryOSS() throws IOException {
        return !httpClient.getVersion().hasAddons();
    }

    public boolean isLocalRepo(String repo) throws IOException {
        return "local".equals(getRepoType(repo));
    }

    public boolean isRemoteRepo(String repo) throws IOException {
        return "remote".equals(getRepoType(repo));
    }

    private String getRepoType(String repo) throws IOException {
        String url = artifactoryUrl + "/api/repositories/" + repo;
        HttpGet httpGet = new HttpGet(url);
        try (CloseableHttpResponse response = httpClient.getHttpClient().execute(httpGet);
             InputStream responseStream = getResponseStream(response, "Failed to retrieve repository configuration '" + repo + "'")) {
            RepositoryConfig repoDetails = readJsonResponse(responseStream, new TypeReference<RepositoryConfig>() {
            }, true);
            return repoDetails.getRclass();
        }
    }

    private CloseableHttpResponse executeDownload(String artifactUrl, boolean isHead, Map<String, String> headers) throws IOException {
        PreemptiveHttpClient client = httpClient.getHttpClient();

        artifactUrl = ArtifactoryHttpClient.encodeUrl(artifactUrl);
        HttpRequestBase httpRequest = isHead ? new HttpHead(artifactUrl) : new HttpGet(artifactUrl);

        // Explicitly force keep alive
        httpRequest.setHeader("Connection", "Keep-Alive");
        // Add all required headers to the request
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                httpRequest.setHeader(entry.getKey(), entry.getValue());
            }
        }

        CloseableHttpResponse response = client.execute(httpRequest);
        StatusLine statusLine = response.getStatusLine();
        int statusCode = statusLine.getStatusCode();
        if (statusCode == HttpStatus.SC_NOT_FOUND) {
            throw new FileNotFoundException("Unable to find " + artifactUrl);
        }

        if (statusCode != HttpStatus.SC_OK && statusCode != HttpStatus.SC_PARTIAL_CONTENT) {
            EntityUtils.consumeQuietly(response.getEntity());
            throw new IOException("Error downloading " + artifactUrl + ". Code: " + statusCode + " Message: " +
                    statusLine.getReasonPhrase());
        }
        return response;
    }

    public void setProperties(String urlPath, String props) throws IOException {
        String url = ArtifactoryHttpClient.encodeUrl(urlPath + "?properties=");
        url += DeploymentUrlUtils.buildMatrixParamsString(mapPropsString(props), true);
        HttpPut httpPut = new HttpPut(url);
        try (CloseableHttpResponse response = httpClient.getHttpClient().execute(httpPut)) {
            checkNoContent(response, "Failed to set properties to '" + urlPath + "'");
        }
    }

    private ArrayListMultimap<String, String> mapPropsString(String props) {
        ArrayListMultimap<String, String> propsMap = ArrayListMultimap.create();
        String[] propsList = props.split(";");
        for (String prop : propsList) {
            String[] propParts = prop.split("=");
            propsMap.put(propParts[0], propParts[1]);
        }
        return propsMap;
    }

    public void deleteProperties(String urlPath, String props) throws IOException {
        String url = ArtifactoryHttpClient.encodeUrl(urlPath + "?properties=" + props);
        HttpDelete httpDelete = new HttpDelete(url);
        try (CloseableHttpResponse response = httpClient.getHttpClient().execute(httpDelete)) {
            checkNoContent(response, "Failed to delete properties to '" + urlPath + "'");
        }
    }

    private void checkNoContent(HttpResponse response, String errorMessage) throws IOException {
        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_NO_CONTENT) {
            throw new IOException(errorMessage + ": " + response.getStatusLine());
        }
    }

    /**
     * Retrieves build number for the provided build name. For LATEST / LAST_RELEASE build numbers, retrieves actual build number from Artifactory.
     *
     * @return actual build number, null if buildName is not found.
     */
    public String getLatestBuildNumberFromArtifactory(String buildName, String buildNumber, String project) throws IOException {
        if (LATEST.equals(buildNumber.trim()) || LAST_RELEASE.equals(buildNumber.trim())) {
            if (isArtifactoryOSS()) {
                throw new IllegalArgumentException(String.format("%s is not supported in Artifactory OSS.", buildNumber));
            }
            List<BuildPatternArtifactsRequest> artifactsRequest = Lists.newArrayList();
            artifactsRequest.add(new BuildPatternArtifactsRequest(buildName, buildNumber, project));
            List<BuildPatternArtifacts> artifactsResponses = retrievePatternArtifacts(artifactsRequest);
            // Artifactory returns null if no build was found
            if (artifactsResponses.get(0) != null) {
                buildNumber = artifactsResponses.get(0).getBuildNumber();
            } else {
                return null;
            }
        }
        return buildNumber;
    }
}