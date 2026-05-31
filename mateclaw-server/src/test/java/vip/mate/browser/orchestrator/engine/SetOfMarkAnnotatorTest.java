package vip.mate.browser.orchestrator.engine;

import org.junit.jupiter.api.Test;
import vip.mate.browser.orchestrator.domain.BBox;
import vip.mate.browser.orchestrator.domain.Viewport;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link SetOfMarkAnnotator}.
 *
 * <p>The annotator is a pure, never-throwing transform: it decodes the input
 * frame, draws numbered marks for each candidate box (scaled from CSS-viewport
 * to image pixels), and re-encodes as JPEG. Tests pin:
 * <ul>
 *   <li>N boxes on a real frame → output is non-empty and ImageIO-decodable.</li>
 *   <li>HiDPI captures (image larger than viewport) are scaled, not rejected.</li>
 *   <li>Off-image boxes are skipped without throwing.</li>
 *   <li>Undecodable / empty / null inputs degrade to the original bytes.</li>
 *   <li>The returned box list preserves the caller's order and contents.</li>
 * </ul>
 */
class SetOfMarkAnnotatorTest {

    private final SetOfMarkAnnotator annotator = new SetOfMarkAnnotator();

    /** Build a solid-colour JPEG of the given pixel size for use as a fixture. */
    private static byte[] solidJpeg(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 220, 240));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "jpg", out);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    @Test
    void annotate_nBoxes_outputIsNonEmptyAndDecodable() throws Exception {
        Viewport vp = new Viewport(1280, 800);
        byte[] src = solidJpeg(1280, 800);

        List<BBox> boxes = List.of(
                new BBox(10, 20, 100, 30),
                new BBox(300, 200, 80, 24),
                new BBox(640, 400, 120, 40),
                new BBox(900, 700, 60, 20));

        SetOfMarkAnnotator.Annotated result = annotator.annotate(src, boxes, vp);

        assertThat(result).isNotNull();
        assertThat(result.jpegBytes()).isNotEmpty();
        // Numbering contract: the returned list mirrors the input 1:1, in order.
        assertThat(result.boxesInOrder()).containsExactlyElementsOf(boxes);

        // The output must be a valid image ImageIO can read back.
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.jpegBytes()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(1280);
        assertThat(decoded.getHeight()).isEqualTo(800);
    }

    @Test
    void annotate_actuallyDrawsMarks_outputDiffersFromInput() throws Exception {
        Viewport vp = new Viewport(400, 300);
        byte[] src = solidJpeg(400, 300);

        SetOfMarkAnnotator.Annotated result =
                annotator.annotate(src, List.of(new BBox(50, 50, 100, 40)), vp);

        // A drawn badge + outline must change pixels, so the re-encoded frame
        // cannot be byte-identical to the (also JPEG) source.
        assertThat(result.jpegBytes()).isNotEmpty();
        assertThat(Arrays.equals(result.jpegBytes(), src)).isFalse();
        assertThat(ImageIO.read(new ByteArrayInputStream(result.jpegBytes()))).isNotNull();
    }

    @Test
    void annotate_hiDpiImageLargerThanViewport_scalesAndDecodes() throws Exception {
        // CSS viewport 800x600, captured at 2x → 1600x1200 physical pixels.
        Viewport vp = new Viewport(800, 600);
        byte[] src = solidJpeg(1600, 1200);

        List<BBox> boxes = List.of(new BBox(0, 0, 100, 30), new BBox(700, 550, 90, 40));
        SetOfMarkAnnotator.Annotated result = annotator.annotate(src, boxes, vp);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.jpegBytes()));
        assertThat(decoded).isNotNull();
        // Output keeps the physical (image) dimensions, not the CSS viewport.
        assertThat(decoded.getWidth()).isEqualTo(1600);
        assertThat(decoded.getHeight()).isEqualTo(1200);
    }

    @Test
    void annotate_offImageBoxes_areSkipped_withoutThrowing() throws Exception {
        Viewport vp = new Viewport(400, 300);
        byte[] src = solidJpeg(400, 300);

        // Mix of on-image and fully off-image boxes (negative + beyond-bounds).
        List<BBox> boxes = new ArrayList<>();
        boxes.add(new BBox(10, 10, 50, 20));        // on-image
        boxes.add(new BBox(5000, 5000, 40, 20));    // far below-right, off-image
        boxes.add(new BBox(100, 100, 30, 30));      // on-image

        SetOfMarkAnnotator.Annotated result = annotator.annotate(src, boxes, vp);

        assertThat(result.jpegBytes()).isNotEmpty();
        assertThat(ImageIO.read(new ByteArrayInputStream(result.jpegBytes()))).isNotNull();
        // The list is preserved verbatim regardless of which boxes were drawn,
        // so caller-side numbering stays aligned.
        assertThat(result.boxesInOrder()).hasSize(3);
    }

    @Test
    void annotate_undecodableBytes_returnsOriginalBytes() {
        Viewport vp = new Viewport(800, 600);
        byte[] garbage = "not-an-image".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        SetOfMarkAnnotator.Annotated result =
                annotator.annotate(garbage, List.of(new BBox(0, 0, 10, 10)), vp);

        // Robustness contract: undecodable input → original bytes, no throw.
        assertThat(result.jpegBytes()).isEqualTo(garbage);
        assertThat(result.boxesInOrder()).hasSize(1);
    }

    @Test
    void annotate_emptyBoxList_returnsOriginalBytes() {
        Viewport vp = new Viewport(800, 600);
        byte[] src = solidJpeg(800, 600);

        SetOfMarkAnnotator.Annotated result = annotator.annotate(src, List.of(), vp);

        // No boxes → nothing to draw → hand back the original frame untouched.
        assertThat(result.jpegBytes()).isEqualTo(src);
        assertThat(result.boxesInOrder()).isEmpty();
    }

    @Test
    void annotate_nullImage_doesNotThrow_returnsEmpty() {
        Viewport vp = new Viewport(800, 600);

        SetOfMarkAnnotator.Annotated result =
                annotator.annotate(null, List.of(new BBox(0, 0, 10, 10)), vp);

        assertThat(result).isNotNull();
        assertThat(result.jpegBytes()).isEmpty();
    }

    @Test
    void annotate_nullViewport_returnsOriginalBytes() {
        byte[] src = solidJpeg(640, 480);

        SetOfMarkAnnotator.Annotated result =
                annotator.annotate(src, List.of(new BBox(0, 0, 10, 10)), null);

        // Without a viewport there is no reliable scale → skip, keep original.
        assertThat(result.jpegBytes()).isEqualTo(src);
    }
}
