package artnetmapper;

import java.util.List;

import processing.core.PApplet;
import processing.core.PGraphics;

/**
 * Draws the location markers. Markers never touch a fixture's own sample squares,
 * so a canvas that is never cleared doesn't feed them back into the colors read.
 * Rings shrink to fit between neighbouring pixels; when even the smallest ring would
 * touch a neighbour, a strip gets small ticks beside its line and a matrix an
 * outline around its grid. (Another fixture within a few pixels can still be touched.)
 */
final class Overlay {

  private Overlay() {}

  static void draw(PGraphics g, List<Fixture> fixtures) {
    g.pushStyle();
    g.pushMatrix();
    if (g.is3D()) {
      g.camera();
      g.hint(PApplet.DISABLE_DEPTH_TEST);
    } else {
      g.resetMatrix();
    }
    g.noFill();
    g.strokeWeight(1);
    g.ellipseMode(PApplet.CENTER);
    for (Fixture f : fixtures) {
      if (f.placed) markers(g, f);
    }
    if (g.is3D()) g.hint(PApplet.ENABLE_DEPTH_TEST);
    g.popMatrix();
    g.popStyle();
  }

  private static void markers(PGraphics g, Fixture f) {
    float hd = f.sampleRadius * 1.415f;          // half-diagonal of the sample square
    float spacing = f.minSpacing();
    float ring = Math.min(hd + 4.5f, spacing - hd - 3f);
    if (ring >= hd + 1.5f) {
      for (int e = 0; e < f.count; e++) {
        float x = f.px[e], y = f.py[e];
        g.stroke(0);
        g.ellipse(x, y, 2 * ring + 3, 2 * ring + 3);
        g.stroke(color(f, e));
        g.ellipse(x, y, 2 * ring, 2 * ring);
      }
    } else if (f.hasRect) {
      float[] c = f.outline(hd + 3f);
      g.stroke(0xFF808080);
      for (int k = 0; k < 4; k++) g.line(c[2 * k], c[2 * k + 1], c[(2 * k + 2) % 8], c[(2 * k + 3) % 8]);
    } else {
      float dx = f.px[f.count - 1] - f.px[0], dy = f.py[f.count - 1] - f.py[0];
      float len = (float) Math.sqrt(dx * dx + dy * dy);
      float nx = len > 0 ? -dy / len : 0, ny = len > 0 ? dx / len : 1;
      float a = hd + 3f, b = hd + 7f;
      for (int e = 0; e < f.count; e++) {
        g.stroke(color(f, e));
        g.line(f.px[e] + nx * a, f.py[e] + ny * a, f.px[e] + nx * b, f.py[e] + ny * b);
      }
    }
  }

  /** The color read from the canvas, or grey for a fully manual fixture. */
  private static int color(Fixture f, int e) {
    return f.colorCount > 0 ? 0xFF000000 | f.sampled[e] : 0xFF808080;
  }
}
