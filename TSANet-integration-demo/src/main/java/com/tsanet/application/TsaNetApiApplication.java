package com.tsanet.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

// JDBC is only used for optional Connect DB setup (tsanet.demo.setup.enabled); no default spring.datasource.
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class TsaNetApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(TsaNetApiApplication.class, args);
    }
}
