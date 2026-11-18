package com.jg.ignition.assistant;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CliExecutable {
    private CliExecutable() {}

    record Match(String executable, String source) {}

    private record Location(Path path, String source) {}

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static String resolve(String configured, String command) {
        String path = configuredPath(configured);
        if (!path.isBlank()) {
            return path;
        }
        return discover(command, Path.of(System.getProperty("user.home")), System.getenv(), windows());
    }

    static String configuredPath(String value) {
        String path = value.strip();
        return path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")
            ? path.substring(1, path.length() - 1).strip() : path;
    }

    static String discover(String command, Path home, Map<String, String> env, boolean windows) {
        var matches = find(command, home, env, windows, true);
        return matches.isEmpty() ? command + (windows ? ".exe" : "") : matches.get(0).executable();
    }

    static List<Match> findAll(String command) {
        return findAll(command, Path.of(System.getProperty("user.home")), System.getenv(), windows());
    }

    static List<Match> findAll(String command, Path home, Map<String, String> env, boolean windows) {
        return find(command, home, env, windows, false);
    }

    private static List<Match> find(String command, Path home, Map<String, String> env, boolean windows,
                                    boolean firstOnly) {
        var locations = new ArrayList<Location>();
        locations.add(new Location(home.resolve(".local/bin"), "User bin"));
        locations.add(new Location(home.resolve("." + command).resolve("bin"), "User install"));
        if (windows) {
            add(locations, env.get("LOCALAPPDATA"), "Microsoft/WinGet/Links", "WinGet");
            add(locations, env.get("ProgramFiles"), "WinGet/Links", "WinGet");
            add(locations, env.get("SCOOP"), "shims", "Scoop");
            locations.add(new Location(home.resolve("scoop/shims"), "Scoop"));
            add(locations, env.get("APPDATA"), "npm", "npm");
            if ("kiro-cli".equals(command)) {
                add(locations, env.get("ProgramFiles"), "Kiro-Cli", "Kiro installer");
            }
            if ("az".equals(command)) {
                add(locations, env.get("ProgramFiles"), "Microsoft SDKs/Azure/CLI2/wbin", "Azure installer");
                add(locations, env.get("ProgramFiles(x86)"), "Microsoft SDKs/Azure/CLI2/wbin", "Azure installer");
            }
        } else {
            locations.add(new Location(Path.of("/opt/homebrew/bin"), "Homebrew"));
            locations.add(new Location(Path.of("/usr/local/bin"), "System bin"));
        }

        String searchPath = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        for (String entry : searchPath.split(windows ? ";" : ":")) {
            add(locations, entry, "", "PATH");
        }
        String binary = windows ? command + ".exe" : command;
        List<String> names = windows && "az".equals(command) ? List.of(binary, "az.cmd") : List.of(binary);
        var matches = new LinkedHashMap<String, Match>();
        for (String name : names) {
            for (Location location : locations) {
                Path candidate = location.path().resolve(name);
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    addMatch(matches, candidate, location.source(), windows);
                    if (firstOnly) {
                        return List.copyOf(matches.values());
                    }
                }
            }
        }
        if (windows) {
            for (Location location : locations) {
                Path executable = npmExecutable(location.path(), command);
                if (executable != null) {
                    addMatch(matches, executable, "npm package", true);
                    if (firstOnly) {
                        return List.copyOf(matches.values());
                    }
                }
            }
        }
        return List.copyOf(matches.values());
    }

    private static void addMatch(Map<String, Match> matches, Path candidate, String source, boolean windows) {
        String executable = candidate.normalize().toString();
        String key = windows ? executable.toLowerCase(Locale.ROOT) : executable;
        matches.putIfAbsent(key, new Match(executable, source));
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

    private static void add(List<Location> locations, String value, String suffix, String source) {
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
                locations.add(new Location(path.resolve(suffix), source));
            }
        } catch (InvalidPathException ignored) {
            // Ignore malformed PATH entries while retaining the remaining installation directories.
        }
    }
}
