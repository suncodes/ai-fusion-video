package com.stonewu.fusion.service.generation.media;

import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.storage.StorageConfig;
import com.stonewu.fusion.service.ai.proxy.AiProxySupport;
import com.stonewu.fusion.service.storage.StorageConfigService;
import com.stonewu.fusion.service.system.PresetArtStyleResourceResolver;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 生成模型出站媒体输入解析器。
 * <p>
 * 该组件只负责把应用可读取的图片引用转换成模型请求可直接使用的 Data URL。
 * Data URL 只用于出站请求，不应写入业务表或返回给前端。
 */
@Service
@RequiredArgsConstructor
public class GenerationMediaInputResolver {

    private static final String DEFAULT_LOCAL_MEDIA_BASE_PATH = "./data/media";
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;

    private final StorageConfigService storageConfigService;
    private final PresetArtStyleResourceResolver presetArtStyleResourceResolver;

    private final OkHttpClient downloadHttpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    public List<String> toImageDataUrls(List<String> imageSources, ApiConfig apiConfig, String fieldName) {
        if (imageSources == null || imageSources.isEmpty()) {
            return List.of();
        }
        return imageSources.stream()
                .filter(StrUtil::isNotBlank)
                .map(source -> toImageDataUrl(source, apiConfig, fieldName))
                .toList();
    }

    public String toImageDataUrl(String imageSource, ApiConfig apiConfig, String fieldName) {
        try {
            GenerationMediaResource resource = loadImageResource(imageSource, apiConfig, fieldName);
            return "data:" + resource.mimeType() + ";base64,"
                    + Base64.getEncoder().encodeToString(resource.bytes());
        } catch (IOException e) {
            throw new BusinessException(label(fieldName) + "处理失败: " + e.getMessage());
        }
    }

    public GenerationMediaResource loadImageResource(String imageSource, ApiConfig apiConfig,
                                                     String fieldName) throws IOException {
        if (StrUtil.isBlank(imageSource)) {
            throw new BusinessException(label(fieldName) + "地址为空");
        }
        String trimmed = imageSource.trim();
        if (StrUtil.startWithIgnoreCase(trimmed, "data:")) {
            return parseDataUrl(trimmed, fieldName);
        }
        if (trimmed.startsWith("/media/")) {
            return loadLocalMedia(trimmed, fieldName);
        }
        if (presetArtStyleResourceResolver.isPresetArtStylePath(trimmed)) {
            PresetArtStyleResourceResolver.PresetArtStyleResource resource =
                    presetArtStyleResourceResolver.load(trimmed);
            ensureImageMimeType(resource.mimeType(), fieldName);
            validateImageSize(resource.bytes().length, fieldName);
            return new GenerationMediaResource(resource.bytes(), normalizeMimeType(resource.mimeType()),
                    "preset-art-style");
        }
        if (StrUtil.startWithIgnoreCase(trimmed, "http://")
                || StrUtil.startWithIgnoreCase(trimmed, "https://")) {
            return downloadRemoteImage(trimmed, apiConfig, fieldName);
        }
        throw new BusinessException(label(fieldName) + "地址不可解析: " + preview(trimmed));
    }

