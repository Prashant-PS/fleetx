package com.fleetboard.assistant;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fleetboard.ai.ClaudeAnswerer;
import com.fleetboard.ai.ClaudeClientProvider;
import com.fleetboard.fleet.FleetRepository;
import com.fleetboard.search.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The AI+ assistant. With Claude configured it runs a tool loop over the fleet, agents, workbook and news;
 * otherwise it falls back to {@link LocalAssistant}.
 */
@Service
public class FleetAssistant {

    private static final Logger log = LoggerFactory.getLogger(FleetAssistant.class);
    private static final int MAX_ROUNDS = 10;
    private static final int MAX_HISTORY_TURNS = 6;

    public record Turn(String question, String answer) {
    }

    private static final Map<String, String> TOOL_STATUS = Map.of(
            "fleet_overview", "Looking at your fleet…",
            "list_vehicles", "Listing vehicles…",
            "get_vehicle", "Opening the vehicle record…",
            "run_agent", "Running an agent…",
            "downtime_risk", "Scoring downtime risk…",
            "search_workbook", "Searching the OEM workbook…",
            "fleet_news", "Reading fleet news…",
            "propose_action", "Preparing an action…");

    private static final Map<String, String> MODE_INSTRUCTIONS = Map.of(
            "search", "Mode: Search. Answer quickly and directly. Use the workbook, news or web search for facts "
                    + "outside the fleet data.",
            "analysis", "Mode: Deep analysis. Investigate thoroughly: pull the data you need with several tool calls, "
                    + "look for patterns and root causes across vehicles, depots and makes, quantify the impact, and "
                    + "finish with prioritised recommendations.",
            "report", "Mode: Create report. Produce a well-structured report in markdown with a title (#), short "
                    + "sections (##), tables for numbers and a closing 'Recommended actions' list. Then call "
                    + "propose_action with type save_report and the full report as content, and offer a schedule if "
                    + "the report is something to repeat.",
            "action", "Mode: Take action. Work out what should be done and call propose_action for each concrete "
                    + "step (at most 5): schedules, reminders, saving a report or marking a finding resolved. Explain "
                    + "each briefly. Nothing happens until the user confirms, so never claim an action is done.");

    private final ClaudeClientProvider claude;
    private final FleetTools tools;
    private final LocalAssistant local;
    private final boolean webSearch;
    private final String defaultEffort;

    public FleetAssistant(ClaudeClientProvider claude, FleetTools tools, LocalAssistant local,
                          @Value("${fleet.ai.web-search:true}") boolean webSearch,
                          @Value("${fleet.ai.effort:medium}") String defaultEffort) {
        this.claude = claude;
        this.tools = tools;
        this.local = local;
        this.webSearch = webSearch;
        this.defaultEffort = defaultEffort;
    }

    public boolean aiEnabled() {
        return claude.enabled();
    }

    /**
     * Streams a reply to {@code out}. Throws {@link com.anthropic.errors.AnthropicException} on API failures and
     * {@link IllegalStateException} when the model declines.
     */
    public void respond(String mode, String message, List<Turn> history, String userName, AssistantListener out) {
        String m = mode == null ? "" : mode;
        if (!claude.enabled()) {
            local.respond(m, message, out);
            return;
        }
        AnthropicClient client = claude.client();
        String effort = switch (m) {
            case "analysis" -> "high";
            case "search" -> "low";
            default -> defaultEffort;
        };
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(claude.model())
                .maxTokens(32000L)
                .system(systemPrompt(m, userName))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build())
                // If a safety classifier declines, the API retries on a fallback model server-side.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .addUserMessage(userMessage(message, history));
        tools.definitions().forEach(params::addTool);
        if (webSearch && (m.equals("search") || m.equals("analysis") || m.isEmpty())) {
            params.addTool(WebSearchTool20260209.builder().maxUses(3L).build());
        }

