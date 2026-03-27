package divisio.depixelizer;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import divisio.depixelizer.SimilarityGraph.Coords;

public class PixelGraph {

    public static class Node {
        public final int idx;
        public final ArrayList<Edge> outgoing = new ArrayList<>(4);
        public final int   gridX;
        public final int   gridY;
        public float x;
        public float y;
        public boolean mayBeOptimized = true;

        public Node(final int idx, final int x, final int y) {
            this.idx = idx;
            this.gridX = x;
            this.gridY = y;
            this.x = x;
            this.y = y;
        }

        public float positionalEnergy() {
            final float length = length(x - gridX / 4f, y - gridY / 4f); //+ 1f;
            final float length2 = length * length;
            return length2 * length2;
        }

        public Edge nextPolygonEdge(final int color, final HashSet<Edge> visited) {
            for (final Edge e : outgoing) {
                if (e.getPolygonColor() == color && !visited.contains(e)) {
                    return e;
                }
            }
            return null;
            //throw new IllegalArgumentException("Polygon not closed!");
        }

        public Edge getEdge(final Node node) {
            for (final Edge e : outgoing) {
                if (e.end == node) {
                    return e;
                }
                if (e.start == node) {
                    return e;
                }
            }
            return null;
        }

        public Node nextSplineNode(final Node in) {
            // nodes with edges coming from all sides cannot be part of splines
            if (valence() > 3) return null;
            // get the edge that comes in from the given node (i.e. the edge (in -> this))
            final Edge incoming = getEdge(in);
            //happens with nodes on the border of the image, this is ok
            if (incoming == null) return null;

            //we have 2 or 3 edges meeting in this node, so there are up to 2 other edges
            Edge candidate0 = null;
            Edge candidate1 = null;
            for (final Edge e : outgoing) {
                if (e != incoming) {
                    if (candidate0 == null) candidate0 = e;
                    else                    candidate1 = e;
                }
            }

            //no further edge found
            if (candidate0 == null) return null;
            //one edge found, return it
            if (candidate1 == null) return candidate0.end;

            //two edges, first try to resolve conflict by contour property
            final boolean isCountourIncoming = incoming.isContour;
            final boolean isCountour0 = candidate0.isContour;
            final boolean isCountour1 = candidate1.isContour;

            //try to continue contour
            if (isCountourIncoming && isCountour0 && !isCountour1) {
                return candidate0.end;
            } else if (isCountourIncoming && isCountour1 && !isCountour0) {
                return candidate1.end;
            } else if (isCountour0 && isCountour1 && !isCountourIncoming) {
                return null;//there is a contour, but the incoming edge is not part of it
            }

            //all edges are contours or none are: we need to decide by angle
            return resolveTCrossingByAngle(in, candidate0.end, candidate1.end);
        }

        private Node resolveTCrossingByAngle(final Node in, final Node out0, final Node out1) {
            final float xIn = in.x - x;
            final float yIn = in.y - y;
            final float xOut0 = out0.x - x;
            final float yOut0 = out0.y - y;
            final float xOut1 = out1.x - x;
            final float yOut1 = out1.y - y;

            final float angleInOut0   = angle(xIn, yIn, xOut0, yOut0);
            final float angleInOut1   = angle(xIn, yIn, xOut1, yOut1);
            final float angleOut0Out1 = angle(xOut0, yOut0, xOut1, yOut1);

            final float minAngle = Math.min(Math.min(angleInOut0, angleInOut1), angleOut0Out1);

            if (minAngle == angleInOut0) return out0;
            if (minAngle == angleInOut1) return out1;
            return null;//minAngle == angleOut0Out1
        }

        public int valence() {
            return outgoing.size();
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) return true;
            if (o == null) return false;
            //we do not mix classes in our collections, so while a bit dangerous, we leave out this check
            //if (! (o instanceof Node)) return false;
            return (((Node) o).idx == this.idx);
        }

        @Override
        public int hashCode() {
            return idx;
        }

