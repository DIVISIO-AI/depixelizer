package divisio.depixelizer;

public class SimilarityGraph {

    public static final int TOP_RIGHT = 0;
    public static final int BOTTOM_RIGHT = 1;
    public static final int BOTTOM_LEFT = 2;
    public static final int TOP_LEFT = 3;


    private final boolean[] edges;
    private final boolean[] tempEdges;
    private final int[]   rgbaColors;
    private final float[] ycbcrColors;
    protected final int w;
    protected final int h;
    private final int[] neighbors = new int[8];//we re-use this to avoid GC
    private final PixelQueue queue;

    public SimilarityGraph(final int w, final int h) {
        this.w = w;
        this.h = h;
        this.edges = new boolean[w * h * 4];
        this.tempEdges = new boolean[w * h * 4];
        this.ycbcrColors = new float[w * h * 4];
        this.rgbaColors = new int[w * h];
        this.queue = new PixelQueue(w * h);
    }

    private int toX(final int idx) {
        return idx % w;
    }

    private int toY(final int idx) {
        return idx / w;
    }

    protected int toIdx(final int x, final int y) {
        if (x < 0 || x > w - 1 || y < 0 || y > h - 1) return -1;
        return y * w + x;
    }

    private static boolean isFirstRow(final int y) {
        return y <= 0;
    }

    private boolean isLastRow(final int y) {
        return y >= h - 1;
    }

    private static boolean isFirstColumn(final int x) {
        return x <= 0;
    }

    private boolean isLastColumn(final int x) {
        return x >= w - 1;
    }

    protected boolean bottomLeft(final int idx) {
        return edges[idx * 4];
    }
    protected boolean bottom(final int idx) {
        return edges[idx * 4 + 1];
    }
    protected boolean bottomRight(final int idx) {
        return edges[idx * 4 + 2];
    }
    protected boolean right(final int idx) {
        return edges[idx * 4 + 3];
    }

    private void setBottomLeft(final int idx, final boolean b) {
        edges[idx * 4] = b;
    }
    private void removeBottomLeftTemp(final int idx) {
        tempEdges[idx * 4] = false;
    }
    private void setBottom(final int idx, final boolean b) {
        edges[idx * 4 + 1] = b;
    }
    private void setBottomRight(final int idx, final boolean b) {
        edges[idx * 4 + 2] = b;
    }
    private void removeBottomRightTemp(final int idx) {
        tempEdges[idx * 4 + 2] = false;
    }
    private void setRight(final int idx, final boolean b) {
        edges[idx * 4 + 3] = b;
    }

    public int[] getRgbaColors() {
        return this.rgbaColors;
    }

    private static void rgbaToYCbCrA(final int rgba, final float[] ycbcra, final int idx) {
        ycbcra[idx] = 0.299f * Color.red(rgba) + 0.587f * Color.green(rgba) + 0.114f * Color.blue(rgba);
        ycbcra[idx + 1] = (Color.blue(rgba) - ycbcra[idx]) * 0.493f;
        ycbcra[idx + 2] = (Color.red(rgba)  - ycbcra[idx]) * 0.877f;
        ycbcra[idx + 3] = Color.alpha(rgba);
    }

    private boolean areSimilarColors(int idx1, int idx2) {
        idx1 *= 4;
        idx2 *= 4;

        if (idx1 < 0 || idx2 < 0 ||
            idx1 + 3 > ycbcrColors.length - 1 ||
            idx2 + 3 > ycbcrColors.length - 1)
        {
            return false;
        }


        if (Math.abs(ycbcrColors[idx1++] - ycbcrColors[idx2++]) >= 48f)   return false;
        if (Math.abs(ycbcrColors[idx1++] - ycbcrColors[idx2++]) >= 7.25f) return false;
        if (Math.abs(ycbcrColors[idx1++] - ycbcrColors[idx2++]) >= 6.25f) return false;
        if (Math.abs(ycbcrColors[idx1  ] - ycbcrColors[idx2  ]) >= 48f)   return false;

        return true;
    }

    private boolean isFlatShaded(final int idx) {
        return bottom(idx) &&
               bottomRight(idx) &&
               right(idx) &&
               bottomLeft(idx + 1) &&
               bottom(idx + 1) &&
               right(idx + w);
    }

    protected boolean isConflicting(final int idx) {
        return bottomRight(idx) && bottomLeft(idx + 1);
    }

