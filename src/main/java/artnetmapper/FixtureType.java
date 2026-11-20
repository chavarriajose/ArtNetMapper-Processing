package artnetmapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Channel layout of one light element, parsed from a string such as
 * {@code "dim=255, r, g, b, w, strobe=0"}.
 *
 * <p>Channels named {@code r}, {@code g}, {@code b} or {@code w} without a value are
 * sampled from the canvas. Channels with {@code =value} are constants. Any other
 * name without a value is a constant 0.
 */
public class FixtureType {

  final String spec;
  final String[] names;
  /** Default value per channel; -1 for sampled channels. */
  final int[] defaults;
  final int r, g, b, w;

  FixtureType(String spec) {
    if (spec == null || spec.trim().isEmpty()) {
      throw new IllegalArgumentException("ArtNetMapper: fixture type is empty. Example: type(\"r, g, b, w\")");
    }
    this.spec = spec;
    List<String> n = new ArrayList<>();
    List<Integer> d = new ArrayList<>();
    for (String raw : spec.split(",")) {
      String tok = raw.trim();
      if (tok.isEmpty()) continue;
      String name;
      int value;
      int eq = tok.indexOf('=');
      if (eq >= 0) {
        name = tok.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        String v = tok.substring(eq + 1).trim();
        try {
          value = Integer.parseInt(v);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("ArtNetMapper: channel \"" + tok + "\" in type \"" + spec
              + "\" needs a number 0-255 after '='");
        }
        if (value < 0 || value > 255) {
          throw new IllegalArgumentException("ArtNetMapper: channel \"" + tok + "\" in type \"" + spec
              + "\" must be 0-255");
        }
      } else {
        name = tok.toLowerCase(Locale.ROOT);
        value = isColor(name) ? -1 : 0;
      }
      if (name.isEmpty()) {
        throw new IllegalArgumentException("ArtNetMapper: empty channel name in type \"" + spec + "\"");
      }
      if (n.contains(name) && isColor(name)) {
        throw new IllegalArgumentException("ArtNetMapper: channel \"" + name + "\" appears twice in type \"" + spec + "\"");
      }
      n.add(name);
      d.add(value);
    }
    if (n.isEmpty()) {
      throw new IllegalArgumentException("ArtNetMapper: fixture type \"" + spec + "\" has no channels");
    }
    names = n.toArray(new String[0]);
    defaults = new int[d.size()];
    for (int i = 0; i < defaults.length; i++) defaults[i] = d.get(i);
    r = sampledIndex("r");
    g = sampledIndex("g");
    b = sampledIndex("b");
    w = sampledIndex("w");
  }

  private static boolean isColor(String name) {
    return name.equals("r") || name.equals("g") || name.equals("b") || name.equals("w");
  }

  private int sampledIndex(String name) {
    for (int i = 0; i < names.length; i++) {
      if (names[i].equals(name) && defaults[i] < 0) return i;
    }
    return -1;
  }

  /** Number of DMX channels per element. */
  public int size() {
    return names.length;
  }

  /** Index of a channel by name, or -1. */
  public int indexOf(String name) {
    String key = name.trim().toLowerCase(Locale.ROOT);
    for (int i = 0; i < names.length; i++) if (names[i].equals(key)) return i;
    return -1;
  }

  boolean hasWhite() {
    return w >= 0;
  }

  @Override
  public String toString() {
    return "FixtureType(" + String.join(", ", names) + ")";
  }
}
