package vip.mate.os.run;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOsMigrationPairTest {

    private static final Path H2 = Path.of("src/main/resources/db/migration/h2/V132__agent_os_run_kernel.sql");
    private static final Path MYSQL = Path.of("src/main/resources/db/migration/mysql/V132__agent_os_run_kernel.sql");

    @Test
    void v132MigrationExistsInH2AndMysqlWithRequiredTables() throws Exception {
        assertThat(H2).exists();
        assertThat(MYSQL).exists();
        String h2 = Files.readString(H2);
        String mysql = Files.readString(MYSQL);

        for (String table : new String[] {
                "mate_agent_run",
                "mate_agent_step",
                "mate_agent_event",
                "mate_agent_artifact",
                "mate_agent_pause",
                "mate_browser_session",
                "mate_browser_tab",
                "mate_browser_region",
                "mate_lead_task",
                "mate_lead_comment",
                "mate_lead_profile",
                "mate_lead_engagement"
        }) {
            assertThat(h2).contains("CREATE TABLE IF NOT EXISTS " + table);
            assertThat(mysql).contains("CREATE TABLE IF NOT EXISTS " + table);
        }
    }
}
