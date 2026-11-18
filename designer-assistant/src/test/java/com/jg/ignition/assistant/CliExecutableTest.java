package com.jg.ignition.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CliExecutableTest {
    @Test
    void findsWindowsNativeInstallLocationsAndPrefersThemToPath(@TempDir Path root) throws Exception {
        Path home = Files.createDirectory(root.resolve("Home Folder"));
        Path local = Files.createDirectory(root.resolve("Local App Data"));
        Path programFiles = Files.createDirectory(root.resolve("Program Files"));
        Path path = Files.createDirectory(root.resolve("User Bin"));
        Files.createFile(path.resolve("claude.exe"));
        Path claude = executable(home.resolve(".local/bin/claude.exe"));
        Path codex = executable(local.resolve("Microsoft/WinGet/Links/codex.exe"));
        Path kiro = executable(programFiles.resolve("Kiro-Cli/kiro-cli.exe"));
        Path az = executable(programFiles.resolve("Microsoft SDKs/Azure/CLI2/wbin/az.cmd"));
        Map<String, String> env = Map.of("Path", "\""+path+"\"", "LOCALAPPDATA", local.toString(),
            "ProgramFiles", programFiles.toString());
        assertEquals(claude.toString(), CliExecutable.discover("claude", home, env, true));
        assertEquals(codex.toString(), CliExecutable.discover("codex", home, env, true));
        assertEquals(kiro.toString(), CliExecutable.discover("kiro-cli", home, env, true));
        assertEquals(az.toString(), CliExecutable.discover("az", home, env, true));
        assertEquals("missing.exe", CliExecutable.discover("missing", home, env, true));
    }

    @Test
    void resolvesNativeClaudeFromNpmWithoutInvokingTheShim(@TempDir Path root) throws Exception {
        Path roaming = Files.createDirectory(root.resolve("Roaming"));
        Path shim = executable(roaming.resolve("npm/claude.cmd"));
        Path claude = executable(shim.resolveSibling("node_modules/@anthropic-ai/claude-code/bin/claude.exe"));
        assertEquals(claude.toString(), CliExecutable.discover("claude", root, Map.of("APPDATA", roaming.toString()), true));
    }

    @Test
    void listsAlternateInstallsInResolutionOrderAndReportsTheirSource(@TempDir Path root) throws Exception {
        Path winget = executable(root.resolve("Microsoft/WinGet/Links/kiro-cli.exe"));
        Path managed = executable(root.resolve("Managed Applications/Kiro/kiro-cli.exe"));
        var env = Map.of("LOCALAPPDATA", root.toString(),
            "PATH", winget.getParent() + ";" + managed.getParent());
        assertEquals(List.of(new CliExecutable.Match(winget.toString(), "WinGet"),
                new CliExecutable.Match(managed.toString(), "PATH")),
            CliExecutable.findAll("kiro-cli", root, env, true));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void discoveredWindowsBinaryLaunchesDirectlyFromAPathWithSpaces(@TempDir Path root) throws Exception {
        Path folder = Files.createDirectory(root.resolve("Test CLI install"));
        Path nativeBinary = Path.of(System.getenv("SystemRoot"), "System32", "where.exe");
        Path alias = Files.copy(nativeBinary, folder.resolve("test-cli.exe"));
        String found = CliExecutable.discover("test-cli", root, Map.of("PATH", folder.toString()), true);
        assertEquals(alias.toString(), found);
        assertEquals(alias.toString(), CliExecutable.resolve("\"" + alias + "\"", "test-cli"));
        var launched = new ProcessBuilder(found, "/?").redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(launched.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, launched.exitValue());
        } finally {
            if (launched.isAlive()) {
                launched.destroyForcibly();
            }
        }
    }

    private static Path executable(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.createFile(path);
    }
}
