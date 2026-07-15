package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ModelPresetService;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.model.AiModelMetadataResolver;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetGenerationModelCapabilitiesToolExecutorTests {

    @Test
    void shouldReturnImageAndVideoCapabilitiesForCurrentDefaults() {
        AiModelService aiModelService = mock(AiModelService.class);
        GenerationModelCapabilityService capabilityService = new GenerationModelCapabilityService(
                new AiModelMetadataResolver(null), new ModelPresetService() {
            @Override
            public String getPresetConfig(String code) {
                return switch (code) {
                    case "gpt-image-1" -> """
                            {
                              "supportReferenceImages": false,
                              "minReferenceImages": 0,
                              "maxReferenceImages": 0,
                              "defaultWidth": 1024,
                              "defaultHeight": 1024
                            }
                            """;
                    case "veo_3_1_r2v_fast" -> """
                            {
                              "supportFirstFrame": false,
                              "supportLastFrame": false,
                              "supportReferenceImages": true,
                              "supportReferenceVideos": false,
                              "supportReferenceAudios": false,
                              "minImageInputs": 0,
                              "maxImageInputs": 3,
                              "maxReferenceImages": 3,
                              "supportedAspectRatios": ["16:9", "9:16"]
                            }
                            """;
                    default -> null;
                };
            }
        });

        AiModel imageModel = AiModel.builder().id(11L).name("GPT Image 1").code("gpt-image-1").build();
        AiModel videoModel = AiModel.builder().id(22L).name("Veo R2V").code("veo_3_1_r2v_fast").build();

        when(aiModelService.getDefaultByType(2)).thenReturn(imageModel);
        when(aiModelService.getDefaultByType(3)).thenReturn(videoModel);

        GetGenerationModelCapabilitiesToolExecutor executor =
                new GetGenerationModelCapabilitiesToolExecutor(aiModelService, capabilityService);

        String result = executor.execute("{}", ToolExecutionContext.builder().userId(1L).build());
        JSONObject json = JSONUtil.parseObj(result);

        assertEquals("success", json.getStr("status"));
        assertFalse(json.getJSONObject("image").getBool("supportsReferenceImages"));
        assertTrue(json.getJSONObject("video").getBool("supportsReferenceImages"));
        assertEquals("default_model", json.getJSONObject("image").getStr("selectionSource"));
        assertTrue(json.getJSONObject("video").getStr("toolGuidance").contains("referenceImageUrls"));
    }

    @Test
    void shouldSupportSingleModelTypeQuery() {
        AiModelService aiModelService = mock(AiModelService.class);
        GenerationModelCapabilityService capabilityService =
                new GenerationModelCapabilityService(new AiModelMetadataResolver(null), new ModelPresetService());

        AiModel imageModel = AiModel.builder().id(11L).name("GPT Image 1").code("gpt-image-1")
                .config("{\"supportReferenceImages\":false,\"maxReferenceImages\":0}")
                .build();
        when(aiModelService.getDefaultByType(2)).thenReturn(imageModel);

        GetGenerationModelCapabilitiesToolExecutor executor =
                new GetGenerationModelCapabilitiesToolExecutor(aiModelService, capabilityService);

        ToolExecutionContext context = ToolExecutionContext.builder().userId(1L).build();
        String result = executor.execute("{\"modelType\":\"image\"}", context);
        JSONObject json = JSONUtil.parseObj(result);

        assertEquals("image", json.getStr("requestedModelType"));
        assertTrue(json.containsKey("image"));
        assertFalse(json.containsKey("video"));
    }
}
