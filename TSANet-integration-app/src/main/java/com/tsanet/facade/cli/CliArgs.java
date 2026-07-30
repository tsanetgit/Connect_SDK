package com.tsanet.facade.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class CliArgs {
    private CliArgs() {
    }

    public static Optional<Long> companyId(String[] args) {
        return valueAfter(args, "--company-id").map(Long::parseLong);
    }

    public static Optional<Long> departmentId(String[] args) {
        return valueAfter(args, "--department-id").map(Long::parseLong);
    }

    public static Optional<Long> documentId(String[] args) {
        return valueAfter(args, "--document-id").map(Long::parseLong);
    }

    public static java.util.Map<Long, String> customFields(String[] args) {
        java.util.Map<Long, String> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if ("--field".equals(args[i])) {
                parseFieldValue(args[i + 1]).ifPresent(entry -> values.put(entry.getKey(), entry.getValue()));
            }
        }
        return values;
    }

    private static java.util.Optional<java.util.Map.Entry<Long, String>> parseFieldValue(String raw) {
        int separator = raw.indexOf('=');
        if (separator <= 0 || separator == raw.length() - 1) {
            throw new IllegalArgumentException("Custom field must use fieldId=value format: " + raw);
        }
        return java.util.Optional.of(java.util.Map.entry(
            Long.parseLong(raw.substring(0, separator)),
            raw.substring(separator + 1)
        ));
    }

    public static Optional<String> token(String[] args) {
        return valueAfter(args, "--token");
    }

    public static Optional<String> search(String[] args) {
        return valueAfter(args, "--search");
    }

    public static Optional<Integer> partnerIndex(String[] args) {
        return valueAfter(args, "--partner-index").map(Integer::parseInt);
    }

    public static Optional<Integer> searchLimit(String[] args) {
        return valueAfter(args, "--limit").map(Integer::parseInt);
    }

    public static Optional<String> caseNumber(String[] args) {
        return valueAfter(args, "--case-number");
    }

    public static Optional<String> summary(String[] args) {
        return valueAfter(args, "--summary");
    }

    public static Optional<String> description(String[] args) {
        return valueAfter(args, "--description");
    }

    public static List<String> files(String[] args) {
        List<String> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--file".equals(args[i]) && i + 1 < args.length) {
                files.add(args[i + 1]);
            }
        }
        return files;
    }

    public static Optional<String> configFile(String[] args) {
        return valueAfter(args, "--config-file");
    }

    public static Optional<String> httpsDomain(String[] args) {
        return valueAfter(args, "--https-domain");
    }

    public static Optional<String> httpsPassword(String[] args) {
        return valueAfter(args, "--https-password");
    }

    public static Optional<String> httpsExpiration(String[] args) {
        return valueAfter(args, "--https-expiration");
    }

    public static Optional<String> httpsPath(String[] args) {
        return valueAfter(args, "--https-path");
    }

    public static Optional<Integer> httpsPort(String[] args) {
        return valueAfter(args, "--https-port").map(Integer::parseInt);
    }

    public static Optional<String> text(String[] args) {
        return valueAfter(args, "--text");
    }

    public static Optional<String> priority(String[] args) {
        return valueAfter(args, "--priority");
    }

    public static Optional<Long> requestId(String[] args) {
        Optional<String> id = valueAfter(args, "--id");
        if (id.isEmpty()) {
            id = valueAfter(args, "--request-id");
        }
        return id.map(Long::parseLong);
    }

    public static Optional<String> engineerName(String[] args) {
        return valueAfter(args, "--engineer-name");
    }

    public static Optional<String> engineerEmail(String[] args) {
        return valueAfter(args, "--engineer-email");
    }

    public static Optional<String> engineerPhone(String[] args) {
        return valueAfter(args, "--engineer-phone");
    }

    public static Optional<String> nextSteps(String[] args) {
        return valueAfter(args, "--next-steps");
    }

    public static Optional<String> reason(String[] args) {
        return valueAfter(args, "--reason");
    }

    public static Optional<String> requestedInformation(String[] args) {
        return valueAfter(args, "--requested-information");
    }

    public static Optional<String> callbackUrl(String[] args) {
        return valueAfter(args, "--callback-url");
    }

    public static Optional<Long> webhookId(String[] args) {
        return valueAfter(args, "--id").map(Long::parseLong);
    }

    public static Optional<Integer> page(String[] args) {
        return valueAfter(args, "--page").map(Integer::parseInt);
    }

    public static Optional<Integer> size(String[] args) {
        return valueAfter(args, "--size").map(Integer::parseInt);
    }

    public static Optional<List<String>> eventTypes(String[] args) {
        return valueAfter(args, "--events").map(CliArgs::parseEventTypes);
    }

    private static List<String> parseEventTypes(String raw) {
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toList();
    }

    public static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> valueAfter(String[] args, String flag) {
        for (int i = 0; i < args.length; i++) {
            if (flag.equals(args[i]) && i + 1 < args.length) {
                return Optional.of(args[i + 1]);
            }
        }
        return Optional.empty();
    }
}
