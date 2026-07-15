package com.stonewu.fusion.service.generation.media;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.storage.StorageConfig;
import com.stonewu.fusion.service.storage.StorageConfigService;
import com.stonewu.fusion.service.system.PresetArtStyleResourceResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationMediaInputResolverTests {

    @TempDir
    private Path tempDir;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldKeepValidatedDataUrlAsDataUrl() {
        GenerationMediaInputResolver resolver = newResolver(mock(StorageConfigService.class));

        String dataUrl = resolver.toImageDataUrl(
                "data:image/png;base64,AQID",
                ApiConfig.builder().build(),
                "测试参考图"
        );

        assertThat(dataUrl).isEqualTo("data:image/png;base64,AQID");
    }

    @Test
    void shouldLoadLocalMediaPathAsDataUrl() throws Exception {
        Path mediaRoot = tempDir.resolve("media-root");
        Path imagePath = mediaRoot.resolve("images/ref.png");
        Files.createDirectories(imagePath.getParent());
        Files.write(imagePath, new byte[]{1, 2, 3});
        StorageConfigService storageConfigService = mock(StorageConfigService.class);
        when(storageConfigService.getDefaultConfig()).thenReturn(StorageConfig.builder()
                .basePath(mediaRoot.toString())
                .build());
        GenerationMediaInputResolver resolver = newResolver(storageConfigService);

        String dataUrl = resolver.toImageDataUrl(
                "/media/images/ref.png",
                ApiConfig.builder().build(),
                "测试参考图"
        );

        assertThat(dataUrl).isEqualTo("data:image/png;base64,AQID");
    }

    @Test
    void shouldDownloadRemoteImageAsDataUrl() throws Exception {
        byte[] imageBytes = new byte[]{4, 5, 6};
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ref.png", exchange -> write(exchange, 200, "image/png", imageBytes));
        server.start();
        GenerationMediaInputResolver resolver = newResolver(mock(StorageConfigService.class));

        String dataUrl = resolver.toImageDataUrl(
                "http://localhost:" + server.getAddress().getPort() + "/ref.png",
                ApiConfig.builder().build(),
                "测试参考图"
        );

        assertThat(dataUrl).isEqualTo("data:image/png;base64,BAUG");
    }

    @Test
    void shouldRejectNonImageRemoteResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/not-image", exchange -> write(exchange, 200, "text/plain", "html".getBytes()));
        server.start();
        GenerationMediaInputResolver resolver = newResolver(mock(StorageConfigService.class));

        assertThatThrownBy(() -> resolver.toImageDataUrl(
                "http://localhost:" + server.getAddress().getPort() + "/not-image",
                ApiConfig.builder().build(),
                "测试参考图"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是有效图片类型");
    }

    private GenerationMediaInputResolver newResolver(StorageConfigService storageConfigService) {
        return new GenerationMediaInputResolver(storageConfigService, new PresetArtStyleResourceResolver());
    }

    private static void write(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
