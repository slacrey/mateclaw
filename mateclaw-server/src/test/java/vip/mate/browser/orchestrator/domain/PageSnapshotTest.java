package vip.mate.browser.orchestrator.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageSnapshotTest {

    @Test
    void lines_parseFrameTaggedWireFormat() {
        var snap = PageSnapshot.fromA11yText(
                "Button[ref=ref_1, frame=1]: Login @{210,310 60x24}",
                new Viewport(1280, 800));

        assertThat(snap.lines()).singleElement().satisfies(line -> {
            assertThat(line.role()).isEqualTo("Button");
            assertThat(line.refId()).isEqualTo("ref_1");
            assertThat(line.frameId()).isEqualTo(1);
            assertThat(line.name()).isEqualTo("Login");
            assertThat(line.bbox()).isEqualTo(new BBox(210, 310, 60, 24));
        });
    }
}
