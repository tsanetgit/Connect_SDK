package com.tsanet.application.setup.step;

import static com.tsanet.application.setup.JdbcSetupSupport.insertIfNotExists;
import static com.tsanet.application.setup.JdbcSetupSupport.resetSequence;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ACME_COMPANY_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ADMIN_USER_EMAIL;
import static com.tsanet.application.setup.TestScenarioDataCatalog.ADMIN_USER_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.API_USER_EMAIL;
import static com.tsanet.application.setup.TestScenarioDataCatalog.API_USER_ID;
import static com.tsanet.application.setup.TestScenarioDataCatalog.BETA_COMPANY_ID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds the Connect API PostgreSQL database with the minimum company and user rows
 * required before integration-demo scenarios can call the API.
 *
 * <p>Writes go directly to the Connect DB (not via HTTP) so local dev can start from an
 * empty or partially seeded database. Every statement uses {@code INSERT ... SELECT ...
 * WHERE NOT EXISTS}, so this step is safe to run on every application startup.
 *
 * <p>Fixed numeric ids are intentional: Connect-1 issues JWTs whose {@code sub} claim must
 * match {@code users.id} in this database. The demo app authenticates as {@code api@appko.com}
 * (id 9587); keep those ids aligned with your identity provider when changing them.
 */
@Component
@Order(1)
@ConditionalOnProperty(prefix = "tsanet.demo.setup", name = "enabled", havingValue = "true")
public class CompanyAndUserSetupStep implements DatabaseSetupStep {
    @Override
    public String name() {
        return "companies-and-users";
    }

    @Override
    public void apply(JdbcTemplate jdbc) {
        ensureCompanies(jdbc);
        ensureUsers(jdbc);
        alignScenarioUserCompanies(jdbc);
        alignIdSequences(jdbc);
    }

    private void ensureCompanies(JdbcTemplate jdbc) {
        // Acme is the "home" company for demo API users (company_id = 1).
        // Slug 'acme' is the stable business key — we skip insert if id OR slug already exists
        // so re-runs never create a second Acme row when data was seeded manually.
        insertIfNotExists(
            jdbc,
            """
                INSERT INTO companies (
                  id, name, full_name, slug, status, membership_type, login_method,
                  community_key_codes, use_filter, use_group_login, show_in_public_web, allow_super_admins,
                  created_at, updated_at
                )
                SELECT ?, 'Acme Corp', 'Acme Corporation', 'acme', 'active', 'standard', 'password',
                  false, false, false, false, false, NOW(), NOW()
                WHERE NOT EXISTS (
                  SELECT 1 FROM companies WHERE id = ? OR slug = 'acme'
                )
                """,
            ACME_COMPANY_ID,
            ACME_COMPANY_ID
        );

        // Beta is the default partner/receiver company (id = 2) for future collaboration scenarios.
        // Same idempotent guard as Acme: match on fixed id or slug 'beta'.
        insertIfNotExists(
            jdbc,
            """
                INSERT INTO companies (
                  id, name, full_name, slug, status, membership_type, login_method,
                  community_key_codes, use_filter, use_group_login, show_in_public_web, allow_super_admins,
                  created_at, updated_at
                )
                SELECT ?, 'Beta Inc', 'Beta Incorporated', 'beta', 'active', 'standard', 'password',
                  false, false, false, false, false, NOW(), NOW()
                WHERE NOT EXISTS (
                  SELECT 1 FROM companies WHERE id = ? OR slug = 'beta'
                )
                """,
            BETA_COMPANY_ID,
            BETA_COMPANY_ID
        );
    }

    private void ensureUsers(JdbcTemplate jdbc) {
        // Admin user (id = 2) exists in Connect-1 as admin@tsanet.org (JWT sub = 2).
        // Initial insert uses Beta as company_id; alignScenarioUserCompanies() keeps this mapping
        // on every startup even if a previous seed attached the user to Acme.
        insertIfNotExists(
            jdbc,
            """
                INSERT INTO users (
                  id, company_id, email, username, first_name, last_name, status, type, role,
                  registration_type, failed_login_attempts, account_verified, pass_reset_flag,
                  requires_approval, force_update_profile, is_global_manager, created_at, updated_at
                )
                SELECT ?, ?, ?, ?, 'Admin', 'User', 'active', 'admin', 'api', 'system',
                  0, true, false, false, false, false, NOW(), NOW()
                WHERE NOT EXISTS (
                  SELECT 1 FROM users WHERE id = ? OR LOWER(email) = LOWER(?)
                )
                """,
            ADMIN_USER_ID,
            BETA_COMPANY_ID,
            ADMIN_USER_EMAIL,
            ADMIN_USER_EMAIL,
            ADMIN_USER_ID,
            ADMIN_USER_EMAIL
        );

        // API user (id = 9587) is the account configured in application.yml (api@appko.com).
        // Connect-1 must issue tokens with sub = 9587; this row must exist for login to succeed.
        insertIfNotExists(
            jdbc,
            """
                INSERT INTO users (
                  id, company_id, email, username, first_name, last_name, status, type, role,
                  registration_type, failed_login_attempts, account_verified, pass_reset_flag,
                  requires_approval, force_update_profile, is_global_manager, created_at, updated_at
                )
                SELECT ?, ?, ?, ?, 'API', 'Appko', 'active', 'admin', 'api', 'system',
                  0, true, false, false, false, false, NOW(), NOW()
                WHERE NOT EXISTS (
                  SELECT 1 FROM users WHERE id = ? OR LOWER(email) = LOWER(?)
                )
                """,
            API_USER_ID,
            ACME_COMPANY_ID,
            API_USER_EMAIL,
            API_USER_EMAIL,
            API_USER_ID,
            API_USER_EMAIL
        );
    }

    private void alignScenarioUserCompanies(JdbcTemplate jdbc) {
        // Login is proxied to Connect-1; only emails registered there can authenticate.
        // We map known Connect-1 identities to demo companies in Connect DB:
        //   api@appko.com   -> Acme (sub 9587)
        //   admin@tsanet.org -> Beta (sub 2)
        jdbc.update(
            """
                UPDATE users SET company_id = ?, updated_at = NOW()
                WHERE LOWER(email) = LOWER(?)
                """,
            ACME_COMPANY_ID,
            API_USER_EMAIL
        );
        jdbc.update(
            """
                UPDATE users SET company_id = ?, updated_at = NOW()
                WHERE LOWER(email) = LOWER(?)
                """,
            BETA_COMPANY_ID,
            ADMIN_USER_EMAIL
        );
    }

    private void alignIdSequences(JdbcTemplate jdbc) {
        // After inserting explicit ids, bump PostgreSQL sequences so the next auto-generated
        // id does not collide with our seeded rows (e.g. a new company getting id 1 again).
        resetSequence(jdbc, "companies_id_seq", "companies");
        resetSequence(jdbc, "users_id_seq", "users");
    }
}
