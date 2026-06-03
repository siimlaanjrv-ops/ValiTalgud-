package ee.bcs.valitalgud.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import ee.bcs.valitalgud.controller.chat.dto.ChatMessageDto;
import ee.bcs.valitalgud.controller.chat.dto.ChatRequestDto;
import ee.bcs.valitalgud.controller.chat.dto.ChatResponseDto;
import ee.bcs.valitalgud.infrastructure.config.GroqProperties;
import ee.bcs.valitalgud.infrastructure.error.ErrorResponse;
import ee.bcs.valitalgud.infrastructure.exception.BadRequestException;
import ee.bcs.valitalgud.infrastructure.exception.ServiceUnavailableException;
import ee.bcs.valitalgud.infrastructure.exception.TooManyRequestsException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final long MIN_INTERVAL_MS = 1000;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final GroqProperties properties;
    private final RestClient groqRestClient;
    private final String systemPrompt;
    private final ConcurrentHashMap<String, Long> lastCallByIp = new ConcurrentHashMap<>();

    public ChatService(GroqProperties properties,
                       @Qualifier("groqRestClient") RestClient groqRestClient,
                       @Value("classpath:chatbot-context.md") Resource contextResource) throws IOException {
        this.properties = properties;
        this.groqRestClient = groqRestClient;
        this.systemPrompt = contextResource.getContentAsString(StandardCharsets.UTF_8).strip();
    }

    public ChatResponseDto chat(ChatRequestDto request, String clientIp) {
        validateConfigured();
        checkRateLimit(clientIp);
        String userMessage = validateMessage(request);
        Map<String, Object> requestBody = buildRequestBody(request, userMessage);
        GroqResponse groqResponse = callGroq(requestBody);
        return new ChatResponseDto(extractReply(groqResponse));
    }

    public SseEmitter streamChat(ChatRequestDto request, String clientIp) {
        validateConfigured();
        checkRateLimit(clientIp);
        String userMessage = validateMessage(request);
        Map<String, Object> requestBody = buildRequestBody(request, userMessage);
        requestBody.put("stream", true);

        SseEmitter emitter = new SseEmitter(30_000L);
        emitter.onTimeout(emitter::complete);

        CompletableFuture.runAsync(() -> {
            try {
                groqRestClient.post()
                        .uri("/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .exchange((req, resp) -> {
                            if (resp.getStatusCode().value() == 429) {
                                sendErrorEvent(emitter, "Pam on hetkel ülekoormatud — proovi 30 sekundi pärast 🙂");
                                return null;
                            }
                            if (!resp.getStatusCode().is2xxSuccessful()) {
                                log.error("Groq stream returned {}", resp.getStatusCode());
                                sendErrorEvent(emitter, "Midagi läks valesti. Proovi uuesti.");
                                return null;
                            }
                            processStream(resp.getBody(), emitter);
                            return null;
                        });
            } catch (Exception e) {
                log.error("Groq stream request failed", e);
                sendErrorEvent(emitter, "Midagi läks valesti. Proovi uuesti.");
            }
        });
        return emitter;
    }

    private void processStream(InputStream inputStream, SseEmitter emitter) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                if (data.isEmpty()) continue;
                String content = extractDeltaContent(data);
                if (content != null) {
                    // JSON-encode the token so SSE space-stripping doesn't corrupt leading spaces
                    emitter.send(objectMapper.writeValueAsString(content));
                }
            }
            emitter.complete();
        } catch (IOException e) {
            log.debug("Stream ended (client may have disconnected): {}", e.getMessage());
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

    private String extractDeltaContent(String json) {
        try {
            StreamChunk chunk = objectMapper.readValue(json, StreamChunk.class);
            if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                StreamDelta delta = chunk.choices().getFirst().delta();
                if (delta != null && delta.content() != null && !delta.content().isEmpty()) {
                    return delta.content();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse stream chunk: {}", json);
        }
        return null;
    }

    private void sendErrorEvent(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void validateConfigured() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ServiceUnavailableException(ErrorResponse.CHAT_NOT_CONFIGURED);
        }
    }

    private void checkRateLimit(String clientIp) {
        long now = System.currentTimeMillis();
        Long prev = lastCallByIp.put(clientIp, now);
        if (prev != null && (now - prev) < MIN_INTERVAL_MS) {
            throw new TooManyRequestsException(ErrorResponse.CHAT_RATE_LIMITED);
        }
    }

    private String validateMessage(ChatRequestDto request) {
        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            throw new BadRequestException(ErrorResponse.CHAT_MESSAGE_REQUIRED);
        }
        String message = request.getMessage().strip();
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new BadRequestException(ErrorResponse.CHAT_MESSAGE_TOO_LONG);
        }
        return message;
    }

    private Map<String, Object> buildRequestBody(ChatRequestDto request, String userMessage) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        addHistory(messages, request.getHistory());
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", properties.getModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", properties.getTemperature());
        requestBody.put("max_completion_tokens", properties.getMaxCompletionTokens());
        return requestBody;
    }

    private void addHistory(List<Map<String, String>> messages, List<ChatMessageDto> history) {
        if (history == null) {
            return;
        }
        int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        for (ChatMessageDto message : history.subList(start, history.size())) {
            if (isValidHistoryMessage(message)) {
                messages.add(Map.of("role", message.getRole(), "content", message.getContent()));
            }
        }
    }

    private boolean isValidHistoryMessage(ChatMessageDto message) {
        return message != null
                && ("user".equals(message.getRole()) || "assistant".equals(message.getRole()))
                && message.getContent() != null && !message.getContent().isBlank();
    }

    private GroqResponse callGroq(Map<String, Object> requestBody) {
        try {
            return groqRestClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(GroqResponse.class);
        } catch (RestClientException ex) {
            log.error("Groq chat request failed", ex);
            throw new ServiceUnavailableException(ErrorResponse.CHAT_REQUEST_FAILED);
        }
    }

    private String extractReply(GroqResponse groqResponse) {
        if (groqResponse == null || groqResponse.choices() == null || groqResponse.choices().isEmpty()
                || groqResponse.choices().getFirst().message() == null) {
            log.error("Groq chat response was empty or malformed");
            throw new ServiceUnavailableException(ErrorResponse.CHAT_REQUEST_FAILED);
        }
        return groqResponse.choices().getFirst().message().content().strip();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GroqResponse(List<GroqChoice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GroqChoice(GroqMessage message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GroqMessage(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StreamChunk(List<StreamChoice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StreamChoice(StreamDelta delta) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StreamDelta(String content) {}
}