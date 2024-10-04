package me.slinet.openanakin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class AnakinClient {
    private static final Logger logger = LoggerFactory.getLogger(AnakinClient.class);
    private final OkHttpClient client;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final Map<String, Integer> modelAppIds;

    public AnakinClient(Map<String, Integer> modelAppIds) {
        this.client = new OkHttpClient();
        this.baseUrl = "https://api.anakin.ai";
        this.objectMapper = new ObjectMapper();
        this.modelAppIds = modelAppIds;
    }

    public String sendMessage(String apiKey, String model, List<OpenAICompatibleController.Message> messages) throws IOException {
        Integer appId = modelAppIds.get(model);
        if (appId == null) {
            throw new IllegalArgumentException("不支持的模型: " + model);
        }

        StringBuilder contentBuilder = new StringBuilder();
        for (OpenAICompatibleController.Message message : messages) {
            contentBuilder.append(message.role).append(": ").append(message.content).append("\n");
        }
        String content = contentBuilder.toString().trim();

        RequestBody body = RequestBody.create(
                objectMapper.writeValueAsString(Map.of("content", content, "stream", false)),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(baseUrl + "/v1/chatbots/" + appId + "/messages")
                .post(body)
                .addHeader("X-Anakin-Api-Version", "2024-05-06")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                logger.error("请求失败: {}, 错误信息: {}", response, errorBody);
                throw new IOException("Unexpected code " + response + ", 错误信息: " + errorBody);
            }

            if (response.body() != null) {
                String responseBody = response.body().string();
                logger.info("Anakin API 响应: {}", responseBody);
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                return jsonNode.path("content").asText("");
            }
        }
        return "";
    }

    public void sendStreamMessage(String apiKey, String model, List<OpenAICompatibleController.Message> messages, StreamCallback callback) throws IOException {
        Integer appId = modelAppIds.get(model);
        if (appId == null) {
            throw new IllegalArgumentException("不支持的模型: " + model);
        }

        StringBuilder contentBuilder = new StringBuilder();
        for (OpenAICompatibleController.Message message : messages) {
            contentBuilder.append(message.role).append(": ").append(message.content).append("\n");
        }
        String content = contentBuilder.toString().trim();

        RequestBody body = RequestBody.create(
                objectMapper.writeValueAsString(Map.of("content", content, "stream", true)),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(baseUrl + "/v1/chatbots/" + appId + "/messages")
                .post(body)
                .addHeader("X-Anakin-Api-Version", "2024-05-06")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                logger.error("流式请求失败", e);
                callback.onError(e);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
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
                                logger.info("流式响应完成");
                                callback.onComplete();
                            } else {
                                logger.debug("收到数据: {}", data);
                                callback.onEvent("message", data);
                            }
                        }
                    }
                }
            }
        });
    }

    public interface StreamCallback {
        void onEvent(String event, String data);

        void onComplete();

        void onError(Throwable t);
    }

    private static class MessageRequest {
        public List<Map<String, String>> messages;
        public boolean stream;

        public MessageRequest(List<Map<String, String>> messages, boolean stream) {
            this.messages = messages;
            this.stream = stream;
        }
    }
}