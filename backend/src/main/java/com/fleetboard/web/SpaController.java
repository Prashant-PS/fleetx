package com.fleetboard.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * In the deployed build the React app is served from {@code classpath:/static}. Client-side routes such as
 * /chat or /agents/compliance have no file, so they get index.html and the app's router takes over.
 */
@Controller
public class SpaController {

    /** Must match the routes in frontend/src/main.jsx. */
    @GetMapping({"/chat", "/fleet", "/agents", "/agents/*", "/custom-agents", "/custom-agents/*", "/custom-agents/*/edit",
            "/saved", "/saved/*", "/schedules", "/apps", "/help", "/search"})
    public String app() {
        return "forward:/index.html";
    }

    /** Liveness check for the hosting platform; never password-protected. */
    @GetMapping("/healthz")
    @ResponseBody
    public String health() {
        return "ok";
    }
}
