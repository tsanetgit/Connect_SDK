package com.tsanet.demo.config;

import com.tsanet.demo.web.ApiErrorHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.catalina.core.ApplicationPart;
import org.apache.tomcat.util.http.fileupload.FileItem;
import org.apache.tomcat.util.http.fileupload.FileUpload;
import org.apache.tomcat.util.http.fileupload.disk.DiskFileItemFactory;
import org.apache.tomcat.util.http.fileupload.servlet.ServletRequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * A guest login has a lower upload limit than the operator. It runs after the Basic filter, so it knows
 * who the login is, and before anything reads the body. A declared length over the limit is answered
 * here. Anything else is read under the limit by the request wrapper below, which is what covers a body
 * with no declared length.
 *
 * <p>The limit has to be enforced in {@code getParts}: Tomcat's own parser reads its connector's stream
 * with the servlet's one configured limit, so a wrapped stream wouldn't reach it. This does what
 * {@code org.apache.catalina.connector.Request.parseParts} does, with a per-request limit.
 */
final class GuestUploadLimitFilter extends OncePerRequestFilter {

    /** Marks a login held to the limit. No rule in {@code SecurityConfig} grants or refuses anything on it. */
    static final String LIMITED_UPLOADS = "LIMITED_UPLOADS";

    /** Tomcat's default for {@code maxPartCount}, which its own parser would apply. */
    private static final int MAX_PARTS = 50;

    private final long limit;
    private final MultipartConfigElement multipart;

    GuestUploadLimitFilter(long limit, MultipartConfigElement multipart) {
        this.limit = limit;
        this.multipart = multipart;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isMultipart(request) || !isLimited(SecurityContextHolder.getContext().getAuthentication())) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limit) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"" + ApiErrorHandler.UPLOAD_TOO_LARGE + "\"}");
            return;
        }
        LimitedRequest limited = new LimitedRequest(request);
        try {
            chain.doFilter(limited, response);
        } finally {
            limited.deleteParts();
        }
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    private static boolean isLimited(Authentication login) {
        return login != null && login.getAuthorities().stream().anyMatch(a -> LIMITED_UPLOADS.equals(a.getAuthority()));
    }

    /** Parses once, under the limit, and answers parameters from those parts so Tomcat never parses the body itself. */
    private final class LimitedRequest extends HttpServletRequestWrapper {
        private List<Part> parts;
        private IOException failure;
        private Map<String, String[]> parameters;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public Collection<Part> getParts() throws IOException {
            parse();
            if (failure != null) {
                throw failure;
            }
            return parts;
        }

        @Override
        public Part getPart(String name) throws IOException {
            for (Part part : getParts()) {
                if (part.getName().equals(name)) {
                    return part;
                }
            }
            return null;
        }

        private void parse() {
            if (parts != null || failure != null) {
                return;
            }
            File location = location(getServletContext());
            location.mkdirs();
            DiskFileItemFactory factory = new DiskFileItemFactory(multipart.getFileSizeThreshold(), location);
            factory.setDefaultCharset(StandardCharsets.UTF_8.name());
            FileUpload upload = new FileUpload();
            upload.setFileItemFactory(factory);
            upload.setSizeMax(limit);
            upload.setFileSizeMax(limit);
            upload.setFileCountMax(MAX_PARTS);
            try {
                List<Part> parsed = new ArrayList<>();
                for (FileItem item : upload.parseRequest(new ServletRequestContext(this))) {
                    parsed.add(new ApplicationPart(item, location));
                }
                parts = parsed;
            } catch (IOException refused) {
                failure = refused;
            }
        }

        private File location(ServletContext context) {
            File temp = (File) context.getAttribute(ServletContext.TEMPDIR);
            String configured = multipart.getLocation();
            if (configured == null || configured.isEmpty()) {
                return temp;
            }
            File location = new File(configured);
            return location.isAbsolute() ? location : new File(temp, configured);
        }

        void deleteParts() {
            if (parts == null) {
                return;
            }
            for (Part part : parts) {
                try {
                    part.delete();
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        }

        private Map<String, String[]> parameters() {
            if (parameters != null) {
                return parameters;
            }
            Map<String, List<String>> values = new LinkedHashMap<>();
            String query = getQueryString();
            if (query != null) {
                UriComponentsBuilder.newInstance().query(query).build().getQueryParams().forEach((name, sent) -> {
                    for (String value : sent) {
                        values.computeIfAbsent(UriUtils.decode(name, StandardCharsets.UTF_8), k -> new ArrayList<>())
                            .add(value == null ? "" : UriUtils.decode(value, StandardCharsets.UTF_8));
                    }
                });
            }
            parse();
            if (parts != null) {
                for (Part part : parts) {
                    if (part.getSubmittedFileName() == null) {
                        values.computeIfAbsent(part.getName(), k -> new ArrayList<>()).add(fieldValue(part));
                    }
                }
            }
            Map<String, String[]> answer = new LinkedHashMap<>();
            values.forEach((name, sent) -> answer.put(name, sent.toArray(String[]::new)));
            parameters = Collections.unmodifiableMap(answer);
            return parameters;
        }

        private static String fieldValue(Part part) {
            try {
                return ((ApplicationPart) part).getString(StandardCharsets.UTF_8.name());
            } catch (IOException unreadable) {
                return "";
            }
        }

        @Override
        public String getParameter(String name) {
            String[] sent = parameters().get(name);
            return sent == null ? null : sent[0];
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters().keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            return parameters().get(name);
        }
    }
}
