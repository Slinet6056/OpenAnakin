package me.slinet.openanakin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/v1")
public class OpenAICompatibleController {
    private static final Logger logger = LoggerFactory.getLogger(OpenAICompatibleController.class);
    private final AnakinClient anakinClient;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public OpenAICompatibleController(AnakinProperties anakinProperties) {
        this.anakinClient = new AnakinClient(anakinProperties.getModels());
    }

    private String convertToOpenAIFormat(String event, String data, String model) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode jsonNode = mapper.readTree(data);
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

            return "data: " + mapper.writeValueAsString(response) + "\n\n";
        } catch (JsonProcessingException e) {
            logger.error("转换数据到OpenAI格式失败", e);
            return "";
        }
    }

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

    @PostMapping("/chat/completions")
    public Object chatCompletions(@RequestHeader("Authorization") String authorization,
                                  @RequestBody OpenAIRequest request) {
        logger.info("收到聊天完成请求，模型: {}, 流式: {}", request.model, request.stream);

        if (request.messages == null || request.messages.isEmpty()) {
            throw new IllegalArgumentException("消息列表不能为空");
        }
        String apiKey = authorization.replace("Bearer ", "");

        if (request.stream) {
            ResponseBodyEmitter emitter = new ResponseBodyEmitter();
            executorService.execute(() -> {
                try {
                    anakinClient.sendStreamMessage(apiKey, request.model, request.messages, new AnakinClient.StreamCallback() {
                        @Override
                        public void onEvent(String event, String data) {
                            try {
                                String openAIFormatData = convertToOpenAIFormat(event, data, request.model);
                                if (!openAIFormatData.isEmpty()) {
                                    logger.debug("发送流式数据: {}", openAIFormatData);
                                    emitter.send(openAIFormatData);
                                }
                            } catch (IOException e) {
                                logger.error("发送流式数据失败", e);
                                emitter.completeWithError(e);
                            }
                        }

                        @Override
                        public void onComplete() {
                            logger.info("流式响应完成");
                            try {
                                emitter.send("data: [DONE]\n\n");
                                emitter.complete();
                            } catch (IOException e) {
                                logger.error("发送完成信号失败", e);
                                emitter.completeWithError(e);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            logger.error("流式响应出错", t);
                            emitter.completeWithError(t);
                        }
                    });
                } catch (Exception e) {
                    logger.error("处理流式请求失败", e);
                    emitter.completeWithError(e);
                }
            });
            return emitter;
        } else {
            try {
                String response = anakinClient.sendMessage(apiKey, request.model, request.messages);
                OpenAIResponse openAIResponse = convertToOpenAIResponse(response, request.model);
                logger.info("非流式响应: {}", openAIResponse);
                return openAIResponse;
            } catch (IOException e) {
                logger.error("发送消息失败", e);
                throw new RuntimeException("发送消息失败", e);
            }
        }
    }

    public static class OpenAIRequest {
        public String model;
        public List<Message> messages;
        public boolean stream;
        public double temperature;
        public Integer max_tokens;
        public Double top_p;
        public Double frequency_penalty;
        public Double presence_penalty;
    }

    public static class Message {
        public String role;
        public String content;
    }

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
            public Message message;
            public Delta delta;
            public String finish_reason;
            public int index;
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