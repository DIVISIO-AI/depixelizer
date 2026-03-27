package divisio.depixelizer;

public class Color {

    public static final int TRANSPARENT = 0;

    public static int red(int color) {
        return ((color >> 16) & 0xFF);
    }

    public static int green(int color) {
        return ((color >> 8) & 0xFF);
    }

    public static int blue (int color) {
        return (color & 0xFF);
    }

    public static int alpha (int color) {
        return (color >>> 24);
    }
}
