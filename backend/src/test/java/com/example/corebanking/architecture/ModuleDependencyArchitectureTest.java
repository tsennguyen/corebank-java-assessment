package com.example.corebanking.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModuleDependencyArchitectureTest {

    @Test
    @DisplayName("Account module must not depend on or import from Transaction module")
    void accountModule_shouldNotDependOnTransactionModule() throws IOException {
        Path accountDir = Paths.get("src/main/java/com/example/corebanking/account");
        if (!Files.exists(accountDir)) {
            accountDir = Paths.get("backend/src/main/java/com/example/corebanking/account");
        }
        assertThat(Files.exists(accountDir))
                .withFailMessage(
                        "Account source directory not found: " + accountDir.toAbsolutePath())
                .isTrue();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(accountDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(
                            file -> {
                                try {
                                    List<String> lines = Files.readAllLines(file);
                                    for (int i = 0; i < lines.size(); i++) {
                                        String line = lines.get(i).trim();
                                        if (line.startsWith(
                                                        "import"
                                                            + " com.example.corebanking.transaction")
                                                || line.startsWith(
                                                        "import"
                                                            + " com.example.corebanking.recurring")) {
                                            violations.add(
                                                    file.getFileName()
                                                            + ":"
                                                            + (i + 1)
                                                            + " -> "
                                                            + line);
                                        }
                                    }
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
        }

        assertThat(violations)
                .withFailMessage(
                        "Found illegal dependency from account module to transaction/recurring"
                                + " module: %s",
                        violations)
                .isEmpty();
    }
}
