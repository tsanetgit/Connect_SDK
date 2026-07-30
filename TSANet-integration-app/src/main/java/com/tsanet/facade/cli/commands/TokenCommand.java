package com.tsanet.facade.cli.commands;

import com.tsanet.api.TsaNetApiSession;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class TokenCommand implements Command {
    private final TsaNetApiSession session;

    public TokenCommand(TsaNetApiSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "token";
    }

    @Override
    public String description() {
        return "Print the current Connect API bearer token";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        var token = session.auth().currentBearerToken();
        if (token.isPresent()) {
            System.out.println(token.get());
            return;
        }
        System.out.println("No bearer token in session.");
    }
}
