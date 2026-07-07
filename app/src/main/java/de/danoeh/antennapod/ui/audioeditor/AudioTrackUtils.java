package de.danoeh.antennapod.ui.audioeditor;

import android.media.MediaExtractor;
import android.media.MediaFormat;

class AudioTrackUtils {

    private AudioTrackUtils() {
    }

    static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }
}
