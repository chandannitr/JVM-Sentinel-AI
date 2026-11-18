package dev.jvmsentinel.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

@Component
final class LocalRequestFilter extends OncePerRequestFilter {
    private static final Pattern LOOPBACK_HOST = Pattern.compile("(?:127\\.0\\.0\\.1|localhost)(?::[0-9]{1,5})?",
            Pattern.CASE_INSENSITIVE);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'");
        if (request.getRequestURI().startsWith("/api/")) response.setHeader("Cache-Control", "no-store");
        String host = request.getHeader("Host");
        if (!"127.0.0.1".equals(request.getRemoteAddr())
                || host == null || !LOOPBACK_HOST.matcher(host).matches()) {
            error(response, 403, "local_only", "Use the local loopback address.");
        } else if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            response.setHeader("Allow", "GET, HEAD");
            error(response, 405, "read_only", "This service is read-only.");
        } else {
            chain.doFilter(request, response);
        }
    }

    private static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
