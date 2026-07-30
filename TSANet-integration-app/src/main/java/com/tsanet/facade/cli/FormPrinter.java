package com.tsanet.facade.cli;

import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.tsanet.api.connectapi.dto.FormFieldDto;
import java.util.List;

public final class FormPrinter {
    private FormPrinter() {
    }

    public static void printTemplate(CliRunContext cliRunContext, String title, CollaborationRequestFormTemplateDto template) {
        System.out.println(EntityPrinter.info(cliRunContext, title));
        System.out.printf(
            " documentId=%s receiverCompanyId=%s departmentId=%s fieldCount=%s%n",
            template.documentId(),
            template.receiverCompanyId(),
            template.departmentId(),
            template.customFieldCount()
        );
        printFields(cliRunContext, template.fields());
    }

    public static void printStoredForm(
        CliRunContext cliRunContext,
        String title,
        long receiverCompanyId,
        long documentId,
        Long departmentId,
        List<FormFieldDto> fields
    ) {
        System.out.println(EntityPrinter.info(cliRunContext, title));
        System.out.printf(
            " receiverCompanyId=%s documentId=%s departmentId=%s fieldCount=%s%n",
            receiverCompanyId,
            documentId,
            departmentId,
            fields != null ? fields.size() : 0
        );
        printFields(cliRunContext, fields);
    }

    private static void printFields(CliRunContext cliRunContext, List<FormFieldDto> fields) {
        if (fields == null || fields.isEmpty()) {
            System.out.println(EntityPrinter.info(cliRunContext, "No custom fields."));
            return;
        }
        System.out.println(EntityPrinter.info(cliRunContext, "Custom fields:"));
        int index = 1;
        for (FormFieldDto field : fields) {
            System.out.printf(
                " %d. fieldId=%s section=%s type=%s required=%s label=%s value=%s%n",
                index++,
                field.fieldId(),
                field.section(),
                field.type(),
                field.required(),
                field.label(),
                field.value() != null ? field.value() : ""
            );
            if (field.options() != null && !field.options().isBlank()) {
                System.out.printf("    options=%s%n", field.options());
            }
            if (field.validationRules() != null && !field.validationRules().isBlank()) {
                System.out.printf("    validationRules=%s%n", field.validationRules());
            }
        }
    }
}
