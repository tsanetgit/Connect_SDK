package com.tsanet.application.setup.step;

import static com.tsanet.application.setup.JdbcSetupSupport.insertIfNotExists;
import static com.tsanet.application.setup.JdbcSetupSupport.resetSequence;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_COMPANY_COMMUNITY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_COMPANY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_CONTACT_DOCUMENT_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_DOCUMENT_VERSION_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_COMPANY_COMMUNITY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_COMPANY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_CONTACT_DOCUMENT_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_DOCUMENT_VERSION_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.DEMO_COMMUNITY_ID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds community partnership and contact forms required for connect-library
 * {@code collaborationRequests().createRequest()} — not collaboration requests themselves.
 */
@Component
@Order(2)
@ConditionalOnProperty(prefix = "tsanet.demo.setup", name = "enabled", havingValue = "true")
public class PartnershipAndContactFormsSetupStep implements DatabaseSetupStep {
    @Override
    public String name() {
        return "partnerships-and-contact-forms";
    }

    @Override
    public void apply(JdbcTemplate jdbc) {
        ensureDocumentRequirements(jdbc);
        ensureContactDocumentsAndVersions(jdbc);
        ensureCommunity(jdbc);
        ensureCompanyCommunities(jdbc);
        ensureVersionRequirements(jdbc);
        alignIdSequences(jdbc);
    }

    private void ensureDocumentRequirements(JdbcTemplate jdbc) {
        insertRequirement(jdbc, 1L, ACME_COMPANY_ID, "email", "Customer email");
        insertRequirement(jdbc, 2L, ACME_COMPANY_ID, "text", "Customer name");
        insertRequirement(jdbc, 3L, ACME_COMPANY_ID, "text", "Customer company");
        insertRequirement(jdbc, 4L, BETA_COMPANY_ID, "email", "Customer email");
        insertRequirement(jdbc, 5L, BETA_COMPANY_ID, "text", "Customer name");
        insertRequirement(jdbc, 6L, BETA_COMPANY_ID, "text", "Customer company");
    }

    private void insertRequirement(JdbcTemplate jdbc, long id, long companyId, String type, String label) {
        insertIfNotExists(
            jdbc,
            """
                INSERT INTO contact_document_requirements (
                  id, company_id, label, status, scope, type, document_type, "class",
                  required, customer_case_number_field, customer_company_field, product_field,
                  display_order, global, section, created_at, updated_at
                )
                SELECT ?, ?, ?, 'active', 'system', ?, 'default', ?, true, false, false, false, 0, true,
                  'common_customer_section', NOW(), NOW()
                WHERE NOT EXISTS (
                  SELECT 1 FROM contact_document_requirements WHERE id = ? OR (company_id = ? AND label = ?)
                )
                """,
            id,
            companyId,
            label,
            type,
            "Class for " + label,
            id,
            companyId,
            label
        );
    }

    private void ensureContactDocumentsAndVersions(JdbcTemplate jdbc) {
        insertIfNotExists(
            jdbc,
            """
                INSERT INTO contact_documents (
                  id, company_id, name, status, is_parent, is_default, notify_admin, notify_on_change,
                  created_at, updated_at
                )
                SELECT ?, ?, 'Default contact form', 'active', false, true, false, false, NOW(), NOW()
                WHERE NOT EXISTS (
                  SELECT 1 FROM contact_documents
                  WHERE id = ? OR (company_id = ? AND name = 'Default contact form')
                )
                """,
            ACME_CONTACT_DOCUMENT_ID,
            ACME_COMPANY_ID,
            ACME_CONTACT_DOCUMENT_ID,
            ACME_COMPANY_ID
        );

        insertIfNotExists(
            jdbc,
            """
                INSERT INTO contact_documents (
                  id, company_id, name, status, is_parent, is_default, notify_admin, notify_on_change,
                  created_at, updated_at
                )
                SELECT ?, ?, 'Beta contact form', 'active', false, true, false, false, NOW(), NOW()
                WHERE NOT EXISTS (
                  SELECT 1 FROM contact_documents
                  WHERE id = ? OR (company_id = ? AND name = 'Beta contact form')
                )
                """,
            BETA_CONTACT_DOCUMENT_ID,
            BETA_COMPANY_ID,
            BETA_CONTACT_DOCUMENT_ID,
            BETA_COMPANY_ID
        );

        insertIfNotExists(
            jdbc,
            """
                INSERT INTO contact_document_versions (
                  id, document_id, title, status, document_type, escalation_instructions, escalation_alias,
                  is_twentyfour_hour, created_at, updated_at
                )
                SELECT ?, ?, 'Acme active form', 'approved', 'default', 'Escalation instructions', 'escalation',
                  false, NOW(), NOW()
                WHERE NOT EXISTS (SELECT 1 FROM contact_document_versions WHERE id = ?)
                """,
            ACME_DOCUMENT_VERSION_ID,
            ACME_CONTACT_DOCUMENT_ID,
            ACME_DOCUMENT_VERSION_ID
        );

        insertIfNotExists(
            jdbc,
            """
                INSERT INTO contact_document_versions (
                  id, document_id, title, status, document_type, escalation_instructions, escalation_alias,
                  is_twentyfour_hour, created_at, updated_at
                )
                SELECT ?, ?, 'Beta active form', 'approved', 'default', 'Escalation instructions', 'escalation',
                  false, NOW(), NOW()
                WHERE NOT EXISTS (SELECT 1 FROM contact_document_versions WHERE id = ?)
                """,
            BETA_DOCUMENT_VERSION_ID,
            BETA_CONTACT_DOCUMENT_ID,
            BETA_DOCUMENT_VERSION_ID
        );
    }

