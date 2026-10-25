package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dynamically resolves the official English Indian Super League (ISL) live stream
 * from the official @IndianSuperLeague YouTube channel, matched to the specific scheduled match.
 *
 * It filters out:
 *  - Hindi audio feeds (tagged with हिंदी / Hindi)
 *  - Vertical mobile feeds (tagged with Vertical)
 * And prioritizes:
 *  1. Streams whose title matches the current/upcoming match teams (e.g. Odisha vs NorthEast United)
 *  2. Active LIVE English broadcast (currently playing)
 *  3. Scheduled English upcoming match stream for today
 *
 * Sets video quality hint (&vq=hd1080) for high definition playback.
 */
public class IslStreamResolver {

    private static final String TAG = "IslStreamResolver";
    private static final String CHANNEL_STREAMS_URL = "https://www.youtube.com/@IndianSuperLeague/streams";
    private static final String DEFAULT_ENGLISH_SEARCH = "https://www.youtube.com/results?search_query=Indian+Super+League+live+English&vq=hd1080";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static volatile String cachedStreamUrl = null;
    private static volatile String cachedMatchKey = null;
    private static volatile long lastFetchTime = 0;
    private static final long CACHE_DURATION_MS = 20 * 1000; // 20 seconds cache

    public interface Callback {
        void onResolved(String streamUrl);
    }

    public interface AvailabilityCallback {
        void onChecked(boolean isLive, String streamUrl);
    }

    public static class StreamResult {
        public final boolean isLive;
        public final String bestVideoId;
        public final String bestUrl;

        public StreamResult(boolean isLive, String videoId, String fallbackSearchUrl) {
            this.isLive = isLive;
            this.bestVideoId = videoId;
            if (videoId != null && !videoId.trim().isEmpty()) {
                this.bestUrl = "https://www.youtube.com/watch?v=" + videoId + "&vq=hd1080";
            } else {
                this.bestUrl = (fallbackSearchUrl != null && !fallbackSearchUrl.isEmpty())
                        ? fallbackSearchUrl
                        : DEFAULT_ENGLISH_SEARCH;
            }
        }
    }

    public static void checkLiveStreamStatus(AvailabilityCallback callback) {
        checkLiveStreamStatus(null, callback);
    }

    public static void checkLiveStreamStatus(String matchName, AvailabilityCallback callback) {
        executor.execute(() -> {
            StreamResult result = fetchStreamResult(matchName);
            boolean isLive = result != null && result.isLive;
            String url = (result != null) ? result.bestUrl : buildFallbackSearchUrl(matchName);
            if (isLive) {
                cachedStreamUrl = url;
                cachedMatchKey = matchName;
                lastFetchTime = System.currentTimeMillis();
            }
            mainHandler.post(() -> callback.onChecked(isLive, url));
        });
    }

    public static void resolveEnglishStream(Callback callback) {
        resolveEnglishStream(null, callback);
    }

    public static void resolveEnglishStream(String matchName, Callback callback) {
        if (cachedStreamUrl != null && (System.currentTimeMillis() - lastFetchTime < CACHE_DURATION_MS)) {
            if (cachedMatchKey == null || cachedMatchKey.equalsIgnoreCase(matchName)) {
                callback.onResolved(cachedStreamUrl);
                return;
            }
        }

        executor.execute(() -> {
            StreamResult result = fetchStreamResult(matchName);
            String resolvedUrl = (result != null) ? result.bestUrl : buildFallbackSearchUrl(matchName);
            cachedStreamUrl = resolvedUrl;
            cachedMatchKey = matchName;
            lastFetchTime = System.currentTimeMillis();

            final String finalUrl = resolvedUrl;
            mainHandler.post(() -> callback.onResolved(finalUrl));
        });
    }

