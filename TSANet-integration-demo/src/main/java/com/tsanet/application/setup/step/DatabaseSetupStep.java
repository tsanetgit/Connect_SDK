package com.tsanet.application.setup.step;

import org.springframework.jdbc.core.JdbcTemplate;

public interface DatabaseSetupStep {
    void apply(JdbcTemplate jdbc);

    String name();
}
