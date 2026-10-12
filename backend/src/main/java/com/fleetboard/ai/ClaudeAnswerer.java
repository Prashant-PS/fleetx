package com.fleetboard.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.fleetboard.data.KnowledgeBase;
import com.fleetboard.data.SheetRecord;
import com.fleetboard.news.NewsService;
import com.fleetboard.search.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI answers streamed from Claude. Active only when ANTHROPIC_API_KEY (or ANTHROPIC_AUTH_TOKEN) is set.
 * <p>
 * The workbook (minus the 600-row evidence register) is a cached system prompt; matching evidence rows
 * and earlier turns of the conversation go in the user message. With web search on, Claude can also
 * answer general commercial-vehicle questions (specs, regulations, prices, news) and cite pages.
 */
@Component
public class ClaudeAnswerer {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAnswerer.class);

    /** Line the model writes before its suggested follow-up questions. */
    public static final String RELATED_MARKER = "<<related>>";

    /** A server-tool turn can pause; each continuation resends the paused turn as-is. */
    private static final int MAX_CONTINUATIONS = 3;
    private static final int MAX_HISTORY_TURNS = 4;

    private static final String INSTRUCTIONS = """
            You are the AI answer engine of "Fleetboard", a search page about fleets and commercial \
            vehicles: trucks, buses, vans and LCVs; fleet management, telematics and connected services; \
            OEMs and their brands; specifications, powertrains and EVs; total cost of ownership, \
            maintenance, safety, regulation and market trends. Below is a research workbook comparing \
            digital fleet services across ten OEM families (feature matrix, definitions, evidence, STP \
            analysis, customer segments, service packages, validation checklist, sources).

            How to answer:
            - Lead with the direct answer in one or two sentences, like a search engine's overview box. \
            Then add detail as short "- " bullets. Use **bold** sparingly. No headings.
            - For comparisons ("X vs Y", "compare", "difference between", "which is better"), give a \
            markdown table with one column per item compared and one row per criterion, followed by a \
            one- or two-sentence takeaway. Keep tables to at most 12 rows.
            - When the workbook covers the question, ground the answer in it and cite feature IDs (e.g. \
            F04) or source IDs (e.g. V01). Respect its caveats: D = documented, P = partner, \
            A = announced (not live), NV = not verified, which does not mean absent. Never add these \
            codes up into vendor scores.
            - For anything the workbook does not cover (vehicle specs, prices, regulations, news, other \
            OEMs or models), use web search when it is available and current facts matter; otherwise \
            answer from general knowledge and say so in a few words. Give figures with units and say \
            when they vary by market or model year.
            - If the question has nothing to do with fleets, vehicles, transport or logistics, say in \
            one sentence that this search covers fleet and commercial-vehicle topics.
            - Recent headlines from fleet news feeds may be supplied with the search. For questions about             news, latest updates or recent developments, lead with those that matter most, give each one             its date and publisher and link it as [title](url); search the web for more when they are not             enough. Ignore headlines that are not relevant to the question.
            - Earlier turns of the conversation, when given, are context for follow-up questions.

            Finish every answer with a line containing only %s, then three short follow-up questions \
            the user might search next, one per line, without numbering or bullets.""".formatted(RELATED_MARKER);

    /** Receives a streamed answer. */
    public interface Listener {
        void status(String message);

        void text(String delta);

        void sources(List<Answer.Source> sources);
    }

    public record Turn(String question, String answer) {
    }

    private final AnthropicClient client;
    private final String model;
    private final String effort;
    private final boolean webSearch;
    private final String workbookContext;

    public ClaudeAnswerer(KnowledgeBase kb, ClaudeClientProvider claude,
                          @Value("${fleet.ai.effort:medium}") String effort,
                          @Value("${fleet.ai.web-search:true}") boolean webSearch) {
        this.model = claude.model();
        this.effort = effort;
        this.webSearch = webSearch;
        this.client = claude.client();
        this.workbookContext = buildContext(kb);
        log.info(client != null
                ? "AI answers enabled: model " + model + ", effort " + effort + ", web search " + webSearch
                : "AI answers disabled (set ANTHROPIC_API_KEY to enable); using workbook answers");
    }

    public boolean isEnabled() {
        return client != null;
    }

    public boolean webSearchEnabled() {
        return client != null && webSearch;
    }

    /**
     * Streams an answer to {@code out}. Throws {@link com.anthropic.errors.AnthropicException} on API
     * failures and {@link IllegalStateException} when the model declines to answer.
     */
    public void stream(String question, List<Turn> history, List<SheetRecord> retrieved,
                       List<NewsService.NewsItem> news, Listener out) {
        if (client == null) {
            throw new IllegalStateException("AI answers are not configured");
        }
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder().text(INSTRUCTIONS).build(),
                        TextBlockParam.builder()
                                .text(workbookContext)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build())
                // If a safety classifier declines, the API retries on a fallback model server-side.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .addUserMessage(userMessage(question, history, retrieved, news));
        if (webSearch) {
            params.addTool(WebSearchTool20260209.builder().maxUses(3L).build());
        }

        Map<String, Answer.Source> sources = new LinkedHashMap<>();
        out.status("Thinking…");
        for (int round = 0; round <= MAX_CONTINUATIONS; round++) {
            MessageAccumulator accumulator = MessageAccumulator.create();
            try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params.build())) {
                stream.stream().forEach(event -> {
                    accumulator.accumulate(event);
                    event.contentBlockStart().ifPresent(start -> reportBlockStart(start, out));
                    event.contentBlockDelta()
                            .flatMap(delta -> delta.delta().text())
                            .ifPresent(text -> out.text(text.text()));
                });
            }
            Message message = accumulator.message();
            collectSources(message, sources);
            if (!sources.isEmpty()) {
                out.sources(new ArrayList<>(sources.values()));
            }

            StopReason stop = message.stopReason().orElse(null);
            if (StopReason.REFUSAL.equals(stop)) {
                throw new IllegalStateException("The AI declined to answer this question.");
            }
            if (!StopReason.PAUSE_TURN.equals(stop)) {
                return;
            }
            // Long server-tool turns pause; resend the turn unchanged so the model picks up where it stopped.
            params.addMessage(message);
        }
        log.warn("Answer still paused after {} continuations: {}", MAX_CONTINUATIONS, question);
    }

    private static void reportBlockStart(RawContentBlockStartEvent start, Listener out) {
        RawContentBlockStartEvent.ContentBlock block = start.contentBlock();
        if (block.isServerToolUse()) {
            out.status("Searching the web…");
        } else if (block.isWebSearchToolResult()) {
            out.status("Reading search results…");
        } else if (block.isThinking()) {
            out.status("Thinking…");
        } else if (block.isText()) {
            out.status("Writing…");
        }
    }

    /** Web pages the model searched or cited, keyed by URL. */
    public static void collectSources(Message message, Map<String, Answer.Source> sources) {
        for (ContentBlock block : message.content()) {
            block.text().flatMap(t -> t.citations()).ifPresent(citations -> citations.forEach(c ->
                    c.webSearchResultLocation().ifPresent(loc ->
                            sources.putIfAbsent(loc.url(), new Answer.Source(loc.title().orElse(loc.url()), loc.url())))));
        }
        for (ContentBlock block : message.content()) {
            block.webSearchToolResult().flatMap(r -> r.content().resultBlocks()).ifPresent(results ->
                    results.forEach(r -> sources.putIfAbsent(r.url(), new Answer.Source(r.title(), r.url()))));
        }
    }

    private static String userMessage(String question, List<Turn> history, List<SheetRecord> retrieved,
                                      List<NewsService.NewsItem> news) {
        StringBuilder sb = new StringBuilder();
        if (!news.isEmpty()) {
            sb.append("Recent headlines from fleet news feeds (newest first where relevance is equal):\n");
            for (NewsService.NewsItem n : news) {
                sb.append("- ").append(n.publishedAt() == null ? "undated" : n.publishedAt().toString().substring(0, 10))
                        .append(" · ").append(n.source()).append(" · ").append(n.title())
                        .append(n.summary().isEmpty() ? "" : " — " + n.summary())
                        .append(" (").append(n.url()).append(")\n");
            }
            sb.append('\n');
        }
        if (!retrieved.isEmpty()) {
            sb.append("Workbook rows that matched the search:\n")
                    .append(retrieved.stream().map(SheetRecord::toContextLine).collect(Collectors.joining("\n")))
                    .append("\n\n");
        }
        List<Turn> recent = history.subList(Math.max(0, history.size() - MAX_HISTORY_TURNS), history.size());
        if (!recent.isEmpty()) {
            sb.append("Earlier in this conversation:\n");
            for (Turn t : recent) {
                String answer = t.answer() == null ? "" : t.answer();
                sb.append("Q: ").append(t.question()).append("\nA: ")
                        .append(answer.length() > 1500 ? answer.substring(0, 1500) + "…" : answer).append("\n\n");
            }
        }
        sb.append("Search: ").append(question);
        return sb.toString();
    }

    private static String buildContext(KnowledgeBase kb) {
        StringBuilder sb = new StringBuilder("WORKBOOK: Commercial Vehicle Feature Matrix and STP\n");
        kb.sheetDescriptions().forEach((sheet, description) -> {
            sb.append("\n## ").append(sheet).append(": ").append(description).append('\n');
            if (sheet.equals(KnowledgeBase.EVIDENCE)) {
                sb.append("(Relevant evidence rows are supplied with each search.)\n");
                return;
            }
            kb.sheet(sheet).forEach(r -> sb.append(r.toContextLine()).append('\n'));
        });
        return sb.toString();
    }
}
