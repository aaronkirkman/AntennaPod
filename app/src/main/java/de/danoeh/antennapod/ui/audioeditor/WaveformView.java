package de.danoeh.antennapod.ui.audioeditor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import de.danoeh.antennapod.ui.common.ThemeUtils;

import java.util.Collections;
import java.util.List;

/**
 * Draws a full-episode amplitude waveform with a playhead and highlighted cut regions.
 * Tapping the view seeks to the tapped position via {@link #setOnSeekListener(OnSeekListener)}.
 */
public class WaveformView extends View {
    private static final float BAR_WIDTH_DP = 6f;

    private float[] amplitudes = new float[0];
    private long durationMs = 0;
    private long playheadMs = 0;
    private List<CutRegion> cutRegions = Collections.emptyList();
    private OnSeekListener onSeekListener;
    private float barWidthPx;

    private final Paint barPaint = new Paint();
    private final Paint cutRegionPaint = new Paint();
    private final Paint playheadPaint = new Paint();

    public interface OnSeekListener {
        void onSeek(long positionMs);
    }

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WaveformView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barPaint.setAntiAlias(true);
        barPaint.setColor(ThemeUtils.getColorFromAttr(getContext(), android.R.attr.textColorSecondary));
        cutRegionPaint.setAntiAlias(true);
        cutRegionPaint.setColor(0x66FF0000);
        playheadPaint.setAntiAlias(true);
        playheadPaint.setColor(ThemeUtils.getColorFromAttr(getContext(), android.R.attr.colorAccent));
        playheadPaint.setStrokeWidth(4f);
        barWidthPx = BAR_WIDTH_DP * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = (int) (amplitudes.length * barWidthPx);
        int width = resolveSizeAndState(desiredWidth, widthMeasureSpec, 0);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    public void setAmplitudes(float[] amplitudes, long durationMs) {
        this.amplitudes = amplitudes;
        this.durationMs = durationMs;
        requestLayout();
        invalidate();
    }

    public void setPlayheadMs(long playheadMs) {
        this.playheadMs = playheadMs;
        invalidate();
    }

    public void setCutRegions(List<CutRegion> cutRegions) {
        this.cutRegions = cutRegions;
        invalidate();
    }

    public void setOnSeekListener(OnSeekListener listener) {
        this.onSeekListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (durationMs > 0 && (event.getAction() == MotionEvent.ACTION_DOWN
                || event.getAction() == MotionEvent.ACTION_MOVE)) {
            float fraction = Math.max(0f, Math.min(1f, event.getX() / getWidth()));
            if (onSeekListener != null) {
                onSeekListener.onSeek((long) (fraction * durationMs));
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (amplitudes.length == 0 || width == 0) {
            return;
        }

        float barWidth = (float) width / amplitudes.length;
        for (int i = 0; i < amplitudes.length; i++) {
            float barHeight = amplitudes[i] * height;
            float x = i * barWidth;
            canvas.drawRect(x, (height - barHeight) / 2f, x + Math.max(1f, barWidth - 1f),
                    (height + barHeight) / 2f, barPaint);
        }

        if (durationMs > 0) {
            for (CutRegion region : cutRegions) {
                float left = (region.startMs / (float) durationMs) * width;
                float right = (region.endMs / (float) durationMs) * width;
                canvas.drawRect(left, 0, right, height, cutRegionPaint);
            }

            float playheadX = (playheadMs / (float) durationMs) * width;
            canvas.drawLine(playheadX, 0, playheadX, height, playheadPaint);
        }
    }
}
