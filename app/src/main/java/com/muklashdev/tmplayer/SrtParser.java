package com.muklashdev.tmplayer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal SubRip (.srt) subtitle parser. */
public final class SrtParser {

    public static final class Cue {
        public long startMs;
        public long endMs;
        public String text;
    }

    private static final Pattern TIME = Pattern.compile(
            "(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)");

    private SrtParser() {}

    public static List<Cue> parse(InputStream in) {
        List<Cue> cues = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            Cue cur = null;
            StringBuilder text = new StringBuilder();
            while ((line = br.readLine()) != null) {
                line = line.trim();
                Matcher m = TIME.matcher(line);
                if (m.find()) {
                    if (cur != null) { cur.text = text.toString().trim(); cues.add(cur); }
                    cur = new Cue();
                    cur.startMs = toMs(m, 1);
                    cur.endMs = toMs(m, 5);
                    text.setLength(0);
                } else if (cur != null && !line.isEmpty()
                        && !line.matches("\\d+")) {
                    if (text.length() > 0) text.append('\n');
                    text.append(line.replaceAll("<[^>]+>", ""));
                }
            }
            if (cur != null) { cur.text = text.toString().trim(); cues.add(cur); }
        } catch (Exception ignored) {}
        return cues;
    }

    private static long toMs(Matcher m, int base) {
        long h = Long.parseLong(m.group(base));
        long min = Long.parseLong(m.group(base + 1));
        long s = Long.parseLong(m.group(base + 2));
        long ms = Long.parseLong(m.group(base + 3));
        if (m.group(base + 3).length() == 2) ms *= 10;
        if (m.group(base + 3).length() == 1) ms *= 100;
        return ((h * 60 + min) * 60 + s) * 1000 + ms;
    }

    /** The cue visible at posMs, or null. */
    public static Cue at(List<Cue> cues, long posMs) {
        if (cues == null) return null;
        for (Cue c : cues) {
            if (posMs >= c.startMs && posMs <= c.endMs) return c;
            if (c.startMs > posMs) break;
        }
        return null;
    }
}
