package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CollaborationRequestNotesTimeline {
    private static final Set<String> NOTES_BLOCKED_STATUSES = Set.of("CANCELED", "CANCELLED", "REJECTED");
    private static final Set<String> NOTES_PERMITTED_STATUSES = Set.of("OPEN", "ACCEPTED", "RESOLVED", "CLOSED");

    private CollaborationRequestNotesTimeline() {
    }

    public record AccessResult(boolean canFetch, String message) {
        public static AccessResult permitted() {
            return new AccessResult(true, null);
        }

        public static AccessResult blocked(String message) {
            return new AccessResult(false, message);
        }
    }

    public static AccessResult validateAccess(CollaborationRequestStatusDto request) {
        if (request.status() == null || request.status().isBlank()) {
            return AccessResult.permitted();
        }
        String normalized = request.status().toUpperCase(Locale.ROOT);
        if (NOTES_BLOCKED_STATUSES.contains(normalized)) {
            return AccessResult.blocked(
                "Notes timeline is not available for requests with status " + request.status() + "."
            );
        }
        if (!NOTES_PERMITTED_STATUSES.contains(normalized)) {
            return AccessResult.blocked(
                "Notes timeline is not available until the request is approved or in an API-permitted state "
                    + "(current status=" + request.status() + ")."
            );
        }
        return AccessResult.permitted();
    }

    public static List<CaseNoteDto> chronological(List<CaseNoteDto> notes) {
        return notes.stream()
            .sorted(Comparator.comparing(CaseNoteDto::createdAt, Comparator.nullsLast(String::compareTo))
                .thenComparing(note -> note.id() != null ? note.id() : 0L))
            .toList();
    }

    public static String author(CaseNoteDto note) {
        if (note.creatorName() != null && !note.creatorName().isBlank()) {
            return note.creatorName();
        }
        if (note.creatorUsername() != null && !note.creatorUsername().isBlank()) {
            return note.creatorUsername();
        }
        return "unknown";
    }

    public static String contentPreview(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String normalized = description.strip();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }

    public static String emptyTimelineMessage(CollaborationRequestStatusDto request) {
        if ("OPEN".equalsIgnoreCase(request.status())) {
            return "No notes available for this request yet. The request may not be approved yet.";
        }
        return "No notes available for this request yet.";
    }
}
