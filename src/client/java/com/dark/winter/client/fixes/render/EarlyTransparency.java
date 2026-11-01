// Freezes one startup transparency choice without loading Minecraft, renderers or visual settings.
package com.dark.winter.client.fixes.render;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public final class EarlyTransparency {
    public enum Mode { BALANCED, PRECISE }
    static final int MAX_CONFIG_BYTES=4096;
    private static final Selection STARTUP=new Selection();

    /** Called once by GameConfig TAIL with the native-parsed forced API and game directory. */
    public static void capture(boolean forcedVulkan,Path gameDirectory) {
        STARTUP.capture(forcedVulkan,gameDirectory);
    }

    /** Startup-only ABI specialization; no frame path calls or settings polling. */
    public static int coefficientExponent(int original) {
        return selectExponent(original,STARTUP.freeze());
    }

    /** Startup-only named shader specialization, sharing the coefficient decision. */
    public static int shaderRank(int original) {
        return selectRank(original,STARTUP.freeze());
    }

    /** Lock-free read for the declared Java rank mirror after class initialization. */
    public static boolean compact() { return STARTUP.compact(); }
    public static Mode requestedMode() { return STARTUP.requestedMode(); }
    /** Frozen launch capability, independent of active quality and the next-launch preference. */
    public static boolean canSelectBalanced() { return STARTUP.canSelectBalanced(); }

    static int selectExponent(int original,boolean compact) {
        if(compact&&original!=3)throw new IllegalStateException("Unsupported native OIT coefficient exponent");
        return compact?2:original;
    }
    static int selectRank(int original,boolean compact) {
        if(compact&&original!=2)throw new IllegalStateException("Unsupported native OIT shader rank");
        return compact?1:original;
    }

    /** Reads only the mod-owned bounded file; native options.txt is never interpreted here. */
    static Mode readPreference(Path gameDirectory) {
        if(gameDirectory==null)return Mode.PRECISE;
        Path file=gameDirectory.resolve("config").resolve("darkwinter").resolve("transparency.properties");
        try(var input=Files.newInputStream(file)) {
            byte[] bytes=input.readNBytes(MAX_CONFIG_BYTES+1);
            return parsePreference(bytes);
        } catch(NoSuchFileException absent) {
            return Mode.BALANCED;
        } catch(IOException|SecurityException invalid) {
            return Mode.PRECISE;
        }
    }

    /** One exact plain mode assignment; malformed or duplicate preferences retain native precision. */
    static Mode parsePreference(byte[] bytes) {
        if(bytes==null||bytes.length>MAX_CONFIG_BYTES)return Mode.PRECISE;
        final String source;
        try {
            source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch(CharacterCodingException invalid) { return Mode.PRECISE; }
        String text=source.startsWith("\uFEFF")?source.substring(1):source;
        Mode selected=null;
        for(String raw:text.lines().toList()) {
            String line=raw.trim();
            if(line.isEmpty()||line.startsWith("#")||line.startsWith("!"))continue;
            int equal=line.indexOf('=');
            if(equal<0||!line.substring(0,equal).trim().equals("mode")||selected!=null)return Mode.PRECISE;
            String value=line.substring(equal+1).trim();
            if(value.equals("BALANCED"))selected=Mode.BALANCED;
            else if(value.equals("PRECISE"))selected=Mode.PRECISE;
            else return Mode.PRECISE;
        }
        return selected==null?Mode.PRECISE:selected;
    }

    static final class Selection {
        private boolean bound,forcedVulkan,invalid;
        private volatile Mode requested=Mode.PRECISE;
        private volatile Decision decision;

        synchronized void capture(boolean forced,Path directory) {
            // Duplicate and late observations perform no file IO and cannot change frozen ABI.
            if(bound||decision!=null) { bind(forced,Mode.PRECISE);return; }
            bind(forced,readPreference(directory));
        }
        synchronized void bind(boolean forced,Mode mode) {
            if(decision!=null)return;
            if(bound) { invalid=true;return; }
            bound=true;forcedVulkan=forced;requested=mode==null?Mode.PRECISE:mode;
        }
        synchronized boolean freeze() {
            Decision current=decision;
            if(current==null) {
                boolean capable=bound&&!invalid&&forcedVulkan;
                current=new Decision(capable&&requested==Mode.BALANCED,capable);
                decision=current;
            }
            return current.compact();
        }
        boolean compact() { Decision current=decision;return current!=null&&current.compact(); }
        boolean canSelectBalanced() { Decision current=decision;return current!=null&&current.canSelectBalanced(); }
        Mode requestedMode() { return requested; }
    }

    private record Decision(boolean compact,boolean canSelectBalanced) { }
    private EarlyTransparency() { }
}
