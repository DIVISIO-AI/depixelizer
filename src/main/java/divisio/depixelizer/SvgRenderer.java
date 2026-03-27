package divisio.depixelizer;

import divisio.depixelizer.PixelGraph.Node;
import divisio.depixelizer.PixelGraph.Polygon;
import divisio.depixelizer.PixelGraph.SplineSegment;

public class SvgRenderer {

    public static String draw(final PixelGraph pixelGraph, final int width, final int height) {
        final StringBuilder result = new StringBuilder();
        result.append("<?xml version=\"1.0\" ?>\n")
              .append("<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.0//EN\" \"http://www.w3.org/TR/SVG/DTD/svg10.dtd\">\n")
              .append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xml=\"http://www.w3.org/XML/1998/namespace\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
              .append("width=\"").append(width*10).append("\" ")
              .append("height=\"").append(height*10).append("\" ")
              .append(">\n")
              .append("<g transform=\"scale(10)\">\n");
        for (final Polygon p : pixelGraph.polygons) {
            result.append("<path stroke=\"none\" ")
                  .append("fill=\"").append(String.format("#%06X", p.color & 0x00FFFFFF)).append("\" ")
                  .append("fill-opacity=\"").append(String.format("%.4f", 1 - (((p.color & 0xFF000000) >> 6) / 255f) )).append("\" ")
                  .append("d=\"");
            Node last = p.nodes.get(0);
            result.append("M ").append(last.x).append(' ').append(last.y).append(' ');

            for (int i = 1; i < p.nodes.size(); ++i) {
                final Node current = p.nodes.get(i);
                final SplineSegment segmentForward = pixelGraph.nodesToSegments.get(PixelGraph.directedFingerprint(last, current));
                if (segmentForward != null) {
                    final float[] b = segmentForward.bezierPoints;

                    result.append("C ")
                          .append(b[2]).append(' ').append(b[3]).append(' ')
                          .append(b[4]).append(' ').append(b[5]).append(' ')
                          .append(b[6]).append(' ').append(b[7]).append(' ')
                          ;
                    last = current;
                    continue;
                }
                final SplineSegment segmentBackward = pixelGraph.nodesToSegments.get(PixelGraph.directedFingerprint(current, last));
                if (segmentBackward != null) {
                    final float[] b = segmentBackward.bezierPoints;
                    result.append("C ")
                    .append(b[4]).append(' ').append(b[5]).append(' ')
                    .append(b[2]).append(' ').append(b[3]).append(' ')
                    .append(b[0]).append(' ').append(b[1]).append(' ')
                    ;
                    last = current;
                    continue;
                }

                result.append("L ").append(current.x).append(' ').append(current.y).append(' ');
                last = current;
            }
            //close coordinate attribute & path element
            result.append("Z\" />\n");
        }
        result.append("</g>\n");
        result.append("</svg>\n");

        return result.toString();
    }
}
