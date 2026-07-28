package de.cpitester;

import java.util.Arrays;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            Cli.printUsage();
            System.exit(2);
        }
        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (command) {
            case "run":
                System.exit(Cli.run(rest));
                break;
            case "ui":
                WebUi.start(rest);
                break;
            case "--help":
            case "-h":
            case "help":
                Cli.printUsage();
                break;
            default:
                System.err.println("Unbekannter Befehl: " + command);
                Cli.printUsage();
                System.exit(2);
        }
    }
}
