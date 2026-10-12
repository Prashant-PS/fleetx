package com.fleetboard.search;

import com.fleetboard.data.KnowledgeBase;
import com.fleetboard.data.SheetRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds an answer straight from the workbook, without an AI model.
 * Recognises a feature and/or OEM in the question and reads the matching matrix, evidence and STP rows.
 */
@Component
public class LocalAnswerer {

    private static final List<String> STATUS_ORDER = List.of("D", "P", "A", "NV");

    /** Domain-wide words that are too broad to identify a single feature on their own. */
    private static final Set<String> GENERIC_WORDS = Set.of(
            "fleet", "telematic", "vehicle", "truck", "service", "management", "digital", "connected", "data");

    /** Sheets whose first column names an entity people search for (a segment, package, gate, ...). */
    private static final List<String> ENTITY_SHEETS = List.of(
            "Service Packages", "Customer Segments", "Project STP", "Validation Checklist", "Read Me");

    private final KnowledgeBase kb;

    public LocalAnswerer(KnowledgeBase kb) {
        this.kb = kb;
    }

    /** OEM families named in the query, in the order they appear. */
    public List<String> detectOems(String query) {
        Set<String> oems = new LinkedHashSet<>();
        for (String t : Text.tokens(query)) {
            String oem = Text.OEM_ALIASES.get(t);
            if (oem != null) {
                oems.add(oem);
            }
        }
        return new ArrayList<>(oems);
    }

    /** The feature-matrix row whose feature name best matches the query, if any word overlaps. */
    public Optional<SheetRecord> detectFeature(String query) {
        Set<String> q = new HashSet<>(Text.queryTokens(query));
        q.removeAll(Text.OEM_ALIASES.keySet());
        q.removeAll(GENERIC_WORDS);
        SheetRecord best = null;
        double bestScore = 0;
        for (SheetRecord row : kb.sheet(KnowledgeBase.FEATURE_MATRIX)) {
            List<String> name = Text.tokens(row.field("Feature"));
            long matched = name.stream().distinct().filter(q::contains).count();
            if (matched == 0) {
                continue;
            }
            double score = matched + (double) matched / name.size();
            if (score > bestScore) {
                bestScore = score;
                best = row;
            }
        }
        // A single overlapping word must cover a good part of a short feature name to count.
        return bestScore >= 1.3 ? Optional.ofNullable(best) : Optional.empty();
    }

    public Answer answer(String query, List<SearchEngine.Hit> hits) {
        List<String> oems = detectOems(query);
        Optional<SheetRecord> feature = detectFeature(query);

        if (feature.isPresent() && !oems.isEmpty()) {
            return featureForOems(feature.get(), oems);
        }
        if (feature.isPresent()) {
            return featureOverview(feature.get());
        }
        if (oems.size() >= 2) {
            return compareOems(oems);
        }
        if (oems.size() == 1) {
            Optional<Answer> profile = oemProfile(oems.get(0));
            if (profile.isPresent()) {
                return profile.get();
            }
        }
        Optional<SheetRecord> entity = detectEntity(query, hits);
        if (entity.isPresent()) {
            return recordSummary(entity.get());
        }
        return hits.isEmpty() ? null : recordSummary(hits.get(0).record());
    }

