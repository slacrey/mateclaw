package vip.mate.browser.edge;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.browser.edge.session.BrowserSessionRegistry;
import vip.mate.browser.edge.session.BrowserSessionView;

import java.util.List;

/**
 * Read-only debug endpoint. Admin-only — protected by {@code SecurityConfig}.
 * Phase 1: in-memory view only; Phase 4 will switch to DB-backed queries.
 */
@RestController
@RequestMapping("/api/v1/browser/sessions")
@RequiredArgsConstructor
public class BrowserSessionDebugController {

    private final BrowserSessionRegistry registry;

    @GetMapping
    public List<BrowserSessionView> list() {
        return registry.snapshot();
    }
}
