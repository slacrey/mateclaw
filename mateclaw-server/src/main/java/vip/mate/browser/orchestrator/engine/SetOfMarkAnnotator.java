package vip.mate.browser.orchestrator.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.browser.orchestrator.domain.BBox;
import vip.mate.browser.orchestrator.domain.Viewport;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Set-of-Mark (SoM) image annotator.
 *
 * <p>Given the screenshot bytes plus a list of candidate element boxes (in CSS
 * <em>viewport</em> pixels, document order), draws a numbered badge + outline on
 * each box and re-encodes the frame as JPEG. The numbers are 1-based and align
 * positionally with the input list, so the caller can map a model-returned
 * number {@code n} back to {@code boxesInOrder.get(n - 1)}.
 *
 * <p><strong>Why SoM:</strong> raw {x,y,w,h} grounding asks a VLM to invent pixel
 * coordinates, which is unreliable on dense / custom UIs. Labelling candidates
 * and asking only "which number?" turns a regression problem into a far more
 * robust multiple-choice one. This annotator is the visual half of that path;
 * {@link VisionEngine} owns the prompt + parse.
 *
 * <h2>Coordinate scaling</h2>
 * The captured image may be HiDPI — physically larger than the CSS viewport
 * (a {@code scale_factor > 1} screenshot). Boxes arrive in CSS-viewport pixels,
 * so we scale each box by {@code imageWidth/viewport.w()} (x, w) and
 * {@code imageHeight/viewport.h()} (y, h) before drawing.
 *
 * <h2>Robustness contract</h2>
 * This class <strong>never throws</strong>. If the image cannot be decoded, the
 * viewport is degenerate, or re-encoding fails, {@link #annotate} returns the
 * <em>original</em> bytes unchanged so the vision path can still proceed (it will
 * simply not have marks to reason over). Boxes that fall entirely off the image
 * are skipped — but their slot is preserved in {@code boxesInOrder} so numbering
 * never shifts relative to the caller's candidate list.
 */
@Slf4j
@Component
public class SetOfMarkAnnotator {

    /**
     * Result of annotating a screenshot.
     *
     * @param jpegBytes     the annotated frame, JPEG-encoded (or the original
     *                      bytes when annotation was skipped / failed)
     * @param boxesInOrder  the exact candidate boxes passed in, in the same
     *                      order; index {@code i} carries badge number
     *                      {@code i + 1}. Returned so the caller does not need
     *                      to retain its own copy to map a number back to a box.
     */
    public record Annotated(byte[] jpegBytes, List<BBox> boxesInOrder) {
        public Annotated {
            if (jpegBytes == null) {
                throw new IllegalArgumentException("jpegBytes is required");
            }
            boxesInOrder = boxesInOrder == null ? List.of() : List.copyOf(boxesInOrder);
        }
    }

    /** Outline thickness in device pixels. */
    private static final float OUTLINE_STROKE = 2f;

    /** Badge padding around the number text, in device pixels. */
    private static final int BADGE_PAD = 3;

    /** Base badge font size; nudged up slightly for HiDPI captures. */
    private static final int BASE_FONT_SIZE = 13;

    /**
     * Annotate {@code imageBytes} with numbered marks for each box.
     *
     * @param imageBytes      raw screenshot bytes (JPEG or PNG; decoded via
     *                        {@link ImageIO}). Must not be {@code null}.
     * @param boxesViewportPx candidate boxes in CSS-viewport pixels, document
     *                        order. {@code null} / empty yields the original
     *                        bytes with an empty box list.
     * @param viewport        the CSS viewport the boxes are expressed in; used
     *                        to derive the image→viewport scale. {@code null}
     *                        or degenerate yields the original bytes.
     * @return an {@link Annotated} carrying the JPEG bytes and the box list.
     *         Never {@code null}; never throws.
     */
    public Annotated annotate(byte[] imageBytes, List<BBox> boxesViewportPx, Viewport viewport) {
        List<BBox> boxes = boxesViewportPx == null ? List.of() : List.copyOf(boxesViewportPx);

        if (imageBytes == null || imageBytes.length == 0) {
            log.debug("[set-of-mark] empty image bytes; returning original");
            return new Annotated(imageBytes == null ? new byte[0] : imageBytes, boxes);
        }
        if (boxes.isEmpty() || viewport == null || viewport.w() <= 0 || viewport.h() <= 0) {
            // Nothing to draw (or no reliable scale): hand back the original frame.
            return new Annotated(imageBytes, boxes);
        }

        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (src == null) {
                log.debug("[set-of-mark] ImageIO could not decode image ({} bytes); returning original",
                        imageBytes.length);
                return new Annotated(imageBytes, boxes);
            }

            int imgW = src.getWidth();
            int imgH = src.getHeight();
            double scaleX = (double) imgW / viewport.w();
            double scaleY = (double) imgH / viewport.h();

            // Draw onto an RGB copy (JPEG has no alpha channel). Copying also
            // guards against source images with exotic colour models.
            BufferedImage canvas = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.drawImage(src, 0, 0, null);

                int fontSize = Math.max(BASE_FONT_SIZE,
                        (int) Math.round(BASE_FONT_SIZE * Math.min(scaleX, scaleY)));
                Font badgeFont = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
                g.setFont(badgeFont);
                float stroke = (float) Math.max(OUTLINE_STROKE, OUTLINE_STROKE * Math.min(scaleX, scaleY));

                int drawn = 0;
                for (int i = 0; i < boxes.size(); i++) {
                    BBox b = boxes.get(i);
                    if (b == null) {
                        continue;
                    }
                    int x = (int) Math.round(b.x() * scaleX);
                    int y = (int) Math.round(b.y() * scaleY);
                    int w = (int) Math.round(b.w() * scaleX);
                    int h = (int) Math.round(b.h() * scaleY);

                    // Skip boxes that lie entirely off the image. Their slot is
                    // still consumed so badge numbers track the caller's list.
                    if (x + w < 0 || y + h < 0 || x > imgW || y > imgH) {
                        continue;
                    }

                    int label = i + 1;
                    drawOutline(g, x, y, w, h, stroke, imgW, imgH);
                    drawBadge(g, badgeFont, label, x, y, imgW, imgH);
                    drawn++;
                }
                log.debug("[set-of-mark] drew {} of {} candidate marks on {}x{} image (scale {}x{})",
                        drawn, boxes.size(), imgW, imgH,
                        String.format("%.2f", scaleX), String.format("%.2f", scaleY));
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(imageBytes.length);
            boolean ok = ImageIO.write(canvas, "jpg", out);
            if (!ok || out.size() == 0) {
                log.debug("[set-of-mark] JPEG re-encode produced no output; returning original");
                return new Annotated(imageBytes, boxes);
            }
            return new Annotated(out.toByteArray(), boxes);
        } catch (Throwable t) {
            // Belt-and-braces: never let annotation break the vision path.
            log.debug("[set-of-mark] annotation failed ({}); returning original bytes",
                    t.toString());
            return new Annotated(imageBytes, boxes);
        }
    }

    /** Thin high-contrast rectangle around the (already scaled) box. */
    private void drawOutline(Graphics2D g, int x, int y, int w, int h,
                             float stroke, int imgW, int imgH) {
        // Clamp the rectangle so it stays paintable even when a box overhangs.
        int rx = Math.max(0, x);
        int ry = Math.max(0, y);
        int rw = Math.max(1, Math.min(w, imgW - rx));
        int rh = Math.max(1, Math.min(h, imgH - ry));
        g.setStroke(new BasicStroke(stroke));
        g.setColor(Color.RED);
        g.drawRect(rx, ry, rw, rh);
    }

    /** Filled badge with the 1-based index, anchored at the box's top-left. */
    private void drawBadge(Graphics2D g, Font font, int label, int x, int y,
                           int imgW, int imgH) {
        String text = Integer.toString(label);
        FontRenderContext frc = g.getFontRenderContext();
        Rectangle2D bounds = font.getStringBounds(text, frc);
        int textW = (int) Math.ceil(bounds.getWidth());
        int textH = (int) Math.ceil(bounds.getHeight());
        int badgeW = textW + BADGE_PAD * 2;
        int badgeH = textH + BADGE_PAD * 2;

        // Anchor at the box top-left, but keep the badge fully on-image.
        int bx = Math.max(0, Math.min(x, imgW - badgeW));
        int by = Math.max(0, Math.min(y, imgH - badgeH));

        // Slightly translucent fill so the underlying element stays legible.
        java.awt.Composite prior = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
        g.setColor(Color.RED);
        g.fillRect(bx, by, badgeW, badgeH);
        g.setComposite(prior);

        g.setColor(Color.WHITE);
        // Baseline = top + ascent; getStringBounds gives a box whose -y is the
        // ascent, so shift down by -bounds.getY() to sit the glyphs correctly.
        int baseline = by + BADGE_PAD + (int) Math.round(-bounds.getY());
        g.drawString(text, bx + BADGE_PAD, baseline);
    }
}
