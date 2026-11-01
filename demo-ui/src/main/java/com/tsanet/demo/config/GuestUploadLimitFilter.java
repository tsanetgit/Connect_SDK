package com.tsanet.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A guest login has a lower upload limit than the operator: a declared length over the limit, or chunked
 * framing, on any method, and no declared length on a POST, PUT or PATCH, is answered here, after the login
 * is known and before anything reads the body. Tomcat holds a request to its declared length. The page's
 * requests always pass: FormData and JSON bodies declare a length, as Chromium does for a bodyless POST.
 */
final class GuestUploadLimitFilter extends OncePerRequestFilter {

    /** Marks a login held to the limit. No rule in {@code SecurityConfig} grants or refuses anything on it. */
    static final String LIMITED_UPLOADS = "LIMITED_UPLOADS";
    static final String OVER_THE_LIMIT = "The request is over this login's upload limit";
    static final String NO_LENGTH = "This login must declare the length of what it sends";

    /** Browsers declare a length of 0 on a bodyless POST or PUT, but none on a bodyless DELETE, so that can't be required. */
    private static final Set<String> METHODS_WITH_A_BODY = Set.of("POST", "PUT", "PATCH");

    private final long limit;

    GuestUploadLimitFilter(long limit) {
        this.limit = limit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isLimited(SecurityContextHolder.getContext().getAuthentication())) {
            if (request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null
                || (request.getContentLengthLong() < 0 && METHODS_WITH_A_BODY.contains(request.getMethod()))) {
                refuse(response, HttpStatus.LENGTH_REQUIRED, NO_LENGTH);
                return;
            }
            if (request.getContentLengthLong() > limit) {
                refuse(response, HttpStatus.PAYLOAD_TOO_LARGE, OVER_THE_LIMIT);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isLimited(Authentication login) {
        return login != null && login.getAuthorities().stream().anyMatch(a -> LIMITED_UPLOADS.equals(a.getAuthority()));
    }

    private static void refuse(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
