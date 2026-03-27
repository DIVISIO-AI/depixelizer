package divisio.depixelizer;

import java.text.DecimalFormat;
import java.util.Arrays;

public class Histogram {

    public final double binWidth;
    public final double min;
    public final double max;
    public final double[] binCenters;
    public final int[] binCounts;
    public final double[] normalizedCounts;

    public Histogram(final double[] values, final int binCount) {
        //init arrays for number of bins
        this.binCenters = new double[binCount];
        this.binCounts = new int[binCount];
        this.normalizedCounts = new double[binCount];

        //determine min & max values and build bin width from it
        double min_ = Double.POSITIVE_INFINITY;
        double max_ = Double.NEGATIVE_INFINITY;
        if (values.length <= 0) {
            this.binWidth = 0;
        } else {
            for (int i = 0; i < values.length; ++i) {
                if (Double.isFinite(values[i])) {
                    min_ = Math.min(min_, values[i]);
                    max_ = Math.max(max_, values[i]);
                }
            }
            this.binWidth = (max_ - min_) / binCount;
        }
        this.min = min_;
        //make sure we do not crash if all values are the same (the all land in the first bin then)
        if (max_ == min_) {
            this.max = max_ + 1;
        } else {
            this.max = max_;
        }

        //read data
        fillHistogram(values);
    }

    public Histogram(final double[] values, final int binCount, final double min, final double max) {
        //init arrays for number of bins
        this.binCenters = new double[binCount];
        this.binCounts = new int[binCount];
        this.normalizedCounts = new double[binCount];

        //determine min & max values and build bin width from it
        this.min = min;
        this.max = max;
        this.binWidth = (max - min) / binCount;

        //read data
        fillHistogram(values);
    }

    private void fillHistogram(final double[] values) {
        //count values into bins
        for (int i = 0; i < values.length; ++i) {
            int binIdx = (int) Math.floor((values[i] - min) / binWidth);
            binIdx = Math.min(binCounts.length - 1, binIdx);
            ++binCounts[binIdx];
        }
        //determine the center for each bin (for display purposes) and calculate a normalized count, so the area under
        //the histogram is one
        double lastBinCenter = min - binWidth / 2;
        for (int i = 0; i < binCenters.length; ++i) {
            binCenters[i] = lastBinCenter + binWidth;
            lastBinCenter = binCenters[i];
            normalizedCounts[i] = binCounts[i] / (double)values.length;
        }
    }

    private char toAsciiPixel(final double d) {
        if (d > 0.95) return '█';
        if (d <= 0)   return ' ';
        char fraction = (char) Math.floor(d / (1 / 8.0));
        return (char)('▁' + fraction);
    }

    public String plotAsciiArt() {
        //TODO: use https://en.wikipedia.org/wiki/Block_Elements for plotting -> maybe write Graphics2D implementation based on ascii art?
        double maxValue = 0.0;
        for (int i = 0; i < normalizedCounts.length; ++i) { maxValue = Math.max(maxValue, normalizedCounts[i]); }
        final StringBuilder b = new StringBuilder(10 * 80);
        for (int rowIdx = 9; rowIdx >= 0; --rowIdx) {
            final char[] row = new char[80];
            Arrays.fill(row, ' ');
            for (int column = 0; column < normalizedCounts.length; ++column) {
                final int columnIdx = normalizedCounts.length > 80 ? (int) Math.floor(column / 80.0) : column;
                final double columnValue = (normalizedCounts[columnIdx] / maxValue) * 10 - rowIdx;
                row[columnIdx] = toAsciiPixel(columnValue);
            }
            b.append(row);
            b.append('\n');
        }
        final DecimalFormat format = new DecimalFormat("#.####");
        final String minS = format.format(min);
        final String maxS = format.format(max);
        final int width = Math.min(80, normalizedCounts.length);
        final int padding = Math.max(1, width - minS.length() - maxS.length());
        b.append(minS);
        for (int i = 0; i < padding; ++i) b.append(' ');
        b.append(maxS);
        return b.toString();
    }

}
