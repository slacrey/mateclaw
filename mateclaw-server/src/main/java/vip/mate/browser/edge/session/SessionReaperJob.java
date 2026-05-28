package vip.mate.browser.edge.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically reaps stale Browser Agent sessions whose Native Host has
 * stopped heart-beating. Spring's {@code @Scheduled} is already enabled by
 * MateClawApplication ({@code @EnableScheduling}).
 *
 * <p>Runs every 10 s — same cadence as the heartbeat itself; combined with
 * the 30s grace window in {@link BrowserSessionRegistry#STALE_GRACE} this
 * gives at most 40s between actual silence and removal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionReaperJob {

    private final BrowserSessionRegistry registry;

    @Scheduled(fixedDelay = 10_000)
    public void run() {
        int reaped = registry.reapStale();
        if (reaped > 0) {
            log.info("[edge-reaper] reaped {} stale sessions", reaped);
        }
    }
}
