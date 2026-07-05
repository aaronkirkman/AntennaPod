package de.danoeh.antennapod.ui.audioeditor;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Removes the given cut regions from an audio file by concatenating the kept segments
 * with Media3 Transformer, and writes the result to a new file. Must be called on a thread
 * with a Looper (the Transformer requires this), typically the main thread.
 */
public class AudioTrimExporter {

    public interface Callback {
        void onSuccess();

        void onError(Exception e);
    }

    public static void export(Context context, String inputPath, List<CutRegion> cutRegionsToRemove,
                              long durationMs, String outputPath, Callback callback) {
        List<CutRegion> sortedCuts = new ArrayList<>(cutRegionsToRemove);
        Collections.sort(sortedCuts, Comparator.comparingLong(r -> r.startMs));

        List<EditedMediaItem> keptSegments = new ArrayList<>();
        Uri inputUri = Uri.fromFile(new java.io.File(inputPath));
        long previousEndMs = 0;
        for (CutRegion cut : sortedCuts) {
            if (cut.startMs > previousEndMs) {
                keptSegments.add(buildClip(inputUri, previousEndMs, cut.startMs));
            }
            previousEndMs = Math.max(previousEndMs, cut.endMs);
        }
        if (previousEndMs < durationMs) {
            keptSegments.add(buildClip(inputUri, previousEndMs, durationMs));
        }

        if (keptSegments.isEmpty()) {
            callback.onError(new IllegalStateException("Nothing left to export"));
            return;
        }

        EditedMediaItemSequence sequence = new EditedMediaItemSequence.Builder(keptSegments).build();
        Composition composition = new Composition.Builder(Collections.singletonList(sequence)).build();

        Transformer transformer = new Transformer.Builder(context)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(@NonNull Composition composition, @NonNull ExportResult exportResult) {
                        callback.onSuccess();
                    }

                    @Override
                    public void onError(@NonNull Composition composition, @NonNull ExportResult exportResult,
                                       @NonNull ExportException exception) {
                        callback.onError(exception);
                    }
                })
                .build();
        transformer.start(composition, outputPath);
    }

    private static EditedMediaItem buildClip(Uri inputUri, long startMs, long endMs) {
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build())
                .build();
        return new EditedMediaItem.Builder(mediaItem).build();
    }
}
