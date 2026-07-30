package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.tsanet.api.connectapi.dto.FormFieldDto;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

final class FormFieldInputResolver {
    private FormFieldInputResolver() {
    }

    static Map<Long, String> resolve(
        CollaborationRequestFormTemplateDto template,
        String[] args,
        Scanner scanner,
        CliRunContext cliRunContext
    ) {
        Map<Long, String> values = new LinkedHashMap<>(CliArgs.customFields(args));
        if (template.fields() == null) {
            return values;
        }
        for (FormFieldDto field : template.fields()) {
            if (field.fieldId() == null) {
                continue;
            }
            if (values.containsKey(field.fieldId())) {
                continue;
            }
            if (field.value() != null && !field.value().isBlank()) {
                values.put(field.fieldId(), field.value());
                continue;
            }
            if (!field.required()) {
                continue;
            }
            if (scanner == null) {
                throw new IllegalArgumentException(
                    "Required custom field '" + label(field) + "' is missing; provide --field " + field.fieldId() + "=VALUE"
                );
            }
            System.out.print("Value for required field '" + label(field) + "' (fieldId=" + field.fieldId() + "): ");
            String entered = scanner.nextLine();
            if (entered == null || entered.isBlank()) {
                throw new IllegalArgumentException("Required custom field '" + label(field) + "' must not be empty.");
            }
            values.put(field.fieldId(), entered.strip());
            System.out.println(EntityPrinter.info(cliRunContext, "Captured custom field " + field.fieldId()));
        }
        return values;
    }

    private static String label(FormFieldDto field) {
        if (field.label() != null && !field.label().isBlank()) {
            return field.label();
        }
        return "fieldId=" + field.fieldId();
    }
}
