package com.fleetboard.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tokenizing and vocabulary helpers shared by search and answer building. */
public final class Text {

    private Text() {
    }

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "by", "with", "at", "from", "as",
            "is", "are", "was", "were", "be", "been", "it", "its", "this", "that", "these", "those",
            "what", "which", "who", "whom", "how", "why", "when", "where", "does", "do", "did", "can",
            "could", "should", "would", "will", "about", "me", "my", "i", "we", "our", "you", "your",
            "tell", "show", "give", "list", "any", "all", "there", "has", "have", "had", "not", "no",
            "than", "then", "so", "if", "into", "per", "vs", "versus", "compare", "comparison");

    /** Words in a query that name an OEM family, mapped to the matrix column name. */
    public static final Map<String, String> OEM_ALIASES = Map.ofEntries(
            Map.entry("daimler", "Daimler Truck"), Map.entry("mercedes", "Daimler Truck"),
            Map.entry("bharatbenz", "Daimler Truck"), Map.entry("fleetboard", "Daimler Truck"),
            Map.entry("detroit", "Daimler Truck"), Map.entry("freightliner", "Daimler Truck"),
            Map.entry("truckonnect", "Daimler Truck"),
            Map.entry("volvo", "Volvo Group"),
            Map.entry("traton", "TRATON"), Map.entry("man", "TRATON"), Map.entry("scania", "TRATON"),
            Map.entry("rio", "TRATON"),
            Map.entry("paccar", "PACCAR"), Map.entry("daf", "PACCAR"), Map.entry("kenworth", "PACCAR"),
            Map.entry("peterbilt", "PACCAR"),
            Map.entry("iveco", "IVECO"),
            Map.entry("isuzu", "Isuzu"),
            Map.entry("hino", "Hino / ARCHION"), Map.entry("archion", "Hino / ARCHION"),
            Map.entry("tata", "Tata Motors"),
            Map.entry("ashok", "Ashok Leyland"), Map.entry("leyland", "Ashok Leyland"),
            Map.entry("hyundai", "Hyundai CV"));

    /** Query words expanded with a related term that appears in the workbook. */
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("ev", "electric"), Map.entry("evs", "electric"), Map.entry("gps", "location"),
            Map.entry("tracking", "location"), Map.entry("track", "location"), Map.entry("battery", "soc"),
            Map.entry("charging", "charge"), Map.entry("repair", "maintenance"), Map.entry("service", "maintenance"),
            Map.entry("fuel", "efficiency"), Map.entry("driver", "coaching"), Map.entry("api", "integration"),
            Map.entry("price", "pricing"), Map.entry("cost", "pricing"), Map.entry("target", "targeting"),
            Map.entry("segment", "segmentation"), Map.entry("stp", "positioning"));

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.isEmpty() || STOPWORDS.contains(raw)) {
                continue;
            }
            out.add(stem(raw));
        }
        return out;
    }

    /** Query tokens plus synonym expansions. */
    public static List<String> queryTokens(String query) {
        List<String> base = tokens(query);
        List<String> out = new ArrayList<>(base);
        for (String t : base) {
            String syn = SYNONYMS.get(t);
            if (syn != null) {
                out.add(stem(syn));
            }
        }
        return out;
    }

    /** Very light plural stripping so "alerts" matches "alert". */
    static String stem(String t) {
        if (t.length() > 4 && t.endsWith("ies")) {
            return t.substring(0, t.length() - 3) + "y";
        }
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss") && !t.endsWith("us")) {
            return t.substring(0, t.length() - 1);
        }
        return t;
    }
}
