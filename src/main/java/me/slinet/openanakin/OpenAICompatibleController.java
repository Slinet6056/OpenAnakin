package me.slinet.openanakin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class OpenAICompatibleController {

    private final AnakinClient anakinClient;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/chat/completions")
    public Object chatCompletions(@RequestHeader("Authorization") String authorization,
                                  @RequestBody OpenAIRequest request) {
        log.info("收到聊天完成请求，模型: {}, 流式: {}", request.model, request.stream);

        if (request.messages == null || request.messages.isEmpty()) {
            throw new IllegalArgumentException("消息列表不能为空");
        }
        String apiKey = authorization.replace("Bearer ", "");

        return request.stream ? handleStreamRequest(apiKey, request) : handleNonStreamRequest(apiKey, request);
    }

    // 处理流式请求
    private ResponseBodyEmitter handleStreamRequest(String apiKey, OpenAIRequest request) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        executorService.execute(() -> processStreamRequest(apiKey, request, emitter));
        return emitter;
    }

    // 处理非流式请求
    private OpenAIResponse handleNonStreamRequest(String apiKey, OpenAIRequest request) {
        try {
            String response = anakinClient.sendMessage(apiKey, request.model, request.messages);
            return convertToOpenAIResponse(response, request.model);
        } catch (IOException e) {
            log.error("发送消息失败", e);
            throw new RuntimeException("发送消息失败", e);
        }
    }

    // 处理流式请求的具体逻辑
    private void processStreamRequest(String apiKey, OpenAIRequest request, ResponseBodyEmitter emitter) {
        try {
            anakinClient.sendStreamMessage(apiKey, request.model, request.messages, new AnakinClient.StreamCallback() {
                @Override
                public void onEvent(String event, String data) {
                    try {
                        String openAIFormatData = convertToOpenAIFormat(data, request.model);
                        if (!openAIFormatData.isEmpty()) {
                            log.debug("发送流式数据: {}", openAIFormatData);
                            emitter.send(openAIFormatData);
                        }
                    } catch (IOException e) {
                        log.error("发送流式数据失败", e);
                        emitter.completeWithError(e);
                    }
                }

                @Override
                public void onComplete() {
                    log.info("流式响应完成");
                    try {
                        emitter.send("data: [DONE]\n\n");
                        emitter.complete();
                    } catch (IOException e) {
                        log.error("发送完成信号失败", e);
                        emitter.completeWithError(e);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    log.error("流式响应出错", t);
                    emitter.completeWithError(t);
                }
            });
        } catch (Exception e) {
            log.error("处理流式请求失败", e);
            emitter.completeWithError(e);
        }
    }

    // 将Anakin响应转换为OpenAI格式
    private String convertToOpenAIFormat(String data, String model) {
        try {
            JsonNode jsonNode = objectMapper.readTree(data);
            String content = jsonNode.path("content").asText("");

            OpenAIResponse response = new OpenAIResponse();
            response.id = "chatcmpl-" + UUID.randomUUID();
            response.object = "chat.completion.chunk";
            response.created = System.currentTimeMillis() / 1000;
            response.model = model;
            response.choices = new ArrayList<>();

            OpenAIResponse.Choice choice = new OpenAIResponse.Choice();
            choice.index = 0;
            choice.delta = new OpenAIResponse.Delta();
            choice.delta.content = content;
            response.choices.add(choice);

            return "data: " + objectMapper.writeValueAsString(response) + "\n\n";
        } catch (JsonProcessingException e) {
            log.error("转换数据到OpenAI格式失败", e);
            return "";
        }
    }

    // 将Anakin响应转换为OpenAI响应对象
    private OpenAIResponse convertToOpenAIResponse(String anakinResponse, String model) throws JsonProcessingException {
        OpenAIResponse response = new OpenAIResponse();
        response.id = "chatcmpl-" + UUID.randomUUID();
        response.object = "chat.completion";
        response.created = System.currentTimeMillis() / 1000;
        response.model = model;
        response.usage = new OpenAIResponse.Usage();
        response.usage.prompt_tokens = 0;
        response.usage.completion_tokens = 0;
        response.usage.total_tokens = 0;

        OpenAIResponse.Choice choice = new OpenAIResponse.Choice();
        choice.index = 0;
        choice.message = new OpenAIResponse.Message();
        choice.message.role = "assistant";
        choice.message.content = anakinResponse != null ? anakinResponse : "";
        choice.finish_reason = "stop";

        response.choices = Collections.singletonList(choice);

        return response;
    }

    // OpenAI请求对象
    public static class OpenAIRequest {
        public String model;
        public List<Message> messages;
        public boolean stream;
    }

    // 消息对象
    public static class Message {
        public String role;
        public String content;
    }

    // OpenAI响应对象
    private static class OpenAIResponse {
        public String id;
        public String object;
        public long created;
        public String model;
        public Usage usage;
        public List<Choice> choices;

        static class Usage {
            public int prompt_tokens;
            public int completion_tokens;
            public int total_tokens;
        }

        static class Choice {
            public int index;
            public Message message;
            public Delta delta;
            public String finish_reason;
        }

        static class Message {
            public String role;
            public String content;
        }

        static class Delta {
            public String content;
        }
    }
}