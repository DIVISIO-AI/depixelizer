package divisio.depixelizer;

public enum EdgeType {
    Cross, Tangent, None;

    public static String toAsciiArt(final EdgeType tr, final EdgeType br, final EdgeType bl, final EdgeType tl) {
        final StringBuilder result = new StringBuilder(4);

        switch (tl) {
        case Cross: result.append('\\'); break;
        case Tangent: result.append('/'); break;
        case None: result.append('.'); break;
        }
        switch (tr) {
        case Cross: result.append('/'); break;
        case Tangent: result.append('\\'); break;
        case None: result.append('.'); break;
        }
        result.append('\n');
        switch (bl) {
        case Cross: result.append('/'); break;
        case Tangent: result.append('\\'); break;
        case None: result.append('.'); break;
        }
        switch (br) {
        case Cross: result.append('\\'); break;
        case Tangent: result.append('/'); break;
        case None: result.append('.'); break;
        }

        return result.toString();
    }
} 