    private int neighbors(final int idx, final int[] result) {
        int valenceCount = 0;
        final int x = toX(idx);
        final int y = toY(idx);
        final int previousRow = idx - w;
        final int nextRow = idx + w;

        if (bottomLeft (idx)) result[valenceCount++] = nextRow - 1;
        if (bottom     (idx)) result[valenceCount++] = nextRow;
        if (bottomRight(idx)) result[valenceCount++] = nextRow + 1;
        if (right      (idx)) result[valenceCount++] = idx + 1;

        final boolean isFirstRow    = isFirstRow(y);
        final boolean isFirstColumn = isFirstColumn(x);
        final boolean isLastColumn  = isLastColumn(x);

        if (!isFirstColumn &&                right      (idx         - 1)) result[valenceCount++] = idx - 1;
        if (!isFirstColumn && !isFirstRow && bottomRight(previousRow - 1)) result[valenceCount++] = previousRow - 1;
        if (                  !isFirstRow && bottom     (previousRow    )) result[valenceCount++] = previousRow;
        if (!isLastColumn  && !isFirstRow && bottomLeft (previousRow + 1)) result[valenceCount++] = previousRow + 1;

        return valenceCount;
    }

    private int valence(final int pixel) {
        return neighbors(pixel, neighbors);
    }


    private void buildSimilarityEdges(final int pixel) {

        final int x = toX(pixel);
        final int y = toY(pixel);

        final boolean isFirstColumn = isFirstColumn(x);
        final boolean isLastColumn  = isLastColumn(x);
        final boolean isLastRow     = isLastRow(y);
        final int nextRow = pixel + w;
        //try {

        setBottomLeft (pixel, !isFirstColumn && !isLastRow && areSimilarColors(pixel, nextRow - 1));
        setBottom     (pixel,                   !isLastRow && areSimilarColors(pixel, nextRow    ));
        setBottomRight(pixel, !isLastColumn  && !isLastRow && areSimilarColors(pixel, nextRow + 1));
        setRight      (pixel, !isLastColumn  &&               areSimilarColors(pixel, pixel   + 1));
        /*
        } catch (final ArrayIndexOutOfBoundsException e) {
            Log.e("~~~~~~~~~~~~~~~~~~~~~~~~", "x = " + x + ", y = " + y + ", first column? " + isFirstColumn + ", last column? " + isLastColumn + ", last row? " + isLastRow);
            throw e;
        }
        */
    }

    private int islandHeuristic(final int pixel1, final int pixel2) {
        if (valence(pixel1) == 1) return 1;
        if (valence(pixel2) == 1) return 1;
        return 0;
    }


    private int curveHeuristic(final int pixel1, final int pixel2) {
        queue.reset();
        int visitedCount = 0;

        if (valence(pixel1) <= 2) {
            queue.enqueue(pixel1);
        }
        if (valence(pixel2) <= 2) {
            queue.enqueue(pixel2);
        }

        while(!queue.isEmpty()) {
            final int pixel = queue.dequeue();
            final int neighborCount = neighbors(pixel, neighbors);
            for (int i = 0; i < neighborCount; i++) {
                final int neighbor = neighbors[i];
                if (valence(neighbor) <= 2) {
                    queue.enqueue(neighbor);
                }
            }
            ++visitedCount;
        }

        return visitedCount;
    }

    private int areaHeuristic(final int range, final int pixel1, final int pixel2) {
        queue.reset();
        int visitedCount = 0;
        final int x = Math.min(toX(pixel1), toX(pixel2));
        final int y = Math.min(toY(pixel1), toY(pixel2));

        final int minX = x - range;
        final int maxX = x + range + 1;
        final int minY = y - range;
        final int maxY = y + range + 1;

        queue.enqueue(pixel1);
        queue.enqueue(pixel2);

        while(!queue.isEmpty()) {
            final int pixel = queue.dequeue();
            final int neighborCount = neighbors(pixel, neighbors);
            for (int i = 0; i < neighborCount; i++) {
                final int neighbor = neighbors[i];
                final int neighborX = toX(neighbor);
                final int neighborY = toY(neighbor);

                if (neighborX > minX &&
                    neighborX < maxX &&
                    neighborY > minY &&
                    neighborY < maxY
                   ) {
                    queue.enqueue(neighbor);
                }
            }
            ++visitedCount;
        }

        return visitedCount;
    }

