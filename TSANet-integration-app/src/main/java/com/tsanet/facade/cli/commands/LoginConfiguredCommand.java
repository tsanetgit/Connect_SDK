package com.tsanet.facade.cli.commands;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.facade.session.AccountCacheSummary;
import com.tsanet.api.session.AccountSessionView;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class LoginConfiguredCommand implements Command {
    private final TsaNetApiSession session;

    public LoginConfiguredCommand(TsaNetApiSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "login-configured";
    }

    @Override
    public String description() {
        return "Log in with credentials from application.yml";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        session.auth().loginWithConfiguredCredentials();
        String username = session.auth().currentUsername().orElse("configured user");
        System.out.println("logged in as: " + username);
        if (session instanceof AccountSessionView accountSession) {
            accountSession.activeSqlitePath().ifPresent(path -> System.out.println("sqlite cache: " + path));
        }
        System.out.println(AccountCacheSummary.from(session).describe());
    }
}
