package com.severedchains.launcher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Loader/renderer for the game's original bitmap font
 * ({@code gfx/fonts/default.png} + {@code default.json}).
 *
 * <p>Used to give the on-screen controller the authentic Legend of Dragoon
 * look instead of the system typeface. The atlas is only 256x256 with 8x16
 * glyph cells, so it is drawn with nearest-neighbour filtering to keep the
 * pixelated PS1 style. {@code colour:true} glyphs are tinted to the requested
 * colour; {@code colour:false} glyphs (the pre-coloured special symbols) are
 * drawn as-is.
 */
public final class LodFont {
    private static final String TAG = "LodFont";
    private static final float CELL_H = 16f;

    private final Bitmap atlas;
    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Paint paint = new Paint();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    private LodFont(final Bitmap atlas) {
        this.atlas = atlas;
        this.paint.setFilterBitmap(false);
        this.paint.setAntiAlias(false);
    }

    /** Returns null (never throws) if the atlas is missing or unparsable. */
    public static LodFont load(final Context context) {
        try {
            final Bitmap atlas = BitmapFactory.decodeStream(
                    context.getAssets().open("game/gfx/fonts/default.png"));
            if (atlas == null) return null;

            final String json;
            try (InputStream in = context.getAssets().open("game/gfx/fonts/default.json")) {
                final ByteArrayOutputStream buf = new ByteArrayOutputStream(32768);
                final byte[] tmp = new byte[8192];
                int n;
                while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
                json = new String(buf.toByteArray(), "UTF-8");
            }

            final JSONObject gl = new JSONObject(json).getJSONObject("glyphs");
            final LodFont font = new LodFont(atlas);
            for (final Iterator<String> it = gl.keys(); it.hasNext(); ) {
                final String key = it.next();
                if (key.isEmpty()) continue;
                final int cp;
                if (key.startsWith("0x") || key.startsWith("0X")) {
                    cp = Integer.parseInt(key.substring(2), 16);
                } else {
                    cp = key.codePointAt(0);
                }
                final JSONObject o = gl.getJSONObject(key);
                final Glyph g = new Glyph();
                g.u = o.optInt("texU");
                g.v = o.optInt("texV");
                g.w = o.optInt("w");
                g.h = o.optInt("h");
                g.advance = o.optInt("texW", g.w);
                g.colour = o.optBoolean("colour", true);
                font.glyphs.put(cp, g);
            }
            return font;
        } catch (Throwable t) {
            Log.w(TAG, "font load failed", t);
            return null;
        }
    }

    public float measure(final String text, final float targetHeight) {
        final float scale = targetHeight / CELL_H;
        float w = 0;
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            final Glyph g = glyphs.get(cp);
            if (g != null) w += g.advance * scale;
        }
        return w;
    }

    /** Draws {@code text} horizontally centred on {@code cx}, vertically centred
     *  on {@code cy}, scaled so the cell height is {@code targetHeight}. */
    public void draw(final Canvas canvas, final String text, final float cx,
                     final float cy, final float targetHeight, final int color) {
        final float scale = targetHeight / CELL_H;
        float x = cx - measure(text, targetHeight) / 2f;
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            final Glyph g = glyphs.get(cp);
            if (g == null) continue;
            if (g.h > 0) {
                src.set(g.u, g.v, g.u + g.w, g.v + g.h);
                final int left = Math.round(x);
                final int top = Math.round(cy - g.h * scale / 2f);
                dst.set(left, top, left + Math.max(1, Math.round(g.w * scale)),
                        top + Math.max(1, Math.round(g.h * scale)));
                paint.setColorFilter(g.colour
                        ? new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                        : null);
                canvas.drawBitmap(atlas, src, dst, paint);
            }
            x += g.advance * scale;
        }
    }

    private static final class Glyph {
        int u, v, w, h, advance;
        boolean colour;
    }
}
