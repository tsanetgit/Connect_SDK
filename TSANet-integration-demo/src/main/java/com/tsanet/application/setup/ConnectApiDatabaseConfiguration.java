package com.tsanet.application.setup;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(prefix = "tsanet.demo.setup", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ConnectApiDatabaseProperties.class)
public class ConnectApiDatabaseConfiguration {
    @Bean
    DataSource connectApiDataSource(ConnectApiDatabaseProperties properties) {
        ConnectApiDatabaseProperties.ConnectDb connectDb = properties.connectDb();
        return DataSourceBuilder.create()
            .url(connectDb.url())
            .username(connectDb.username())
            .password(connectDb.password())
            .driverClassName("org.postgresql.Driver")
            .build();
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource connectApiDataSource) {
        return new JdbcTemplate(connectApiDataSource);
    }
}
