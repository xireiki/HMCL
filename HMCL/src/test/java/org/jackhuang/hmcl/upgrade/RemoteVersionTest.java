// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.upgrade;

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for parsing update API responses.
@NotNullByDefault
public final class RemoteVersionTest {
    private HttpServer server;

    /// Starts a local server that always serves the given JSON body.
    private String serve(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /// Accepts a nightly response without a download URL and keeps its workflow source.
    @Test
    public void parsesNightlyWithoutDownloadUrl() throws IOException {
        String url = serve("""
                {"version":"3.17.unofficial-86398b4","force":false,
                 "nightly":{"repository":"xireiki/HMCL","workflow":"gradle.yml","branch":"main"}}
                """);

        RemoteVersion version = RemoteVersion.fetch(UpdateChannel.NIGHTLY, false, url);

        assertNull(version.url());
        assertNull(version.integrityCheck());
        assertEquals("3.17.unofficial-86398b4", version.version());
        assertEquals(new RemoteVersion.NightlySource("xireiki/HMCL", "gradle.yml", "main"), version.nightly());
    }

    /// Rejects a response without a download URL on channels that need one.
    @Test
    public void rejectsMissingDownloadUrlOutsideNightly() throws IOException {
        String url = serve("""
                {"version":"3.17.unofficial-86398b4","force":false,
                 "nightly":{"repository":"xireiki/HMCL","workflow":"gradle.yml","branch":"main"}}
                """);

        assertThrows(IOException.class, () -> RemoteVersion.fetch(UpdateChannel.STABLE, false, url));
    }
}
