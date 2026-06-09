package vip.mate.browser.orchestrator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class OrchestratorConfig {

    @Bean
    public Clock browserOrchestratorClock() {
        return Clock.systemUTC();
    }
}
