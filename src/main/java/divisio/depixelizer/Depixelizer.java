package divisio.depixelizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class Depixelizer {

    private BufferedImage   image;
    private SimilarityGraph similarityGraph;
    private PixelGraph      pixelGraph;
    private String          svg;
    private long            lastStageStartInMs;

    public enum DepixelizeStage {
        ColorConversion, SimilarityGraph, ConflictsRemoval,
        PolygonAssembly, SplineAssembly, EnergyMinimization,
        SvgCreation
    };

    public Depixelizer() {}

    private void startStage() { lastStageStartInMs = System.currentTimeMillis(); }

    private long getStageTime() {
        return Math.max(0, System.currentTimeMillis() - lastStageStartInMs);
    }

    private boolean runStage(final Runnable operation, final DepixelizeStage stage,
                             final DepixelizationProgressCallback callback)
    {
        if (callback != null && callback.isCancelled()) { return false; }
        startStage();
        operation.run();
        if (callback != null) { callback.stageDone(stage, getStageTime()); }
        return true;
    }

    public BufferedImage getImage()             { return image; }
    public SimilarityGraph getSimilarityGraph() { return similarityGraph; }
    public PixelGraph getPixelGraph()           { return pixelGraph; }
    public String getSvg()                      { return svg; }

    /**
     * Runs the depixelization algorithm. This can be lengthy, so if you want to be able to
     * cancel this, use a callback to get feedback on the progress and cancel if it takes too long.
     *
     * @param image    the image to depixelize
     * @param callback progress / cancellation callback, may be null
     * @return true: depixelization was successful, false: process was cancelled
     */
    public boolean runDepixelization(final BufferedImage image,
                                     final DepixelizationProgressCallback callback)
    {
        this.image           = image;
        this.similarityGraph = new SimilarityGraph(image.getWidth(), image.getHeight());
        image.getRGB(0, 0, image.getWidth(), image.getHeight(),
                similarityGraph.getRgbaColors(),
                0, image.getWidth());
        this.pixelGraph = new PixelGraph();

        boolean ok = callback == null || !callback.isCancelled();

        if (ok) ok = runStage(() -> similarityGraph.colorConversion(),   DepixelizeStage.ColorConversion,   callback);
        if (ok) ok = runStage(() -> similarityGraph.buildGraph(),         DepixelizeStage.SimilarityGraph,   callback);
        if (ok) ok = runStage(() -> similarityGraph.removeConflicts(),    DepixelizeStage.ConflictsRemoval,  callback);
        if (ok) ok = runStage(() -> similarityGraph.fillPixelGraph(pixelGraph), DepixelizeStage.PolygonAssembly, callback);
        if (ok) ok = runStage(() -> pixelGraph.assembleSplines(),         DepixelizeStage.SplineAssembly,    callback);
        if (ok) ok = runStage(() -> pixelGraph.optimizeNodes(callback),   DepixelizeStage.EnergyMinimization, callback);
        if (ok) ok = runStage(() -> this.svg = SvgRenderer.draw(pixelGraph, image.getWidth(), image.getHeight()),
                                                                          DepixelizeStage.SvgCreation, callback);
        return ok;
    }

    private static void writeSvg(final String svg, final File file) throws IOException {
        try (final BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)))
        {
            out.write(svg);
        }
    }

    public static void main(final String[] args) {
        // parse flags
        boolean debug     = false;
        String  inputPath = null;
        for (final String arg : args) {
            if ("-d".equals(arg) || "--debug".equals(arg)) {
                debug = true;
            } else {
                inputPath = arg;
            }
        }

        if (inputPath == null) {
            System.out.println("Usage: Depixelizer [-d|--debug] FILE_NAME");
            System.out.println("  -d, --debug   also write debug PNGs for each processing step");
            System.exit(0);
        }

        final File input = new File(inputPath);
        if (!input.isFile()) {
            System.err.println("Cannot read " + input);
            System.exit(-1);
        }

        try {
            final BufferedImage image       = ImageIO.read(input);
            final Depixelizer   depixelizer = new Depixelizer();
            final String        basePath    = input.getAbsolutePath();

            // build callback — debug mode adds PNG rendering after each stage/iteration
            final DepixelizationProgressCallback callback = debug
                    ? DepixelizationProgressCallback.debugCallback(depixelizer, image, basePath)
                    : DepixelizationProgressCallback.SYSTEM_OUT_CALLBACK;

            depixelizer.runDepixelization(image, callback);

            // write SVG output
            writeSvg(depixelizer.svg, new File(basePath + ".svg"));
        } catch (final Throwable t) {
            System.err.println("Depixelization failed: " + t.getLocalizedMessage());
            t.printStackTrace();
            System.exit(-2);
        }
    }
}
