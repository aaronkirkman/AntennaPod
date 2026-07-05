package de.danoeh.antennapod.ui.audioeditor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Computes a fixed number of amplitude buckets representing a full episode's waveform.
 * Rather than decoding the entire file, it seeks to each bucket's timestamp and decodes only
 * a short window there, so the time this takes stays roughly constant regardless of episode length.
 */
public class WaveformExtractor {
    private static final long SAMPLE_WINDOW_US = 150_000L;

    private WaveformExtractor() {
    }

    public interface ProgressListener {
        void onProgress(float fraction);
    }

    public static float[] extract(Context context, String path, int numBuckets,
                                  ProgressListener listener) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, Uri.parse(path), null);
            int trackIndex = selectAudioTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("No audio track found in " + path);
            }
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            extractor.selectTrack(trackIndex);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            if (durationUs <= 0) {
                throw new IOException("Unknown duration for " + path);
            }

            String mime = format.getString(MediaFormat.KEY_MIME);
            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            try {
                codec.configure(format, null, null, 0);
                codec.start();
                return decodeSparse(extractor, codec, durationUs, numBuckets, listener);
            } finally {
                codec.stop();
                codec.release();
            }
        } finally {
            extractor.release();
        }
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private static float[] decodeSparse(MediaExtractor extractor, MediaCodec codec, long durationUs,
                                        int numBuckets, ProgressListener listener) {
        float[] buckets = new float[numBuckets];
        long bucketDurationUs = durationUs / numBuckets;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        for (int bucket = 0; bucket < numBuckets; bucket++) {
            long targetUs = bucket * bucketDurationUs;
            long windowEndUs = targetUs + SAMPLE_WINDOW_US;
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            codec.flush();
            decodeWindow(extractor, codec, windowEndUs, info, buckets, bucket);

            if (listener != null) {
                listener.onProgress((bucket + 1) / (float) numBuckets);
            }
        }

        normalize(buckets);
        return buckets;
    }

    private static void decodeWindow(MediaExtractor extractor, MediaCodec codec, long windowEndUs,
                                     MediaCodec.BufferInfo info, float[] buckets, int bucketIndex) {
        boolean inputDone = false;
        boolean windowDone = false;

        while (!windowDone) {
            if (!inputDone) {
                int inputBufferId = codec.dequeueInputBuffer(10000);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                    int sampleSize = inputBuffer != null ? extractor.readSampleData(inputBuffer, 0) : -1;
                    if (sampleSize < 0 || extractor.getSampleTime() > windowEndUs) {
                        codec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outputBufferId = codec.dequeueOutputBuffer(info, 10000);
            if (outputBufferId >= 0) {
                if (info.size > 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
                    if (outputBuffer != null) {
                        updateBucketMax(outputBuffer, info, buckets, bucketIndex);
                    }
                }
                boolean isEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(outputBufferId, false);
                if (isEos || info.presentationTimeUs >= windowEndUs) {
                    windowDone = true;
                }
            }
        }
    }

    private static void updateBucketMax(ByteBuffer outputBuffer, MediaCodec.BufferInfo info,
                                        float[] buckets, int bucketIndex) {
        ByteBuffer buffer = outputBuffer.duplicate();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(info.offset);
        buffer.limit(info.offset + info.size);
        while (buffer.remaining() >= 2) {
            short sample = buffer.getShort();
            float amplitude = Math.abs(sample) / 32768f;
            if (amplitude > buckets[bucketIndex]) {
                buckets[bucketIndex] = amplitude;
            }
        }
    }

    private static void normalize(float[] buckets) {
        float max = 0f;
        for (float bucket : buckets) {
            max = Math.max(max, bucket);
        }
        if (max > 0f) {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] /= max;
            }
        }
    }
}
