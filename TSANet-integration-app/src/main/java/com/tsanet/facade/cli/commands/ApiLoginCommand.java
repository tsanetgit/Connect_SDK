package com.tsanet.facade.cli.commands;

import com.tsanet.api.TsaNetApiSession;
import java.util.Scanner;
import org.springframework.stereotype.Component;

@Component
public class ApiLoginCommand implements Command {
    private final TsaNetApiSession session;

    public ApiLoginCommand(TsaNetApiSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "api-login";
    }

    @Override
    public String description() {
        return "Log in and print the JWT only (for scripting)";
    }

    @Override
    public void execute(String[] args, Scanner scanner) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: api-login USERNAME PASSWORD");
        }
        String token = session.auth().login(args[0], args[1]);
        System.out.println(token);
    }
}
