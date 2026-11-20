package artnetmapper;

/** Color pipeline helpers: white extraction, gamma, brightness. */
final class ColorMath {

  private ColorMath() {}

  /** 256-entry gamma lookup table. gamma 1.0 is identity. */
  static int[] gammaTable(float gamma) {
    int[] t = new int[256];
    for (int i = 0; i < 256; i++) {
      t[i] = gamma == 1f ? i : (int) Math.round(255.0 * Math.pow(i / 255.0, gamma));
    }
    return t;
  }

  /**
   * Runs the pipeline for one element: white extraction, then gamma, then brightness.
   *
   * @param out receives {r, g, b, w}; w is 0 when no white extraction happens
   */
  static void process(int r, int g, int b, boolean extractWhite, int[] gammaLut,
                      float brightness, int[] out) {
    int w = 0;
    if (extractWhite) {
      w = Math.min(r, Math.min(g, b));
      r -= w;
      g -= w;
      b -= w;
    }
    out[0] = scale(gammaLut[r], brightness);
    out[1] = scale(gammaLut[g], brightness);
    out[2] = scale(gammaLut[b], brightness);
    out[3] = scale(gammaLut[w], brightness);
  }

  private static int scale(int v, float brightness) {
    if (brightness >= 1f) return v;
    if (brightness <= 0f) return 0;
    return Math.round(v * brightness);
  }
}
