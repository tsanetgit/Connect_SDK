package com.tsanet.api;

/**
 * A Connect API call failed, in a form the caller can act on. Thrown from every call the
 * library makes through its HTTP client, replacing Spring's transport exceptions (whose
 * messages carry the request URL, and the URL carries the case token for most endpoints).
 *
 * <p>{@link #kind()} says what the API answered with:
 * <ul>
 *   <li>{@link Kind#PROBLEM}: an RFC 7807 body ({@code type}, {@code title}, {@code status},
 *       {@code detail}, {@code instance}), which the API returns for the documented 4xx codes
 *       because the library sends {@code Accept: application/json, application/problem+json}
 *       (tsanetgit/Connect_SDK#127). A {@code /v1/} endpoint returns one only to a caller whose
 *       {@code Accept} asks for it.</li>
 *   <li>{@link Kind#LEGACY}: the API's default {@code {"message": ...}} body, which a
 *       {@code /v1/} endpoint returns to a caller that doesn't ask for problem details, usually
 *       with a 500 where a 4xx belongs (tsanetgit/Connect-API-Code#122; kept for backward
 *       compatibility). Classified by the body, not the status, so it never reads as an
 *       unknown server error.</li>
 *   <li>{@link Kind#CONNECTIVITY}: the API could not be reached, or its answer was lost;
 *       {@link #detail()} names the failure's class only. On the OAuth token call,
 *       {@link #title()} is {@value #TOKEN_ENDPOINT}.</li>
 *   <li>{@link Kind#OTHER}: a non-2xx with no recognizable body, where {@link #detail()} carries
 *       at most a short excerpt of it; a redirect, which the client never follows; or a call the
 *       caller interrupted, with status 0 and {@link #detail()} {@code interrupted}.</li>
 * </ul>
 *
 * <p>The message is value-free by construction: status, kind, the API's own title and detail,
 * never the request URL, a header or a token. The request URL and its path are scrubbed from
 * any echoed body before an excerpt is taken; a body that spells out an identifier on its own
 * is not recognized as such.
 *
 * <p>Note for anyone raising the generated client's retry count: its retry loop catches
 * Spring's {@code HttpServerErrorException}, which this handler no longer throws, so that loop
 * is inert. Retries belong in the caller that knows the operation is idempotent.
 */
public class ConnectApiException extends RuntimeException {

    public enum Kind { PROBLEM, LEGACY, CONNECTIVITY, OTHER }

    /** The {@link #title()} of a {@link Kind#CONNECTIVITY} failure on the OAuth token call. */
    public static final String TOKEN_ENDPOINT = "OAuth token endpoint";

    private final Kind kind;
    private final int status;
    private final String type;
    private final String title;
    private final String detail;
    private final String instance;

    public ConnectApiException(Kind kind, int status, String type, String title, String detail, String instance,
                               Throwable cause) {
        super(format(kind, status, type, title, detail), cause);
        this.kind = kind;
        this.status = status;
        this.type = type;
        this.title = title;
        this.detail = detail;
        this.instance = instance;
    }

    public static ConnectApiException connectivity(Throwable cause) {
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return new ConnectApiException(Kind.CONNECTIVITY, 0, null, null, root.getClass().getSimpleName(), null, cause);
    }

    /**
     * The OAuth token endpoint could not be reached, or its answer was lost. Kind
     * {@link Kind#CONNECTIVITY}, with {@link #title()} {@value #TOKEN_ENDPOINT} so the message names
     * what was unreachable, and {@link #detail()} the failure's class name only: never the token
     * URL, which carries the tenant (tsanetgit/Connect_SDK#131). The token gateway passes the
     * transport's own {@code IOException} as {@code cause}, not Spring's wrapper, whose message
     * embeds the URL.
     */
    public static ConnectApiException tokenEndpointUnreachable(Throwable cause) {
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return new ConnectApiException(Kind.CONNECTIVITY, 0, null, TOKEN_ENDPOINT, root.getClass().getSimpleName(), null,
            cause);
    }

    /**
     * The calling thread was interrupted while the call was in flight. The thread's interrupt flag
     * stays set. Kind {@link Kind#OTHER} with status 0, not {@link Kind#CONNECTIVITY}: the caller
     * cancelled, so retrying the same call is not the remedy (tsanetgit/Connect_SDK#131).
     */
    public static ConnectApiException interrupted(InterruptedException cause) {
        return new ConnectApiException(Kind.OTHER, 0, null, null, "interrupted", null, cause);
    }

    public Kind kind() {
        return kind;
    }

    /**
     * The status the API asserted: for {@link Kind#PROBLEM} the body's own {@code status} when it
     * carries one (which can differ from the wire status, see tsanetgit/Connect-API-Code#122),
     * otherwise the wire status; 0 for {@link Kind#CONNECTIVITY} and an interrupted call. Not a
     * transport-level fact.
     */
    public int status() {
        return status;
    }

    /** The RFC 7807 {@code type} URI, or null. */
    public String type() {
        return type;
    }

    public String title() {
        return title;
    }

    /** The API's {@code detail}, the legacy {@code message}, the cause class, or a body excerpt. */
    public String detail() {
        return detail;
    }

    public String instance() {
        return instance;
    }

    /** Whether the problem {@code type} ends with the given segment, for example {@code authentication-error}. */
    public boolean isProblem(String typeSuffix) {
        return type != null && (type.equals(typeSuffix) || type.endsWith("/" + typeSuffix));
    }

    private static String format(Kind kind, int status, String type, String title, String detail) {
        StringBuilder message = new StringBuilder();
        switch (kind) {
            case CONNECTIVITY -> message.append("cannot reach the ").append(title == null ? "Connect API" : title)
                .append(" (").append(detail).append(')');
            case PROBLEM -> {
                message.append("HTTP ").append(status);
                if (title != null && !title.isBlank()) {
                    message.append(' ').append(title);
                }
                if (type != null && !type.isBlank()) {
                    message.append(" (").append(lastSegment(type)).append(')');
                }
                if (detail != null && !detail.isBlank()) {
                    message.append(": ").append(detail);
                }
            }
            case LEGACY -> {
                message.append("HTTP ").append(status).append(" (legacy error)");
                if (detail != null && !detail.isBlank()) {
                    message.append(": ").append(detail);
                }
            }
            case OTHER -> {
                message.append(status == 0 ? "Connect API call" : "HTTP " + status + " from the Connect API");
                if (detail != null && !detail.isBlank()) {
                    message.append(": ").append(detail);
                }
            }
        }
        return message.toString();
    }

    private static String lastSegment(String type) {
        int slash = type.lastIndexOf('/');
        return slash < 0 || slash == type.length() - 1 ? type : type.substring(slash + 1);
    }
}