    private GenerationMediaResource parseDataUrl(String sourceUrl, String fieldName) {
        int commaIndex = sourceUrl.indexOf(',');
        if (commaIndex <= 0) {
            throw new BusinessException(label(fieldName) + "data URL 格式非法");
        }
        String metadata = sourceUrl.substring(0, commaIndex);
        String payload = sourceUrl.substring(commaIndex + 1).replaceAll("\\s+", "");
        if (!metadata.toLowerCase(Locale.ROOT).contains(";base64")) {
            throw new BusinessException(label(fieldName) + "data URL 必须使用 base64 编码");
        }
        String mimeType = normalizeMimeType(metadata.substring("data:".length()).split(";", 2)[0]);
        ensureImageMimeType(mimeType, fieldName);
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            validateImageSize(bytes.length, fieldName);
            return new GenerationMediaResource(bytes, mimeType, "data-url");
        } catch (IllegalArgumentException e) {
            throw new BusinessException(label(fieldName) + "data URL base64 非法: " + e.getMessage());
        }
    }

    private GenerationMediaResource loadLocalMedia(String sourceUrl, String fieldName) throws IOException {
        String relativePath = sourceUrl.replaceFirst("^/media/?", "");
        if (StrUtil.isBlank(relativePath)) {
            throw new BusinessException(label(fieldName) + "本地媒体路径为空");
        }
        List<Path> roots = new ArrayList<>();
        StorageConfig config = storageConfigService.getDefaultConfig();
        if (config != null && StrUtil.isNotBlank(config.getBasePath())) {
            roots.add(Paths.get(config.getBasePath()));
        }
        roots.add(Paths.get(DEFAULT_LOCAL_MEDIA_BASE_PATH));

        for (Path root : roots) {
            Path base = root.toAbsolutePath().normalize();
            Path candidate = base.resolve(relativePath).normalize();
            if (!candidate.startsWith(base)) {
                continue;
            }
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return loadFile(candidate, sourceUrl, fieldName, "local-media");
            }
        }
        throw new BusinessException(label(fieldName) + "本地媒体不存在: " + sourceUrl);
    }

    private GenerationMediaResource loadFile(Path path, String sourceUrl, String fieldName,
                                             String sourceKind) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        validateImageSize(bytes.length, fieldName);
        String mimeType = inferImageMimeType(null, sourceUrl);
        ensureImageMimeType(mimeType, fieldName);
        return new GenerationMediaResource(bytes, mimeType, sourceKind);
    }

    private GenerationMediaResource downloadRemoteImage(String sourceUrl, ApiConfig apiConfig,
                                                        String fieldName) throws IOException {
        Request request = new Request.Builder()
                .url(sourceUrl)
                .get()
                .addHeader("Accept", "image/*,*/*;q=0.8")
                .build();
        OkHttpClient client = AiProxySupport.okHttpClient(downloadHttpClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new BusinessException(label(fieldName) + "下载失败: HTTP " + response.code()
                        + " url=" + preview(sourceUrl));
            }
            long contentLength = body.contentLength();
            if (contentLength > MAX_IMAGE_BYTES) {
                throw new BusinessException(label(fieldName) + "图片过大: " + contentLength + " bytes");
            }
            byte[] bytes = body.bytes();
            validateImageSize(bytes.length, fieldName);
            String mimeType = inferImageMimeType(response.header("Content-Type"), sourceUrl);
            ensureImageMimeType(mimeType, fieldName);
            return new GenerationMediaResource(bytes, mimeType, "remote-url");
        }
    }

    private void validateImageSize(long size, String fieldName) {
        if (size <= 0) {
            throw new BusinessException(label(fieldName) + "图片内容为空");
        }
        if (size > MAX_IMAGE_BYTES) {
            throw new BusinessException(label(fieldName) + "图片过大: " + size + " bytes");
        }
    }

    private void ensureImageMimeType(String mimeType, String fieldName) {
        if (StrUtil.isBlank(mimeType) || !mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BusinessException(label(fieldName) + "不是有效图片类型: " + mimeType);
        }
    }

    private String inferImageMimeType(String contentType, String sourceUrl) {
        if (StrUtil.isNotBlank(contentType)) {
            String normalized = normalizeMimeType(contentType);
            if (!"application/octet-stream".equals(normalized)) {
                return normalized;
            }
        }
        String lower = sourceUrl == null ? "" : sourceUrl.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    private String normalizeMimeType(String mimeType) {
        if (StrUtil.isBlank(mimeType)) {
            return "application/octet-stream";
        }
        return mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private String label(String fieldName) {
        return StrUtil.blankToDefault(fieldName, "参考图");
    }

    private String preview(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 160) {
            return trimmed;
        }
        return trimmed.substring(0, 160) + "...";
    }
}
