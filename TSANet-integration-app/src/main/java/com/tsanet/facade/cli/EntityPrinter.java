package com.tsanet.facade.cli;

import static com.tsanet.facade.cli.TerminalColors.BLUE;
import static com.tsanet.facade.cli.TerminalColors.GREEN;
import static com.tsanet.facade.cli.TerminalColors.RED;
import static com.tsanet.facade.cli.TerminalColors.RESET;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.PartnerSelectionDto;
import com.tsanet.api.connectapi.dto.UserContextDto;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionDto;
import com.tsanet.api.connectapi.dto.WebhookDeliveryDto;
import com.tsanet.api.connectapi.dto.WebhookDeliveryPageDto;
import com.tsanet.api.connectapi.dto.WebhookInboundEventDto;
import java.util.List;

public final class EntityPrinter {
    private EntityPrinter() {
    }

    public static void printNotes(CliRunContext context, String title, List<CaseNoteDto> notes) {
        if (notes.isEmpty()) {
            println(context, BLUE, "No notes.");
            return;
        }
        println(context, GREEN, title + " (" + notes.size() + "):");
        for (CaseNoteDto note : CollaborationRequestNotesTimeline.chronological(notes)) {
            System.out.printf(
                " - id=%s caseToken=%s token=%s status=%s priority=%s summary=%s creator=%s%n",
                note.id(),
                note.caseToken(),
                note.token(),
                note.status(),
                note.priority(),
                note.summary(),
                CollaborationRequestNotesTimeline.author(note)
            );
        }
    }

    public static void printNotesTimeline(
        CliRunContext context,
        CollaborationRequestStatusDto request,
        List<CaseNoteDto> notes
    ) {
        println(
            context,
            GREEN,
            "Notes timeline for request id=" + request.id()
                + " token=" + request.token()
                + " status=" + request.status()
        );
        if (notes.isEmpty()) {
            println(context, BLUE, CollaborationRequestNotesTimeline.emptyTimelineMessage(request));
            return;
        }
        int index = 1;
        for (CaseNoteDto note : notes) {
            System.out.printf(
                " %d. [%s] %s | %s%n",
                index++,
                note.createdAt() != null ? note.createdAt() : "unknown",
                CollaborationRequestNotesTimeline.author(note),
                note.summary() != null ? note.summary() : ""
            );
            String preview = CollaborationRequestNotesTimeline.contentPreview(note.description());
            if (!preview.isEmpty()) {
                System.out.printf("    %s%n", preview);
            }
        }
    }

    public static void printResponses(CliRunContext context, String title, List<CaseResponseDto> responses) {
        if (responses.isEmpty()) {
            println(context, BLUE, "No responses.");
            return;
        }
        println(context, GREEN, title + " (" + responses.size() + "):");
        for (CaseResponseDto response : responses) {
            System.out.printf(
                " - id=%s caseToken=%s type=%s engineer=%s nextSteps=%s%n",
                response.id(),
                response.caseToken(),
                response.type(),
                response.engineerName(),
                response.nextSteps()
            );
        }
    }

    public static void printUserContext(CliRunContext context, String title, UserContextDto user) {
        println(context, GREEN, title + ":");
        System.out.printf(
            " - companyId=%s companyName=%s userId=%s username=%s email=%s name=%s %s%n",
            user.companyId(),
            user.companyName(),
            user.userId(),
            user.username(),
            user.email(),
            user.firstName(),
            user.lastName()
        );
    }

    public static void printWebhooks(CliRunContext context, String title, List<WebhookSubscriptionDto> subscriptions) {
        if (subscriptions.isEmpty()) {
            println(context, BLUE, "No webhook subscriptions.");
            return;
        }
        println(context, GREEN, title + " (" + subscriptions.size() + "):");
        for (WebhookSubscriptionDto subscription : subscriptions) {
            System.out.printf(
                " - id=%s active=%s events=%s url=%s%n",
                subscription.id(),
                subscription.active(),
                subscription.eventTypes(),
                subscription.callbackUrl()
            );
        }
    }

    public static void printWebhookDeliveries(CliRunContext context, String title, WebhookDeliveryPageDto page) {
        if (page.content().isEmpty()) {
            println(context, BLUE, "No webhook deliveries.");
            return;
        }
        println(
            context,
            GREEN,
            title + " (page " + page.number() + ", " + page.content().size() + " of " + page.totalElements() + "):"
        );
        for (WebhookDeliveryDto delivery : page.content()) {
            System.out.printf(
                " - id=%s event=%s success=%s httpStatus=%s attempt=%s createdAt=%s%n",
                delivery.id(),
                delivery.eventType(),
                delivery.success(),
                delivery.httpStatus(),
                delivery.attemptNumber(),
                delivery.createdAt()
            );
        }
    }

    public static void printWebhookEvents(CliRunContext context, String title, List<WebhookInboundEventDto> events) {
        if (events.isEmpty()) {
            println(context, BLUE, "No inbound webhook events.");
            return;
        }
        println(context, GREEN, title + " (" + events.size() + "):");
        for (WebhookInboundEventDto event : events) {
            System.out.printf(
                " - id=%s event=%s requestToken=%s signatureValid=%s cacheSynced=%s receivedAt=%s message=%s%n",
                event.id(),
                event.eventType(),
                event.requestToken(),
                event.signatureValid(),
                event.cacheSynced(),
                event.receivedAt(),
                event.syncMessage()
            );
        }
    }

    public static void printPartners(CliRunContext context, String title, List<PartnerSelectionDto> partners) {
        printPartnersNumbered(context, title, partners, false);
    }

    public static void printPartnersNumbered(CliRunContext context, String title, List<PartnerSelectionDto> partners) {
        printPartnersNumbered(context, title, partners, true);
    }

    private static void printPartnersNumbered(
        CliRunContext context,
        String title,
        List<PartnerSelectionDto> partners,
        boolean numbered
    ) {
        if (partners.isEmpty()) {
            println(context, BLUE, "No partners.");
            return;
        }
        println(context, GREEN, title + " (" + partners.size() + "):");
        for (int i = 0; i < partners.size(); i++) {
            PartnerSelectionDto partner = partners.get(i);
            String prefix = numbered ? (i + 1) + ". " : " - ";
            System.out.printf(
                "%ssearch=%s label=%s company=%s department=%s companyId=%s departmentId=%s documentId=%s%n",
                prefix,
                partner.searchTerm(),
                partner.label(),
                partner.companyName(),
                partner.departmentName(),
                partner.companyId(),
                partner.departmentId(),
                partner.documentId()
            );
        }
    }

    public static String info(CliRunContext context, String message) {
        if (context.isPlainOutput()) {
            return message;
        }
        return BLUE + message + RESET;
    }

    public static String error(CliRunContext context, String message) {
        if (context.isPlainOutput()) {
            return message;
        }
        return RED + message + RESET;
    }

    private static void println(CliRunContext context, String color, String message) {
        if (context.isPlainOutput()) {
            System.out.println(message);
            return;
        }
        System.out.println(color + message + RESET);
    }
}