    private int heuristic(final int pixel1, final int pixel2) {
        final int islandHeuristic = 5 * islandHeuristic(pixel1, pixel2);
        final int curveHeuristic  = 3 * curveHeuristic(pixel1, pixel2);
        final int areaHeuristic   = -1 * areaHeuristic(4, pixel1, pixel2);
        final int sum = islandHeuristic + curveHeuristic + areaHeuristic;
        //Log.d("%%%%%%%%%%%%%%%%%%%%%%%", pixel1 + "/" + pixel2 + ", island: " + islandHeuristic + ", curve: " + curveHeuristic + ", area: " + areaHeuristic + ", sum=" + sum);
        return sum;
    }

    private boolean edgeExists(final int pixel1, final int pixel2) {
        if (pixel1 < 0 || pixel2 < 0 ||
            pixel1 >= rgbaColors.length || pixel2 >= rgbaColors.length) return false;

        final int u = Math.min(pixel1, pixel2);
        final int v = Math.max(pixel1, pixel2);
        final int nextRow = u + w;

        if (nextRow - 1 == v) return bottomLeft(u);
        if (nextRow     == v) return bottom(u);
        if (nextRow + 1 == v) return bottomRight(u);
        if (u + 1     == v)   return right(u);

        return false;
    }

    private EdgeType getEdgeType(final int center, final int topRight, final int top, final int right) {
        if (edgeExists(center, topRight)) return EdgeType.Cross;
        if (edgeExists(top, right))       return EdgeType.Tangent;

        return EdgeType.None;
    }

    private void fillEdgeTypes(final int idx, final EdgeType[] types) {
        final int previousRow = idx - w;
        final int nextRow = idx + w;

        //final int x = toX(idx);
        //final int y = toY(idx);
        /*
        final boolean isFirstRow    = isFirstRow(y);
        final boolean isFirstColumn = isFirstColumn(x);
        final boolean isLastColumn  = isLastColumn(x);
        final boolean isLastRow     = isLastRow(y);
        */
        //top right
        types[TOP_RIGHT] = getEdgeType(idx, previousRow + 1, previousRow, idx + 1);
        //bottom right
        types[BOTTOM_RIGHT] = getEdgeType(idx, nextRow     + 1, idx + 1,     nextRow);
        //bottom left
        types[BOTTOM_LEFT] = getEdgeType(idx, nextRow     - 1, nextRow,     idx - 1);
        //top left
        types[TOP_LEFT] = getEdgeType(idx, previousRow - 1, idx - 1,     previousRow);
    }

    final EdgeType[] types = new EdgeType[4];

    /*
    private int getColor(final int x, final int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) return Color.TRANSPARENT;
        return rgbaColors[toIdx(x, y)];
    }
    */

    public static class Coords {
        public int x0;
        public int y0;
        public int x1;
        public int y1;

        public Coords(final int x, final int y) {
            x0 = x;
            y0 = y;
            x1 = x;
            y1 = y;
        }

        public void push(final int x, final int y) {
            x0 = x1;
            y0 = y1;
            x1 = x;
            y1 = y;
        }
    }

    final Coords coords = new Coords(0,0);
    private void fillCoords(final PixelGraph g, final int idx) {
        fillEdgeTypes(idx, types);

        final int x = toX(idx);
        final int y = toY(idx);

        final int xc = 4 * x + 2;
        final int yc = 4 * y + 2;

        final int s = 1;
        final int m = 2;
        final int l = 3;

        switch(types[TOP_LEFT]) {
            case Cross:   coords.push(xc - l, yc - s);
                          coords.push(xc - s, yc - l); g.addEdge(coords, toIdx(x - 1, y - 1), idx, rgbaColors, ycbcrColors); break;
            case Tangent: coords.push(xc - s, yc - s); break;
            case None:    coords.push(xc - m, yc - m); break;
        }
        switch(types[TOP_RIGHT]) {
            case Cross:   coords.push(xc + s, yc - l); g.addEdge(coords, toIdx(x, y-1), idx, rgbaColors, ycbcrColors);
                          coords.push(xc + l, yc - s); g.addEdge(coords, toIdx(x + 1, y - 1), idx, rgbaColors, ycbcrColors); break;
            case Tangent: coords.push(xc + s, yc - s); g.addEdge(coords, toIdx(x, y-1), idx, rgbaColors, ycbcrColors); break;
            case None:    coords.push(xc + m, yc - m); g.addEdge(coords, toIdx(x, y-1), idx, rgbaColors, ycbcrColors); break;
        }
        switch(types[BOTTOM_RIGHT]) {
            case Cross:   coords.push(xc + l, yc + s); g.addEdge(coords, toIdx(x + 1, y), idx, rgbaColors, ycbcrColors);
                          coords.push(xc + s, yc + l); g.addEdge(coords, toIdx(x + 1, y + 1), idx, rgbaColors, ycbcrColors); break;
            case Tangent: coords.push(xc + s, yc + s); g.addEdge(coords, toIdx(x + 1, y), idx, rgbaColors, ycbcrColors); break;
            case None:    coords.push(xc + m, yc + m); g.addEdge(coords, toIdx(x + 1, y), idx, rgbaColors, ycbcrColors); break;
        }
        switch(types[BOTTOM_LEFT]) {
            case Cross:   coords.push(xc - s, yc + l); g.addEdge(coords, toIdx(x, y + 1), idx, rgbaColors, ycbcrColors);
                          coords.push(xc - l, yc + s); g.addEdge(coords, toIdx(x - 1, y + 1), idx, rgbaColors, ycbcrColors); break;
            case Tangent: coords.push(xc - s, yc + s); g.addEdge(coords, toIdx(x, y + 1), idx, rgbaColors, ycbcrColors); break;
            case None:    coords.push(xc - m, yc + m); g.addEdge(coords, toIdx(x, y + 1), idx, rgbaColors, ycbcrColors); break;
        }
        switch(types[TOP_LEFT]) {
            case Cross:   coords.push(xc - l, yc - s); g.addEdge(coords, toIdx(x - 1, y), idx, rgbaColors, ycbcrColors); break;
            case Tangent: coords.push(xc - s, yc - s); g.addEdge(coords, toIdx(x - 1, y), idx, rgbaColors, ycbcrColors); break;
            case None:    coords.push(xc - m, yc - m); g.addEdge(coords, toIdx(x - 1, y), idx, rgbaColors, ycbcrColors); break;
        }
    }

