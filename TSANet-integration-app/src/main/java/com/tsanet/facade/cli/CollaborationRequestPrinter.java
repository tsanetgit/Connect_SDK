package com.tsanet.facade.cli;

import static com.tsanet.facade.cli.TerminalColors.BLUE;
import static com.tsanet.facade.cli.TerminalColors.GREEN;
import static com.tsanet.facade.cli.TerminalColors.RESET;

import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;

public final class CollaborationRequestPrinter {
    private CollaborationRequestPrinter() {
    }

    public static void printList(CliRunContext cliRunContext, String title, List<CollaborationRequestStatusDto> requests) {
        if (requests.isEmpty()) {
            println(cliRunContext, BLUE, "No collaboration requests.");
            return;
        }

        println(cliRunContext, GREEN, title + " (" + requests.size() + "):");
        for (CollaborationRequestStatusDto request : requests) {
            System.out.printf(
                " - id=%s status=%s token=%s submitCompanyId=%s receiveCompanyId=%s from=%s to=%s summary=%s%n",
                request.id(),
                request.status(),
                request.token(),
                request.submitCompanyId(),
                request.receiveCompanyId(),
                request.submitCompanyName(),
                request.receiveCompanyName(),
                request.summary()
            );
        }
    }

    public static void printApprovalResult(
        CliRunContext cliRunContext,
        CollaborationRequestStatusDto request,
        List<CaseResponseDto> responses
    ) {
        printList(cliRunContext, "Approved collaboration request", List.of(request));
        List<CaseResponseDto> approvalResponses = responses.stream()
            .filter(response -> CollaborationRequestApproval.RESPONSE_TYPE_APPROVAL.equals(response.type()))
            .toList();
        EntityPrinter.printResponses(cliRunContext, "Approval response details", approvalResponses);
    }

    public static void printCloseResult(CliRunContext cliRunContext, CollaborationRequestStatusDto request) {
        printList(cliRunContext, "Closed collaboration request", List.of(request));
    }

    public static void printRejectionResult(
        CliRunContext cliRunContext,
        CollaborationRequestStatusDto request,
        List<CaseResponseDto> responses,
        String reason
    ) {
        printList(cliRunContext, "Rejected collaboration request", List.of(request));
        System.out.println(EntityPrinter.info(cliRunContext, "Rejection reason: " + reason));
        List<CaseResponseDto> rejectionResponses = responses.stream()
            .filter(response -> CollaborationRequestRejection.RESPONSE_TYPE_REJECTION.equals(response.type()))
            .toList();
        EntityPrinter.printResponses(cliRunContext, "Rejection response details", rejectionResponses);
    }

    public static void printInformationRequestResult(
        CliRunContext cliRunContext,
        CollaborationRequestStatusDto request,
        List<CaseResponseDto> responses,
        String requestedInformation
    ) {
        printList(cliRunContext, "Information requested on collaboration request", List.of(request));
        System.out.println(EntityPrinter.info(cliRunContext, "Requested information: " + requestedInformation));
        List<CaseResponseDto> informationRequests = responses.stream()
            .filter(response -> CollaborationRequestInformationRequest.RESPONSE_TYPE_INFORMATION_REQUEST
                .equals(response.type()))
            .toList();
        EntityPrinter.printResponses(cliRunContext, "Information request response details", informationRequests);
    }

    public static void printInformationResponseResult(
        CliRunContext cliRunContext,
        CollaborationRequestStatusDto request,
        List<CaseResponseDto> responses,
        String responseText
    ) {
        printList(cliRunContext, "Information response submitted for collaboration request", List.of(request));
        System.out.println(EntityPrinter.info(cliRunContext, "Information response: " + responseText));
        List<CaseResponseDto> informationResponses = responses.stream()
            .filter(response -> CollaborationRequestInformationResponse.RESPONSE_TYPE_INFORMATION_RESPONSE
                .equals(response.type()))
            .toList();
        EntityPrinter.printResponses(cliRunContext, "Information response details", informationResponses);
    }

    private static void println(CliRunContext cliRunContext, String color, String message) {
        if (cliRunContext.isPlainOutput()) {
            System.out.println(message);
            return;
        }
        System.out.println(color + message + RESET);
    }
}
