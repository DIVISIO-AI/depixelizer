package divisio.depixelizer;

import divisio.depixelizer.Depixelizer.DepixelizeStage;
import divisio.depixelizer.DebugRenderer.Step;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Standalone Swing UI for the Depixelizer.
 *
 * Layout:
 *   WEST  — progress panel (stage list + overlay toggles)
 *   CENTER — zoomable canvas
 *   SOUTH  — status bar
 *
 * Depixelization runs on a background SwingWorker thread.
 * The canvas is updated after each stage via {@link DepixelizationProgressCallback}.
 */
public class SwingUi extends JFrame {

    // ----------------------------------------------------------------- canvas

    /**
     * Canvas that delegates all painting to {@link DebugRenderer#drawOnto}.
     * Repaints on demand; no off-screen buffer needed because DebugRenderer is fast.
     */
    private static final class DepixelCanvas extends JPanel {
        final DebugRenderer renderer = new DebugRenderer();

        DepixelCanvas() {
            setBackground(java.awt.Color.DARK_GRAY);
            setPreferredSize(new Dimension(600, 600));
        }

        @Override
        protected void paintComponent(final Graphics g) {
            super.paintComponent(g);
            renderer.drawOnto((Graphics2D) g, getWidth(), getHeight());
        }
    }

    // --------------------------------------------------------------- fields

    private final DepixelCanvas  canvas       = new DepixelCanvas();
    private final JProgressBar   progressBar  = new JProgressBar(0, 1000);
    private final JLabel         statusLabel  = new JLabel("Load an image to start.");

    // stage timing labels — one per stage
    private final JLabel[] stageLabels = new JLabel[DepixelizeStage.values().length];
    private final JLabel[] timeLabels  = new JLabel[DepixelizeStage.values().length];

    // toolbar buttons
    private final JButton loadButton    = new JButton("Open image…");
    private final JButton saveSvgButton = new JButton("Save SVG…");

    // step radio buttons
    private final ButtonGroup stepGroup  = new ButtonGroup();
    private final JRadioButton[] stepRadios = new JRadioButton[Step.values().length];

    // overlay checkboxes
    private final JCheckBox cbSimilarityGraph = new JCheckBox("Similarity graph");
    private final JCheckBox cbSplineEdges     = new JCheckBox("Spline edges");
    private final JCheckBox cbContourEdges    = new JCheckBox("Contour edges");
    private final JCheckBox cbFixedNodes      = new JCheckBox("Fixed nodes");
    private final JCheckBox cbSplinePoints    = new JCheckBox("Spline points");

    private volatile String  currentSvg      = null;
    private volatile boolean processing      = false;
    private volatile File    lastLoadedFile  = null;

    /** Last directory visited via any file chooser — persisted across open/save. */
    private File lastDirectory = null;

    // ---------------------------------------------------------------- ctor

