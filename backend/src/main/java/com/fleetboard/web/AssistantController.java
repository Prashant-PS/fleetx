package com.fleetboard.web;

import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.RateLimitException;
import com.fleetboard.assistant.ActionService;
import com.fleetboard.assistant.AssistantListener;
import com.fleetboard.assistant.FleetAssistant;
import com.fleetboard.assistant.ProposedAction;
import com.fleetboard.search.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);
    private static final long TIMEOUT_MS = 300_000;

    private final FleetAssistant assistant;
    private final ActionService actions;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    public AssistantController(FleetAssistant assistant, ActionService actions) {
        this.assistant = assistant;
        this.actions = actions;
    }

    /**
     * @param mode search | analysis | report | action, or empty for general
     */
    public record ChatRequest(String message, String mode, List<FleetAssistant.Turn> history, String userName) {
    }

    public record ExecuteRequest(String type, String title, Map<String, Object> payload) {
    }

    /**
     * Streams the reply as server-sent events: {@code status}, {@code delta}, {@code action} (a proposed action
     * card), {@code sources}, then {@code done} or {@code error}.
     */
    @PostMapping(value = "/assistant/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            send(emitter, "error", Map.of("message", "Type a question first."));
            emitter.complete();
            return emitter;
        }
        executor.submit(() -> {
            try {
                assistant.respond(request.mode(), message, request.history(), request.userName(), new AssistantListener() {
                    @Override
                    public void status(String text) {
                        send(emitter, "status", Map.of("message", text));
                    }

                    @Override
                    public void text(String delta) {
                        send(emitter, "delta", Map.of("text", delta));
                    }

                    @Override
                    public void action(ProposedAction action) {
                        send(emitter, "action", action);
                    }

                    @Override
                    public void sources(List<Answer.Source> sources) {
                        send(emitter, "sources", sources);
                    }
                });
                send(emitter, "done", Map.of());
            } catch (RateLimitException e) {
                log.warn("Rate limited by the Claude API");
                send(emitter, "error", Map.of("message", "The AI is busy right now. Please try again in a moment."));
            } catch (AnthropicException e) {
                log.error("Claude API call failed", e);
                send(emitter, "error", Map.of("message", "The AI answer could not be generated."));
            } catch (IllegalStateException | IllegalArgumentException e) {
                send(emitter, "error", Map.of("message", e.getMessage()));
            } catch (ClientGoneException e) {
                log.debug("Client disconnected during chat");
            } catch (RuntimeException e) {
                log.error("Assistant failed", e);
                send(emitter, "error", Map.of("message", "Something went wrong while answering."));
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    /** Carries out an action card the user confirmed. */
    @PostMapping("/actions/execute")
    public ActionService.Result execute(@RequestBody ExecuteRequest request) {
        return actions.execute(request.type(), request.title(), request.payload());
    }

    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) {
            super(cause);
        }
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new ClientGoneException(e);
        }
    }
}