    public void colorConversion() {
        int ycbcraIdx = 0;
        for (int rgbaIdx = 0; rgbaIdx < rgbaColors.length; ++rgbaIdx) {
            rgbaToYCbCrA(rgbaColors[rgbaIdx], ycbcrColors, ycbcraIdx);
            ycbcraIdx += 4;
        }
    }

    public void buildGraph() {
        for (int pixelIdx = 0; pixelIdx < rgbaColors.length; pixelIdx++) {
            buildSimilarityEdges(pixelIdx);
        }
    }

    public synchronized void removeConflicts() {
        removeFlatShaded();

        System.arraycopy(edges, 0, tempEdges, 0, edges.length);

        for (int x = 0; x < w - 1; ++x) {
            for (int y = 0; y < h - 1; ++y) {
                final int idx = toIdx(x, y);

                if (isConflicting(idx)) {
                    final int bottomRightHeuristic = heuristic(idx, idx + w + 1);
                    final int bottomLeftHeuristic = heuristic(idx + 1, idx + w);
                    if (bottomRightHeuristic > bottomLeftHeuristic) {
                        removeBottomLeftTemp(idx + 1);
                        //Log.d("----------------------", "Choosing " + idx + "/" + (idx + w + 1));
                    } else {
                        removeBottomRightTemp(idx);
                        //Log.d("----------------------", "Choosing " + (idx + 1) + "/" + (idx + w));
                    }
                }
            }
        }

        System.arraycopy(tempEdges, 0, edges, 0, edges.length);
    }

    private void removeFlatShaded() {
        for (int x = 0; x < w - 1; ++x) {
            for (int y = 0; y < h - 1; ++y) {
                final int idx = toIdx(x, y);
                if (isFlatShaded(idx)) {
                    setBottomRight(idx, false);
                    setBottomLeft(idx + 1, false);
                }
            }
        }
    }

    public void fillPixelGraph(final PixelGraph g) {
        g.reset();
        for (int n = 0; n < rgbaColors.length; n++) {
            fillCoords(g, n);
        }
        g.assemblePolygons();
    }

    public String dumpEdges(final int x0, final int y0, final int x1, final int y1) {
        final StringBuilder builder = new StringBuilder();
        builder.append("--------------------------------------\n");
        for (int y = y0; y < y1; y++) {
            if (y < 10) builder.append(' ');
            builder.append(y).append(": ");
            for (int x = x0; x < x1; x++) {
                final int idx = toIdx(x, y);
                if (bottomLeft (idx)) builder.append('↙'); else builder.append(' ');
                if (bottom     (idx)) builder.append('↓'); else builder.append(' ');
                if (bottomRight(idx)) builder.append('↘'); else builder.append(' ');
                if (right      (idx)) builder.append('→'); else builder.append(' ');
                builder.append('|');
                            }
            builder.append('\n');
        }
        return builder.toString();
    }
}
