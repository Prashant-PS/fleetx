package com.fleetboard.search;

import com.fleetboard.data.KnowledgeBase;
import com.fleetboard.data.SheetRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** BM25 ranking over every workbook row. */
@Component
public class SearchEngine {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int TITLE_BOOST = 3;

    /** The 600-row evidence register would otherwise crowd out the summary sheets. */
    private static final Map<String, Double> SHEET_WEIGHT = Map.of(KnowledgeBase.EVIDENCE, 0.6);

    public record Hit(SheetRecord record, double score) {
    }

    private record Doc(SheetRecord record, Map<String, Integer> tf, int length) {
    }

    private final List<Doc> docs = new ArrayList<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private final double avgLength;
    private final List<String> vocabulary;

    public SearchEngine(KnowledgeBase kb) {
        long total = 0;
        for (SheetRecord r : kb.records()) {
            Map<String, Integer> tf = new HashMap<>();
            List<String> body = Text.tokens(r.text());
            body.forEach(t -> tf.merge(t, 1, Integer::sum));
            Text.tokens(r.title()).forEach(t -> tf.merge(t, TITLE_BOOST, Integer::sum));
            tf.keySet().forEach(t -> docFreq.merge(t, 1, Integer::sum));
            docs.add(new Doc(r, tf, body.size()));
            total += body.size();
        }
        avgLength = docs.isEmpty() ? 1 : (double) total / docs.size();
        vocabulary = buildVocabulary(kb);
    }

    public List<Hit> search(String query, int limit) {
        List<String> terms = Text.queryTokens(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        String phrase = query.toLowerCase(Locale.ROOT).trim();
        List<Hit> hits = new ArrayList<>();
        for (Doc d : docs) {
            double score = 0;
            for (String t : new LinkedHashSet<>(terms)) {
                Integer f = d.tf().get(t);
                if (f == null) {
                    continue;
                }
                double idf = Math.log(1 + (docs.size() - docFreq.get(t) + 0.5) / (docFreq.get(t) + 0.5));
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * d.length() / avgLength));
            }
            if (score <= 0) {
                continue;
            }
            if (phrase.length() > 3 && d.record().title().toLowerCase(Locale.ROOT).contains(phrase)) {
                score += 3;
            }
            score *= SHEET_WEIGHT.getOrDefault(d.record().sheet(), 1.0);
            hits.add(new Hit(d.record(), score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    /** Autocomplete over feature names, OEMs, segments, packages and sheet names. */
    public List<String> suggest(String prefix, int limit) {
        String p = prefix.toLowerCase(Locale.ROOT).trim();
        if (p.isEmpty()) {
            return List.of();
        }
        List<String> starts = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String v : vocabulary) {
            String lower = v.toLowerCase(Locale.ROOT);
            if (lower.startsWith(p)) {
                starts.add(v);
            } else if (lower.contains(p)) {
                contains.add(v);
            }
        }
        starts.addAll(contains);
        return starts.size() > limit ? starts.subList(0, limit) : starts;
    }

    private static List<String> buildVocabulary(KnowledgeBase kb) {
        Set<String> vocab = new LinkedHashSet<>(kb.oemNames());
        for (SheetRecord r : kb.records()) {
            String term = switch (r.sheet()) {
                case KnowledgeBase.FEATURE_MATRIX -> r.field("Feature");
                case "Customer Segments" -> r.field("Segment");
                case "Service Packages" -> r.field("Package");
                case "Validation Checklist" -> r.field("Gate");
                case "Project STP" -> r.field("STP element");
                default -> null;
            };
            if (term != null) {
                vocab.add(term);
            }
        }
        vocab.addAll(kb.sheetDescriptions().keySet());
        return new ArrayList<>(vocab);
    }
}
