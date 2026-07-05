package de.danoeh.antennapod.ui.audioeditor;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Caches computed waveform amplitude buckets on disk, keyed by the source file path,
 * so re-opening the editor for the same episode skips recomputation entirely.
 */
public class WaveformCache {

    private WaveformCache() {
    }

    public static float[] read(Context context, String path, int numBuckets) {
        File file = cacheFile(context, path, numBuckets);
        if (!file.exists()) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int count = in.readInt();
            if (count != numBuckets) {
                return null;
            }
            float[] amplitudes = new float[count];
            for (int i = 0; i < count; i++) {
                amplitudes[i] = in.readFloat();
            }
            return amplitudes;
        } catch (IOException e) {
            return null;
        }
    }

    public static void write(Context context, String path, int numBuckets, float[] amplitudes) {
        File file = cacheFile(context, path, numBuckets);
        file.getParentFile().mkdirs();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(amplitudes.length);
            for (float amplitude : amplitudes) {
                out.writeFloat(amplitude);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static File cacheFile(Context context, String path, int numBuckets) {
        String key = path.hashCode() + "_" + numBuckets;
        return new File(new File(context.getCacheDir(), "waveforms"), key + ".cache");
    }
}