    /** A row whose name (first column) appears in full in the query, e.g. "Uptime" in "uptime package". */
    private Optional<SheetRecord> detectEntity(String query, List<SearchEngine.Hit> hits) {
        Set<String> q = new HashSet<>(Text.tokens(query));
        SheetRecord best = null;
        int bestSize = 0;
        int bestRank = Integer.MAX_VALUE;
        for (String sheet : ENTITY_SHEETS) {
            for (SheetRecord row : kb.sheet(sheet)) {
                List<String> name = Text.tokens(row.fields().values().iterator().next());
                if (name.isEmpty() || !q.containsAll(name)) {
                    continue;
                }
                // Longest name wins; ties go to the row the search engine ranked higher.
                int rank = rankOf(row, hits);
                if (name.size() > bestSize || (name.size() == bestSize && rank < bestRank)) {
                    best = row;
                    bestSize = name.size();
                    bestRank = rank;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static int rankOf(SheetRecord row, List<SearchEngine.Hit> hits) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).record().id().equals(row.id())) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private Answer featureForOems(SheetRecord matrix, List<String> oems) {
        String id = matrix.field("ID");
        StringBuilder sb = new StringBuilder("**").append(matrix.field("Feature")).append("** (")
                .append(id).append(", ").append(matrix.field("Theme")).append(")\n\n");
        List<Answer.Source> sources = new ArrayList<>();
        for (String oem : oems) {
            String code = matrix.field(oem);
            sb.append("- **").append(oem).append("**: ").append(label(code));
            kb.evidence(id, oem).ifPresent(e -> {
                appendIf(sb, " — scope: ", sentence(e.field("Exact platform / regional scope")));
                appendIf(sb, " ", e.field("Evidence note / limitation"));
                addSources(sources, e);
            });
            sb.append('\n');
        }
        List<String> documented = oemsWith(matrix, "D");
        documented.removeAll(oems);
        if (!documented.isEmpty()) {
            sb.append("\nAlso documented by: ").append(String.join(", ", documented)).append('.');
        }
        return new Answer(sb.toString().trim(), sources, "workbook");
    }

    private Answer featureOverview(SheetRecord matrix) {
        String id = matrix.field("ID");
        StringBuilder sb = new StringBuilder("**").append(matrix.field("Feature")).append("** (")
                .append(id).append(", ").append(matrix.field("Theme")).append(") — proposed priority: **")
                .append(matrix.field("Proposed project priority")).append("**\n\n");
        Optional<SheetRecord> dict = kb.dictionaryRow(id);
        dict.ifPresent(d -> appendIf(sb, "", d.field("Acceptance definition")));
        sb.append("\n\n");
        for (String code : STATUS_ORDER) {
            List<String> oems = oemsWith(matrix, code);
            if (!oems.isEmpty()) {
                sb.append("- **").append(label(code)).append("**: ").append(String.join(", ", oems)).append('\n');
            }
        }
        dict.ifPresent(d -> {
            appendIf(sb, "- **Customer outcome**: ", d.field("Customer outcome"));
            sb.append('\n');
            appendIf(sb, "- **Data / delivery prerequisite**: ", d.field("Data / delivery prerequisite"));
        });
        List<Answer.Source> sources = new ArrayList<>();
        addSources(sources, matrix);
        return new Answer(sb.toString().trim(), sources, "workbook");
    }

    /** Side-by-side table of two or more OEMs: STP profile, status counts and the features where they differ. */
    private Answer compareOems(List<String> oems) {
        List<SheetRecord> matrix = kb.sheet(KnowledgeBase.FEATURE_MATRIX);
        StringBuilder sb = new StringBuilder("**").append(String.join(" vs ", oems))
                .append("** — digital fleet services compared across ").append(matrix.size())
                .append(" features.\n\n");

        sb.append(tableHeader("", oems));
        for (String key : List.of("Segment emphasis", "Positioning interpretation", "Platform examples")) {
            sb.append("| ").append(key).append(" |");
            for (String oem : oems) {
                sb.append(' ').append(cell(kb.oemStp(oem).map(r -> r.field(key)).orElse("—"))).append(" |");
            }
            sb.append('\n');
        }
        for (String code : STATUS_ORDER) {
            sb.append("| ").append(KnowledgeBase.STATUS_LABELS.get(code)).append(" (").append(code).append(") |");
            for (String oem : oems) {
                long n = matrix.stream().filter(r -> code.equals(statusOf(r, oem))).count();
                sb.append(' ').append(n).append(" |");
            }
            sb.append('\n');
        }

        List<SheetRecord> differing = matrix.stream()
                .filter(r -> oems.stream().map(o -> statusOf(r, o)).distinct().count() > 1)
                .toList();
        if (!differing.isEmpty()) {
            sb.append("\nWhere they differ (").append(differing.size()).append(" features):\n\n")
                    .append(tableHeader("Feature", oems));
            for (SheetRecord r : differing.subList(0, Math.min(15, differing.size()))) {
                sb.append("| ").append(r.field("ID")).append(' ').append(cell(r.field("Feature"))).append(" |");
                for (String oem : oems) {
                    sb.append(' ').append(statusOf(r, oem)).append(" |");
                }
                sb.append('\n');
            }
            if (differing.size() > 15) {
                sb.append("\n…and ").append(differing.size() - 15).append(" more.");
            }
        }
        sb.append("\n\nCodes show evidence status, not scores: NV means not verified in public sources, not absent.");

        List<Answer.Source> sources = new ArrayList<>();
        oems.forEach(o -> kb.oemStp(o).ifPresent(r -> addSources(sources, r)));
        return new Answer(sb.toString().trim(), sources, "workbook");
    }

    private static String tableHeader(String first, List<String> columns) {
        return "| " + first + " | " + String.join(" | ", columns) + " |\n|---|" + "---|".repeat(columns.size()) + "\n";
    }

    private static String statusOf(SheetRecord matrixRow, String oem) {
        String code = matrixRow.field(oem);
        return code == null ? "NV" : code;
    }

    /** Text safe to place inside a markdown table cell. */
    private static String cell(String text) {
        return text == null ? "—" : text.replace('|', '/').replace('\n', ' ');
    }

    private Optional<Answer> oemProfile(String oem) {
        return kb.oemStp(oem).map(stp -> {
            StringBuilder sb = new StringBuilder("**").append(oem).append("**\n\n");
            for (String key : List.of("Segment emphasis", "Target buyer / user", "Positioning interpretation",
                    "Platform examples", "Critical qualification")) {
                appendIf(sb, "- **" + key + "**: ", stp.field(key));
                sb.append('\n');
            }
            Map<String, List<String>> byCode = new LinkedHashMap<>();
            for (SheetRecord row : kb.sheet(KnowledgeBase.FEATURE_MATRIX)) {
                byCode.computeIfAbsent(row.field(oem) == null ? "NV" : row.field(oem), k -> new ArrayList<>())
                        .add(row.field("Feature"));
            }
            int total = kb.sheet(KnowledgeBase.FEATURE_MATRIX).size();
            sb.append("\nOf ").append(total).append(" features: ");
            List<String> counts = new ArrayList<>();
            for (String code : STATUS_ORDER) {
                counts.add(byCode.getOrDefault(code, List.of()).size() + " " + KnowledgeBase.STATUS_LABELS.get(code).toLowerCase());
            }
            sb.append(String.join(", ", counts)).append('.');
            List<String> documented = byCode.getOrDefault("D", List.of());
            if (!documented.isEmpty()) {
                sb.append("\n\nDocumented features include: ")
                        .append(String.join(", ", documented.subList(0, Math.min(12, documented.size()))))
                        .append(documented.size() > 12 ? ", …" : "").append('.');
            }
            List<Answer.Source> sources = new ArrayList<>();
            addSources(sources, stp);
            return new Answer(sb.toString().trim(), sources, "workbook");
        });
    }

    private Answer recordSummary(SheetRecord r) {
        StringBuilder sb = new StringBuilder("**").append(r.title()).append("** — from *")
                .append(r.sheet()).append("*\n\n");
        r.fields().forEach((k, v) -> {
            if (!k.toLowerCase().contains("url")) {
                sb.append("- **").append(k).append("**: ").append(v.replace('\n', ' ')).append('\n');
            }
        });
        List<Answer.Source> sources = new ArrayList<>();
        addSources(sources, r);
        return new Answer(sb.toString().trim(), sources, "workbook");
    }

    private List<String> oemsWith(SheetRecord matrix, String code) {
        List<String> out = new ArrayList<>();
        for (String oem : kb.oemNames()) {
            if (code.equals(matrix.field(oem))) {
                out.add(oem);
            }
        }
        return out;
    }

    private static String label(String code) {
        if (code == null) {
            return "Not verified";
        }
        String label = KnowledgeBase.STATUS_LABELS.get(code);
        return label == null ? code : label + " (" + code + ")";
    }

    /** Ensures the text ends with exactly one full stop. */
    private static String sentence(String text) {
        return text == null ? null : text.trim().replaceAll("\\.+$", "") + ".";
    }

    private static void appendIf(StringBuilder sb, String prefix, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(prefix).append(value.replace('\n', ' '));
        }
    }

    /** Pairs each URL with its source id (columns hold parallel comma- and newline-separated lists). */
    private static void addSources(List<Answer.Source> sources, SheetRecord r) {
        String ids = r.field("Source IDs", "Market example source IDs", "Example sources", "ID");
        String urls = r.field("Source URLs", "Market example URL", "URL");
        if (urls == null) {
            return;
        }
        String[] idParts = ids == null ? new String[0] : ids.split("[,\\s]+");
        String[] urlParts = urls.split("\\s+");
        for (int i = 0; i < urlParts.length; i++) {
            String url = urlParts[i];
            if (!url.startsWith("http")) {
                continue;
            }
            String label = i < idParts.length ? idParts[i] : "Source";
            if (sources.stream().noneMatch(s -> s.url().equals(url))) {
                sources.add(new Answer.Source(label, url));
            }
        }
    }
}
