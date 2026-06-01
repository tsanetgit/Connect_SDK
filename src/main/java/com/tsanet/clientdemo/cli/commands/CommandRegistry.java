package com.tsanet.clientdemo.cli.commands;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CommandRegistry {
    private final Map<String, Command> commandsByName;

    public CommandRegistry(List<Command> commands) {
        this.commandsByName = new LinkedHashMap<>();
        commands.forEach(command -> this.commandsByName.put(command.name(), command));
    }

    public Optional<Command> find(String commandName) {
        return Optional.ofNullable(commandsByName.get(commandName));
    }

    public List<Command> list() {
        return List.copyOf(commandsByName.values());
    }
}
