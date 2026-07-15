package com.stonewu.fusion.service.generation.media;

/**
 * 模型出站请求使用的媒体二进制资源。
 *
 * @param bytes 媒体字节
 * @param mimeType MIME 类型
 * @param sourceKind 来源类型
 */
public record GenerationMediaResource(byte[] bytes, String mimeType, String sourceKind) {
}