        @Override
        public String toString() {
            return "[Node " + gridX + ", " + gridY + " - " + x + ", " + y + "]";
         }
    }

    public static class Edge {
        public final Node start;
        public final Node end;
        public final int colorLeftIdx;
        public final int colorRightIdx;
        public final float[] ycbcra;//TODO: only needed for contour calculation leave in Similarity Graph
        public final int[] rgba;
        public final boolean isContour;


        public Edge(final Node start, final Node end, final int colorLeftIdx, final int colorRightIdx, final int[] rgba, final float[] ycbcra) {
            this.start = start;
            this.end = end;
            this.colorLeftIdx = colorLeftIdx;
            this.colorRightIdx = colorRightIdx;
            this.ycbcra = ycbcra;
            this.rgba = rgba;
            start.outgoing.add(this);
            this.isContour = calculateContourEdge();
        }

        public int getPolygonColor() {
            if (colorRightIdx < 0) return Color.TRANSPARENT;
            return rgba[colorRightIdx];
        }

        private boolean calculateContourEdge() {
            if (colorLeftIdx == -1 || colorRightIdx == -1) return false;
            final int idx0 = colorLeftIdx  * 4;
            final int idx1 = colorRightIdx * 4;
            final float delta0 = ycbcra[idx0 + 0] - ycbcra[idx1 + 0];
            final float delta1 = ycbcra[idx0 + 1] - ycbcra[idx1 + 1];
            final float delta2 = ycbcra[idx0 + 2] - ycbcra[idx1 + 2];
            final float delta3 = ycbcra[idx0 + 3] - ycbcra[idx1 + 3];
            final double ycbcraDistance = Math.sqrt(
                delta0 * delta0 +
                delta1 * delta1 +
                delta2 * delta2 +
                delta3 * delta3
            );
            return ycbcraDistance >= 100f;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) return true;
            if (o == null) return false;
            //we do not mix classes in our collections, so while a bit dangerous, we leave out this check
            //if (! (o instanceof Edge)) return false;
            final Edge that = (Edge)o;
            return this.start.idx == that.start.idx &&
                   this.end.idx   == that.end.idx;
        }

        public long undirectedFingerprint() {
            return PixelGraph.undirectedFingerprint(start, end);
        }

        public Node getNodeWithLargerIdx() {
            return start.idx > end.idx ? start : end;
        }

        public Node getNodeWithSmallerIdx() {
            return start.idx < end.idx ? start : end;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + start.idx;
            result = prime * result + end.idx;
            return result;
        }
    }

    public static class Polygon {
        public final int color;
        public final ArrayList<Node> nodes = new ArrayList<Node>();

        public Polygon(final int color) {
            this.color = color;
        }

        private boolean isClockwise() {
            //this over-simplified calculation only works because we know nodes
            //and edges are added in scanline order.
            //(or more precisely: the edges for each pixel cell are added clockwise
            //starting top left, and the cells themselves are added in scanline
            //order)
            return nodes.get(1).x > nodes.get(0).x;
        }

        public boolean isValid() {
            //no need to paint transparent polys
            if (color == Color.TRANSPARENT) return false;
            //too small?
            if (nodes.size() < 3) return false;
            //make sure the first and last index are the same
            //if (!nodes.get(0).equals(nodes.get(nodes.size() - 1))) return false;

            //make sure it runs clockwise
            return isClockwise();
        }
    }

    public static class Spline {
        public final ArrayList<Node> nodes = new ArrayList<>();

        public boolean isCircle() {
            return nodes.get(0).equals(nodes.get(nodes.size() - 1));
        }

        public int getSegmentCount() {
            return nodes.size() - 1;
        }

        public Node getControlPoint(final int idx) {
            if (idx <= -1) {
                if (isCircle()) {
                    return nodes.get(nodes.size() - 1 + idx);
                } else {
                    return nodes.get(0);
                }
            }
            if (idx >= nodes.size()) {
                if (isCircle()) {
                    return nodes.get(idx % (nodes.size() - 1));
                } else {
                    return nodes.get(nodes.size() - 1);
                }
            }
            return nodes.get(idx);
        }

        public void fillCRSegment(final float[] crSegment, final int segmentIdx) {
            final Node c_1 = getControlPoint(segmentIdx - 1);
            final Node c0  = getControlPoint(segmentIdx + 0);
            final Node c1  = getControlPoint(segmentIdx + 1);
            final Node c2  = getControlPoint(segmentIdx + 2);
            crSegment[0] = c_1.x;
            crSegment[1] = c_1.y;
            crSegment[2] = c0 .x;
            crSegment[3] = c0 .y;
            crSegment[4] = c1 .x;
            crSegment[5] = c1 .y;
            crSegment[6] = c2 .x;
            crSegment[7] = c2 .y;
        }
    }

    public static class SplineSegment {
        private final Spline s;
        private final int idx;
        public final float[] crPoints = new float[8];
        public final float[] bezierPoints = new float[8];
        public final float[] precomputedCr = new float[8];

        public SplineSegment(final Spline s, final int idx) {
            this.s = s;
            this.idx = idx;
            computeSplines();
        }

        private void computeSplines() {
            s.fillCRSegment(crPoints, idx);
            precomputeBezier();
            precomputeCatmullRom();
        }

        private void precomputeBezier() {
            //conversion according to http://processingjs.nihongoresources.com/code%20repository/?get=Catmull-Rom-to-Bezier
            final float cmr_to_bezier = 6f;
            final float dx1 = (crPoints[4] - crPoints[0])/cmr_to_bezier;
            final float dy1 = (crPoints[5] - crPoints[1])/cmr_to_bezier;

            final float dx2 = (crPoints[6] - crPoints[2])/cmr_to_bezier;
            final float dy2 = (crPoints[7] - crPoints[3])/cmr_to_bezier;


            //first bezier point is equal to the second cr point
            bezierPoints[0] = crPoints[2];
            bezierPoints[1] = crPoints[3];

            //the tricky part: conversion of the control points
            bezierPoints[2] = crPoints[2] + dx1;
            bezierPoints[3] = crPoints[3] + dy1;
            bezierPoints[4] = crPoints[4] - dx2;
            bezierPoints[5] = crPoints[5] - dy2;

            //last bezier point is equal to the third cr point
            bezierPoints[6] = crPoints[4];
            bezierPoints[7] = crPoints[5];
        }

        private void precomputeCatmullRom() {
            /*
            precomputeCatmullRom (p0, p1, p2, p3) =
                    (                              p1,
                     0.5 *. ((-1) *. p0 +.                       p2),
                     0.5 *. (  2  *. p0 +. (-5) *. p1 +.   4  *. p2 +. (-1) *. p3),
                     0.5 *. ((-1) *. p0 +.   3  *. p1 +. (-3) *. p2 +.         p3)
                    )
                    */
            /*
            q(t) = 0.5 *(   (2 * P1) +
                            (-P0 + P2) * t +
                            (2*P0 - 5*P1 + 4*P2 - P3) * t2 +
                            (-P0 + 3*P1- 3*P2 + P3) * t3
                        )
            Equation 2
            */
            precomputedCr[0] = crPoints[2];
            precomputedCr[1] = crPoints[3];

            precomputedCr[2] = 0.5f * (-crPoints[0] + crPoints[4]);
            precomputedCr[3] = 0.5f * (-crPoints[1] + crPoints[5]);

            precomputedCr[4] = 0.5f * (2 * crPoints[0] - 5 * crPoints[2] + 4 * crPoints[4] - crPoints[6]);
            precomputedCr[5] = 0.5f * (2 * crPoints[1] - 5 * crPoints[3] + 4 * crPoints[5] - crPoints[7]);

            precomputedCr[6] = 0.5f * (-crPoints[0] + 3 * crPoints[2] - 3 * crPoints[4] + crPoints[6]);
            precomputedCr[7] = 0.5f * (-crPoints[1] + 3 * crPoints[3] - 3 * crPoints[5] + crPoints[7]);
        }

        public void calculatePoint(final float[] result, final int resultOffset, final float t1, final float t2, final float t3) {
            result[resultOffset    ] = precomputedCr[0] + t1 * precomputedCr[2] + t2 * precomputedCr[4] + t3 * precomputedCr[6];
            result[resultOffset + 1] = precomputedCr[1] + t1 * precomputedCr[3] + t2 * precomputedCr[5] + t3 * precomputedCr[7];
        }

        public static final int pointCount = 6;
        private static final float pointOffset = 1f / pointCount;
        private static final float[] t1s = new float[pointCount];
        private static final float[] t2s = new float[pointCount];
        private static final float[] t3s = new float[pointCount];
        static {
            for (int i = 0; i < pointCount; ++i) {
                t1s[i] = i * pointOffset;
                t2s[i] = t1s[i] * t1s[i];
                t3s[i] = t2s[i] * t1s[i];
                //Log.d("tttttttttttttttttt", t1s[i] + " ");
            }
        }

        public void calculatePoints(final float[] points, final int idx) {
            computeSplines();
            for (int i = 0; i < pointCount; ++i) {
                calculatePoint(points, idx + i * 2, t1s[i], t2s[i], t3s[i]);
            }
        }

        @Override
        public String toString() {
            String b = "(" +
                    crPoints[0] + ',' + crPoints[1] + ' ' +
                    crPoints[2] + ',' + crPoints[3] + ' ' +
                    crPoints[4] + ',' + crPoints[5] + ' ' +
                    crPoints[6] + ',' + crPoints[7] + ')';
            return b;
        }
    }

    public ArrayList<Edge> edges = new ArrayList<>();

    //TODO: use http://fastutil.dsi.unimi.it/
    public Map<Long, Node> coordsToNode = new HashMap<>();

    final HashSet<?> visited = new HashSet<>();

    public ArrayList<Polygon> polygons = new ArrayList<>();

    public ArrayList<Spline> splines = new ArrayList<>();

    public Map<Long, SplineSegment> nodesToSegments = new HashMap<>();

    public void addEdge(final Coords coords, final int colorLeftIdx, final int colorRightIdx, final int[] rgba, final float[] ycbcra) {
        //edges with the same color on both sides cannot be part of a polygon, so we ditch them
        final int colorLeft  = colorLeftIdx  < 0 ? Color.TRANSPARENT : rgba[colorLeftIdx];
        final int colorRight = colorRightIdx < 0 ? Color.TRANSPARENT : rgba[colorRightIdx];

        if (colorLeft == colorRight) return;

        //make sure the polygon coords are stored in the node lists & get their indices
        final Node node0 = assertNode(coords.x0, coords.y0);
        final Node node1 = assertNode(coords.x1, coords.y1);

        edges.add(new Edge(node0, node1, colorLeftIdx, colorRightIdx, rgba, ycbcra));
    }

    public Node assertNode(final int x, final int y) {
        final long key = intsToKey(x, y);
        final Node node = coordsToNode.get(key);
        if (node != null) {
            return node;
        }

        final int newIdx = coordsToNode.size();

        final Node newNode = new Node(newIdx, x, y);
        coordsToNode.put(key, newNode);
        return newNode;
    }

    public static long undirectedFingerprint(final Node start, final Node end) {
        final int idx1 = Math.min(start.idx, end.idx);
        final int idx2 = Math.max(start.idx, end.idx);
        return (((long)idx1) << 32) | (idx2 & 0xffffffffL);
    }

    public static long directedFingerprint(final Node start, final Node end) {
        return (((long)start.idx) << 32) | (end.idx & 0xffffffffL);
    }

    public static long intsToKey(final int x, final int y) {
        return (((long)x) << 32) | (y & 0xffffffffL);
    }

    public static float angle(final float x0, final float y0, final float x1, final float y1) {
        final float length0 = length(x0, y0);
        final float length1 = length(x1, y1);

        return (x0 / length0) * (x1 / length1) + (y0 / length0) * (y1 / length1);
    }

    public static float length(final float x, final float y) {
        return (float)Math.sqrt(x * x + y * y);
    }

    public void reset() {
        visited.clear();
        coordsToNode.clear();
        edges.clear();
        polygons.clear();
        splines.clear();
        nodesToSegments.clear();
    }


    public void assemblePolygons() {
        //normalize coords back to original size from enlarged grid
        //check all polygon nodes if their positions may be changed when
        //optimizing smoothness
        for (final Node n : coordsToNode.values()) {
            n.x /= 4f;
            n.y /= 4f;
            //n.mayBeOptimized = n.valence() == 2;//only nodes that are part of a chain may be changed
        }

        visited.clear();
        @SuppressWarnings("unchecked")
        final HashSet<Edge> visitedEdges =
                (HashSet<Edge>) visited;
        for (Edge edge : edges) {
            //add to polygon, if possible
            if (visited.contains(edge)) continue;
            if (edge.getPolygonColor() == Color.TRANSPARENT) continue;

            final int startIdx = edge.start.idx;
            final Polygon p = new Polygon(edge.getPolygonColor());
            p.nodes.add(edge.start);
            //starts of polygons may not be optimized
            //edge.start.mayBeOptimized = false;

            do {
                visitedEdges.add(edge);
                p.nodes.add(edge.end);
                edge = edge.end.nextPolygonEdge(p.color, visitedEdges);
            } while (edge != null && edge.start.idx != startIdx);

            if (p.isValid()) {
                polygons.add(p);
            }
        }
    }

    public void assembleSplines() {
        visited.clear();
        @SuppressWarnings("unchecked") final HashSet<Long> visitedPairs = (HashSet<Long>)visited;

        //start with any edge
        for (final Edge startEdge : edges) {
            final long normalized = startEdge.undirectedFingerprint();
            if (visitedPairs.contains(normalized)) {
                continue;
            }
            // create a new, empty spline
            final Spline s = new Spline();
            // mark current pair as visited and add it to the spline (this is our starting point)
            // we add the two nodes of the edge to the spline in the order of the indices (we
            // regard the edge as non-directed in this case, as we do not care about orientation
            // of edges in splines - only that it is deterministic and always the same)
            visitedPairs.add(normalized);
            s.nodes.add(startEdge.getNodeWithSmallerIdx());
            s.nodes.add(startEdge.getNodeWithLargerIdx());
            // perform assembly in one direction
            assembleSpline(s, visitedPairs);
            // reverse the spline direction, so the old starting point is last
            Collections.reverse(s.nodes);
            // perform assembly again, only in the other direction
            assembleSpline(s, visitedPairs);

            if (s.nodes.size() > 2) {
                splines.add(s);
                for (int i = 0; i < s.nodes.size() - 1; ++i) {
                    final SplineSegment segment = new SplineSegment(s, i);
                    nodesToSegments.put(directedFingerprint(s.nodes.get(i), s.nodes.get(i + 1)), segment);
                }

                //TODO: increase coords for small circles manually here
                if (s.nodes.size() <= 5) {
                    for (final Node n : s.nodes) {
                        n.mayBeOptimized = n.mayBeOptimized && n.valence() > 2;
                    }
                    //a hack for single-pixel splines: make them a little bit bigger,
                    //as the cr-splines are *too* precise
                    if (s.isCircle()) {
                        for (int i = 0; i < s.nodes.size() - 1; ++i) {
                            final Node n = s.nodes.get(i);
                            n.x += (Math.round(n.x) - n.x) / 2f;
                            n.y += (Math.round(n.y) - n.y) / 2f;
                        }
                        for (int i = 0; i < s.nodes.size(); ++i) {
                            nodesToSegments.get(directedFingerprint(s.getControlPoint(i), s.getControlPoint(i + 1))).computeSplines();
                        }
                    }
                }

                //mark corners in spline
                for (int i = 0; i < s.nodes.size(); ++i) {
                    markCorners(s.getControlPoint(i)
                               ,s.getControlPoint(i + 1)
                               ,s.getControlPoint(i + 2)
                               ,s.getControlPoint(i + 3)
                               ,s.getControlPoint(i + 4)
                               );
                }
            }
        }
    }

    /**
     * Assembles splines, given a spline with at least two nodes (the start points), nodes are
     * added incrementally if they are not already contained in visitedPairs
     * @param s the spline to assemble
     * @param visitedPairs already visited node pairs
     */
    public void assembleSpline(final Spline s, final HashSet<Long> visitedPairs) {
        Node start = s.nodes.get(s.nodes.size() - 2);
        Node end   = s.nodes.get(s.nodes.size() - 1);
        Node next  = null;
        while ((next = end.nextSplineNode(start)) != null) {
            start = end;
            end   = next;
            final long pair = undirectedFingerprint(start, end);
            // we hit the end of the spline, end assembly
            if (visitedPairs.contains(pair)) return;
            // add the new node to the spline
            s.nodes.add(end);
            // add the pair to the set of visited pairs
            visitedPairs.add(pair);
        }
    }

    private final int[] gridCoordinates = new int[8];

    private static final int[][] cornerPatterns = new int[][]{
        //pattern 1
        new int[]{1,-3,  3,-3,  4,0},
        new int[]{2,-2,  4,-2,  5,1},
        new int[]{1,-3,  3,-3,  5,-1},
        new int[]{2,-2,  4,-2,  6,0},
        new int[]{0,-4,  4,-4,  4,0},
        new int[]{-2,-2,  -2,-4,  0,-4,  2,-2},
        new int[]{ 2,-2,   4,-2,  4,0,   2,2}
    };

    private static final ArrayList<int[]> corners = new ArrayList<int[]>(cornerPatterns.length * 8);
    static {
        final int[][] matrices = new int[][] {
            new int[] { 1, 0,  0, 1},
            new int[] { 0,-1,  1, 0},
            new int[] {-1, 0,  0,-1},
            new int[] { 0, 1, -1, 0},
            new int[] {-1, 0,  0, 1},
            new int[] { 0,-1, -1, 0},
            new int[] { 1, 0,  0,-1},
            new int[] { 0, 1,  1, 0},
        };

        for (final int[] matrix : matrices) {
            for (final int[] cornerPattern : cornerPatterns) {
                final int[] corner = new int[cornerPattern.length];
                for (int i = 0; i < cornerPattern.length; i += 2) {
                    corner[i]     = matrix[0] * cornerPattern[i] + matrix[2] * cornerPattern[i + 1];
                    corner[i + 1] = matrix[1] * cornerPattern[i] + matrix[3] * cornerPattern[i + 1];
                }
                corners.add(corner);
            }
        }
    }



    final StringBuilder debug = new StringBuilder();
    public void markCorners(final Node n0, final Node n1, final Node n2, final Node n3, final Node n4) {

        gridCoordinates[0] = n1.gridX - n0.gridX;
        gridCoordinates[1] = n1.gridY - n0.gridY;

        gridCoordinates[2] = n2.gridX - n0.gridX;
        gridCoordinates[3] = n2.gridY - n0.gridY;

        gridCoordinates[4] = n3.gridX - n0.gridX;
        gridCoordinates[5] = n3.gridY - n0.gridY;

        gridCoordinates[6] = n4.gridX - n0.gridX;
        gridCoordinates[7] = n4.gridY - n0.gridY;

        debug.setLength(0);
        for (int i = 0; i < gridCoordinates.length; i+=2) {
            debug.append(gridCoordinates[i]).append(',').append(gridCoordinates[i+1]).append(' ');
        }

        for (int i = 0; i < corners.size(); ++i) {
            final int[] corner = corners.get(i);
            if (prefixEquals(corner, gridCoordinates)) {
                n0.mayBeOptimized = false;
                n1.mayBeOptimized = false;
                n2.mayBeOptimized = false;
                n3.mayBeOptimized = false;
                if (corner.length == 4) {
                    n4.mayBeOptimized = false;
                }
                return;
            }
        }
    }

    private static boolean prefixEquals(final int[] reference, final int[] compare) {
        for (int n = 0; n < reference.length; n++) {
            if (reference[n] != compare[n]) return false;
        }
        return true;
    }

    public SplineSegment getSegment(final Node n0, final Node n1) {
        final SplineSegment forward = nodesToSegments.get(directedFingerprint(n0, n1));
        if (forward != null) { return forward; }
        return nodesToSegments.get(directedFingerprint(n1, n0));
    }

    /**
     * Runs spline node energy optimization.
     *
     * @param callback progress / cancellation callback, may be null
     */
    public void optimizeNodes(final DepixelizationProgressCallback callback) {
        final ArrayList<SimpleImmutableEntry<Node, List<SplineSegment>>> toOptimize = new ArrayList<>();
        //loop over all splines
        for (final Spline s : splines) {
            //loop over nodes in splines
            for (int i = 0; i < s.nodes.size(); ++i) {
                final Node n = s.nodes.get(i);
                //if node is optimizable, get affected spline segments
                if (!n.mayBeOptimized) continue;
                //fill pair of optimizable node & spline segments in list
                final List<SplineSegment> segments = new ArrayList<>(6);
                for (int j = -2; j < 2; ++j) {
                    final SplineSegment segment = getSegment(s.getControlPoint(i + j), s.getControlPoint(i + j + 1));
                    if (segment != null) segments.add(segment);
                }
                toOptimize.add(new SimpleImmutableEntry<>(n, segments));
            }
        }
        //Log.d("%%%%%%%%%%%%%%%%%%%%", "to optimize: " + toOptimize.size());
        //repeat n - times
        final Random r = new Random(12345);
        final int samplesCount = 20;
        final int offsetsCount = 1 + samplesCount;
        final float[] coords = new float[offsetsCount * 2];
        final float[] energies = new float[offsetsCount];
        final int iterations = 15;
        @SuppressWarnings("unused")
        float totalImprovement = 0;
        //TODO: this should be *much* faster with a gradient descent
        for (int i = 0; i < iterations; ++i) {
            if (callback != null && callback.isCancelled()) return;

            float energyImprovement = 0;
            //TODO: experiment with gamma function
            final float sampleDelta = 1f / (i + 1f);

            //shuffle list
            Collections.shuffle(toOptimize, r);
            //loop over list
            for (final SimpleImmutableEntry<Node, List<SplineSegment>> pair : toOptimize) {
                final Node n = pair.getKey();

                //if (debugCount < debugExamples) Log.d("--", "node: " + n.idx);
                //create random coords for node
                coords[0] = n.x;
                coords[1] = n.y;
                //simple random samples in a 1-px range work best, stratified sampling
                //does not yield good results
                for (int sample = 1; sample < samplesCount; ++sample) {
                    coords[sample * 2]     = n.x + (r.nextFloat() - 0.5f) * sampleDelta;
                    coords[sample * 2 + 1] = n.y + (r.nextFloat() - 0.5f) * sampleDelta;
                }

                //calculate energy for all coords
                for (int sample = 0; sample < samplesCount; ++sample) {
                    n.x = coords[sample * 2];
                    n.y = coords[sample * 2 + 1];
                    energies[sample] = calculateEnergy(n, pair.getValue());
                }

                //set offset with lowest energy
                int minIdx = 0;
                for (int sample = 1; sample < samplesCount; ++sample) {
                    if (energies[sample] < energies[minIdx]) {
                        minIdx = sample;
                    }
                }
                n.x = coords[minIdx * 2];
                n.y = coords[minIdx * 2 + 1];

                //if (minIdx != 0) Log.d("!!!!!!!!!!!k", "Improving by " + (energies[0] - energies[minIdx]));
                //if (debugCount < debugExamples) Log.d("++++", "Using minIdx: " + minIdx);
                energyImprovement += (energies[0] - energies[minIdx]);
            }
            //System.out.println("iteration=" + i + ", energy improvement: " + energyImprovement);
            totalImprovement += energyImprovement;
            if (callback != null) {
                callback.energyOptimizationProgress(i / (float)iterations);
            }
        }
        //now update all spline segments so coords are right
        //some nodes affect multiple splines - so the segment for which the
        //node was last optimized is the only one with the correct node coords
        //hence we need to run over all segments again & update their coords
        for (final SplineSegment seg : nodesToSegments.values()) {
            seg.computeSplines();
        }
    }

    private final float[] curvePoints = new float[7 * SplineSegment.pointCount * 2];
    private final float t = 0.1f;
    private float calculateEnergy(final Node n, final List<SplineSegment> segments) {
        final float positionalEnergy = n.positionalEnergy();
        //Log.d("%%%%", "segment size: " + segments.size());

        int pointLength = 0;
        for (final SplineSegment segment : segments) {
            segment.calculatePoints(curvePoints, pointLength);
            pointLength += SplineSegment.pointCount * 2;
        }

        final float curvatureEnergy = calculateCurvatureEnergy(curvePoints, pointLength);
        final float energy = t * positionalEnergy + (1 - t) * curvatureEnergy;

        //if (debugCount < debugExamples) Log.d("****", "pos: " + positionalEnergy + ", curve: " + curvatureEnergy + ", sum: " + energy);

        return energy;
    }


    public float calculateCurvatureEnergy(final float[] points, final int length) {
        float curvature = 0;
        for (int i = 2; i < length - 2; i += 2) {
            final int now    = i;
            final int before = now - 2;
            final int after  = now + 2;
            final float cos = angle(points[before]     - points[now],
                                    points[before + 1] - points[now + 1],
                                    points[after]      - points[now],
                                    points[after + 1]  - points[now + 1]
                                   )
                                   ;
            //final float c = (float)Math.abs(Math.acos(cos) - Math.PI);
            /*
            curvature += Math.abs(Math.acos(cos) - Math.PI) /
                                  (
                                  length(points[before]     - points[now],
                                         points[before + 1] - points[now + 1]) +
                                  length(points[after]      - points[now],
                                         points[after + 1]  - points[now + 1])
                                )
                                  ;
                                  */
            //curvature += c * c;
            curvature += cos;

        }
        return curvature / length;
    }
}
