package com.tsanet.facade.cli.commands;

import com.tsanet.facade.cli.CliRunContext;
import com.tsanet.facade.cli.EntityPrinter;
import com.tsanet.api.TsaNetApiSession;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class LogoutCommand implements Command {
    private final TsaNetApiSession session;
    private final CliRunContext cliRunContext;

    public LogoutCommand(TsaNetApiSession session, CliRunContext cliRunContext) {
        this.session = session;
        this.cliRunContext = cliRunContext;
    }

    @Override
    public String name() {
        return "logout";
    }

    @Override
    public String description() {
        return "Clear the current Connect API session";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        session.auth().logout();
        if (cliRunContext.isPlainOutput()) {
            System.out.println("logged out");
            return;
        }
        System.out.println("Connect API session cleared.");
    }
}
