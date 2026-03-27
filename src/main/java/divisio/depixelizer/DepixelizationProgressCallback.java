package divisio.depixelizer;

import divisio.depixelizer.Depixelizer.DepixelizeStage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.logging.Logger;

public interface DepixelizationProgressCallback {
    boolean isCancelled();

    void stageDone(DepixelizeStage stage, long durationInMs);

    void energyOptimizationProgress(final float progress);

    /** Prints each stage and energy-optimization step to stdout. */
    DepixelizationProgressCallback SYSTEM_OUT_CALLBACK =
        new DepixelizationProgressCallback() {
            @Override
            public boolean isCancelled() { return false; }

            @Override
            public void stageDone(final DepixelizeStage stage, long durationInMs) {
                System.out.println(stage + " done, took: " + durationInMs + "ms");
            }

            @Override
            public void energyOptimizationProgress(float progress) {
                System.out.println("Energy Optimization: " +
                        String.format("%.2f", progress * 100) + "%");
            }
        };

    /** Logs each stage and energy-optimization step via java.util.logging. */
    DepixelizationProgressCallback LOGGER_CALLBACK =
        new DepixelizationProgressCallback() {
            private final Logger log = Logger.getLogger(DepixelizationProgressCallback.class.getName());

            @Override
            public boolean isCancelled() { return false; }

            @Override
            public void stageDone(final DepixelizeStage stage, long durationInMs) {
                log.info(stage + " done, took: " + durationInMs + "ms");
            }

            @Override
            public void energyOptimizationProgress(float progress) {
                log.fine("Energy Optimization: " +
                        String.format("%.2f", progress * 100) + "%");
            }
        };

    /**
     * Builds a callback that writes debug PNG images alongside normal console output.
     *
     * <p>Files written (N = iteration index):
     * <pre>
     *   basePath.1.png   — raw pixels (after ColorConversion)
     *   basePath.2.png   — similarity graph (after ConflictsRemoval)
     *   basePath.3.png   — polygon outlines (after PolygonAssembly)
     *   basePath.4.png   — filled polygons, straight edges (after PolygonAssembly)
     *   basePath.5.N.png — spline state after optimisation iteration N
     *   basePath.5.png   — final splines (after SvgCreation)
     * </pre>
     *
     * @param depixelizer  the running depixelizer (graph refs are read lazily)
     * @param image        the source image used for rendering
     * @param basePath     base output path (i.e. the input file's absolute path)
     */
    static DepixelizationProgressCallback debugCallback(final Depixelizer depixelizer,
                                                        final BufferedImage image,
                                                        final String basePath)
    {
        // iteration counter for optimization snapshots
        final int[] iterationCount = {0};

        return new DepixelizationProgressCallback() {
            @Override
            public boolean isCancelled() { return false; }

            @Override
            public void stageDone(final DepixelizeStage stage, final long durationInMs) {
                SYSTEM_OUT_CALLBACK.stageDone(stage, durationInMs);

                final DebugRenderer dbg = new DebugRenderer(
                        image, depixelizer.getSimilarityGraph(), depixelizer.getPixelGraph());
                try {
                    switch (stage) {
                        case ColorConversion:
                            dbg.writeSingleStep(DebugRenderer.Step.Pixels,          basePath + ".1.png");
                            break;
                        case SimilarityGraph:
                        case ConflictsRemoval:
                            // re-render after conflicts are resolved so the final graph is shown
                            dbg.writeSingleStep(DebugRenderer.Step.SimilarityGraph, basePath + ".2.png");
                            break;
                        case PolygonAssembly:
                            dbg.writeSingleStep(DebugRenderer.Step.GraphLines,      basePath + ".3.png");
                            dbg.writeSingleStep(DebugRenderer.Step.GraphPolygons,   basePath + ".4.png");
                            break;
                        case SvgCreation:
                            dbg.writeSingleStep(DebugRenderer.Step.Splines,         basePath + ".5.png");
                            break;
                        default:
                            break;
                    }
                } catch (final IOException e) {
                    System.err.println("Failed to write debug image: " + e.getMessage());
                }
            }

            @Override
            public void energyOptimizationProgress(final float progress) {
                SYSTEM_OUT_CALLBACK.energyOptimizationProgress(progress);

                final DebugRenderer dbg = new DebugRenderer(
                        image, depixelizer.getSimilarityGraph(), depixelizer.getPixelGraph());
                try {
                    dbg.writeSingleStep(DebugRenderer.Step.Splines,
                            basePath + ".5." + iterationCount[0] + ".png");
                } catch (final IOException e) {
                    System.err.println("Failed to write optimization snapshot: " + e.getMessage());
                }
                iterationCount[0]++;
            }
        };
    }
}
