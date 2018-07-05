package com.gdubina.jss7.console;

import org.apache.commons.cli.*;
import org.jboss.jreadline.console.Console;
import org.jboss.jreadline.console.settings.Settings;
import org.jboss.jreadline.terminal.TestTerminal;
import org.mobicents.ss7.management.console.CommandContextImpl;
import org.mobicents.ss7.management.console.CommandHandler;
import org.mobicents.ss7.management.console.ConsoleImpl;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class ImportFile {

    private static final int EXIT_ERROR = 1;
    private static final int EXIT_PARAM_ERROR = 2;
    private static final int EXIT_SYNTAX_ERROR = 3;

    private final String user;
    private final String password;

    private List<CommandHandler> commandHandlerList;

    public ImportFile(String user, String password) {
        this.user = user;
        this.password = password;
    }

    public static void main(String[] args) throws IOException {
        if (args == null || args.length == 0) {
            // print usage
            return;
        }
        Options options = new Options();

        Option input = new Option("f", "file", true, "input file path");
        input.setRequired(true);
        options.addOption(input);

        Option username = new Option("u", "user", true, "username");
        username.setRequired(true);
        options.addOption(username);

        Option password = new Option("p", "password", true, "password");
        password.setRequired(true);
        options.addOption(password);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("jSS7 file importer", options);

            System.exit(EXIT_ERROR);
        }
        Path file = Paths.get(cmd.getOptionValue("file"));
        new ImportFile(cmd.getOptionValue("user"), cmd.getOptionValue("password")).importFromFile(file);

    }

    private void importFromFile(Path file) throws IOException {
        System.out.println("jSS7 File Importer");
        List<String> lines = readLines(file);

        Settings.getInstance().setDisableCompletion(true);

        Settings.getInstance().setTerminal(new TestTerminal());
        LinkedList<String> input = new LinkedList<>();
        input.add(user);
        input.add(password);
        CommandContextImpl commandContext = new CommandContextImpl() {

            @Override
            public void printLine(String message) {
                if (message != null) {
                    if (message.contains("Exception")) {
                        throw new IllegalStateException(message);
                    }
                    if (message.contains("Channel closed by server")) {
                        throw new IllegalStateException("Server closed connection");
                    }
                }
                super.printLine(message);
            }
        };
        replaceConsole(commandContext, new Console() {

            @Override
            public String read(String prompt, Character mask) throws IOException {
                System.out.println(prompt);
                return input.pop();
            }
        });
        commandHandlerList = getCommandHandlerList();

        if (!validateCommands(lines)) {
            System.exit(EXIT_SYNTAX_ERROR);
            return;
        }
        for (String line : lines) {
            handleLine(commandContext, line);
        }
    }

    private boolean validateCommands(List<String> lines) {
        boolean isError = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!dryRun(line)) {
                isError = true;
                System.out.println("Syntax error: line #" + (i + 1) + ": " + line);
            }
        }
        return !isError;
    }

    private List<String> readLines(Path file) {
        List<String> lines;
        try {
            lines = Files.lines(file).collect(Collectors.<String>toList());
        } catch (IOException e) {
            throw new IllegalStateException("Can not read file", e);
        }
        return lines;
    }

    private boolean dryRun(String line) {
        line = line.trim();
        if (line.startsWith("#") || line.equals("") || line.equals("clear") || line.equals("cls")) {
            return true;
        }
        CommandHandler commandHandler = getCommandHandler(line);
        return commandHandler != null;
    }

    private void handleLine(CommandContextImpl commandContext, String line) {
        if (commandContext.isTerminated()) {
            throw new IllegalStateException("Already terminated");
        }
        line = line.trim();
        if (line.equals("") || line.startsWith("#")) {
            return;
        }
        if (line.equals("clear") || line.equals("cls")) {
            commandContext.clearScreen();
            return;
        }
        CommandHandler commandHandler = getCommandHandler(line);
        if (commandHandler != null) {
            if (!commandHandler.isAvailable(commandContext)) {
                throw new IllegalStateException("Handler is unavailable, cmd: " + line);
            }
            commandContext.printLine(">> " + line);
            commandHandler.handle(commandContext, line);
        } else {
            throw new IllegalStateException("Unexpected command: " + line);
        }
    }

    private CommandHandler getCommandHandler(String line) {
        for (CommandHandler commandHandlerTemp : commandHandlerList) {
            if (commandHandlerTemp.handles(line)) {
                return commandHandlerTemp;
            }
        }
        return null;
    }

    private List<CommandHandler> getCommandHandlerList() {
        Field f = null;
        try {
            f = ConsoleImpl.class.getDeclaredField("commandHandlerList");
            f.setAccessible(true);
            return (List<CommandHandler>) f.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("Can not get commandHandlerList", e);
        }

    }

    private void replaceConsole(CommandContextImpl commandContext, Console newConsole) {
        try {
            Field consoleField = CommandContextImpl.class.getDeclaredField("console");
            consoleField.setAccessible(true);
            Object console = consoleField.get(commandContext);
            Field jbossConsole = console.getClass().getDeclaredField("console");
            jbossConsole.setAccessible(true);
            jbossConsole.set(console, newConsole);
        } catch (Exception e) {
            throw new IllegalStateException("Can not replace console", e);
        }

    }
}
