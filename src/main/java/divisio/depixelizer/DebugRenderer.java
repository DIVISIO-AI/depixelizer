package divisio.depixelizer;

import divisio.depixelizer.PixelGraph.Edge;
import divisio.depixelizer.PixelGraph.Node;
import divisio.depixelizer.PixelGraph.Polygon;
import divisio.depixelizer.PixelGraph.Spline;
import divisio.depixelizer.PixelGraph.SplineSegment;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;

/**
 * Renders depixelization processing steps and optional overlays.
 * Used by both the debug PNG exporter and the Swing UI.
 *
 * Ported from Android {@code BitmapRenderer} / {@code SimilarityGraphAdapter}.
 *
 * <h3>Steps</h3>
 * <ol>
 *   <li>Pixels — raw scaled-up input pixels</li>
 *   <li>SimilarityGraph — similarity-graph edges overlaid on pixels</li>
 *   <li>GraphLines — polygon outlines (straight edges)</li>
 *   <li>GraphPolygons — filled polygon graph (straight edges)</li>
 *   <li>Splines — filled polygons with cubic bezier splines</li>
 * </ol>
 *
 * <h3>Optional overlays</h3>
 * Toggle via the public boolean fields before calling {@link #drawOnto}.
 */
public class DebugRenderer {

    // ------------------------------------------------------------------ scale

    /** Scale used when rendering to PNG (pixels per input-pixel). */
    private static final int PNG_SCALE = 10;

    // --------------------------------------------------------------- strokes

    private static final BasicStroke STROKE_THIN      = new BasicStroke(0.1f);
    private static final BasicStroke STROKE_GRAPH     = new BasicStroke(0.05f);
    private static final BasicStroke STROKE_SPLINE_PT = new BasicStroke(0.05f);

    // ---------------------------------------------------------------- colours

    private static final java.awt.Color BG_COLOR       = java.awt.Color.LIGHT_GRAY;
    private static final java.awt.Color GRAPH_COLOR    = new java.awt.Color(0, 100, 200, 200);
    private static final java.awt.Color CONFLICT_COLOR = new java.awt.Color(220, 0,   0,   220);
    private static final java.awt.Color CONTOUR_COLOR  = java.awt.Color.RED;
    private static final java.awt.Color NODE_OPT_COLOR = java.awt.Color.GREEN;
    private static final java.awt.Color NODE_FIX_COLOR = java.awt.Color.RED;

    // ------------------------------------------------------------------ steps

    /**
     * Processing step to display.
     * Matches Android {@code BitmapRenderer.ProcessingStep} (with SimilarityGraph added).
     */
    public enum Step {
        Pixels,
        SimilarityGraph,
        GraphLines,
        GraphPolygons,
        Splines
    }

    // --------------------------------------------------------- mutable state

    /** Current step being displayed. */
    public Step            currentStep      = Step.Pixels;
    /** Source image (may be null until loaded). */
    public BufferedImage   sourceImage;
    /** Similarity graph (may be null during early stages). */
    public SimilarityGraph similarityGraph;
    /** Pixel graph (may be null during early stages). */
    public PixelGraph      pixelGraph;

    // overlays — match Android BitmapRenderer boolean flags
    /** Overlay: draw similarity-graph edges. */
    public boolean paintSimilarityGraph       = false;
    /** Overlay: draw spline polylines in rainbow colours. */
    public boolean paintSplineEdges           = false;
    /** Overlay: highlight contour edges in red. */
    public boolean paintContourEdges          = false;
    /** Overlay: show which spline nodes are fixed vs optimisable. */
    public boolean paintImmutableSplineNodes  = false;
    /** Overlay: show Catmull-Rom sample points on each spline segment. */
    public boolean paintSplinePoints          = false;

    /** Reusable path. */
    private final Path2D.Float path = new Path2D.Float();

    // ------------------------------------------------------------ constructors

    /** Default constructor — fields must be set before drawing. */
    public DebugRenderer() {}

