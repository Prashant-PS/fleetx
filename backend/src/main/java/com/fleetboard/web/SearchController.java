package com.fleetboard.web;

import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.RateLimitException;
import com.fleetboard.ai.ClaudeAnswerer;
import com.fleetboard.data.KnowledgeBase;
import com.fleetboard.data.SheetRecord;
import com.fleetboard.news.NewsService;
import com.fleetboard.search.Answer;
import com.fleetboard.search.LocalAnswerer;
import com.fleetboard.search.SearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private static final int MAX_RESULTS = 50;
    private static final long AI_TIMEOUT_MS = 180_000;

    private final ExecutorService aiExecutor = Executors.newFixedThreadPool(8);

    private final KnowledgeBase kb;
    private final SearchEngine engine;
    private final LocalAnswerer localAnswerer;
    private final ClaudeAnswerer claude;
    private final NewsService news;

    public SearchController(KnowledgeBase kb, SearchEngine engine, LocalAnswerer localAnswerer, ClaudeAnswerer claude,
                            NewsService news) {
        this.news = news;
        this.kb = kb;
        this.engine = engine;
        this.localAnswerer = localAnswerer;
        this.claude = claude;
    }

    public record Result(String id, String sheet, String title, String snippet, String url,
                         Map<String, String> fields, double score) {
    }

    public record SearchResponse(String query, long tookMs, int total, Answer answer, List<Result> results,
                                 boolean aiEnabled) {
    }

    public record Info(int records, List<String> sheets, List<String> oems, boolean aiEnabled, boolean webSearch) {
    }

    public record AiRequest(String question, List<ClaudeAnswerer.Turn> history) {
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam("q") String query) {
        long start = System.nanoTime();
        String q = query.trim();
        List<SearchEngine.Hit> hits = engine.search(q, MAX_RESULTS);

        Answer answer = q.isEmpty() ? null : localAnswerer.answer(q, hits);
        List<Result> results = hits.stream().map(h -> toResult(h.record(), h.score())).toList();
        return new SearchResponse(q, (System.nanoTime() - start) / 1_000_000, results.size(), answer, results,
                claude.isEnabled());
    }

    /**
     * Streams the AI answer as server-sent events: {@code status} (progress text), {@code delta}
     * (answer text), {@code sources} (cited web pages), then {@code done} or {@code error}.
     */
    @PostMapping(value = "/ai/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter aiAnswer(@RequestBody AiRequest request) {
        SseEmitter emitter = new SseEmitter(AI_TIMEOUT_MS);
        String q = request.question() == null ? "" : request.question().trim();
        List<ClaudeAnswerer.Turn> history = request.history() == null ? List.of() : request.history();
        if (q.isEmpty() || !claude.isEnabled()) {
            send(emitter, "error", Map.of("message", q.isEmpty() ? "Empty question" : "AI answers are not configured"));
            emitter.complete();
            return emitter;
        }
        aiExecutor.submit(() -> {
            try {
                List<SheetRecord> context = contextFor(q, engine.search(q, MAX_RESULTS));
                List<NewsService.NewsItem> headlines = news.latest(q, 8).items();
                claude.stream(q, history, context, headlines, new ClaudeAnswerer.Listener() {
                    @Override
                    public void status(String message) {
                        send(emitter, "status", Map.of("message", message));
                    }

                    @Override
                    public void text(String delta) {
                        send(emitter, "delta", Map.of("text", delta));
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
            } catch (IllegalStateException e) {
                send(emitter, "error", Map.of("message", e.getMessage()));
            } catch (ClientGoneException e) {
                log.debug("Client disconnected during AI answer");
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    /** Thrown from a stream callback once the browser has gone away, which stops the model stream. */
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

    @GetMapping("/suggest")
    public List<String> suggest(@RequestParam("q") String prefix) {
        return engine.suggest(prefix, 8);
    }

    @GetMapping("/info")
    public Info info() {
        return new Info(kb.records().size(), new ArrayList<>(kb.sheetDescriptions().keySet()), kb.oemNames(),
                claude.isEnabled(), claude.webSearchEnabled());
    }

    /** Top hits plus the evidence rows for any feature/OEM pair named in the question. */
    private List<SheetRecord> contextFor(String q, List<SearchEngine.Hit> hits) {
        Set<SheetRecord> rows = new LinkedHashSet<>();
        localAnswerer.detectFeature(q).ifPresent(f -> {
            List<String> oems = localAnswerer.detectOems(q);
            for (String oem : oems.isEmpty() ? kb.oemNames() : oems) {
                kb.evidence(f.field("ID"), oem).ifPresent(rows::add);
            }
        });
        hits.stream().limit(25).forEach(h -> rows.add(h.record()));
        return new ArrayList<>(rows);
    }

    private static Result toResult(SheetRecord r, double score) {
        StringBuilder snippet = new StringBuilder();
        r.fields().forEach((k, v) -> {
            if (v.startsWith("http") || (v.length() > 3 && r.title().contains(v))) {
                return;
            }
            snippet.append(snippet.length() > 0 ? " · " : "").append(k).append(": ").append(v.replace('\n', ' '));
        });
        String s = snippet.length() > 260 ? snippet.substring(0, 257) + "…" : snippet.toString();
        return new Result(r.id(), r.sheet(), r.title(), s, r.firstUrl(), r.fields(), Math.round(score * 100) / 100.0);
    }
}
