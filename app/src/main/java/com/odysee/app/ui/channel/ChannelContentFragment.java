package com.odysee.app.ui.channel;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.odysee.app.OdyseeApp;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.odysee.app.MainActivity;
import com.odysee.app.R;
import com.odysee.app.adapter.ClaimListAdapter;
import com.odysee.app.callable.ChannelLiveStatus;
import com.odysee.app.callable.Search;
import com.odysee.app.dialog.ContentFromDialogFragment;
import com.odysee.app.dialog.ContentSortDialogFragment;
import com.odysee.app.model.Claim;
import com.odysee.app.model.OdyseeCollection;
import com.odysee.app.model.Page;
import com.odysee.app.tasks.lbryinc.FetchStatCountTask;
import com.odysee.app.utils.Helper;
import com.odysee.app.utils.Lbry;
import com.odysee.app.utils.Predefined;
import lombok.Setter;

public class ChannelContentFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static int CHANNEL_CONTENT_CONTEXT_GROUP_ID = 4;

    @Setter
    private String channelId;
    private View sortLink;
    private View contentFromLink;
    private TextView sortLinkText;
    private TextView contentFromLinkText;
    private RecyclerView contentList;
    private int currentSortBy;
    private int currentContentFrom;
    private String contentReleaseTime;
    private List<String> contentSortOrder;
    private View contentLoading;
    private View bigContentLoading;
    private View noContentView;
    private ClaimListAdapter contentListAdapter;
    private RecyclerView scheduledStreamsList;
    private ClaimListAdapter scheduledClaimsListAdapter;
    private View scheduledLivestreamsLayout;
    private boolean contentClaimSearchLoading;
    private boolean contentHasReachedEnd;
    private int currentClaimSearchPage;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_channel_content, container, false);

        scheduledLivestreamsLayout = root.findViewById(R.id.scheduled_livestreams_container);
        scheduledStreamsList = root.findViewById(R.id.scheduled_livestreams_recyclerview);
        LinearLayoutManager llm = new LinearLayoutManager(getContext());
        scheduledStreamsList.setLayoutManager(llm);
        root.findViewById(R.id.expand_scheduled_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Helper.setViewVisibility(scheduledStreamsList, scheduledStreamsList.getVisibility() == View.VISIBLE ? View.GONE: View.VISIBLE);
                MainActivity a = (MainActivity) getActivity();

                if (a != null) {
                    a.setExpandedStatePreferenceScheduledClaims(scheduledStreamsList.getVisibility() == View.VISIBLE);
                }
            }
        });

        currentSortBy = ContentSortDialogFragment.ITEM_SORT_BY_NEW;
        currentContentFrom = ContentFromDialogFragment.ITEM_FROM_PAST_WEEK;

        sortLink = root.findViewById(R.id.channel_content_sort_link);
        contentFromLink = root.findViewById(R.id.channel_content_time_link);

        sortLinkText = root.findViewById(R.id.channel_content_sort_link_text);
        contentFromLinkText = root.findViewById(R.id.channel_content_time_link_text);

        bigContentLoading = root.findViewById(R.id.channel_content_main_progress);
        contentLoading = root.findViewById(R.id.channel_content_load_progress);
        noContentView = root.findViewById(R.id.channel_content_no_claim_search_content);

        contentList = root.findViewById(R.id.channel_content_list);
        LinearLayoutManager llmContent = new LinearLayoutManager(getContext());
        contentList.setLayoutManager(llmContent);
        contentList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (contentClaimSearchLoading) {
                    return;
                }

                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null) {
                    int visibleItemCount = lm.getChildCount();
                    int totalItemCount = lm.getItemCount();
                    int pastVisibleItems = lm.findFirstVisibleItemPosition();
                    if (pastVisibleItems + visibleItemCount >= totalItemCount) {
                        if (!contentHasReachedEnd) {
                            // load more
                            currentClaimSearchPage++;
                            fetchClaimSearchContent();
                        }
                    }
                }
            }
        });

        sortLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ContentSortDialogFragment dialog = ContentSortDialogFragment.newInstance();
                dialog.setCurrentSortByItem(currentSortBy);
                dialog.setSortByListener(new ContentSortDialogFragment.SortByListener() {
                    @Override
                    public void onSortByItemSelected(int sortBy) {
                        onSortByChanged(sortBy);
                    }
                });

                Context context = getContext();
                if (context instanceof MainActivity) {
                    MainActivity activity = (MainActivity) context;
                    dialog.show(activity.getSupportFragmentManager(), ContentSortDialogFragment.TAG);
                }
            }
        });
        contentFromLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ContentFromDialogFragment dialog = ContentFromDialogFragment.newInstance();
                dialog.setCurrentFromItem(currentContentFrom);
                dialog.setContentFromListener(new ContentFromDialogFragment.ContentFromListener() {
                    @Override
                    public void onContentFromItemSelected(int contentFromItem) {
                        onContentFromChanged(contentFromItem);
                    }
                });
                Context context = getContext();
                if (context instanceof MainActivity) {
                    MainActivity activity = (MainActivity) context;
                    dialog.show(activity.getSupportFragmentManager(), ContentFromDialogFragment.TAG);
                }
            }
        });

        return root;
    }

    private void onContentFromChanged(int contentFrom) {
        currentContentFrom = contentFrom;

        // rebuild options and search
        updateContentFromLinkText();
        contentReleaseTime = Helper.buildReleaseTime(currentContentFrom);
        fetchClaimSearchContent(true);
    }

    private void onSortByChanged(int sortBy) {
        currentSortBy = sortBy;

        // rebuild options and search
        Helper.setViewVisibility(contentFromLink, currentSortBy == ContentSortDialogFragment.ITEM_SORT_BY_TOP ? View.VISIBLE : View.GONE);
        currentContentFrom = currentSortBy == ContentSortDialogFragment.ITEM_SORT_BY_TOP ?
                (currentContentFrom == 0 ? ContentFromDialogFragment.ITEM_FROM_PAST_WEEK : currentContentFrom) : 0;

        updateSortByLinkText();
        contentSortOrder = Helper.buildContentSortOrder(currentSortBy);
        contentReleaseTime = Helper.buildReleaseTime(currentContentFrom);
        fetchClaimSearchContent(true);
    }

    private void updateSortByLinkText() {
        int stringResourceId;
        switch (currentSortBy) {
            case ContentSortDialogFragment.ITEM_SORT_BY_NEW: default: stringResourceId = R.string.new_text; break;
            case ContentSortDialogFragment.ITEM_SORT_BY_TOP: stringResourceId = R.string.top; break;
            case ContentSortDialogFragment.ITEM_SORT_BY_TRENDING: stringResourceId = R.string.trending; break;
        }

        Helper.setViewText(sortLinkText, stringResourceId);
    }

    private void updateContentFromLinkText() {
        int stringResourceId;
        switch (currentContentFrom) {
            case ContentFromDialogFragment.ITEM_FROM_PAST_24_HOURS: stringResourceId = R.string.past_24_hours; break;
            case ContentFromDialogFragment.ITEM_FROM_PAST_WEEK: default: stringResourceId = R.string.past_week; break;
            case ContentFromDialogFragment.ITEM_FROM_PAST_MONTH: stringResourceId = R.string.past_month; break;
            case ContentFromDialogFragment.ITEM_FROM_PAST_YEAR: stringResourceId = R.string.past_year; break;
            case ContentFromDialogFragment.ITEM_FROM_ALL_TIME: stringResourceId = R.string.all_time; break;
        }

        Helper.setViewText(contentFromLinkText, stringResourceId);
    }

    public void onResume() {
        super.onResume();
        Context context = getContext();
        if (context != null) {
            PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(this);
        }

        if (contentReleaseTime == null) {
            String relTime = String.valueOf(Double.valueOf(Math.floor(System.currentTimeMillis()) / 1000.0).intValue());
            contentReleaseTime = "<=" + relTime;
        }
        fetchClaimSearchContent();
    }

    public void onPause() {
        Context context = getContext();
        if (context != null) {
            PreferenceManager.getDefaultSharedPreferences(context).unregisterOnSharedPreferenceChangeListener(this);
        }
        super.onPause();
    }

    private Map<String, Object> buildContentOptions() {
        Context context = getContext();
        boolean canShowMatureContent = false;
        if (context != null) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            canShowMatureContent = sp.getBoolean(MainActivity.PREFERENCE_KEY_SHOW_MATURE_CONTENT, false);
        }

        // Exclude Collections from requested claims
        List<String> claimTypes = new ArrayList<>(2);
        claimTypes.add(Claim.TYPE_STREAM);
        claimTypes.add(Claim.TYPE_REPOST);

        return Lbry.buildClaimSearchOptions(
                claimTypes,
                null,
                canShowMatureContent ? null : new ArrayList<>(Predefined.MATURE_TAGS),
                null,
                !Helper.isNullOrEmpty(channelId) ? Collections.singletonList(channelId) : null,
                null,
                getContentSortOrder(),
                contentReleaseTime,
                0,
                0,
                currentClaimSearchPage == 0 ? 1 : currentClaimSearchPage,
                Helper.CONTENT_PAGE_SIZE);
    }

    private List<String> getContentSortOrder() {
        if (contentSortOrder == null) {
            return Collections.singletonList(Claim.ORDER_BY_RELEASE_TIME);
        }
        return contentSortOrder;
    }

    private View getLoadingView() {
        return (contentListAdapter == null || contentListAdapter.getItemCount() == 0) ? bigContentLoading : contentLoading;
    }

    private void fetchClaimSearchContent() {
        fetchClaimSearchContent(false);
    }

    private void fetchClaimSearchContent(boolean reset) {
        if (reset && contentListAdapter != null) {
            contentListAdapter.clearItems();
            currentClaimSearchPage = 1;
        }

        contentClaimSearchLoading = true;
        Helper.setViewVisibility(noContentView, View.GONE);
        Map<String, Object> claimSearchOptions = buildContentOptions();

        Activity a = getActivity();

        if (a != null) {
            Collection<Callable<Page>> callables = new ArrayList<>(2);
            callables.add(() -> new Page(findActiveStream(), true /* ignored */));
            callables.add(() -> Lbry.claimSearch(claimSearchOptions, Lbry.API_CONNECTION_STRING));

            getLoadingView().setVisibility(View.VISIBLE);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        List<Claim> scheduledClaims = findScheduledLivestreams();

                        if (scheduledClaims != null && scheduledClaims.size() > 0) {
                            scheduledClaims = Helper.filterClaimsByOutpoint(scheduledClaims);

                            for (Claim c : scheduledClaims) {
                                c.setHighlightLive(true);
                            }

                            List<Claim> finalScheduledClaims = scheduledClaims;
                            a.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (scheduledClaimsListAdapter == null) {
                                        Context context = getContext();
                                        if (context != null) {
                                            scheduledClaimsListAdapter = new ClaimListAdapter(finalScheduledClaims, ClaimListAdapter.STYLE_SMALL_LIST, context);
                                            scheduledClaimsListAdapter.setListener(new ClaimListAdapter.ClaimListItemListener() {
                                                @Override
                                                public void onClaimClicked(Claim claim, int position) {
                                                    Context context = getContext();
                                                    if (context instanceof MainActivity) {
                                                        MainActivity activity = (MainActivity) context;

                                                        // Scheduled livestreams will be from current channel, so there is no point in navigating to currently displayed data
                                                        if (claim.getValueType().equals(Claim.TYPE_STREAM)) {
                                                            activity.openFileClaim(claim);
                                                        }
                                                    }
                                                }
                                            });
                                        }
                                    } else {
                                        scheduledClaimsListAdapter.addItems(finalScheduledClaims);
                                    }

                                    if (scheduledStreamsList != null && scheduledStreamsList.getAdapter() == null) {
                                        scheduledStreamsList.setAdapter(scheduledClaimsListAdapter);
                                    }
                                }
                            });
                        }

                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                checkNoScheduledLivestreams();
                            }
                        });

                        List<Future<Page>> results = ((OdyseeApp) a.getApplication()).getExecutor().invokeAll(callables);

                        List<Claim> items = new ArrayList<>();

                        Future<Page> activeStreamFuture = results.get(0);
                        Page activeStreamPage = activeStreamFuture.get();
                        if (activeStreamPage != null) {
                            List<Claim> activeStream = activeStreamPage.getClaims();
                            if (activeStream != null) {
                                items.addAll(activeStream);
                            }
                        }

                        Future<Page> claimSearchFuture = results.get(1);
                        Page claimSearchPage = claimSearchFuture.get();
                        boolean hasReachedEnd;
                        if (claimSearchPage != null) {
                            items.addAll(claimSearchPage.getClaims());
                            hasReachedEnd = claimSearchPage.isLastPage();
                        } else {
                            hasReachedEnd = true;
                        }

                        List<Claim> liveItems = items.stream().filter(e -> e != null && e.isHighlightLive()).collect(Collectors.toList());
                        List<Claim> regularItems = items.stream().filter(e -> e != null && !e.isHighlightLive()).collect(Collectors.toList());

                        for (Claim c : liveItems) {
                            regularItems.removeIf(e -> e.getClaimId().equalsIgnoreCase(c.getClaimId()));
                        }

                        regularItems.removeIf(c -> {
                            Claim.GenericMetadata streamMetadata = c.getValue();
                            if (streamMetadata instanceof Claim.StreamMetadata) {
                                long releaseTime = ((Claim.StreamMetadata)streamMetadata).getReleaseTime();
                                String claimValueType = ((Claim.StreamMetadata) streamMetadata).getStreamType();
                                return (!(releaseTime > System.currentTimeMillis()) && claimValueType == null);
                            } else {
                                return false;
                            }
                        });

                        loadViewCounts(regularItems);

                        items = Stream.concat(liveItems.stream(), regularItems.stream()).collect(Collectors.toList());
                        items = Helper.sortingLivestreamingFirst(Helper.filterClaimsByOutpoint(items));

                        List<Claim> finalItems = items;
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                getLoadingView().setVisibility(View.GONE);
                                if (contentListAdapter == null) {
                                    Context context = getContext();
                                    if (context != null) {
                                        contentListAdapter = new ClaimListAdapter(finalItems, context);
                                        contentListAdapter.setContextGroupId(CHANNEL_CONTENT_CONTEXT_GROUP_ID);
                                        contentListAdapter.setListener(new ClaimListAdapter.ClaimListItemListener() {
                                            @Override
                                            public void onClaimClicked(Claim claim, int position) {
                                                Context context = getContext();
                                                if (context instanceof MainActivity) {
                                                    MainActivity activity = (MainActivity) context;
                                                    if (claim.getName().startsWith("@")) {
                                                        // channel claim
                                                        activity.openChannelClaim(claim);
                                                    } else {
                                                        activity.openFileClaim(claim);
                                                    }
                                                }
                                            }
                                        });
                                    }
                                } else {
                                    contentListAdapter.addItems(finalItems);
                                }

                                if (contentList != null && contentList.getAdapter() == null) {
                                    contentList.setAdapter(contentListAdapter);
                                }

                                contentHasReachedEnd = hasReachedEnd;
                                contentClaimSearchLoading = false;
                                checkNoContent();
                            }
                        });
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    }
                }
            });
            t.start();
        }
    }

    private List<Claim> findActiveStream() {
        List<Claim> mostRecentClaims = new ArrayList<>();
        Map<String, JSONObject> livestreamingChannels;
        String livestreamUrl = null;

        Activity a = getActivity();

        if (a != null) {
            try {
                Future<Map<String, JSONObject>> isLiveFuture = ((OdyseeApp) a.getApplication()).getExecutor().submit(new ChannelLiveStatus(Collections.singletonList(channelId)));

                livestreamingChannels = isLiveFuture.get();

                Map<String, Integer> viewersForClaim = new HashMap<>();
                Map<String, Long> releaseTimeForClaim = new HashMap<>();
                if (livestreamingChannels != null && livestreamingChannels.containsKey(channelId)) {
                    String activeClaimId = null;
                    JSONObject jsonData = livestreamingChannels.get(channelId);
                    if (jsonData != null && jsonData.has("ActiveClaim")) {
                        livestreamUrl = jsonData.getString("VideoURL");
                        JSONObject jsonActiveClaimID = jsonData.getJSONObject("ActiveClaim");
                        activeClaimId = jsonActiveClaimID.getString("ClaimID");
                        viewersForClaim.put(activeClaimId, jsonData.getInt("ViewerCount"));
                        try {
                            releaseTimeForClaim.put(activeClaimId, ZonedDateTime.parse(
                                    jsonData.getString("Start")).toInstant().getEpochSecond());
                        } catch (DateTimeParseException ex) {
                            ex.printStackTrace();
                        }
                    }

                    if (activeClaimId != null && !activeClaimId.equalsIgnoreCase("Confirming")) {
                        Map<String, Object> claimSearchOptions = buildContentOptions();

                        claimSearchOptions.put("claim_type", Collections.singletonList(Claim.TYPE_STREAM));
                        claimSearchOptions.put("has_no_source", true);
                        claimSearchOptions.put("claim_id", activeClaimId);
                        Future<List<Claim>> mostRecentsFuture = ((OdyseeApp) a.getApplication()).getExecutor().submit(new Search(claimSearchOptions));

                        mostRecentClaims = mostRecentsFuture.get();
                    }
                }

                if (mostRecentClaims.size() == 0) {
                    return null;
                } else {
                    String finalLivestreamUrl = livestreamUrl;
                    mostRecentClaims.stream().forEach(c -> {
                        c.setHighlightLive(true);
                        c.setLivestreamUrl(finalLivestreamUrl);
                        Integer viewers = viewersForClaim.get(c.getClaimId());
                        if (viewers != null) {
                            c.setLivestreamViewers(viewers);
                        }
                        Long releaseTime = releaseTimeForClaim.get(c.getClaimId());
                        if (releaseTime != null) {
                            ((Claim.StreamMetadata) c.getValue()).setReleaseTime(releaseTime);
                        }
                    });
                }
            } catch (InterruptedException | ExecutionException | JSONException e) {
                Throwable cause = e.getCause();
                if (cause != null) {
                    cause.printStackTrace();
                }
            }
        }

        return mostRecentClaims;
    }

    private void checkNoContent() {
        boolean noContent = contentListAdapter == null || contentListAdapter.getItemCount() == 0;
        Helper.setViewVisibility(noContentView, noContent ? View.VISIBLE : View.GONE);
    }

    private List<Claim> findScheduledLivestreams() {
        MainActivity a = (MainActivity) getActivity();
        List<Claim> upcomingClaims = null;
        if (a != null) {
            try {
                Callable<Map<String, JSONObject>> callable = new ChannelLiveStatus(Collections.singletonList(channelId), true, true);
                Future<Map<String, JSONObject>> futureUpcoming = ((OdyseeApp) a.getApplication()).getExecutor().submit(callable);
                Map<String, JSONObject> upcomingJsonData = futureUpcoming.get();

                if (upcomingJsonData != null && upcomingJsonData.size() > 0 && upcomingJsonData.containsKey(channelId)) {
                    JSONObject channelData = upcomingJsonData.get(channelId);
                    if (channelData != null && channelData.has("FutureClaims")) {
                        JSONArray jsonClaimIds = channelData.optJSONArray("FutureClaims");

                        if (jsonClaimIds != null) {
                            List<String> claimIds = new ArrayList<>();
                            for (int j = 0; j < jsonClaimIds.length(); j++) {
                                JSONObject obj = jsonClaimIds.getJSONObject(j);
                                claimIds.add(obj.getString("ClaimID"));
                            }

                            Map<String, Object> claimSearchOptions = buildScheduledLivestreamsOptions();

                            claimSearchOptions.put("claim_type", Collections.singletonList(Claim.TYPE_STREAM));
                            claimSearchOptions.put("has_no_source", true);
                            claimSearchOptions.put("claim_ids", claimIds);
                            Future<List<Claim>> upcomingFuture = ((OdyseeApp) a.getApplication()).getExecutor().submit(new Search(claimSearchOptions));

                            upcomingClaims = upcomingFuture.get();
                            if (channelData.has("ActiveClaim")) {
                                // Extract active livestream's claimId to compare with future ones
                                JSONObject activeClaimIdJSON = (JSONObject) channelData.get("ActiveClaim");

                                upcomingClaims.removeIf(t -> {
                                    try {
                                        return channelData.getBoolean("Live") && t.getClaimId().equalsIgnoreCase(activeClaimIdJSON.getString("ClaimID"));
                                    } catch (JSONException e) {
                                        e.printStackTrace();
                                        return false;
                                    }
                                });
                            }
                        }
                    }
                }

            } catch (InterruptedException | ExecutionException | JSONException e) {
                Throwable cause = e.getCause();
                if (cause != null) {
                    cause.printStackTrace();
                }
                return null;
            }
        }
        return upcomingClaims;
    }

    private void checkNoScheduledLivestreams() {
        boolean noScheduled = scheduledClaimsListAdapter == null || scheduledClaimsListAdapter.getItemCount() == 0;
        Helper.setViewVisibility(scheduledLivestreamsLayout, noScheduled ? View.GONE : View.VISIBLE);

        MainActivity a = (MainActivity) getActivity();
        if (!noScheduled && a != null) {
            scheduledStreamsList.setVisibility(a.getExpandedStatePreferenceScheduledClaims() ? View.VISIBLE : View.GONE);
        }
    }

    private Map<String, Object> buildScheduledLivestreamsOptions() {
        Context context = getContext();
        boolean canShowMatureContent = false;
        if (context != null) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            canShowMatureContent = sp.getBoolean(MainActivity.PREFERENCE_KEY_SHOW_MATURE_CONTENT, false);
        }

        Date ct = new Date();
        Calendar cal = GregorianCalendar.getInstance();
        cal.setTime(ct);

        int releaseTime = Long.valueOf(cal.getTimeInMillis() / 1000L).intValue();

        return Lbry.buildClaimSearchOptions(
                Collections.singletonList(Claim.TYPE_STREAM),
                null,
                canShowMatureContent ? null : new ArrayList<>(Predefined.MATURE_TAGS),
                null,
                !Helper.isNullOrEmpty(channelId) ? Collections.singletonList(channelId) : null,
                null,
                Collections.singletonList("^release_time"),
                ">" + releaseTime,
                0,
                0,
                currentClaimSearchPage == 0 ? 1 : currentClaimSearchPage,
                Helper.CONTENT_PAGE_SIZE);
    }

    private void loadViewCounts(List<Claim> claims) {
        List<String> claimIds = claims.stream().map(Claim::getClaimId).collect(Collectors.toList());
        FetchStatCountTask task = new FetchStatCountTask(
                FetchStatCountTask.STAT_VIEW_COUNT, claimIds, null, new FetchStatCountTask.FetchStatCountHandler() {
            @Override
            public void onSuccess(List<Integer> counts) {
                for (int i = 0; i < counts.size(); i++) {
                    claims.get(i).setViews(counts.get(i));
                    contentListAdapter.notifyItemChanged(i);
                }
            }

            @Override
            public void onError(Exception error) {
                // pass
            }
        });
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (item.getGroupId() == CHANNEL_CONTENT_CONTEXT_GROUP_ID && (item.getItemId() == R.id.action_block || item.getItemId() == R.id.action_mute)) {
            if (contentListAdapter != null) {
                int position = contentListAdapter.getCurrentPosition();
                Claim claim = contentListAdapter.getItems().get(position);
                if (claim != null && claim.getSigningChannel() != null) {
                    Claim channel = claim.getSigningChannel();
                    Context context = getContext();
                    MainActivity activity = (MainActivity) context;
                    if (item.getItemId() == R.id.action_block) {
                        activity.handleBlockChannel(channel, null);
                    } else {
                        activity.handleMuteChannel(channel);
                    }
                }
            }
            return true;
        }

        if (item.getGroupId() == CHANNEL_CONTENT_CONTEXT_GROUP_ID && item.getItemId() == R.id.action_report) {
            if (contentListAdapter != null) {
                int position = contentListAdapter.getCurrentPosition();
                Claim claim = contentListAdapter.getItems().get(position);
                Context context = getContext();
                if (context instanceof MainActivity) {
                    ((MainActivity) context).handleReportClaim(claim);
                }
            }
            return true;
        }

        if (item.getGroupId() == CHANNEL_CONTENT_CONTEXT_GROUP_ID) {
            if (contentListAdapter != null) {
                int position = contentListAdapter.getCurrentPosition();
                Claim claim = contentListAdapter.getItems().get(position);
                String url = claim.getPermanentUrl();

                Context context = getContext();
                if (context instanceof MainActivity) {
                    MainActivity activity = (MainActivity) context;
                    if (item.getItemId() == R.id.action_add_to_watch_later) {
                        activity.handleAddUrlToList(url, OdyseeCollection.BUILT_IN_ID_WATCHLATER);
                    } else if (item.getItemId() == R.id.action_add_to_favorites) {
                        activity.handleAddUrlToList(url, OdyseeCollection.BUILT_IN_ID_FAVORITES);
                    } else if (item.getItemId() == R.id.action_add_to_lists) {
                        activity.handleAddUrlToList(url, null);
                    } else if (item.getItemId() == R.id.action_add_to_queue) {
                        activity.handleAddToNowPlayingQueue(claim);
                    }
                }
            }
        }

        return super.onContextItemSelected(item);
    }

    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key.equalsIgnoreCase(MainActivity.PREFERENCE_KEY_SHOW_MATURE_CONTENT)) {
            fetchClaimSearchContent(true);
        }
    }
}
