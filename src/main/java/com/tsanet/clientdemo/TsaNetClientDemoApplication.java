package com.tsanet.clientdemo;

import com.tsanet.clientdemo.app.ConsoleShellRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class TsaNetClientDemoApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TsaNetClientDemoApplication.class)
            .web(org.springframework.boot.WebApplicationType.NONE)
            .run(args);

        int exitCode = SpringApplication.exit(context);
        System.exit(exitCode);
    }
}