// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.upgrade;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jackhuang.hmcl.task.FileDownloadTask.IntegrityCheck;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Optional;

/// Describes an available launcher update.
///
/// Stable and development updates carry a download URL and checksum. Nightly updates may omit them:
/// the launcher then downloads the build from GitHub Actions with a user-supplied token.
///
/// @param channel the update channel
/// @param version the target version
/// @param url the jar download URL, or `null` when the launcher must resolve it
/// @param type the artifact type
/// @param integrityCheck the expected checksum, or `null` when none was provided
/// @param preview whether the update is a preview build
/// @param force whether the update must be installed regardless of version comparison
/// @param nightly the GitHub Actions source for an unresolved nightly update, or `null`
@NotNullByDefault
public record RemoteVersion(UpdateChannel channel, String version, @Nullable String url, Type type,
                            @Nullable IntegrityCheck integrityCheck, boolean preview, boolean force,
                            @Nullable NightlySource nightly) {

    /// Reads an update description from the update API.
    ///
    /// @param channel the requested update channel
    /// @param preview whether the request accepts preview builds
    /// @param url the update API URL
    /// @return the parsed update description
    /// @throws IOException if the response is malformed or has no usable download source
    public static RemoteVersion fetch(UpdateChannel channel, boolean preview, String url) throws IOException {
        try {
            JsonObject response = JsonUtils.fromNonNullJson(NetworkUtils.doGet(url), JsonObject.class);
            String version = Optional.ofNullable(response.get("version")).map(JsonElement::getAsString).orElseThrow(() -> new IOException("version is missing"));
            String jarUrl = Optional.ofNullable(response.get("jar")).map(JsonElement::getAsString).orElse(null);
            String jarHash = Optional.ofNullable(response.get("jarsha1")).map(JsonElement::getAsString).orElse(null);
            boolean force = Optional.ofNullable(response.get("force")).map(JsonElement::getAsBoolean).orElse(false);
            NightlySource nightly = parseNightlySource(response.get("nightly"));
            if (jarUrl != null && jarHash != null) {
                return new RemoteVersion(channel, version, jarUrl, Type.JAR, new IntegrityCheck("SHA-1", jarHash), preview, force, null);
            }
            if (channel == UpdateChannel.NIGHTLY && nightly != null) {
                return new RemoteVersion(channel, version, null, Type.JAR, null, preview, force, nightly);
            }
            throw new IOException("No download url is available");
        } catch (JsonParseException e) {
            throw new IOException("Malformed response", e);
        }
    }

    /// Returns a copy of this update that downloads from the given URL.
    ///
    /// @param url the resolved download URL
    /// @return the resolved update description
    public RemoteVersion withUrl(String url) {
        return new RemoteVersion(channel, version, url, type, integrityCheck, preview, force, nightly);
    }

    /// Parses the optional GitHub Actions source of a nightly update.
    private static @Nullable NightlySource parseNightlySource(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String repository = stringOf(object, "repository");
        String workflow = stringOf(object, "workflow");
        String branch = stringOf(object, "branch");
        return repository != null && workflow != null && branch != null
                ? new NightlySource(repository, workflow, branch)
                : null;
    }

    /// Reads a non-blank string field, returning null when it is absent.
    private static @Nullable String stringOf(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        String value = element.getAsString();
        return value.isBlank() ? null : value;
    }

    @Override
    public @NotNull String toString() {
        return "[" + version + " from " + url + "]";
    }

    /// The launcher update artifact format.
    public enum Type {
        JAR
    }

    /// A GitHub Actions workflow that publishes nightly builds.
    ///
    /// @param repository the `owner/name` repository
    /// @param workflow the workflow file name
    /// @param branch the branch the workflow runs on
    public record NightlySource(String repository, String workflow, String branch) {
    }
}
