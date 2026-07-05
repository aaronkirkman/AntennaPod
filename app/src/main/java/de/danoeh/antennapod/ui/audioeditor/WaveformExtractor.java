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
 * Decodes an audio file once and downsamples it to a fixed number of amplitude buckets,
 * so a full-episode waveform can be drawn without holding the decoded PCM data in memory.
 */
public class WaveformExtractor {

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

            String mime = format.getString(MediaFormat.KEY_MIME);
            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            try {
                codec.configure(format, null, null, 0);
                codec.start();
                return decode(extractor, codec, durationUs, numBuckets, listener);
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

    private static float[] decode(MediaExtractor extractor, MediaCodec codec, long durationUs,
                                  int numBuckets, ProgressListener listener) {
        float[] buckets = new float[numBuckets];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;

        while (!outputDone) {
            if (!inputDone) {
                int inputBufferId = codec.dequeueInputBuffer(10000);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                    int sampleSize = inputBuffer != null ? extractor.readSampleData(inputBuffer, 0) : -1;
                    if (sampleSize < 0) {
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
                if (info.size > 0 && durationUs > 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
                    if (outputBuffer != null) {
                        addSamplesToBuckets(outputBuffer, info, durationUs, buckets);
                    }
                }
                codec.releaseOutputBuffer(outputBufferId, false);
                if (listener != null && durationUs > 0) {
                    listener.onProgress(Math.min(1f, info.presentationTimeUs / (float) durationUs));
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }

        float max = 0f;
        for (float bucket : buckets) {
            max = Math.max(max, bucket);
        }
        if (max > 0f) {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] /= max;
            }
        }
        return buckets;
    }

    private static void addSamplesToBuckets(ByteBuffer outputBuffer, MediaCodec.BufferInfo info,
                                            long durationUs, float[] buckets) {
        ByteBuffer buffer = outputBuffer.duplicate();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(info.offset);
        buffer.limit(info.offset + info.size);
        int sampleCount = buffer.remaining() / 2;
        int bucketIndex = (int) ((info.presentationTimeUs / (float) durationUs) * buckets.length);
        bucketIndex = Math.max(0, Math.min(buckets.length - 1, bucketIndex));
        for (int i = 0; i < sampleCount; i++) {
            short sample = buffer.getShort();
            float amplitude = Math.abs(sample) / 32768f;
            if (amplitude > buckets[bucketIndex]) {
                buckets[bucketIndex] = amplitude;
            }
        }
    }
}
