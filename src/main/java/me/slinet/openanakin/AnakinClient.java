package me.slinet.openanakin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnakinClient {
    private static final Logger logger = LoggerFactory.getLogger(AnakinClient.class);
    private static final String BASE_URL = "https://api.anakin.ai";
    private static final String API_VERSION = "2024-05-06";

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private final Map<String, Integer> modelAppIds;

    @Autowired
    public AnakinClient(ObjectMapper objectMapper, AnakinProperties anakinProperties) {
        this.client = new OkHttpClient();
        this.objectMapper = objectMapper;
        this.modelAppIds = anakinProperties.getModels();
    }

    // 发送消息并获取响应
    public String sendMessage(String apiKey, String model, List<OpenAICompatibleController.Message> messages) throws IOException {
        Integer appId = getAppId(model);
        String content = buildMessageContent(messages);

        Request request = buildRequest(apiKey, appId, content, false);

        try (Response response = client.newCall(request).execute()) {
            return handleResponse(response);
        }
    }

    // 发送流式消息
    public void sendStreamMessage(String apiKey, String model, List<OpenAICompatibleController.Message> messages, StreamCallback callback) throws IOException {
        Integer appId = getAppId(model);
        String content = buildMessageContent(messages);

        Request request = buildRequest(apiKey, appId, content, true);

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                logger.error("流式请求失败", e);
                callback.onError(e);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                handleStreamResponse(response, callback);
            }
        });
    }

    // 获取应用ID
    private Integer getAppId(String model) {
        Integer appId = modelAppIds.get(model);
        if (appId == null) {
            throw new IllegalArgumentException("不支持的模型: " + model);
        }
        return appId;
    }

    // 构建消息内容
    private String buildMessageContent(List<OpenAICompatibleController.Message> messages) {
        StringBuilder contentBuilder = new StringBuilder();
        for (OpenAICompatibleController.Message message : messages) {
            contentBuilder.append(message.role).append(": ").append(message.content).append("\n");
        }
        return contentBuilder.toString().trim();
    }

    // 构建请求
    private Request buildRequest(String apiKey, Integer appId, String content, boolean isStream) throws IOException {
        RequestBody body = RequestBody.create(
                objectMapper.writeValueAsString(Map.of("content", content, "stream", isStream)),
                MediaType.parse("application/json")
        );

        return new Request.Builder()
                .url(BASE_URL + "/v1/chatbots/" + appId + "/messages")
                .post(body)
                .addHeader("X-Anakin-Api-Version", API_VERSION)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    // 处理非流式响应
    private String handleResponse(Response response) throws IOException {
        if (!response.isSuccessful()) {
            String errorBody = response.body() != null ? response.body().string() : "";
            log.error("请求失败: {}, 错误信息: {}", response, errorBody);
            throw new IOException("Unexpected code " + response + ", 错误信息: " + errorBody);
        }

        if (response.body() != null) {
            String responseBody = response.body().string();
            log.info("Anakin API 响应: {}", responseBody);
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            return jsonNode.path("content").asText("");
        }
        return "";
    }

    // 处理流式响应
    private void handleStreamResponse(Response response, StreamCallback callback) throws IOException {
        try (ResponseBody responseBody = response.body()) {
            if (!response.isSuccessful()) {
                callback.onError(new IOException("Unexpected code " + response));
                return;
            }

            if (responseBody == null) {
                callback.onError(new IOException("Response body is null"));
                return;
            }

            String line;
            while ((line = responseBody.source().readUtf8Line()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    if ("[DONE]".equals(data)) {
                        log.info("流式响应完成");
                        callback.onComplete();
                    } else {
                        log.debug("收到数据: {}", data);
                        callback.onEvent("message", data);
                    }
                }
            }
        }
    }

    // 流式回调接口
    public interface StreamCallback {
        void onEvent(String event, String data);

        void onComplete();

        void onError(Throwable t);
    }
}