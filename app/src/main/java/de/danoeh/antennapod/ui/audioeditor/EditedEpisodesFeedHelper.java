package de.danoeh.antennapod.ui.audioeditor;

import android.content.Context;
import android.net.Uri;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.FeedDatabaseWriter;
import de.danoeh.antennapod.model.feed.SortOrder;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class EditedEpisodesFeedHelper {
    private static final String FOLDER_NAME = "EditedEpisodes";

    private EditedEpisodesFeedHelper() {
    }

    public static File getOrCreateFolder(Context context) {
        File folder = new File(context.getExternalFilesDir(null), FOLDER_NAME);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }

    public static Feed getOrCreateFeed(Context context) {
        String downloadUrl = Feed.PREFIX_LOCAL_FOLDER + Uri.fromFile(getOrCreateFolder(context));
        List<Feed> feeds = DBReader.getFeedList();
        for (Feed feed : feeds) {
            if (downloadUrl.equals(feed.getDownloadUrl())) {
                return feed;
            }
        }
        Feed newFeed = new Feed(downloadUrl, null, context.getString(R.string.edited_episodes_feed_title));
        newFeed.setItems(Collections.emptyList());
        newFeed.setSortOrder(SortOrder.DATE_NEW_OLD);
        return FeedDatabaseWriter.updateFeed(context, newFeed, false);
    }

    public static void rescan(Context context, Feed feed) {
        FeedUpdateManager.getInstance().runOnce(context, feed);
    }
}
