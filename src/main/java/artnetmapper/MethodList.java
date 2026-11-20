package artnetmapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the controlP5-style method list used by
 * {@link ArtNetMapper#printPublicMethodsFor(Class)} and at the end of the examples,
 * grouped by what the methods do.
 */
final class MethodList {

  private MethodList() {}

  /** Never called by sketches (Processing calls draw/dispose itself), or not worth listing. */
  private static final Set<String> HIDDEN = Set.of("draw", "dispose", "toString", "printPublicMethodsFor", "VERSION");

  /** Group of each member, by name. Order of groups = order of first appearance here. */
  private static final Map<String, String> GROUP = new LinkedHashMap<>();

  static {
    group("Create and connect", "<init>", "list", "fixture", "strip", "matrix");
    group("Place", "setPos", "line", "rect", "rotate", "wiring");
    group("Follow the canvas", "colorChannels", "sampleRadius", "whiteMode", "gamma", "brightness", "source",
        "showLocations", "getSampleRadius", "getWhiteMode", "getGamma", "getBrightness");
    group("Set values by hand", "setVal", "setVals", "release", "getVal", "pixel");
    group("Sending", "autoSend", "send", "maxRate", "getMaxRate", "blackout", "isBlackout", "blackoutOnExit");
    group("Info", "isConnected", "ip", "fixtures", "getUniverse", "channels", "totalChannels", "pixels", "address",
        "lastAddress", "universe", "x", "y");
    group("Constants for wiring()", "PROGRESSIVE", "SERPENTINE", "TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT",
        "BOTTOM_RIGHT", "ROWS", "COLUMNS");
  }

  private static void group(String name, String... members) {
    for (String m : members) GROUP.put(m, name);
  }

  /** Lines for one class: "// group" headers followed by "Class : type name(params)" lines. */
  static List<String> lines(Class<?> c) {
    String owner = simple(c);
    Map<String, List<String>> byGroup = new LinkedHashMap<>();
    for (String g : GROUP.values()) byGroup.putIfAbsent(g, new ArrayList<>());
    byGroup.put("Other", new ArrayList<>());

    for (Constructor<?> k : c.getConstructors()) {
      byGroup.get(GROUP.get("<init>")).add(owner + " : new " + owner + params(k));
    }
    for (Method m : c.getMethods()) {
      if (m.getDeclaringClass() == Object.class || m.isSynthetic() || m.isBridge()) continue;
      if (HIDDEN.contains(m.getName())) continue;
      String line = owner + " : " + (Modifier.isStatic(m.getModifiers()) ? "static " : "")
          + type(m.getGenericReturnType()) + " " + m.getName() + params(m);
      byGroup.get(GROUP.getOrDefault(m.getName(), "Other")).add(line);
    }
    for (Field f : c.getFields()) {
      int mod = f.getModifiers();
      if (f.getDeclaringClass() != c || !Modifier.isStatic(mod) || !Modifier.isFinal(mod)) continue;
      if (HIDDEN.contains(f.getName())) continue;
      String line = owner + " : static " + type(f.getGenericType()) + " " + f.getName();
      byGroup.get(GROUP.getOrDefault(f.getName(), "Other")).add(line);
    }

    List<String> out = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : byGroup.entrySet()) {
      List<String> ls = e.getValue();
      if (ls.isEmpty()) continue;
      if (!e.getKey().startsWith("Constants")) ls.sort(MethodList::byName);
      if (!out.isEmpty()) out.add("");
      out.add("// " + e.getKey());
      out.addAll(ls);
    }
    return out;
  }

  /** Sorts by method name, then by the full line (so overloads stay together). */
  private static int byName(String a, String b) {
    int c = name(a).compareTo(name(b));
    return c != 0 ? c : a.compareTo(b);
  }

  private static String name(String line) {
    int paren = line.indexOf('(');
    String head = paren < 0 ? line : line.substring(0, paren);
    return head.substring(head.lastIndexOf(' ') + 1);
  }

  private static String params(Executable m) {
    StringBuilder s = new StringBuilder("(");
    Parameter[] ps = m.getParameters();
    for (int i = 0; i < ps.length; i++) {
      if (i > 0) s.append(", ");
      Parameter p = ps[i];
      s.append(p.isVarArgs() ? simple(p.getType().getComponentType()) + "..." : type(p.getParameterizedType()));
      if (p.isNamePresent()) s.append(' ').append(p.getName());
    }
    return s.append(')').toString();
  }

  private static String type(Type t) {
    if (t instanceof Class) return simple((Class<?>) t);
    if (t instanceof ParameterizedType) {
      ParameterizedType pt = (ParameterizedType) t;
      StringBuilder s = new StringBuilder(type(pt.getRawType())).append('<');
      Type[] args = pt.getActualTypeArguments();
      for (int i = 0; i < args.length; i++) s.append(i > 0 ? ", " : "").append(type(args[i]));
      return s.append('>').toString();
    }
    return t.getTypeName();
  }

  private static String simple(Class<?> t) {
    if (t.isArray()) return simple(t.getComponentType()) + "[]";
    String n = t.getName();
    n = n.startsWith("artnetmapper.") ? n.substring("artnetmapper.".length()) : t.getSimpleName();
    return n.replace('$', '.');
  }
}