    /** Convenience constructor for the PNG-export use-case. */
    public DebugRenderer(final BufferedImage sourceImage,
                         final SimilarityGraph similarityGraph,
                         final PixelGraph pixelGraph)
    {
        this.sourceImage     = sourceImage;
        this.similarityGraph = similarityGraph;
        this.pixelGraph      = pixelGraph;
    }

    // ------------------------------------------------------ PNG export helpers

    /**
     * Renders {@code step} to a PNG file at {@code filePath}.
     *
     * @param step     step to render
     * @param filePath output file path
     * @throws IOException if the file cannot be written
     */
    public void writeSingleStep(final Step step, final String filePath) throws IOException {
        final Step saved = currentStep;
        currentStep = step;
        final BufferedImage img = renderToPng();
        currentStep = saved;
        final File out = new File(filePath);
        ImageIO.write(img, "PNG", out);
        System.out.println("Debug image written: " + out.getPath());
    }

    /**
     * Renders {@link #currentStep} to a new {@link BufferedImage} at
     * {@link #PNG_SCALE} pixels per input-pixel.
     */
    private BufferedImage renderToPng() {
        if (sourceImage == null) throw new IllegalStateException("sourceImage is null");
        final int w = sourceImage.getWidth()  * PNG_SCALE;
        final int h = sourceImage.getHeight() * PNG_SCALE;

        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = out.createGraphics();
        try {
            g.setColor(BG_COLOR);
            g.fillRect(0, 0, w, h);
            g.scale(PNG_SCALE, PNG_SCALE);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawStep(g);
            drawActiveOverlays(g);
        } finally {
            g.dispose();
        }
        return out;
    }

    // --------------------------------------------------- Swing live rendering

    /**
     * Draws the current step (and any active overlays) into {@code g},
     * scaled to fit within {@code canvasW × canvasH} while preserving aspect ratio.
     *
     * <p>Call this from {@code JPanel.paintComponent(Graphics)} after casting to
     * {@code Graphics2D}.
     *
     * @param g        Swing graphics context (not yet transformed)
     * @param canvasW  available canvas width in screen pixels
     * @param canvasH  available canvas height in screen pixels
     */
    public void drawOnto(final Graphics2D g, final int canvasW, final int canvasH) {
        // fill background
        g.setColor(BG_COLOR);
        g.fillRect(0, 0, canvasW, canvasH);

        if (sourceImage == null) return;

        final int imgW = sourceImage.getWidth();
        final int imgH = sourceImage.getHeight();

        // compute uniform scale to fit image in canvas
        final double scaleX = (double) canvasW / imgW;
        final double scaleY = (double) canvasH / imgH;
        final double scale  = Math.min(scaleX, scaleY);

        // centre the image
        final double offX = (canvasW - imgW * scale) / 2.0;
        final double offY = (canvasH - imgH * scale) / 2.0;

        final AffineTransform saved = g.getTransform();
        g.translate(offX, offY);
        g.scale(scale, scale);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawStep(g);
        drawActiveOverlays(g);

        g.setTransform(saved);
    }

    // ---------------------------------------------------------- step dispatch

    /** Dispatches to the appropriate draw method for {@link #currentStep}. */
    private void drawStep(final Graphics2D g) {
        switch (currentStep) {
            case Pixels:          drawPixels(g);            break;
            case SimilarityGraph: drawSimilarityGraph(g);   break;
            case GraphLines:      drawGraphLines(g);        break;
            case GraphPolygons:   drawGraphPolygons(g);     break;
            case Splines:         drawGraphSplineFilled(g); break;
        }
    }

    /** Draws all active overlay flags. */
    private void drawActiveOverlays(final Graphics2D g) {
        if (paintSimilarityGraph      && currentStep != Step.SimilarityGraph) drawSimilarityGraphOverlay(g);
        if (paintSplineEdges)           drawSplineEdges(g);
        if (paintContourEdges)          drawContourEdges(g);
        if (paintImmutableSplineNodes)  drawImmutableSplineNodes(g);
        if (paintSplinePoints)          drawSplinePoints(g);
    }

