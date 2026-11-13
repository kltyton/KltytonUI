package io.github.kltyton.kltytonui.style;

import io.github.kltyton.kltytonui.layout.Box;
import io.github.kltyton.kltytonui.layout.Size;
import io.github.kltyton.kltytonui.layout.CssLength;
import io.github.kltyton.kltytonui.init.Element;
import io.github.kltyton.kltytonui.init.Window;
import io.github.kltyton.kltytonui.style.Style;
import io.github.kltyton.kltytonui.style.StyleFrameCache;
import io.github.kltyton.kltytonui.render.Drawer;

import java.util.*;
import io.github.kltyton.kltytonui.parser.Color;
import io.github.kltyton.kltytonui.parser.CSS;
import java.util.regex.Pattern;

public record Transition(String name, double start, double end, double duration, double delay, long startTime,
                         String timing, String startTransform, String endTransform, String gridStart, String gridEnd) {
    private static final Object LOCK = new Object();
    private static final Map<UUID, List<Transition>> workList = new HashMap<>();

    public Transition(String name, double start, double end, double duration, double delay, long startTime,
                      String timing) {
        this(name, start, end, duration, delay, startTime, timing, null, null, null, null);
    }

    public Transition(String name, double start, double end, double duration, double delay, long startTime,
                      String timing, String startTransform, String endTransform) {
        this(name, start, end, duration, delay, startTime, timing, startTransform, endTransform, null, null);
    }

    public Transition(String name, double start, double end, double duration, double delay, long startTime) {
        this(name, start, end, duration, delay, startTime, "ease");
    }

    public static final class Change {
        private String name;
        private double value;

        public Change(String name, double value) {
            this.name = name;
            this.value = value;
        }

        public String name() {
            return name;
        }

        public double value() {
            return value;
        }

        private void set(String name, double value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Change change)) return false;
            return Double.compare(value, change.value) == 0 && Objects.equals(name, change.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value);
        }

        @Override
        public String toString() {
            return "Change[name=" + name + ", value=" + value + "]";
        }
    }

    static final class ChangeBuffer extends ArrayList<Change> {
        private final ArrayList<Change> pool = new ArrayList<>();

        void addReusable(String name, double value) {
            int index = size();
            Change change;
            if (index < pool.size()) {
                change = pool.get(index);
                change.set(name, value);
            } else {
                change = new Change(name, value);
                pool.add(change);
            }
            super.add(change);
        }
    }

    private static final ThreadLocal<ChangeBuffer> CHANGE_BUFFER =
            ThreadLocal.withInitial(ChangeBuffer::new);

    public static void addChange(List<Change> changes, String name, double value) {
        if (changes instanceof ChangeBuffer buffer) {
            buffer.addReusable(name, value);
        } else {
            changes.add(new Change(name, value));
        }
    }

    public static void create(Element element, Style startStyle, Style endStyle) {
        String transitionSpec = resolveTransitionSpec(startStyle, endStyle);
        if (transitionSpec.equals(Style.DEFAULT.transition)) {
            cancel(element);
            return;
        }

        List<Transition> parsed = deferStartTimes(parseTransitions(element, startStyle, endStyle, transitionSpec));
        synchronized (LOCK) {
            List<Transition> existing = workList.get(element.uuid);
            if (existing != null && Objects.equals(startStyle.transition, endStyle.transition)) {
                for (Transition active : existing) {
                    boolean unchangedTarget;
                    if (active.gridEnd != null) {
                        unchangedTarget = Objects.equals(active.gridEnd, endStyle.get(active.name));
                    } else if (active.endTransform != null) {
                        unchangedTarget = Objects.equals(active.endTransform, endStyle.transform);
                    } else {
                        Double target = parseInterpolableStyle(element, active.name, endStyle.get(active.name));
                        unchangedTarget = target != null && Math.abs(target - active.end) <= 0.0001;
                    }
                    if (unchangedTarget) {
                        parsed.removeIf(replacement -> replacement.name.equals(active.name));
                        parsed.add(active);
                    }
                }
            }
            if (parsed.isEmpty()) {
                cancel(element);
                return;
            }
            if (hasSameTransitionTargets(existing, parsed)) {
                return;
            }
            retargetFromActiveTransition(existing, parsed, Window.window.animationTimeMillis());
            workList.put(element.uuid, parsed);
        }
        if (element.document != null) {
            element.document.setTransitionActive(element, true);
        }
        primeCurrentFrameStyle(element, endStyle);
    }

    public static boolean isActive(Element element) {
        synchronized (LOCK) {
            return workList.containsKey(element.uuid);
        }
    }

    private static void cancel(Element element) {
        if (element == null) return;
        List<Transition> cancelled;
        synchronized (LOCK) {
            cancelled = workList.remove(element.uuid);
        }
        if (cancelled != null && !cancelled.isEmpty()) {
            invalidateCancelledTransitionCaches(element, cancelled);
        }
        if (element.document != null) {
            element.document.setTransitionActive(element, false);
        }
    }

    /**
     * A rapid state flip can coalesce back to the same raw style while an
     * earlier transition is still sampled at an intermediate value. Cancelling
     * that transition must invalidate its committed geometry; otherwise the
     * prior transform remains drawable even though the raw style has won.
     */
    private static void invalidateCancelledTransitionCaches(Element element, List<Transition> cancelled) {
        boolean layoutChanged = cancelled.stream().anyMatch(transition -> isLayoutProperty(transition.name));
        boolean transformChanged = cancelled.stream().anyMatch(transition -> "transform".equals(transition.name));
        boolean rectChanged = cancelled.stream().anyMatch(transition -> isRectProperty(transition.name));
        boolean compositingChanged = cancelled.stream().anyMatch(transition -> "opacity".equals(transition.name)
                || transition.name.startsWith("filter-"));
        if (layoutChanged) {
            element.forEachRoute(routeElement -> {
                var renderer = routeElement.getRenderer();
                renderer.invalidateLayoutVersion();
                renderer.size.clear();
                renderer.box.clear();
                renderer.position.clear();
            });
        }
        StyleFrameCache.invalidate(element);

        var renderer = element.getRenderer();
        if (rectChanged && !layoutChanged) {
            renderer.clearVisualBoxCache();
            renderer.invalidateStyleVersion();
        }
        renderer.invalidateTransformVersion();
        renderer.transform.clear();
        renderer.opacity.clear();
        renderer.filter.clear();
        renderer.backdropFilter.clear();
        renderer.background.clear();
        renderer.text.clear();
        renderer.wrappedText.clear();
        if (element.document != null) {
            int dirty = Drawer.REPAINT;
            if (layoutChanged) dirty |= Drawer.RELAYOUT | Drawer.HITTEST;
            else if (transformChanged || rectChanged) dirty |= Drawer.COMMIT_LAYOUT | Drawer.HITTEST;
            if (compositingChanged) dirty |= Drawer.REORDER;
            element.document.markDirty(element, dirty);
        }
    }

    private static boolean hasSameTransitionTargets(List<Transition> existing, List<Transition> next) {
        if (existing == null || existing.isEmpty() || next == null || existing.size() != next.size()) {
            return false;
        }
        HashMap<String, Transition> byName = new HashMap<>();
        for (Transition transition : existing) {
            byName.put(transition.name, transition);
        }
        for (Transition transition : next) {
            Transition old = byName.get(transition.name);
            if (old == null) return false;
            if (Math.abs(old.end - transition.end) > 0.0001) return false;
            if (Math.abs(old.duration - transition.duration) > 0.0001) return false;
            if (Math.abs(old.delay - transition.delay) > 0.0001) return false;
            if (!Objects.equals(old.timing, transition.timing)) return false;
            if (!Objects.equals(old.endTransform, transition.endTransform)) return false;
            if (!Objects.equals(old.gridEnd, transition.gridEnd)) return false;
        }
        return true;
    }

    public static boolean affectsFilter(Element element) {
        synchronized (LOCK) {
            List<Transition> transitions = workList.get(element.uuid);
            if (transitions == null || transitions.isEmpty()) return false;
            for (Transition transition : transitions) {
                String n = transition.name;
                if (n == null) continue;
                if (n.startsWith("filter-") || n.equals("opacity")) {
                    return true;
                }
            }
            return false;
        }
    }

    public static boolean updateStyle(Element element, Style originStyle) {
        List<Transition> transitions;
        synchronized (LOCK) {
            transitions = workList.get(element.uuid);
            if (transitions == null || transitions.isEmpty()) return false;
        }

        long now = Window.window.animationTimeMillis();
        ChangeBuffer changes = CHANGE_BUFFER.get();
        Map<String, String> gridChanges = new LinkedHashMap<>();
        changes.clear();
        try {
            boolean stillActive;
            synchronized (LOCK) {
                transitions = workList.get(element.uuid);
                if (transitions == null || transitions.isEmpty()) return false;

                for (ListIterator<Transition> it = transitions.listIterator(); it.hasNext(); ) {
                    Transition t = it.next();
                    if (t.startTime < 0) {
                        t = new Transition(t.name, t.start, t.end, t.duration, t.delay, now, t.timing,
                                t.startTransform, t.endTransform, t.gridStart, t.gridEnd);
                        it.set(t);
                    }
                    if (t.gridStart != null && now < t.startTime + t.delay) {
                        gridChanges.put(t.name, t.gridStart);
                        continue;
                    }
                    if (t.duration <= 0.0) {
                        if (t.gridStart != null && t.gridEnd != null) gridChanges.put(t.name, t.gridEnd);
                        else if (t.endTransform != null) originStyle.transform = t.endTransform;
                        else addChange(changes, t.name, t.end);
                        it.remove();
                        continue;
                    }
                    double progress = (now - t.startTime - t.delay) / t.duration;
                    if (progress < 0) {
                        if (t.startTransform != null) originStyle.transform = t.startTransform;
                        continue;
                    }
                    if (progress > 1) progress = 1;

                    double fraction = Animation.applyTiming(progress, t.timing);
                    if (t.gridStart != null && t.gridEnd != null) {
                        gridChanges.put(t.name, interpolateGridTracks(t.gridStart, t.gridEnd, fraction));
                    } else if (t.endTransform != null) {
                        originStyle.transform = progress >= 1 ? t.endTransform
                                : Transform.interpolateTransformCss(t.startTransform, t.endTransform,
                                        getOffset(t.name, t.start, t.end, fraction));
                    } else {
                        addChange(changes, t.name, getOffset(t.name, t.start, t.end, fraction));
                    }
                    if (progress >= 1) it.remove();
                }

                if (transitions.isEmpty()) {
                    workList.remove(element.uuid);
                    stillActive = false;
                } else {
                    stillActive = true;
                }
            }

            if (!changes.isEmpty()) {
                applyChanges(originStyle, changes);
            }
            for (Map.Entry<String, String> change : gridChanges.entrySet()) {
                originStyle.update(change.getKey(), change.getValue());
            }

            return stillActive;
        } finally {
            changes.clear();
        }
    }

    public static void applyChanges(Style style, List<Change> changes) {
        Filter.readTransition(changes, style);
        Box.readShadowTransition(changes, style);
        changes.forEach(c -> {
            if (c.name.equals("opacity")) style.opacity = String.valueOf(c.value);
            else merge(style, c.name, c.value);
        });
    }

    public static boolean affectsLayout(Element element) {
        return anyActiveTransitionMatches(element, Transition::isLayoutProperty);
    }

    public static boolean affectsFlowLayout(Element element) {
        return anyActiveTransitionMatches(element, name -> isLayoutProperty(name)
                && !isLocalGeometryProperty(name));
    }

    public static boolean isLocalGeometryProperty(String name) {
        return "width".equals(name) || "height".equals(name)
                || "top".equals(name) || "right".equals(name)
                || "bottom".equals(name) || "left".equals(name);
    }

    public static boolean affectsTransform(Element element) {
        return anyActiveTransitionMatches(element, name -> name.equals("transform"));
    }

    public static boolean affectsRect(Element element) {
        return anyActiveTransitionMatches(element, Transition::isRectProperty);
    }

    private static boolean anyActiveTransitionMatches(Element element, java.util.function.Predicate<String> predicate) {
        if (element == null || predicate == null) return false;
        synchronized (LOCK) {
            List<Transition> transitions = workList.get(element.uuid);
            if (transitions == null || transitions.isEmpty()) return false;
            for (Transition transition : transitions) {
                String name = transition.name;
                if (name != null && predicate.test(name)) return true;
            }
            return false;
        }
    }

    private static boolean isLayoutProperty(String name) {
        if (name == null) return false;
        return name.equals("width") || name.equals("height")
                || name.equals("top") || name.equals("right") || name.equals("bottom") || name.equals("left")
                || name.startsWith("margin-") || name.startsWith("padding-")
                || (name.startsWith("border-") && name.endsWith("-width"))
                || name.equals("gap") || name.equals("row-gap") || name.equals("column-gap")
                || name.equals("grid-template-columns") || name.equals("grid-template-rows");
    }

    private static boolean isRectProperty(String name) {
        if (name == null) return false;
        return isLayoutProperty(name)
                || name.equals("background-color")
                || name.equals("border-radius")
                || name.startsWith("box-shadow-");
    }

    private static void primeCurrentFrameStyle(Element element, Style endStyle) {
        if (!StyleFrameCache.isActive()) return;
        Style animated = endStyle.clone();
        boolean stillActive = updateStyle(element, animated);
        StyleFrameCache.put(element, animated);
        element.getRenderer().transform.clear();
        element.getRenderer().filter.clear();
        element.getRenderer().backdropFilter.clear();
        if (!Objects.equals(animated.boxShadow, endStyle.boxShadow)) {
            element.getRenderer().clearVisualBoxCache();
            element.getRenderer().invalidateStyleVersion();
        }
        if (element.document != null && !stillActive) {
            element.document.setTransitionActive(element, false);
        }
    }

    public static double getOffset(String name, double start, double end, double progress) {
        if (name.contains("color")) return Color.mixColors(start, end, progress);
        return (end - start) * progress + start;
    }

    public static double parseTime(String token) {
        if (token == null || token.isEmpty() || "unset".equals(token)) return 0;
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || "unset".equals(t)) return 0;
        if (t.endsWith("ms")) {
            Double value = Size.parseNumber(t.substring(0, t.length() - 2));
            return value == null ? 0 : value;
        }
        if (t.endsWith("s")) {
            Double value = Size.parseNumber(t.substring(0, t.length() - 1));
            return value == null ? 0 : value * 1000;
        }
        Double value = Size.parseNumber(t);
        return value == null ? 0 : value * 1000;
    }

    public static double parseStyle(String name, String value) {
        if (value == null || value.equals("unset") || value.isEmpty()) {
            return 0;
        }
        if (name.contains("color")) return new Color(value).getValue();
        if (name.equals("opacity")) return Double.parseDouble(value);
        Double parsed = Size.parseNumber(value);
        return parsed == null ? 0 : parsed;
    }

    private static double parseStyle(Element element, String name, String value) {
        if (value == null || value.equals("unset") || value.isEmpty()) {
            return 0;
        }
        if (name.contains("color") || name.equals("opacity")) {
            return parseStyle(name, value);
        }
        Double resolved = Size.tryResolveLength(value, transitionPercentBasis(element, name));
        if (resolved != null) return resolved;
        return parseStyle(name, value);
    }

    static double transitionPercentBasis(Element element, String name) {
        if (element != null && !"fixed".equals(element.getRawComputedStyle().position)) {
            Element containing = element.parentElement;
            boolean positioned = "absolute".equals(element.getRawComputedStyle().position);
            while (positioned && containing != null
                    && "static".equals(containing.getRawComputedStyle().position)) {
                containing = containing.parentElement;
            }
            if (containing != null) {
                io.github.kltyton.kltytonui.render.Rect committed = containing.getRenderer().getCommittedRect();
                if (committed != null) {
                    Size size = positioned ? committed.getBodyRectSize() : committed.getElementSize();
                    double extent = isVerticalLengthProperty(name) ? size.height() : size.width();
                    if (!positioned) {
                        extent -= isVerticalLengthProperty(name)
                                ? committed.box.getBorderVertical() + committed.box.getPaddingVertical()
                                : committed.box.getBorderHorizontal() + committed.box.getPaddingHorizontal();
                    }
                    return Math.max(0, extent);
                }
            }
        }
        if (element != null && isInsetProperty(name)) {
            String position = element.getRawComputedStyle().position;
            if ("fixed".equals(position)) {
                Size viewport = Size.getWindowSize();
                return isVerticalLengthProperty(name) ? viewport.height() : viewport.width();
            }
            if ("absolute".equals(position)) {
                // 样式重算阶段不做强制布局：containing block 尺寸已缓存时取精确基准，
                // 否则回退到父级 scale，避免为解析 transition 反复触发整树布局。
                Double containingBlock = isVerticalLengthProperty(name)
                        ? Size.getCachedContainingBlockPaddingBoxHeight(element)
                        : Size.getCachedContainingBlockPaddingBoxWidth(element);
                if (containingBlock != null) return containingBlock;
            }
        }
        if (element == null) {
            Size viewport = Size.getWindowSize();
            return isVerticalLengthProperty(name) ? viewport.height() : viewport.width();
        }
        return isVerticalLengthProperty(name)
                ? Size.getScaleHeight(element)
                : Size.getScaleWidth(element);
    }

    private static boolean isVerticalLengthProperty(String name) {
        if (name == null) return false;
        return name.equals("top")
                || name.equals("bottom")
                || name.equals("height")
                || name.equals("min-height")
                || name.equals("max-height")
                || name.equals("minHeight")
                || name.equals("maxHeight")
                || name.equals("margin-top")
                || name.equals("margin-bottom")
                || name.equals("marginTop")
                || name.equals("marginBottom")
                || name.equals("padding-top")
                || name.equals("padding-bottom")
                || name.equals("paddingTop")
                || name.equals("paddingBottom")
                || name.equals("border-top-width")
                || name.equals("border-bottom-width")
                || name.equals("borderTop")
                || name.equals("borderBottom");
    }

    private static boolean isInsetProperty(String name) {
        return "top".equals(name) || "right".equals(name) || "bottom".equals(name) || "left".equals(name);
    }

    public static void merge(Style style, String name, double value) {
        if (name.contains("color")) {
            style.update(name, new Color(value).toRgbaString());
        } else if (name.equals("opacity")) {
            style.opacity = String.valueOf(value);
        } else {
            style.update(name, String.format("%.2fpx", value));
        }
    }

    private static String resolveTransitionSpec(Style startStyle, Style endStyle) {
        // 与浏览器一致：优先使用“目标状态”上的 transition 定义（例如 :active 进入态）
        if (endStyle.transition != null && !endStyle.transition.isBlank() && !endStyle.transition.equals("none")) {
            return endStyle.transition;
        }
        return startStyle.transition == null ? "none" : startStyle.transition;
    }

    private record TransitionSpec(String property, double duration, double delay, String timing) { }

    private static final ThreadLocal<Map<String, List<TransitionSpec>>> SPEC_CACHE = ThreadLocal.withInitial(
            () -> new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<TransitionSpec>> eldest) {
                    return size() > 256;
                }
            });

    private static List<Transition> parseTransitions(Element element, Style startStyle, Style endStyle, String raw) {
        List<Transition> result = new ArrayList<>();
        for (TransitionSpec spec : transitionSpecs(raw)) {
            if ("all".equals(spec.property)) {
                ANIMATABLE.forEach(name -> build(element, startStyle, endStyle, result, name,
                        spec.duration, spec.delay, spec.timing));
            } else {
                build(element, startStyle, endStyle, result, spec.property,
                        spec.duration, spec.delay, spec.timing);
            }
        }
        return result;
    }

    private static List<TransitionSpec> transitionSpecs(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        if (raw.length() > 4096) return parseTransitionSpecs(raw);
        return SPEC_CACHE.get().computeIfAbsent(raw, Transition::parseTransitionSpecs);
    }

    private static List<TransitionSpec> parseTransitionSpecs(String raw) {
        List<TransitionSpec> result = new ArrayList<>();
        for (String part : splitTransitionParts(raw)) {
            String prop = "all";
            String timing = "ease";
            double dur = 0, del = 0;
            int timeCount = 0;
            boolean invalid = false;
            for (String token : splitTransitionTokens(part)) {
                String normalized = token.toLowerCase(Locale.ROOT);
                if (isTimeToken(normalized)) {
                    if (timeCount++ == 0) {
                        dur = parseTime(normalized);
                        if (dur < 0.0) invalid = true;
                    } else if (timeCount == 2) {
                        del = parseTime(normalized);
                    } else {
                        invalid = true;
                    }
                } else if (isTimingFunctionToken(normalized)) {
                    timing = normalized;
                } else if (normalized.startsWith("steps(") || normalized.startsWith("cubic-bezier(")) {
                    invalid = true;
                } else if ("none".equals(normalized)) {
                    prop = "none";
                } else {
                    prop = token;
                }
            }
            if (invalid || "none".equals(prop)) continue;
            result.add(new TransitionSpec(prop, dur, del, timing));
        }
        return List.copyOf(result);
    }

    private static List<Transition> deferStartTimes(List<Transition> transitions) {
        if (transitions == null || transitions.isEmpty()) return transitions;
        ArrayList<Transition> result = new ArrayList<>(transitions.size());
        for (Transition transition : transitions) {
            result.add(new Transition(
                    transition.name,
                    transition.start,
                    transition.end,
                    transition.duration,
                    transition.delay,
                    -1L,
                    transition.timing,
                    transition.startTransform,
                    transition.endTransform,
                    transition.gridStart,
                    transition.gridEnd
            ));
        }
        return result;
    }

    private static void build(Element element, Style sS, Style eS, List<Transition> res, String name, double dur,
                              double del, String timing) {
        name = name.toLowerCase(Locale.ROOT);
        if (name.equals("border-color") || name.equals("border-width")) {
            String suffix = name.substring("border".length());
            for (String side : List.of("top", "right", "bottom", "left")) {
                build(element, sS, eS, res, "border-" + side + suffix, dur, del, timing);
            }
            return;
        }
        if (name.equals("gap")) {
            build(element, sS, eS, res, "row-gap", dur, del, timing);
            build(element, sS, eS, res, "column-gap", dur, del, timing);
            return;
        }
        if (!isAnimatable(name)) return;
        boolean borderComponent = name.startsWith("border-") && (name.endsWith("-color") || name.endsWith("-width"));
        if (!borderComponent && Objects.equals(sS.get(name), eS.get(name))) return;
        if (isGridTrackProperty(name) || name.equals("row-gap") || name.equals("column-gap")) {
            Transition grid = gridTrackTransition(sS.get(name), eS.get(name), name, dur, del, timing);
            if (grid != null) res.add(grid);
            return;
        }
        int first = res.size();
        if (name.equals("transform")) {
            if (!Objects.equals(sS.transform, eS.transform)) {
                res.add(new Transition(name, 0, 1, dur, del, Window.window.animationTimeMillis(), timing,
                        sS.transform, eS.transform));
            }
        }
        else if (name.equals("filter")) Filter.createTransition(sS, eS, res, dur, del);
        else if (name.equals("box-shadow")) Box.createShadowTransition(sS, eS, res, dur, del);
        else if (Box.matchStyleName(name)) Box.createTransition(sS, eS, res, name, dur, del);
        else {
            Double s = parseInterpolableStyle(element, name, sS.get(name));
            Double e = parseInterpolableStyle(element, name, eS.get(name));
            if (s == null || e == null) return;
            if (Math.abs(s - e) > 0.0001) res.add(new Transition(name, s, e, dur, del, Window.window.animationTimeMillis()));
        }
        for (int i = first; i < res.size(); i++) {
            Transition transition = res.get(i);
            res.set(i, new Transition(transition.name, transition.start, transition.end, transition.duration,
                    transition.delay, transition.startTime, timing, transition.startTransform, transition.endTransform,
                    transition.gridStart, transition.gridEnd));
        }
    }

    private static List<String> splitTransitionParts(String raw) {
        ArrayList<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')' && depth > 0) depth--;
            else if (ch == ',' && depth == 0) {
                parts.add(raw.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(raw.substring(start));
        return parts;
    }

    private static List<String> splitTransitionTokens(String part) {
        ArrayList<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < part.length(); i++) {
            char ch = part.charAt(i);
            if (Character.isWhitespace(ch) && depth == 0) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            if (ch == '(') depth++;
            else if (ch == ')' && depth > 0) depth--;
            current.append(ch);
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }

    private static boolean isTimeToken(String token) {
        return Animation.isTimeToken(token);
    }

    private static boolean isTimingFunctionToken(String token) {
        return Animation.isTimingFunctionToken(token);
    }

    private static void retargetFromActiveTransition(List<Transition> existing, List<Transition> next, long now) {
        if (existing == null || existing.isEmpty() || next == null || next.isEmpty()) return;
        HashMap<String, Transition> activeByName = new HashMap<>();
        for (Transition transition : existing) {
            activeByName.put(transition.name, transition);
        }
        for (ListIterator<Transition> it = next.listIterator(); it.hasNext(); ) {
            Transition replacement = it.next();
            Transition active = activeByName.get(replacement.name);
            if (active == null) continue;

            if (active.gridStart != null && replacement.gridStart != null) {
                double progress = Animation.applyTiming(transitionProgress(active, now), active.timing);
                String current = interpolateGridTracks(active.gridStart, active.gridEnd, progress);
                double duration = replacement.duration;
                if (isReversing(active, replacement.gridEnd)) duration *= Math.max(0, Math.min(1, Math.abs(progress)));
                it.set(new Transition(replacement.name, 0, 1, duration, replacement.delay,
                        replacement.startTime, replacement.timing, null, null, current, replacement.gridEnd));
                continue;
            }
            double current = currentValue(active, now);
            double duration = replacement.duration;
            if (replacement.endTransform != null && active.endTransform != null) {
                if (Objects.equals(replacement.endTransform, active.startTransform)) {
                    duration *= Math.min(1.0, Math.abs(current - active.start));
                }
                it.set(new Transition(replacement.name, 0, 1, duration, replacement.delay,
                        replacement.startTime, replacement.timing,
                        Transform.interpolateTransformCss(active.startTransform, active.endTransform, current),
                        replacement.endTransform));
                continue;
            }
            if (isReversing(active, replacement.end)) {
                double span = Math.abs(active.end - active.start);
                if (span > 0.0001) {
                    duration *= Math.min(1.0, Math.abs(current - active.start) / span);
                }
            }
            it.set(new Transition(replacement.name, current, replacement.end, duration, replacement.delay,
                    replacement.startTime, replacement.timing));
        }
    }

    private static boolean isReversing(Transition active, double newEnd) {
        return Math.abs(newEnd - active.start) <= 0.0001;
    }

    private static boolean isReversing(Transition active, String newEnd) {
        return active.gridEnd != null && Objects.equals(active.gridStart, newEnd);
    }

    private static double transitionProgress(Transition transition, long now) {
        if (transition.startTime < 0 || now <= transition.startTime + transition.delay) return 0;
        if (transition.duration <= 0) return 1;
        return Math.max(0, Math.min(1, (now - transition.startTime - transition.delay) / transition.duration));
    }

    private static double currentValue(Transition transition, long now) {
        if (transition.startTime < 0 || now <= transition.startTime + transition.delay) return transition.start;
        if (transition.duration <= 0.0) return transition.end;
        double progress = (now - transition.startTime - transition.delay) / transition.duration;
        if (progress <= 0.0) return transition.start;
        if (progress >= 1.0) return transition.end;
        return getOffset(transition.name, transition.start, transition.end,
                Animation.applyTiming(progress, transition.timing));
    }

    private static final Set<String> ANIMATABLE = Set.of(
            "opacity", "width", "height", "filter", "transform", "color", "background-color",
            "top", "left", "right", "bottom",
            "margin-top", "margin-right", "margin-bottom", "margin-left",
            "padding-top", "padding-right", "padding-bottom", "padding-left",
            "border-top-width", "border-right-width", "border-bottom-width", "border-left-width",
            "border-radius", "box-shadow", "row-gap", "column-gap",
            "grid-template-columns", "grid-template-rows"
    );

    private static boolean isAnimatable(String name) {
        return "transform".equals(name)
                || "filter".equals(name)
                || Box.matchStyleName(name)
                || ANIMATABLE.contains(name);
    }

    private static boolean isGridTrackProperty(String name) {
        return "grid-template-columns".equals(name) || "grid-template-rows".equals(name);
    }

    private static Transition gridTrackTransition(String start, String end, String name,
                                                  double duration, double delay, String timing) {
        if (Objects.equals(start, end)) return null;
        List<GridTrackValue> s = parseGridTracks(start), e = parseGridTracks(end);
        if (s == null || e == null || s.size() != e.size()) return null;
        for (int i = 0; i < s.size(); i++) if (s.get(i).fraction != e.get(i).fraction) return null;
        String from = serializeGridTracks(s), to = serializeGridTracks(e);
        if (from.equals(to)) return null;
        return new Transition(name, 0, 1, duration, delay, Window.window.animationTimeMillis(), timing,
                null, null, from, to);
    }

    private static List<GridTrackValue> parseGridTracks(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("[") || raw.contains("]")) return null;
        List<String> tokens = splitTransitionTokens(raw);
        if (tokens.isEmpty()) return null;
        ArrayList<GridTrackValue> values = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            GridTrackValue value = parseGridValue(token);
            if (value == null) return null;
            values.add(value);
        }
        return values;
    }

    private static String interpolateGridTracks(String start, String end, double progress) {
        List<GridTrackValue> s = parseGridTracks(start), e = parseGridTracks(end);
        ArrayList<GridTrackValue> result = new ArrayList<>(s.size());
        for (int i = 0; i < s.size(); i++) {
            GridTrackValue sv = s.get(i), ev = e.get(i);
            result.add(new GridTrackValue(sv.pixels + (ev.pixels - sv.pixels) * progress,
                    sv.percent + (ev.percent - sv.percent) * progress, sv.fraction));
        }
        return serializeGridTracks(result);
    }

    private static String serializeGridTracks(List<GridTrackValue> values) {
        StringJoiner result = new StringJoiner(" ");
        for (GridTrackValue value : values) result.add(value.css());
        return result.toString();
    }

    private static final Pattern GRID_FRACTION = Pattern.compile(
            "[+]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)(e[+-]?[0-9]+)?fr");

    private static GridTrackValue parseGridValue(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (GRID_FRACTION.matcher(lower).matches()) {
            double value = Double.parseDouble(lower.substring(0, lower.length() - 2));
            return Double.isFinite(value) ? new GridTrackValue(value, 0, true) : null;
        }
        if (lower.contains("(") && !lower.startsWith("calc(")) return null;
        CssLength length = CssLength.parse(token);
        Double zero = length.resolve(0), hundred = length.resolve(100);
        if (zero == null || hundred == null || !Double.isFinite(zero) || !Double.isFinite(hundred)) return null;
        return new GridTrackValue(zero, hundred - zero, false);
    }

    private record GridTrackValue(double pixels, double percent, boolean fraction) {
        String css() {
            if (fraction) return String.format(Locale.ROOT, "%.6ffr", Math.max(0, pixels));
            if (percent == 0) return String.format(Locale.ROOT, "%.6fpx", Math.max(0, pixels));
            if (pixels == 0) return String.format(Locale.ROOT, "%.6f%%", Math.max(0, percent));
            return String.format(Locale.ROOT, "calc(%.6fpx %s %.6f%%)", pixels,
                    percent < 0 ? "-" : "+", Math.abs(percent));
        }
    }

    private static Double parseInterpolableStyle(Element element, String name, String value) {
        if (value == null || value.isBlank()) return null;
        if (name.contains("color")) return (double) new Color(value).getValue();
        if (name.equals("opacity")) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        // CSS-wide keywords and intrinsic sizing keywords such as auto are
        // discrete values. Browsers do not coerce them to 0px for transitions.
        io.github.kltyton.kltytonui.layout.CssLength length = io.github.kltyton.kltytonui.layout.CssLength.parse(value);
        return length.resolve(length.dependsOnPercentage() ? transitionPercentBasis(element, name) : 0);
    }

}
