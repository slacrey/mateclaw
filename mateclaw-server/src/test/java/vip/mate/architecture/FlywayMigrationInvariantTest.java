package vip.mate.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationInvariantTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("V(\\d+)__.+\\.sql");

    @Test
    void flywayMigrationVersionsAreUniquePerDialect() throws Exception {
        assertUniqueVersions("h2");
        assertUniqueVersions("mysql");
    }

    @Test
    void h2AndMysqlMigrationFilenamesStayPaired() throws Exception {
        assertThat(migrationFilenames("h2"))
                .containsExactlyElementsOf(migrationFilenames("mysql"));
    }

    private static void assertUniqueVersions(String dialect) throws IOException {
        Map<Integer, List<String>> namesByVersion = new TreeMap<>();
        for (String filename : migrationFilenames(dialect)) {
            Matcher matcher = VERSIONED_MIGRATION.matcher(filename);
            assertThat(matcher.matches())
                    .as("Flyway migration filename %s/%s must match Vn__description.sql", dialect, filename)
                    .isTrue();
            int version = Integer.parseInt(matcher.group(1));
            namesByVersion.computeIfAbsent(version, ignored -> new java.util.ArrayList<>()).add(filename);
        }

        Map<Integer, List<String>> duplicates = namesByVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, TreeMap::new));

        assertThat(duplicates)
                .as("Flyway migration versions must be unique in %s", dialect)
                .isEmpty();
    }

    private static List<String> migrationFilenames(String dialect) throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS.resolve(dialect))) {
            return files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }
}
