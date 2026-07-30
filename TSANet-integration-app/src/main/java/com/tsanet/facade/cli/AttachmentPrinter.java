package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.AttachmentForwardResultDto;
import com.tsanet.api.connectapi.dto.CompanyAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.NormalizedHttpsAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentForwardResultDto;
import java.util.List;

public final class AttachmentPrinter {
    private AttachmentPrinter() {
    }

    public static void printForwardResults(
        CliRunContext cliRunContext,
        String title,
        List<AttachmentForwardResultDto> results
    ) {
        if (results.isEmpty()) {
            System.out.println(EntityPrinter.error(cliRunContext, "No attachment forward results."));
            return;
        }
        System.out.println(EntityPrinter.info(cliRunContext, title + " (" + results.size() + "):"));
        for (AttachmentForwardResultDto result : results) {
            System.out.printf(
                " - file=%s submitterStatus=%s submitterMessage=%s receiverStatus=%s receiverMessage=%s completeSuccess=%s partialSuccess=%s%n",
                result.fileName(),
                result.submitterStatus(),
                result.submitterMessage(),
                result.receiverStatus(),
                result.receiverMessage(),
                result.completeSuccess(),
                result.partialSuccess()
            );
        }
    }

    public static void printStoredForwardResults(
        CliRunContext cliRunContext,
        String title,
        List<StoredAttachmentForwardResultDto> results
    ) {
        if (results.isEmpty()) {
            System.out.println(EntityPrinter.info(cliRunContext, "No attachments recorded for this request."));
            return;
        }
        System.out.println(EntityPrinter.info(cliRunContext, title + " (" + results.size() + "):"));
        for (StoredAttachmentForwardResultDto result : results) {
            System.out.printf(
                " - caseToken=%s description=%s file=%s submitterStatus=%s submitterMessage=%s receiverStatus=%s receiverMessage=%s completeSuccess=%s partialSuccess=%s%n",
                result.caseToken(),
                result.description(),
                result.fileName(),
                result.submitterStatus(),
                result.submitterMessage(),
                result.receiverStatus(),
                result.receiverMessage(),
                result.completeSuccess(),
                result.partialSuccess()
            );
        }
    }

    public static void printAttachmentConfig(CliRunContext cliRunContext, String title, AttachmentConfigDto config) {
        System.out.println(EntityPrinter.info(cliRunContext, title + ":"));
        printCompanyConfig("submitter", config.submitter());
        printCompanyConfig("receiver", config.receiver());
    }

    public static void printHttpsConfig(
        CliRunContext cliRunContext,
        String title,
        NormalizedHttpsAttachmentConfigDto config
    ) {
        System.out.println(EntityPrinter.info(cliRunContext, title + ":"));
        System.out.printf(
            " domain=%s httpsPath=%s httpsPort=%s expiration=%s password=%s%n",
            config.domain(),
            config.httpsPath(),
            config.httpsPort(),
            config.expiration(),
            maskSecret(config.password())
        );
    }

    private static void printCompanyConfig(String role, CompanyAttachmentConfigDto company) {
        if (company == null) {
            System.out.printf(" %s: unavailable%n", role);
            return;
        }
        System.out.printf(
            " %s: companyId=%s parameters=%s%n",
            role,
            company.companyId(),
            company.parameters() != null ? company.parameters() : "{}"
        );
    }

    private static String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= 2) {
            return "**";
        }
        return value.charAt(0) + "***" + value.charAt(value.length() - 1);
    }
}
