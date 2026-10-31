// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.upgrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Resolves the download URL of a nightly build from GitHub Actions.
///
/// GitHub requires authentication for Actions artifacts, so the caller provides a personal access token.
/// The artifact endpoint redirects to a pre-signed URL, which is returned for the regular download task.
@NotNullByDefault
final class NightlyUpdateSource {
    private static final String API_URL = "https://api.github.com";

    private NightlyUpdateSource() {
    }

    /// Finds the download URL of the nightly build matching the offered version.
    ///
    /// @param version the update description containing the nightly source
    /// @param token the user's GitHub token
    /// @return the pre-signed artifact download URL
    /// @throws IOException if the workflow, artifact, or redirect cannot be resolved
    static String resolve(RemoteVersion version, String token) throws IOException {
        RemoteVersion.NightlySource source = version.nightly();
        if (source == null) {
            throw new IOException("Nightly source is missing");
        }
        LOG.info("Resolving nightly build " + version.version() + " from " + source.repository());

        try {
            String runsBody = getJson("/repos/" + source.repository()
                    + "/actions/workflows/" + encode(source.workflow())
                    + "/runs?branch=" + encode(source.branch()) + "&status=success&per_page=1", token);
            JsonArray runs = JsonUtils.fromNonNullJson(runsBody, JsonObject.class)
                    .getAsJsonArray("workflow_runs");
            if (runs == null || runs.isEmpty()) {
                throw new IOException("No successful nightly workflow run");
            }
            long runId = runs.get(0).getAsJsonObject().get("id").getAsLong();

            String artifactsBody = getJson("/repos/" + source.repository() + "/actions/runs/" + runId + "/artifacts?per_page=100", token);
            JsonArray artifacts = JsonUtils.fromNonNullJson(artifactsBody, JsonObject.class)
                    .getAsJsonArray("artifacts");
            if (artifacts == null) {
                throw new IOException("No nightly artifact list");
            }

            String expectedName = "HMCL-" + version.version() + ".jar";
            String archiveUrl = null;
            for (JsonElement element : artifacts) {
                JsonObject artifact = element.getAsJsonObject();
                if (artifact.has("expired") && artifact.get("expired").getAsBoolean()) {
                    continue;
                }
                String name = artifact.get("name").getAsString();
                if (expectedName.equals(name)) {
                    archiveUrl = artifact.get("archive_download_url").getAsString();
                    break;
                }
            }
            if (archiveUrl == null) {
                throw new IOException("Nightly build " + version.version() + " is unavailable");
            }

            return resolveRedirect(archiveUrl, token);
        } catch (JsonParseException e) {
            throw new IOException("Malformed GitHub API response", e);
        }
    }

    /// Requests a GitHub API path and returns the response body.
    private static String getJson(String path, String token) throws IOException {
        HttpURLConnection connection = open(API_URL + path);
        try {
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

            int code = connection.getResponseCode();
            try (InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                String body = input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
                if (code < 200 || code >= 300) {
                    throw new IOException("GitHub API responded with " + code);
                }
                return body;
            }
        } finally {
            connection.disconnect();
        }
    }

    /// Reads the redirect target of the artifact download endpoint.
    private static String resolveRedirect(String url, String token) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setInstanceFollowRedirects(false);

            int code = connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            if (code < 300 || code >= 400 || location == null || location.isBlank()) {
                throw new IOException("GitHub artifact did not return a download redirect");
            }
            return location;
        } finally {
            connection.disconnect();
        }
    }

    /// Opens a connection with the headers shared by all GitHub requests.
    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = NetworkUtils.createHttpConnection(url);
        connection.setRequestProperty("User-Agent", Metadata.NAME);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        return connection;
    }

    /// URL-encodes a value for use in a query string or path segment.
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
