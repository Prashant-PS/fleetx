package com.fleetboard.news;

import com.fleetboard.search.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Latest fleet and commercial-vehicle news from publisher RSS/Atom feeds.
 * Feeds are fetched in parallel at startup and every {@code fleet.news.refresh-minutes}; requests are
 * served from the in-memory cache.
 */
@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; FleetboardNews/1.0)";
    private static final int MAX_ITEMS_PER_FEED = 40;
    private static final Pattern IMG_SRC = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    public record Feed(String name, String url) {
    }

    public record NewsItem(String title, String url, String summary, String source, Instant publishedAt,
                           String imageUrl) {
    }

    public record NewsPage(Instant updatedAt, List<String> sources, List<NewsItem> items) {
    }

    private final List<Feed> feeds;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile List<NewsItem> items = List.of();
    private volatile List<String> liveSources = List.of();
    private volatile Instant updatedAt;

    public NewsService(@Value("${fleet.news.feeds}") List<String> feedSpecs) {
        List<Feed> parsed = new ArrayList<>();
        for (String spec : feedSpecs) {
            int bar = spec.indexOf('|');
            if (bar > 0) {
                parsed.add(new Feed(spec.substring(0, bar).trim(), spec.substring(bar + 1).trim()));
            }
        }
        this.feeds = List.copyOf(parsed);
    }

    /** Runs once shortly after startup, then on the configured interval. */
    @Scheduled(initialDelay = 0, fixedDelayString = "#{${fleet.news.refresh-minutes:30} * 60000}")
    public void refresh() {
        List<CompletableFuture<List<NewsItem>>> jobs = feeds.stream()
                .map(f -> CompletableFuture.supplyAsync(() -> fetch(f)))
                .toList();
        List<NewsItem> all = new ArrayList<>();
        List<String> ok = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            List<NewsItem> fromFeed = jobs.get(i).join();
            if (!fromFeed.isEmpty()) {
                ok.add(feeds.get(i).name());
                all.addAll(fromFeed);
            }
        }
        if (all.isEmpty()) {
            log.warn("No news fetched from any feed; keeping {} cached items", items.size());
            return;
        }
        Set<String> seen = new HashSet<>();
        List<NewsItem> unique = new ArrayList<>();
        for (NewsItem item : all) {
            if (seen.add(item.url()) && seen.add(item.title().toLowerCase(Locale.ROOT))) {
                unique.add(item);
            }
        }
        unique.sort(Comparator.comparing(NewsItem::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        items = List.copyOf(unique);
        liveSources = List.copyOf(ok);
        updatedAt = Instant.now();
        log.info("News refreshed: {} items from {}/{} feeds", unique.size(), ok.size(), feeds.size());
    }

    /** Newest items, or with a query, the items that mention its words, best matches first. */
    public NewsPage latest(String query, int limit) {
        List<NewsItem> snapshot = items;
        List<NewsItem> result;
        Set<String> terms = new LinkedHashSet<>(Text.queryTokens(query));
        terms.removeAll(Set.of("latest", "news", "update", "new", "recent", "today", "fleet", "fleets", "headline"));
        if (terms.isEmpty()) {
            result = mixSources(snapshot, limit);
        } else {
            record Scored(NewsItem item, int score) {
            }
            result = snapshot.stream()
                    .map(item -> new Scored(item, score(item, terms)))
                    .filter(s -> s.score() > 0)
                    .sorted(Comparator.comparingInt(Scored::score).reversed()
                            .thenComparing(s -> s.item().publishedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(limit)
                    .map(Scored::item)
                    .toList();
        }
        return new NewsPage(updatedAt, liveSources, result);
    }

    /**
     * Newest stories, interleaved by publisher: every publisher's newest story comes before anyone's
     * second, so a feed that posts in bursts does not crowd out the rest.
     */
    private static List<NewsItem> mixSources(List<NewsItem> newestFirst, int limit) {
        Map<String, Integer> seenPerSource = new HashMap<>();
        Map<NewsItem, Integer> rank = new HashMap<>();
        for (NewsItem item : newestFirst) {
            rank.put(item, seenPerSource.merge(item.source(), 1, Integer::sum));
        }
        return newestFirst.stream()
                .sorted(Comparator.<NewsItem>comparingInt(rank::get)
                        .thenComparing(NewsItem::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    private static int score(NewsItem item, Set<String> terms) {
        Set<String> title = new HashSet<>(Text.tokens(item.title()));
        Set<String> body = new HashSet<>(Text.tokens(item.summary()));
        int score = 0;
        for (String t : terms) {
            if (title.contains(t)) {
                score += 3;
            } else if (body.contains(t)) {
                score += 1;
            }
        }
        return score;
    }

    private List<NewsItem> fetch(Feed feed) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(feed.url()))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.5")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("News feed {} returned HTTP {}", feed.name(), response.statusCode());
                return List.of();
            }
            return parse(response.body(), feed.name());
        } catch (Exception e) {
            log.warn("News feed {} failed: {}", feed.name(), e.toString());
            return List.of();
        }
    }

    static List<NewsItem> parse(byte[] xml, String source) throws Exception {
        // Some feeds carry control characters that are illegal in XML 1.0; drop them instead of failing.
        String text = new String(xml, StandardCharsets.UTF_8)
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replaceFirst("^\uFEFF", "");
        Document doc = newSafeBuilder().parse(new InputSource(new StringReader(text)));
        List<NewsItem> out = new ArrayList<>();
        NodeList entries = doc.getElementsByTagNameNS("*", "item");
        boolean atom = entries.getLength() == 0;
        if (atom) {
            entries = doc.getElementsByTagNameNS("*", "entry");
        }
        for (int i = 0; i < entries.getLength() && out.size() < MAX_ITEMS_PER_FEED; i++) {
            Element e = (Element) entries.item(i);
            String title = clean(child(e, "title"));
            String link = atom ? atomLink(e) : child(e, "link");
            if (title.isEmpty() || link == null || !link.trim().startsWith("http")) {
                continue;
            }
            String rawSummary = firstNonBlank(child(e, "description"), child(e, "summary"), child(e, "encoded"),
                    child(e, "content"));
            String summary = clean(rawSummary);
            if (summary.length() > 260) {
                summary = summary.substring(0, 257).replaceAll("\\s+\\S*$", "") + "…";
            }
            Instant published = parseDate(firstNonBlank(child(e, "pubDate"), child(e, "published"),
                    child(e, "updated"), child(e, "date")));
            out.add(new NewsItem(title, link.trim(), summary, source, published, image(e, rawSummary)));
        }
        return out;
    }

    /** XML parser with DOCTYPEs and external entities disabled (feeds are untrusted input). */
    private static DocumentBuilder newSafeBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder builder = f.newDocumentBuilder();
        builder.setErrorHandler(null);
        return builder;
    }

    /** Text of the first direct child with this local name. */
    private static String child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && localName.equals(el.getLocalName())) {
                return el.getTextContent();
            }
        }
        return null;
    }

    private static String atomLink(Element entry) {
        String fallback = null;
        for (Node n = entry.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && "link".equals(el.getLocalName())) {
                String rel = el.getAttribute("rel");
                if (rel.isEmpty() || rel.equals("alternate")) {
                    return el.getAttribute("href");
                }
                fallback = el.getAttribute("href");
            }
        }
        return fallback;
    }

    private static String image(Element e, String rawSummary) {
        for (String name : List.of("content", "thumbnail")) {
            NodeList media = e.getElementsByTagNameNS("*", name);
            for (int i = 0; i < media.getLength(); i++) {
                Element m = (Element) media.item(i);
                String url = m.getAttribute("url");
                String type = m.getAttribute("type");
                String medium = m.getAttribute("medium");
                if (url.startsWith("http") && (type.startsWith("image") || medium.equals("image") || name.equals("thumbnail")
                        || url.matches("(?i).*\\.(jpe?g|png|webp|gif)(\\?.*)?$"))) {
                    return url;
                }
            }
        }
        NodeList enclosures = e.getElementsByTagNameNS("*", "enclosure");
        for (int i = 0; i < enclosures.getLength(); i++) {
            Element enc = (Element) enclosures.item(i);
            if (enc.getAttribute("type").startsWith("image") && enc.getAttribute("url").startsWith("http")) {
                return enc.getAttribute("url");
            }
        }
        String html = firstNonBlank(rawSummary, child(e, "encoded"));
        if (html != null) {
            Matcher m = IMG_SRC.matcher(html);
            if (m.find() && m.group(1).startsWith("http")) {
                return m.group(1);
            }
        }
        return null;
    }

    /** Strips HTML tags and decodes the common entities left in feed text. */
    static String clean(String html) {
        if (html == null) {
            return "";
        }
        String text = html.replaceAll("(?s)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&#8216;", "‘").replace("&#8217;", "’").replace("&#8220;", "“").replace("&#8221;", "”")
                .replace("&#8211;", "–").replace("&#8212;", "—").replace("&#8230;", "…")
                .replaceAll("\\s+", " ")
                .trim();
        return text.replaceAll("(?i)\\s*The post .* appeared first on .*$", "").trim();
    }

    private static Instant parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim().replaceAll("\\s+", " ");
        try {
            return OffsetDateTime.parse(v).toInstant(); // ISO 8601 (Atom, Dublin Core)
        } catch (Exception ignored) {
            // RSS dates follow RFC 822 loosely; try the variants seen in real feeds
        }
        for (DateTimeFormatter f : RSS_DATE_FORMATS) {
            try {
                return ZonedDateTime.parse(v, f).toInstant();
            } catch (Exception ignored) {
                // try the next format
            }
        }
        return null;
    }

    /** RFC 1123, plus two-digit years ("Sat, 10 Oct 26") and zone names ("EDT"). */
    private static final List<DateTimeFormatter> RSS_DATE_FORMATS = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, d MMM yy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, d MMM yy HH:mm:ss z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH));

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