    private static String buildFallbackSearchUrl(String matchName) {
        if (matchName == null || matchName.trim().isEmpty()) {
            return DEFAULT_ENGLISH_SEARCH;
        }
        try {
            String query = "Indian Super League live " + matchName.replace("ISL:", "").trim();
            return "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8") + "&vq=hd1080";
        } catch (Exception e) {
            return DEFAULT_ENGLISH_SEARCH;
        }
    }

    private static List<String> extractTeamKeywords(String matchName) {
        List<String> keywords = new ArrayList<>();
        if (matchName == null || matchName.trim().isEmpty()) return keywords;

        String clean = matchName.replace("ISL:", "").replace("ISL", "").replace("FC", "").replace("SC", "").trim();
        String[] parts = clean.split("(?i)\\s+vs\\s+|\\s+v\\s+");
        for (String p : parts) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                keywords.add(t);
            }
        }
        return keywords;
    }

    private static StreamResult fetchStreamResult(String matchName) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CHANNEL_STREAMS_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                Log.w(TAG, "YouTube HTTP response: " + responseCode);
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }

            String html = sb.toString();
            int idx = html.indexOf("var ytInitialData = ");
            if (idx == -1) {
                idx = html.indexOf("window[\"ytInitialData\"] = ");
            }
            if (idx == -1) {
                return null;
            }

            int startJson = html.indexOf('{', idx);
            int endJson = html.indexOf(";</script>", startJson);
            if (startJson == -1 || endJson == -1) {
                return null;
            }

            String jsonStr = html.substring(startJson, endJson);
            JSONObject root = new JSONObject(jsonStr);

            JSONObject contents = root.optJSONObject("contents");
            if (contents == null) return null;
            JSONObject twoCol = contents.optJSONObject("twoColumnBrowseResultsRenderer");
            if (twoCol == null) return null;
            JSONArray tabs = twoCol.optJSONArray("tabs");
            if (tabs == null) return null;

            List<String> teamKeywords = extractTeamKeywords(matchName);
            Log.d(TAG, "Resolving stream for match: " + matchName + ", keywords: " + teamKeywords);

            String bestVideoId = null;
            int highestScore = -1;
            boolean bestIsLiveNow = false;

            for (int t = 0; t < tabs.length(); t++) {
                JSONObject tabObj = tabs.optJSONObject(t);
                if (tabObj == null) continue;
                JSONObject tabRenderer = tabObj.optJSONObject("tabRenderer");
                if (tabRenderer == null || !tabRenderer.optBoolean("selected", false)) continue;

                JSONObject content = tabRenderer.optJSONObject("content");
                if (content == null) continue;
                JSONObject richGrid = content.optJSONObject("richGridRenderer");
                if (richGrid == null) continue;
                JSONArray gridContents = richGrid.optJSONArray("contents");
                if (gridContents == null) continue;

                for (int i = 0; i < gridContents.length(); i++) {
                    JSONObject item = gridContents.optJSONObject(i);
                    if (item == null) continue;
                    JSONObject richItem = item.optJSONObject("richItemRenderer");
                    if (richItem == null) continue;
                    JSONObject itemContent = richItem.optJSONObject("content");
                    if (itemContent == null) continue;
                    JSONObject lockup = itemContent.optJSONObject("lockupViewModel");
                    if (lockup == null) continue;

                    String contentId = lockup.optString("contentId", "");
                    if (contentId.isEmpty()) continue;

                    JSONObject meta = lockup.optJSONObject("metadata");
                    if (meta == null) continue;
                    JSONObject lockupMeta = meta.optJSONObject("lockupMetadataViewModel");
                    if (lockupMeta == null) continue;

                    JSONObject titleObj = lockupMeta.optJSONObject("title");
                    String title = titleObj != null ? titleObj.optString("content", "") : "";
                    if (title.isEmpty()) continue;

                    // Filter out Hindi commentary and Vertical mobile streams
                    if (isHindi(title) || isVertical(title)) {
                        continue;
                    }

                    // Check live and upcoming status badges
                    boolean isLiveNow = false;
                    boolean isUpcoming = false;

                    JSONObject contentImage = lockup.optJSONObject("contentImage");
                    if (contentImage != null) {
                        JSONObject thumbVm = contentImage.optJSONObject("thumbnailViewModel");
                        if (thumbVm != null) {
                            JSONArray overlays = thumbVm.optJSONArray("overlays");
                            if (overlays != null) {
                                for (int o = 0; o < overlays.length(); o++) {
                                    JSONObject ov = overlays.optJSONObject(o);
                                    if (ov == null) continue;
                                    JSONObject badgeVm = ov.optJSONObject("thumbnailBadgeViewModel");
                                    if (badgeVm != null) {
                                        String badgeText = badgeVm.optString("text", "");
                                        if ("LIVE".equalsIgnoreCase(badgeText)) {
                                            isLiveNow = true;
                                        } else if ("Upcoming".equalsIgnoreCase(badgeText)) {
                                            isUpcoming = true;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    JSONObject contentMeta = lockupMeta.optJSONObject("metadata");
                    if (contentMeta != null) {
                        JSONObject contentMetaView = contentMeta.optJSONObject("contentMetadataViewModel");
                        if (contentMetaView != null) {
                            JSONArray metadataRows = contentMetaView.optJSONArray("metadataRows");
                            if (metadataRows != null) {
                                for (int r = 0; r < metadataRows.length(); r++) {
                                    JSONObject row = metadataRows.optJSONObject(r);
                                    if (row == null) continue;
                                    JSONArray parts = row.optJSONArray("metadataParts");
                                    if (parts == null) continue;
                                    for (int p = 0; p < parts.length(); p++) {
                                        JSONObject part = parts.optJSONObject(p);
                                        if (part == null) continue;
                                        JSONObject partText = part.optJSONObject("text");
                                        String textVal = partText != null ? partText.optString("content", "") : "";
                                        String lower = textVal.toLowerCase(Locale.ROOT);
                                        if (lower.contains("watching")) {
                                            isLiveNow = true;
                                        }
                                        if (lower.contains("waiting") || lower.contains("scheduled") || lower.contains("live in")) {
                                            isUpcoming = true;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    String titleLower = title.toLowerCase(Locale.ROOT);
                    int score = 0;

                    // Match team keywords
                    if (!teamKeywords.isEmpty()) {
                        int teamMatches = 0;
                        for (String kw : teamKeywords) {
                            if (titleLower.contains(kw)) {
                                teamMatches++;
                            }
                        }
                        if (teamMatches >= 2) {
                            score += 50; // Both teams match! Definitive match!
                        } else if (teamMatches == 1) {
                            score += 25; // One team match
                        } else {
                            score -= 100; // Completely different teams, penalize heavily!
                        }
                    } else {
                        score += 10; // Generic match
                    }

                    if (isLiveNow) {
                        score += 25;
                    } else if (isUpcoming) {
                        score += 10;
                    } else if (titleLower.contains("live:")) {
                        score += 6;
                    }

                    if (score > highestScore) {
                        highestScore = score;
                        bestVideoId = contentId;
                        bestIsLiveNow = isLiveNow;
                        Log.i(TAG, "Selected stream Candidate: " + contentId + " (" + title + ") with score: " + score);
                    }
                }
            }

            if (bestVideoId != null) {
                Log.i(TAG, "Resolved Best Stream: " + bestVideoId + " (isLive=" + bestIsLiveNow + ", score=" + highestScore + ")");
                return new StreamResult(bestIsLiveNow, bestVideoId, null);
            }

            return new StreamResult(false, null, buildFallbackSearchUrl(matchName));

        } catch (Exception e) {
            Log.e(TAG, "Error resolving English ISL stream: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static boolean isHindi(String text) {
        if (text == null) return false;
        return text.contains("\u0939\u093f\u0902\u0926\u0940")
                || text.toLowerCase(Locale.ROOT).contains("hindi")
                || text.contains("(हिंदी)");
    }

    private static boolean isVertical(String text) {
        if (text == null) return false;
        return text.toLowerCase(Locale.ROOT).contains("vertical");
    }
}
