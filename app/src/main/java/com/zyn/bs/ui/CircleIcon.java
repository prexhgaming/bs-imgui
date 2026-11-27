package com.zyn.bs.ui;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

/**
 * Turns an arbitrary app icon ({@link Drawable}, including adaptive icons) into
 * a circular drawable at a fixed pixel size.
 */
public final class CircleIcon {

    private CircleIcon() {
    }

    /**
     * @param source icon to clip; may be {@code null}
     * @param sizePx target diameter in pixels
     * @return circular drawable, or {@code null} when {@code source} is null
     */
    public static Drawable of(Resources resources, Drawable source, int sizePx) {
        if (source == null || sizePx <= 0) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        source.setBounds(0, 0, sizePx, sizePx);
        source.draw(canvas);

        RoundedBitmapDrawable rounded =
                RoundedBitmapDrawableFactory.create(resources, bitmap);
        rounded.setCircular(true);
        rounded.setAntiAlias(true);
        return rounded;
    }
}
