package com.fleetboard.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Protects a public demo with one shared password (HTTP Basic, any user name) when
 * {@code fleet.demo.password} / FLEET_DEMO_PASSWORD is set. The browser remembers it for the session,
 * so API and streaming calls from the app are covered too.
 */
@Component
public class DemoPasswordFilter extends OncePerRequestFilter {

    private final byte[] password;

    public DemoPasswordFilter(@Value("${fleet.demo.password:}") String password) {
        this.password = password.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return password.length == 0 || "/healthz".equals(request.getRequestURI())
                || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (authorized(request.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("WWW-Authenticate", "Basic realm=\"Fleet Bharat demo\", charset=\"UTF-8\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Password required");
    }

    private boolean authorized(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            byte[] given = (colon < 0 ? "" : decoded.substring(colon + 1)).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(given, password);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