    private void ensureCommunity(JdbcTemplate jdbc) {
        insertIfNotExists(
            jdbc,
            """
                INSERT INTO communities (
                  id, sponsor_company_id, partner_group_id, inherit_community_id, name, description,
                  short_description, type, terms, status, default_relationship, custom_sla,
                  p1_message, p1_response_time, p2_message, p2_response_time, p3_message, p3_response_time,
                  addendum, addendum_name, msteams_integration, msteams_manager_notified,
                  created_at, updated_at
                )
                SELECT ?, ?, 1, 1, 'Acme Corp''s Community', 'Integration demo community',
                  'Integration demo community', 'solution_support', 'Demo terms', 'active', 'many_to_many', true,
                  'P1 message', 1, 'P2 message', 2, 'P3 message', 3,
                  'Demo addendum', 'Demo addendum', false, false, NOW(), NOW()
                WHERE NOT EXISTS (SELECT 1 FROM communities WHERE id = ? OR name = 'Acme Corp''s Community')
                """,
            DEMO_COMMUNITY_ID,
            ACME_COMPANY_ID,
            DEMO_COMMUNITY_ID
        );
    }

    private void ensureCompanyCommunities(JdbcTemplate jdbc) {
        insertIfNotExists(
            jdbc,
            """
                INSERT INTO company_communities (
                  id, company_id, community_id, is_sponsor, user_access_count, user_access_flag,
                  member_relationship, is_active, created_at, updated_at
                )
                SELECT ?, ?, ?, true, 10, 'all', 'many_to_many', true, NOW(), NOW()
                WHERE NOT EXISTS (SELECT 1 FROM company_communities WHERE id = ?)
                """,
            ACME_COMPANY_COMMUNITY_ID,
            ACME_COMPANY_ID,
            DEMO_COMMUNITY_ID,
            ACME_COMPANY_COMMUNITY_ID
        );

        insertIfNotExists(
            jdbc,
            """
                INSERT INTO company_communities (
                  id, company_id, community_id, document_id, is_sponsor, user_access_count, user_access_flag,
                  member_relationship, is_active, created_at, updated_at
                )
                SELECT ?, ?, ?, ?, false, 10, 'all', 'many_to_many', true, NOW(), NOW()
                WHERE NOT EXISTS (SELECT 1 FROM company_communities WHERE id = ?)
                """,
            BETA_COMPANY_COMMUNITY_ID,
            BETA_COMPANY_ID,
            DEMO_COMMUNITY_ID,
            BETA_CONTACT_DOCUMENT_ID,
            BETA_COMPANY_COMMUNITY_ID
        );
    }

    private void ensureVersionRequirements(JdbcTemplate jdbc) {
        linkVersionRequirement(jdbc, 1L, ACME_CONTACT_DOCUMENT_ID, ACME_DOCUMENT_VERSION_ID, 1L);
        linkVersionRequirement(jdbc, 2L, ACME_CONTACT_DOCUMENT_ID, ACME_DOCUMENT_VERSION_ID, 2L);
        linkVersionRequirement(jdbc, 3L, ACME_CONTACT_DOCUMENT_ID, ACME_DOCUMENT_VERSION_ID, 3L);
        linkVersionRequirement(jdbc, 4L, BETA_CONTACT_DOCUMENT_ID, BETA_DOCUMENT_VERSION_ID, 4L);
        linkVersionRequirement(jdbc, 5L, BETA_CONTACT_DOCUMENT_ID, BETA_DOCUMENT_VERSION_ID, 5L);
        linkVersionRequirement(jdbc, 6L, BETA_CONTACT_DOCUMENT_ID, BETA_DOCUMENT_VERSION_ID, 6L);
    }

    private void linkVersionRequirement(
        JdbcTemplate jdbc, long id, long documentId, long versionId, long requirementId
    ) {
        insertIfNotExists(
            jdbc,
            """
                INSERT INTO contact_document_version_requirements (
                  id, document_id, version_id, requirement_id, required, customer_case_number_field,
                  display_order, section, created_at, updated_at
                )
                SELECT ?, ?, ?, ?, true, false, 0, 'common_customer_section', NOW(), NOW()
                WHERE NOT EXISTS (SELECT 1 FROM contact_document_version_requirements WHERE id = ?)
                """,
            id,
            documentId,
            versionId,
            requirementId,
            id
        );
    }

    private void alignIdSequences(JdbcTemplate jdbc) {
        resetSequence(jdbc, "contact_documents_id_seq", "contact_documents");
        resetSequence(jdbc, "contact_document_versions_id_seq", "contact_document_versions");
        resetSequence(jdbc, "contact_document_requirements_id_seq", "contact_document_requirements");
        resetSequence(
            jdbc, "contact_document_version_requirements_id_seq", "contact_document_version_requirements");
        resetSequence(jdbc, "communities_id_seq", "communities");
        resetSequence(jdbc, "company_communities_id_seq", "company_communities");
    }
}
