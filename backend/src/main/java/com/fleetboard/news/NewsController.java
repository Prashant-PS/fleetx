package com.fleetboard.news;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class NewsController {

    private final NewsService news;

    public NewsController(NewsService news) {
        this.news = news;
    }

    /** Latest fleet news; with {@code q}, only stories that mention the query's words. */
    @GetMapping("/news")
    public NewsService.NewsPage news(@RequestParam(value = "q", required = false) String q,
                                     @RequestParam(value = "limit", defaultValue = "12") int limit) {
        return news.latest(q, Math.max(1, Math.min(limit, 50)));
    }
}