    // -------------------------------------------------------------- step impls

    private void drawPixels(final Graphics2D g) {
        if (sourceImage != null) g.drawImage(sourceImage, 0, 0, null);
    }

    /**
     * Similarity graph as the primary step (pixels as bg, edges on top).
     */
    private void drawSimilarityGraph(final Graphics2D g) {
        drawPixels(g);
        drawSimilarityGraphOverlay(g);
    }

    /**
     * Similarity graph edges as overlay (can also be used on top of other steps).
     */
    private void drawSimilarityGraphOverlay(final Graphics2D g) {
        if (similarityGraph == null) return;
        g.setStroke(STROKE_GRAPH);
        final int w = similarityGraph.w;
        final int h = similarityGraph.h;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                final float xf  = x + 0.5f;
                final float yf  = y + 0.5f;
                final int   idx = similarityGraph.toIdx(x, y);

                if (similarityGraph.bottomLeft(idx)) {
                    g.setColor(x > 0 && similarityGraph.isConflicting(idx - 1) ? CONFLICT_COLOR : GRAPH_COLOR);
                    g.draw(new Line2D.Float(xf, yf, xf - 1, yf + 1));
                }
                if (similarityGraph.bottom(idx)) {
                    g.setColor(GRAPH_COLOR);
                    g.draw(new Line2D.Float(xf, yf, xf, yf + 1));
                }
                if (similarityGraph.bottomRight(idx)) {
                    g.setColor(x < w - 1 && similarityGraph.isConflicting(idx) ? CONFLICT_COLOR : GRAPH_COLOR);
                    g.draw(new Line2D.Float(xf, yf, xf + 1, yf + 1));
                }
                if (similarityGraph.right(idx)) {
                    g.setColor(GRAPH_COLOR);
                    g.draw(new Line2D.Float(xf, yf, xf + 1, yf));
                }
            }
        }
    }

    private void drawGraphLines(final Graphics2D g) {
        if (pixelGraph == null) return;
        g.setStroke(STROKE_THIN);
        for (final Polygon p : pixelGraph.polygons) {
            g.setColor(toAwtColor(p.color));
            float x0 = p.nodes.get(0).x;
            float y0 = p.nodes.get(0).y;
            for (int i = 1; i < p.nodes.size(); ++i) {
                final Node n = p.nodes.get(i);
                drawInsetLine(g, x0, y0, n.x, n.y);
                x0 = n.x;
                y0 = n.y;
            }
        }
    }

    private void drawGraphPolygons(final Graphics2D g) {
        if (pixelGraph == null) return;
        for (final Polygon p : pixelGraph.polygons) {
            g.setColor(toAwtColor(p.color));
            path.reset();
            path.moveTo(p.nodes.get(0).x, p.nodes.get(0).y);
            for (int i = 1; i < p.nodes.size(); ++i) {
                path.lineTo(p.nodes.get(i).x, p.nodes.get(i).y);
            }
            path.closePath();
            g.fill(path);
        }
    }

    private void drawGraphSplineFilled(final Graphics2D g) {
        if (pixelGraph == null) return;
        for (final Polygon p : pixelGraph.polygons) {
            g.setColor(toAwtColor(p.color));
            path.reset();
            Node last = p.nodes.get(0);
            path.moveTo(last.x, last.y);
            for (int i = 1; i < p.nodes.size(); ++i) {
                final Node current = p.nodes.get(i);

                final SplineSegment fwd = pixelGraph.nodesToSegments.get(
                        PixelGraph.directedFingerprint(last, current));
                if (fwd != null) {
                    final float[] b = fwd.bezierPoints;
                    path.curveTo(b[2], b[3], b[4], b[5], b[6], b[7]);
                    last = current;
                    continue;
                }

                final SplineSegment bwd = pixelGraph.nodesToSegments.get(
                        PixelGraph.directedFingerprint(current, last));
                if (bwd != null) {
                    final float[] b = bwd.bezierPoints;
                    path.curveTo(b[4], b[5], b[2], b[3], b[0], b[1]);
                    last = current;
                    continue;
                }

                path.lineTo(current.x, current.y);
                last = current;
            }
            path.closePath();
            g.fill(path);
        }
    }

    // ------------------------------------------------------------- overlays

    /** Rainbow-coloured spline polylines. */
    private void drawSplineEdges(final Graphics2D g) {
        if (pixelGraph == null) return;
        g.setStroke(STROKE_THIN);
        float hue = 0f;
        for (final Spline s : pixelGraph.splines) {
            hue = (hue + 20f) % 360f;
            g.setColor(java.awt.Color.getHSBColor(hue / 360f, 1f, 1f));
            path.reset();
            path.moveTo(s.nodes.get(0).x, s.nodes.get(0).y);
            for (int i = 1; i < s.nodes.size(); i++) {
                final Node n = s.nodes.get(i);
                path.lineTo(n.x, n.y);
            }
            g.draw(path);
        }
    }

    /** Contour edges drawn in red. */
    private void drawContourEdges(final Graphics2D g) {
        if (pixelGraph == null) return;
        g.setStroke(STROKE_THIN);
        g.setColor(CONTOUR_COLOR);
        for (final Edge e : pixelGraph.edges) {
            if (e.isContour) {
                drawInsetLine(g, e.start.x, e.start.y, e.end.x, e.end.y);
            }
        }
    }

    /** Green = optimisable, red = fixed. */
    private void drawImmutableSplineNodes(final Graphics2D g) {
        if (pixelGraph == null) return;
        for (final Node n : pixelGraph.coordsToNode.values()) {
            g.setColor(n.mayBeOptimized ? NODE_OPT_COLOR : NODE_FIX_COLOR);
            g.fill(new Ellipse2D.Float(n.x - 0.1f, n.y - 0.1f, 0.2f, 0.2f));
        }
    }

    /** Catmull-Rom sample points on each spline segment. */
    private final float[] splinePoints = new float[SplineSegment.pointCount * 2];
    private void drawSplinePoints(final Graphics2D g) {
        if (pixelGraph == null) return;
        g.setStroke(STROKE_SPLINE_PT);
        for (final SplineSegment s : pixelGraph.nodesToSegments.values()) {
            s.calculatePoints(splinePoints, 0);
            for (int i = 1; i < SplineSegment.pointCount; ++i) {
                final java.awt.Color c;
                if      (i == 1)                            c = java.awt.Color.GREEN;
                else if (i == SplineSegment.pointCount - 1) c = java.awt.Color.RED;
                else                                        c = java.awt.Color.LIGHT_GRAY;
                g.setColor(c);
                g.draw(new Line2D.Float(
                        splinePoints[(i-1)*2], splinePoints[(i-1)*2+1],
                        splinePoints[i*2],     splinePoints[i*2+1]));
            }
        }
    }

    // -------------------------------------------------------------- helpers

    private static final float INSET = 0.08f;
    private void drawInsetLine(final Graphics2D g,
                               float x0, float y0, float x1, float y1)
    {
        float dx = x1 - x0;
        float dy = y1 - y0;
        final float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0) return;
        dx /= len;  dy /= len;
        x0 -= INSET * dy;  x1 -= INSET * dy;
        y0 += INSET * dx;  y1 += INSET * dx;
        g.draw(new Line2D.Float(x0, y0, x1, y1));
    }

    /** Converts a packed ARGB int to {@link java.awt.Color}. */
    static java.awt.Color toAwtColor(final int argb) {
        if (argb == Color.TRANSPARENT) return new java.awt.Color(0, 0, 0, 0);
        final int a     = (argb >> 24) & 0xFF;
        final int r     = (argb >> 16) & 0xFF;
        final int green = (argb >>  8) & 0xFF;
        final int b     =  argb        & 0xFF;
        return new java.awt.Color(r, green, b, a);
    }
}
