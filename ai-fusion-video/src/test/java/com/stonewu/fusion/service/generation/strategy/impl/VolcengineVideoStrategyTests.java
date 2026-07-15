package com.stonewu.fusion.service.generation.strategy.impl;

import com.stonewu.fusion.entity.generation.VideoTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VolcengineVideoStrategyTests {

    @Test
    void shouldBuildOnlyFirstAndLastFrameWhenReferenceMediaExists() throws Exception {
        VolcengineVideoStrategy strategy = new VolcengineVideoStrategy(null, null, null);
        VideoTask task = VideoTask.builder()
                .prompt("镜头从首帧平滑过渡到尾帧")
                .firstFrameImageUrl("https://example.com/first.png")
                .lastFrameImageUrl("https://example.com/last.png")
                .referenceImageUrls("[\"https://example.com/ref.png\"]")
                .referenceVideoUrls("[\"https://example.com/ref.mp4\"]")
                .referenceAudioUrls("[\"https://example.com/ref.mp3\"]")
                .build();

        List<?> contents = buildContents(strategy, task);

        assertEquals(3, contents.size());
        assertEquals("text", read(contents.get(0), "getType"));
        assertEquals("image_url", read(contents.get(1), "getType"));
        assertEquals("first_frame", read(contents.get(1), "getRole"));
        assertEquals("image_url", read(contents.get(2), "getType"));
        assertEquals("last_frame", read(contents.get(2), "getRole"));
    }

    @Test
    void shouldBuildReferenceMediaWhenNoFrameExists() throws Exception {
        VolcengineVideoStrategy strategy = new VolcengineVideoStrategy(null, null, null);
        VideoTask task = VideoTask.builder()
                .prompt("参考素材生成视频")
                .referenceImageUrls("[\"https://example.com/ref.png\"]")
                .referenceVideoUrls("[\"https://example.com/ref.mp4\"]")
                .referenceAudioUrls("[\"https://example.com/ref.mp3\"]")
                .build();

        List<?> contents = buildContents(strategy, task);

        assertEquals(4, contents.size());
        assertEquals("reference_image", read(contents.get(1), "getRole"));
        assertEquals("reference_video", read(contents.get(2), "getRole"));
        assertEquals("reference_audio", read(contents.get(3), "getRole"));
    }

    private List<?> buildContents(VolcengineVideoStrategy strategy, VideoTask task) throws Exception {
        Method method = VolcengineVideoStrategy.class.getDeclaredMethod("buildContents", VideoTask.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(strategy, task);
    }

    private Object read(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
