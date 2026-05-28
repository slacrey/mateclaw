package vip.mate.browser.edge.session;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class SessionReaperJobTest {

    @Test
    void run_invokesRegistryReap() {
        BrowserSessionRegistry registry = mock(BrowserSessionRegistry.class);
        when(registry.reapStale()).thenReturn(3);

        SessionReaperJob job = new SessionReaperJob(registry);
        job.run();

        verify(registry).reapStale();
    }
}
