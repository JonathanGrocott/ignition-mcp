package com.jg.ignition.assistant;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CliExecutable {
    private CliExecutable() {}

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static String resolve(String configured, String command) {
        if (!configured.isBlank()) {
            return configured.strip();
        }
        return discover(command, Path.of(System.getProperty("user.home")), System.getenv(), windows());
    }

    static String discover(String command, Path home, Map<String, String> env, boolean windows) {
        var locations = new ArrayList<Path>();
        locations.add(home.resolve(".local/bin"));
        locations.add(home.resolve("." + command).resolve("bin"));
        if (windows) {
            add(locations, env.get("LOCALAPPDATA"), "Microsoft/WinGet/Links");
            add(locations, env.get("ProgramFiles"), "WinGet/Links");
            add(locations, env.get("SCOOP"), "shims");
            locations.add(home.resolve("scoop/shims"));
            add(locations, env.get("APPDATA"), "npm");
            if ("kiro-cli".equals(command)) {
                add(locations, env.get("ProgramFiles"), "Kiro-Cli");
            }
            if ("az".equals(command)) {
                add(locations, env.get("ProgramFiles"), "Microsoft SDKs/Azure/CLI2/wbin");
                add(locations, env.get("ProgramFiles(x86)"), "Microsoft SDKs/Azure/CLI2/wbin");
            }
        } else {
            locations.add(Path.of("/opt/homebrew/bin"));
            locations.add(Path.of("/usr/local/bin"));
        }

        String searchPath = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        for (String entry : searchPath.split(windows ? ";" : ":")) {
            add(locations, entry, "");
        }
        String binary = windows ? command + ".exe" : command;
        List<String> names = windows && "az".equals(command) ? List.of(binary, "az.cmd") : List.of(binary);
        for (String name : names) {
            for (Path location : locations) {
                Path candidate = location.resolve(name);
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    return candidate.toString();
                }
            }
        }
        if (windows) {
            for (Path location : locations) {
                Path executable = npmExecutable(location, command);
                if (executable != null) {
                    return executable.toString();
                }
            }
        }
        return binary;
    }

    private static Path npmExecutable(Path location, String command) {
        Path modules = location.resolve("node_modules");
        if ("claude".equals(command)) {
            Path executable = modules.resolve("@anthropic-ai/claude-code/bin/claude.exe");
            return Files.isRegularFile(executable) ? executable : null;
        }
        if ("codex".equals(command)) {
            String machine = System.getProperty("os.arch", "");
            boolean arm = "aarch64".equalsIgnoreCase(machine) || "arm64".equalsIgnoreCase(machine);
            String packageName = "@openai/codex-win32-" + (arm ? "arm64" : "x64");
            String target = (arm ? "aarch64" : "x86_64") + "-pc-windows-msvc";
            Path codex = modules.resolve("@openai/codex");
            for (Path root : List.of(codex.resolve("node_modules").resolve(packageName), modules.resolve(packageName), codex)) {
                for (String folder : List.of("bin", "codex")) {
                    Path executable = root.resolve("vendor").resolve(target).resolve(folder).resolve("codex.exe");
                    if (Files.isRegularFile(executable)) {
                        return executable;
                    }
                }
            }
        }
        return null;
    }

    private static void add(List<Path> locations, String value, String suffix) {
        if (value == null || value.isBlank()) {
            return;
        }
        String entry = value.strip();
        if (entry.length() >= 2 && entry.startsWith("\"") && entry.endsWith("\"")) {
            entry = entry.substring(1, entry.length() - 1);
        }
        try {
            Path path = Path.of(entry);
            if (path.isAbsolute()) {
                locations.add(path.resolve(suffix));
            }
        } catch (InvalidPathException ignored) {
            // Ignore malformed PATH entries while retaining the remaining installation directories.
        }
    }
}