    public SwingUi() {
        super("Depixelizer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        buildUi();
        setControls(false);
        pack();
        setLocationRelativeTo(null);
    }

    // --------------------------------------------------------------- build UI

    private void buildUi() {
        // ---- toolbar ----
        loadButton.addActionListener(e -> onLoad());
        saveSvgButton.addActionListener(e -> onSaveSvg());
        final JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(loadButton);
        toolbar.addSeparator();
        toolbar.add(saveSvgButton);

        // ---- west panel ----
        final JPanel west = new JPanel();
        west.setLayout(new BoxLayout(west, BoxLayout.Y_AXIS));
        west.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        west.add(buildStagePanel());
        west.add(Box.createVerticalStrut(8));
        west.add(buildStepPanel());
        west.add(Box.createVerticalStrut(8));
        west.add(buildOverlayPanel());
        west.add(Box.createVerticalGlue());

        // ---- south ----
        final JPanel south = new JPanel(new BorderLayout(4, 0));
        south.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        south.add(progressBar, BorderLayout.CENTER);
        south.add(statusLabel, BorderLayout.EAST);

        // ---- layout ----
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(toolbar,      BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(canvas, JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                                              JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        getContentPane().add(west,         BorderLayout.WEST);
        getContentPane().add(south,        BorderLayout.SOUTH);
    }

    /** Stage checklist panel — stage name + elapsed time. */
    private JPanel buildStagePanel() {
        final JPanel p = new JPanel(new GridLayout(0, 2, 4, 2));
        p.setBorder(new TitledBorder("Progress"));
        final DepixelizeStage[] stages = DepixelizeStage.values();
        for (int i = 0; i < stages.length; i++) {
            final JLabel name = new JLabel(stages[i].name());
            name.setForeground(java.awt.Color.GRAY);
            final JLabel time = new JLabel("—");
            time.setHorizontalAlignment(SwingConstants.RIGHT);
            time.setForeground(java.awt.Color.GRAY);
            stageLabels[i] = name;
            timeLabels[i]  = time;
            p.add(name);
            p.add(time);
        }
        return p;
    }

    /** Processing-step radio-button panel. */
    private JPanel buildStepPanel() {
        final JPanel p = new JPanel(new GridLayout(0, 1));
        p.setBorder(new TitledBorder("Display step"));
        final Step[] steps = Step.values();
        for (int i = 0; i < steps.length; i++) {
            final Step step = steps[i];
            final JRadioButton rb = new JRadioButton(step.name());
            rb.setEnabled(false);
            rb.addActionListener(e -> {
                canvas.renderer.currentStep = step;
                canvas.repaint();
            });
            stepGroup.add(rb);
            stepRadios[i] = rb;
            p.add(rb);
        }
        return p;
    }

    /** Overlay-toggle checkboxes panel. */
    private JPanel buildOverlayPanel() {
        final JPanel p = new JPanel(new GridLayout(0, 1));
        p.setBorder(new TitledBorder("Overlays"));

        final JCheckBox[] boxes = {
            cbSimilarityGraph, cbSplineEdges, cbContourEdges,
            cbFixedNodes, cbSplinePoints
        };
        for (final JCheckBox cb : boxes) {
            cb.setEnabled(false);
            cb.addActionListener(e -> syncOverlays());
            p.add(cb);
        }
        return p;
    }

    // ---------------------------------------------------------------- actions

    /**
     * Creates a JFileChooser with New-Folder / Delete / Rename buttons hidden.
     * The UIManager "readOnly" key is L&F-dependent, so we walk the component
     * tree instead and hide any AbstractButton whose action command matches the
     * three file-management actions.
     */
    private JFileChooser readOnlyChooser() {
        final JFileChooser c = new JFileChooser();
        hideFileManagementButtons(c);
        return c;
    }

    /** Recursively hides New Folder / Delete / Rename action buttons. */
    private static void hideFileManagementButtons(final Container root) {
        for (final Component comp : root.getComponents()) {
            if (comp instanceof AbstractButton) {
                final String cmd = ((AbstractButton) comp).getActionCommand();
                if ("New Folder".equals(cmd) || "Delete File".equals(cmd) || "Rename File".equals(cmd)) {
                    comp.setVisible(false);
                }
            }
            if (comp instanceof Container) hideFileManagementButtons((Container) comp);
        }
    }

    private void onLoad() {
        if (processing) return;
        final JFileChooser chooser = readOnlyChooser();
        chooser.setDialogTitle("Open pixel-art image");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Image files", "png", "gif", "bmp", "jpg", "jpeg"));
        // restore last visited directory
        if (lastDirectory != null) chooser.setCurrentDirectory(lastDirectory);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        final File selected = chooser.getSelectedFile();
        lastDirectory = selected.getParentFile();
        loadAndProcess(selected);
    }

    private void onSaveSvg() {
        final String svg = currentSvg;
        if (svg == null) return;
        final JFileChooser chooser = readOnlyChooser();
        chooser.setDialogTitle("Save SVG");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("SVG files", "svg"));
        // restore last visited directory
        if (lastDirectory != null) chooser.setCurrentDirectory(lastDirectory);
        // pre-fill a sensible filename: replace the source extension with .svg
        if (lastLoadedFile != null) {
            final String srcName = lastLoadedFile.getName();
            final int dot = srcName.lastIndexOf('.');
            final String svgName = (dot >= 0 ? srcName.substring(0, dot) : srcName) + ".svg";
            chooser.setSelectedFile(new File(lastDirectory != null ? lastDirectory : lastLoadedFile.getParentFile(), svgName));
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (!file.getName().endsWith(".svg")) file = new File(file.getAbsolutePath() + ".svg");
        lastDirectory = file.getParentFile();
        try (final BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            w.write(svg);
            statusLabel.setText("SVG saved: " + file.getName());
        } catch (final IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to save SVG:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Syncs overlay checkboxes → renderer flags → repaint. */
    private void syncOverlays() {
        final DebugRenderer r      = canvas.renderer;
        r.paintSimilarityGraph      = cbSimilarityGraph.isSelected();
        r.paintSplineEdges          = cbSplineEdges.isSelected();
        r.paintContourEdges         = cbContourEdges.isSelected();
        r.paintImmutableSplineNodes = cbFixedNodes.isSelected();
        r.paintSplinePoints         = cbSplinePoints.isSelected();
        canvas.repaint();
    }

    /** Enables/disables interactive controls. */
    private void setControls(final boolean enabled) {
        saveSvgButton.setEnabled(enabled && currentSvg != null);
        for (final JRadioButton rb : stepRadios)   rb.setEnabled(enabled);
        for (final JCheckBox    cb : new JCheckBox[]{
                cbSimilarityGraph, cbSplineEdges, cbContourEdges,
                cbFixedNodes, cbSplinePoints})      cb.setEnabled(enabled);
    }

    // --------------------------------------------------------------- loading

    /**
     * Loads {@code file} and runs depixelization on a SwingWorker thread,
     * posting UI updates on the EDT after each stage.
     */
    private void loadAndProcess(final File file) {
        // remember file for SVG pre-fill
        lastLoadedFile = file;

        // reset UI
        currentSvg = null;
        processing = true;
        progressBar.setValue(0);
        statusLabel.setText("Processing " + file.getName() + "…");
        setControls(false);
        resetStageLabels();

        // show the first step while processing starts
        canvas.renderer.currentStep = Step.Pixels;
        canvas.renderer.sourceImage = null;
        canvas.renderer.similarityGraph = null;
        canvas.renderer.pixelGraph      = null;
        canvas.repaint();

        // select first radio
        if (stepRadios.length > 0) stepRadios[0].setSelected(true);

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                final BufferedImage image = ImageIO.read(file);
                if (image == null) throw new IOException("Unsupported image format: " + file.getName());

                // show pixels immediately
                SwingUtilities.invokeLater(() -> {
                    canvas.renderer.sourceImage = image;
                    canvas.renderer.currentStep = Step.Pixels;
                    canvas.repaint();
                });

                final Depixelizer depixelizer = new Depixelizer();
                depixelizer.runDepixelization(image, new DepixelizationProgressCallback() {
                    private int stageIdx = 0;

                    @Override public boolean isCancelled() { return false; }

                    @Override
                    public void stageDone(final DepixelizeStage stage, final long durationInMs) {
                        final int idx = stage.ordinal();
                        SwingUtilities.invokeLater(() -> {
                            // update stage row
                            stageLabels[idx].setForeground(java.awt.Color.BLACK);
                            timeLabels[idx].setForeground(java.awt.Color.BLACK);
                            timeLabels[idx].setText(durationInMs < 1000
                                    ? durationInMs + " ms"
                                    : String.format("%.1f s", durationInMs / 1000f));

                            // update renderer state & display step
                            canvas.renderer.similarityGraph = depixelizer.getSimilarityGraph();
                            canvas.renderer.pixelGraph      = depixelizer.getPixelGraph();
                            canvas.renderer.currentStep     = stepForStage(stage);
                            // sync overlays for new stage
                            setAutoOverlaysForStage(stage);

                            // update radio selection
                            final int stepIdx = canvas.renderer.currentStep.ordinal();
                            if (stepIdx < stepRadios.length) stepRadios[stepIdx].setSelected(true);

                            // overall progress (simple fraction: ordinal / total)
                            final int total = DepixelizeStage.values().length;
                            progressBar.setValue((idx + 1) * 1000 / total);

                            canvas.repaint();
                        });
                    }

                    @Override
                    public void energyOptimizationProgress(final float progress) {
                        SwingUtilities.invokeLater(() -> {
                            // keep progress bar moving during optimisation
                            final int baseProgress = DepixelizeStage.EnergyMinimization.ordinal() * 1000
                                    / DepixelizeStage.values().length;
                            final int stageWidth   = 1000 / DepixelizeStage.values().length;
                            progressBar.setValue(baseProgress + (int)(progress * stageWidth));
                            canvas.renderer.pixelGraph = depixelizer.getPixelGraph();
                            canvas.repaint();
                        });
                    }
                });

                // store SVG
                currentSvg = depixelizer.getSvg();
                return null;
            }

            @Override
            protected void done() {
                processing = false;
                progressBar.setValue(1000);
                try {
                    get(); // rethrow if exception
                    // final render
                    canvas.renderer.currentStep = Step.Splines;
                    final int lastIdx = stepRadios.length - 1;
                    if (lastIdx >= 0) stepRadios[lastIdx].setSelected(true);
                    canvas.repaint();
                    setControls(true);
                    saveSvgButton.setEnabled(true);
                    statusLabel.setText("Done — " + file.getName());
                } catch (final Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                    JOptionPane.showMessageDialog(SwingUi.this,
                            "Processing failed:\n" + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    // -------------------------------------------------------- stage → step mapping

    /**
     * Maps each processing stage to the most informative display step,
     * matching Android {@code DepixelizeActivity.depixelizingProgress()}.
     */
    private static Step stepForStage(final DepixelizeStage stage) {
        switch (stage) {
            case ColorConversion:   return Step.Pixels;
            case SimilarityGraph:   return Step.Pixels;        // conflicts not yet resolved
            case ConflictsRemoval:  return Step.SimilarityGraph;
            case PolygonAssembly:   return Step.GraphPolygons;
            case SplineAssembly:    return Step.GraphPolygons;  // + contour overlay
            case EnergyMinimization:return Step.Splines;
            case SvgCreation:       return Step.Splines;
            default:                return Step.Splines;
        }
    }

    /**
     * Sets auto-overlays to match the Android app's per-stage behaviour.
     */
    private void setAutoOverlaysForStage(final DepixelizeStage stage) {
        // reset all overlays first
        cbSimilarityGraph.setSelected(false);
        cbContourEdges.setSelected(false);
        cbFixedNodes.setSelected(false);
        cbSplineEdges.setSelected(false);
        cbSplinePoints.setSelected(false);

        switch (stage) {
            case ConflictsRemoval:
                // show similarity graph overlay
                cbSimilarityGraph.setSelected(true);
                break;
            case SplineAssembly:
                // show contour & fixed-node overlays
                cbContourEdges.setSelected(true);
                cbFixedNodes.setSelected(true);
                break;
            default:
                break;
        }
        syncOverlays();
    }

    private void resetStageLabels() {
        for (int i = 0; i < stageLabels.length; i++) {
            stageLabels[i].setForeground(java.awt.Color.GRAY);
            timeLabels[i].setForeground(java.awt.Color.GRAY);
            timeLabels[i].setText("—");
        }
    }

    // ------------------------------------------------------------- main entry

    /** Launches the Swing UI on the EDT. */
    public static void main(final String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (final Exception ignored) {}
            final SwingUi ui = new SwingUi();
            // if a file was passed as argument, open it directly
            if (args.length > 0) {
                final File f = new File(args[0]);
                if (f.isFile()) ui.loadAndProcess(f);
            }
            ui.setVisible(true);
        });
    }
}