        Map<String, Answer.Source> sources = new LinkedHashMap<>();
        boolean[] wroteText = {false};
        out.status("Thinking…");
        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean[] roundText = {false};
            MessageAccumulator accumulator = MessageAccumulator.create();
            try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params.build())) {
                stream.stream().forEach(event -> {
                    accumulator.accumulate(event);
                    event.contentBlockStart().ifPresent(start -> status(start, out));
                    event.contentBlockDelta().flatMap(d -> d.delta().text()).ifPresent(t -> {
                        if (!roundText[0] && wroteText[0]) {
                            out.text("\n\n");
                        }
                        roundText[0] = true;
                        wroteText[0] = true;
                        out.text(t.text());
                    });
                });
            }
            Message reply = accumulator.message();
            ClaudeAnswerer.collectSources(reply, sources);
            if (!sources.isEmpty()) {
                out.sources(new ArrayList<>(sources.values()));
            }

            StopReason stop = reply.stopReason().orElse(null);
            if (StopReason.REFUSAL.equals(stop)) {
                throw new IllegalStateException("The AI declined to answer this request.");
            }
            if (StopReason.MAX_TOKENS.equals(stop)) {
                out.text("\n\n_(The answer was cut off because it reached the length limit.)_");
                return;
            }
            if (StopReason.PAUSE_TURN.equals(stop)) {
                params.addMessage(reply.toParam());
                continue;
            }
            if (!StopReason.TOOL_USE.equals(stop)) {
                return;
            }
            params.addMessage(reply.toParam());
            params.addUserMessageOfBlockParams(runTools(reply, out));
        }
        log.warn("Assistant stopped after {} tool rounds", MAX_ROUNDS);
        out.text("\n\n_(Stopped after " + MAX_ROUNDS + " tool steps. Ask me to continue if you need more.)_");
    }

    /** Runs every tool call in the message; all results go back together in one user message. */
    private List<ContentBlockParam> runTools(Message message, AssistantListener out) {
        List<ContentBlockParam> results = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block.toolUse().isEmpty()) {
                continue;
            }
            ToolUseBlock call = block.toolUse().get();
            String content;
            boolean error = false;
            try {
                Map<String, Object> input = call._input().convert(new TypeReference<Map<String, Object>>() {
                });
                content = tools.execute(call.name(), input == null ? Map.of() : input, out);
            } catch (FleetTools.ToolInputException e) {
                content = "Error: " + e.getMessage();
                error = true;
            } catch (RuntimeException e) {
                // Includes arguments that arrived truncated or malformed while streaming.
                log.warn("Tool {} failed", call.name(), e);
                content = "Error: the tool input could not be processed (" + e.getClass().getSimpleName() + ")";
                error = true;
            }
            results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(call.id())
                    .content(content)
                    .isError(error)
                    .build()));
        }
        return results;
    }

    private static void status(RawContentBlockStartEvent start, AssistantListener out) {
        RawContentBlockStartEvent.ContentBlock block = start.contentBlock();
        if (block.isToolUse()) {
            out.status(TOOL_STATUS.getOrDefault(block.toolUse().map(t -> t.name()).orElse(""), "Working…"));
        } else if (block.isServerToolUse()) {
            out.status("Searching the web…");
        } else if (block.isWebSearchToolResult()) {
            out.status("Reading search results…");
        } else if (block.isThinking()) {
            out.status("Thinking…");
        } else if (block.isText()) {
            out.status("Writing…");
        }
    }

    private String systemPrompt(String mode, String userName) {
        String today = LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH));
        return """
                You are Fleet Bharat AI+, the assistant of a fleet manager%s. Today is %s.

                You can see the user's fleet through tools: vehicles, live status, faults, service plans, fuel, \
                AdBlue and battery levels, and compliance documents and contracts. %s Five agents check the fleet: \
                fleet-hygiene (data issues), compliance (expiries and deadlines), vehicle-operations (exceptions), \
                ev-operations (charging readiness) and reporting (weekly report); 'attention' combines the most \
                urgent findings. You also have an OEM research workbook comparing connected-fleet services of ten \
                truck OEM families, and fleet industry news.

                How to work:
                - Get facts from the tools; never invent vehicles, numbers or dates. Name vehicles by registration.
                - Lead with the answer in one or two sentences, then details. Use markdown tables for lists of \
                vehicles or comparisons and "- " bullets for steps. Use headings only in reports.
                - Recommend concrete next steps. When something could be scheduled, reminded, saved or marked \
                resolved, offer it with propose_action; the user confirms each card, so never say it is done.
                - In the workbook, D = documented, P = partner, A = announced, NV = not verified (not absent).
                - For questions unrelated to fleets, vehicles or transport, say briefly what you can help with.

                %s

                Finish every reply with a line containing only <<related>>, then three short follow-up requests \
                the user might make next, one per line."""
                .formatted(userName == null || userName.isBlank() ? "" : " named " + userName, today,
                        FleetRepository.DEMO ? "This is a demo fleet of sample vehicles; mention that once if the user "
                                + "seems to treat it as real." : "",
                        MODE_INSTRUCTIONS.getOrDefault(mode, "Mode: General. Pick the right tools for the request."));
    }

    private static String userMessage(String message, List<Turn> history) {
        StringBuilder sb = new StringBuilder();
        List<Turn> recent = history == null ? List.of()
                : history.subList(Math.max(0, history.size() - MAX_HISTORY_TURNS), history.size());
        if (!recent.isEmpty()) {
            sb.append("Earlier in this chat:\n");
            for (Turn t : recent) {
                String a = t.answer() == null ? "" : t.answer();
                sb.append("User: ").append(t.question()).append("\nAssistant: ")
                        .append(a.length() > 2000 ? a.substring(0, 2000) + "…" : a).append("\n\n");
            }
        }
        sb.append(message);
        return sb.toString();
    }
}
