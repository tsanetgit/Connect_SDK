package com.tsanet.api.storage;

import org.springframework.jdbc.core.JdbcTemplate;

public final class DatabaseInitializer {
    private static final String COLLABORATION_REQUEST_TABLE = """
        CREATE TABLE IF NOT EXISTS collaboration_request (
            id INTEGER PRIMARY KEY,
            status TEXT,
            summary TEXT,
            submit_company_name TEXT,
            submit_company_id INTEGER,
            receive_company_name TEXT,
            receive_company_id INTEGER,
            token TEXT NOT NULL UNIQUE,
            created_at TEXT,
            updated_at TEXT,
            fetched_at TEXT NOT NULL
        )
        """;

    private static final String CASE_NOTE_TABLE = """
        CREATE TABLE IF NOT EXISTS case_note (
            id INTEGER PRIMARY KEY,
            case_id INTEGER,
            case_token TEXT NOT NULL,
            company_name TEXT,
            creator_username TEXT,
            creator_email TEXT,
            creator_name TEXT,
            summary TEXT,
            description TEXT,
            priority TEXT,
            status TEXT,
            token TEXT NOT NULL UNIQUE,
            created_at TEXT,
            updated_at TEXT,
            fetched_at TEXT NOT NULL
        )
        """;

    private static final String CASE_RESPONSE_TABLE = """
        CREATE TABLE IF NOT EXISTS case_response (
            id INTEGER NOT NULL,
            case_token TEXT NOT NULL,
            type TEXT,
            case_number TEXT,
            engineer_name TEXT,
            engineer_phone TEXT,
            engineer_email TEXT,
            next_steps TEXT,
            created_at TEXT,
            fetched_at TEXT NOT NULL,
            PRIMARY KEY (id, case_token)
        )
        """;

    private static final String USER_CONTEXT_TABLE = """
        CREATE TABLE IF NOT EXISTS user_context (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            company_id INTEGER,
            company_name TEXT,
            user_id INTEGER,
            username TEXT,
            email TEXT,
            first_name TEXT,
            last_name TEXT,
            fetched_at TEXT NOT NULL
        )
        """;

    private static final String WEBHOOK_SUBSCRIPTION_TABLE = """
        CREATE TABLE IF NOT EXISTS webhook_subscription (
            id INTEGER PRIMARY KEY,
            callback_url TEXT,
            event_types TEXT,
            active INTEGER,
            secret TEXT,
            created_at TEXT,
            updated_at TEXT,
            fetched_at TEXT NOT NULL
        )
        """;

    private static final String WEBHOOK_INBOUND_EVENT_TABLE = """
        CREATE TABLE IF NOT EXISTS webhook_inbound_event (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            subscription_id INTEGER,
            event_type TEXT NOT NULL,
            request_token TEXT NOT NULL,
            note_token TEXT,
            event_timestamp TEXT,
            received_at TEXT NOT NULL,
            signature_valid INTEGER NOT NULL,
            cache_synced INTEGER NOT NULL,
            sync_message TEXT,
            raw_payload TEXT NOT NULL
        )
        """;

    private static final String PARTNER_SELECTION_TABLE = """
        CREATE TABLE IF NOT EXISTS partner_selection (
            row_id INTEGER PRIMARY KEY AUTOINCREMENT,
            search_term TEXT NOT NULL,
            label TEXT,
            company_name TEXT,
            department_name TEXT,
            company_id INTEGER,
            department_id INTEGER,
            document_id INTEGER,
            fetched_at TEXT NOT NULL
        )
        """;

    private static final String COLLABORATION_REQUEST_FORM_TABLE = """
        CREATE TABLE IF NOT EXISTS collaboration_request_form (
            receiver_company_id INTEGER PRIMARY KEY,
            document_id INTEGER NOT NULL,
            custom_field_count INTEGER NOT NULL,
            fetched_at TEXT NOT NULL
        )
        """;

    private static final String ATTACHMENT_CONFIG_TABLE = """
        CREATE TABLE IF NOT EXISTS attachment_config (
            case_token TEXT PRIMARY KEY,
            submitter_company_id INTEGER,
            receiver_company_id INTEGER,
            fetched_at TEXT NOT NULL
        )
        """;

    private static final String ATTACHMENT_FORWARD_RESULT_TABLE = """
        CREATE TABLE IF NOT EXISTS attachment_forward_result (
            row_id INTEGER PRIMARY KEY AUTOINCREMENT,
            case_token TEXT NOT NULL,
            description TEXT,
            file_name TEXT,
            receiver_status TEXT,
            receiver_message TEXT,
            submitter_status TEXT,
            submitter_message TEXT,
            complete_success INTEGER,
            partial_success INTEGER,
            forwarded_at TEXT NOT NULL
        )
        """;

    private DatabaseInitializer() {
    }

    public static void createSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(COLLABORATION_REQUEST_TABLE);
        jdbcTemplate.execute(CASE_NOTE_TABLE);
        jdbcTemplate.execute(CASE_RESPONSE_TABLE);
        jdbcTemplate.execute(USER_CONTEXT_TABLE);
        jdbcTemplate.execute(WEBHOOK_SUBSCRIPTION_TABLE);
        jdbcTemplate.execute(WEBHOOK_INBOUND_EVENT_TABLE);
        ensureColumn(jdbcTemplate, "webhook_subscription", "secret", "TEXT");
        jdbcTemplate.execute(PARTNER_SELECTION_TABLE);
        jdbcTemplate.execute(COLLABORATION_REQUEST_FORM_TABLE);
        ensureColumn(jdbcTemplate, "collaboration_request_form", "department_id", "INTEGER");
        ensureColumn(jdbcTemplate, "collaboration_request_form", "fields_json", "TEXT");
        jdbcTemplate.execute(ATTACHMENT_CONFIG_TABLE);
        jdbcTemplate.execute(ATTACHMENT_FORWARD_RESULT_TABLE);
    }

    private static void ensureColumn(JdbcTemplate jdbcTemplate, String table, String column, String sqlType) {
        try {
            jdbcTemplate.query("SELECT " + column + " FROM " + table + " LIMIT 0", rs -> {});
        } catch (Exception ignored) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + sqlType);
        }
    }
}
