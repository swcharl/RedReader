/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.fragments;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import org.apache.http.StatusLine;
import org.holoeverywhere.LayoutInflater;
import org.holoeverywhere.app.Fragment;
import org.holoeverywhere.widget.LinearLayout;
import org.holoeverywhere.widget.ListView;
import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.account.RedditAccount;
import org.quantumbadger.redreader.account.RedditAccountManager;
import org.quantumbadger.redreader.activities.BugReportActivity;
import org.quantumbadger.redreader.adapters.MainMenuAdapter;
import org.quantumbadger.redreader.adapters.MainMenuSelectionListener;
import org.quantumbadger.redreader.cache.RequestFailureType;
import org.quantumbadger.redreader.common.General;
import org.quantumbadger.redreader.common.RRError;
import org.quantumbadger.redreader.common.TimestampBound;
import org.quantumbadger.redreader.io.RequestResponseHandler;
import org.quantumbadger.redreader.reddit.APIResponseHandler;
import org.quantumbadger.redreader.reddit.RedditSubredditManager;
import org.quantumbadger.redreader.reddit.api.RedditSubredditSubscriptionManager;
import org.quantumbadger.redreader.reddit.api.SubredditRequestFailure;
import org.quantumbadger.redreader.reddit.things.RedditSubreddit;
import org.quantumbadger.redreader.views.liststatus.ErrorView;
import org.quantumbadger.redreader.views.liststatus.LoadingView;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class MainMenuFragment extends Fragment implements MainMenuSelectionListener, RedditSubredditSubscriptionManager.SubredditSubscriptionStateChangeListener {

	private MainMenuAdapter adapter;

	private LinearLayout notifications;
	private LoadingView loadingView;

	private RedditAccount user;
	private Context context;

	private boolean force;

	public static enum MainMenuAction {
		FRONTPAGE, PROFILE, INBOX, SUBMITTED, UPVOTED, DOWNVOTED, SAVED, MODMAIL, HIDDEN, CUSTOM, ALL
	}

	public static enum MainMenuUserItems {
		PROFILE, INBOX, SUBMITTED, SAVED, HIDDEN, UPVOTED, DOWNVOTED, MODMAIL
	}

	public static MainMenuFragment newInstance(final boolean force) {

		final MainMenuFragment f = new MainMenuFragment();

		final Bundle bundle = new Bundle(1);
		bundle.putBoolean("force", force);
		f.setArguments(bundle);

		return f;
	}

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		force = getArguments().getBoolean("force");
	}

	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {

		if(container != null) {
			context = container.getContext(); // TODO just use the inflater's context in every case?
		} else {
			context = inflater.getContext();
		}

		user = RedditAccountManager.getInstance(context).getDefaultAccount();

		final LinearLayout outer = new LinearLayout(context);
		outer.setOrientation(LinearLayout.VERTICAL);

		notifications = new LinearLayout(context);
		notifications.setOrientation(LinearLayout.VERTICAL);

		loadingView = new LoadingView(context, R.string.download_waiting, true, true);

		final ListView lv = new ListView(context);
		lv.setDivider(null);

		lv.addFooterView(notifications);

		final int paddingPx = General.dpToPixels(context, 8);
		lv.setPadding(paddingPx, 0, paddingPx, 0);

		adapter = new MainMenuAdapter(context, user, this);
		lv.setAdapter(adapter);

		lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(final AdapterView<?> adapterView, final View view, final int position, final long id) {
				adapter.clickOn(position);
			}
		});

		final AtomicReference<APIResponseHandler.SubredditResponseHandler> accessibleSubredditResponseHandler = new AtomicReference<APIResponseHandler.SubredditResponseHandler>(null);

		final APIResponseHandler.SubredditResponseHandler responseHandler = new APIResponseHandler.SubredditResponseHandler(context) {

			@Override
			protected void onDownloadNecessary() {

			}

			@Override
			protected void onDownloadStarted() {
				loadingView.setIndeterminate(R.string.download_subreddits);
			}

			@Override
			protected void onSuccess(final List<RedditSubreddit> result, final long timestamp) {


			}

			@Override
			protected void onCallbackException(final Throwable t) {
				BugReportActivity.handleGlobalError(context, t);
			}

			@Override
			protected void onFailure(final RequestFailureType type, final Throwable t, final StatusLine status, final String readableMessage) {

				if(loadingView != null) loadingView.setDone(R.string.download_failed);
				final RRError error = General.getGeneralErrorForFailure(context, type, t, status, null);

				new Handler(Looper.getMainLooper()).post(new Runnable() {
					public void run() {
						notifications.addView(new ErrorView(getSupportActivity(), error));
					}
				});
			}

			@Override
			protected void onFailure(final APIFailureType type) {
				onError(General.getGeneralErrorForFailure(context, type));
			}
		};

		new Handler(Looper.getMainLooper()).post(new Runnable() {
			public void run() {
				notifications.addView(loadingView);
			}
		});

		final RedditSubredditSubscriptionManager subredditSubscriptionManager
				= RedditSubredditSubscriptionManager.getSingleton(context, user);

		if(force) {
			subredditSubscriptionManager.triggerUpdate(new RequestResponseHandler<HashSet<String>, SubredditRequestFailure>() {
				@Override
				public void onRequestFailed(SubredditRequestFailure failureReason) {
					onError(failureReason.asError(context));
				}

				@Override
				public void onRequestSuccess(HashSet<String> result, long timeCached) {
					subredditSubscriptionManager.addListener(MainMenuFragment.this);
					onSubscriptionsChanged(result);
				}
			}, TimestampBound.NONE);

		} else {

			subredditSubscriptionManager.addListener(MainMenuFragment.this);

			if(subredditSubscriptionManager.areSubscriptionsReady()) {
				onSubscriptionsChanged(subredditSubscriptionManager.getSubscriptionList());
			}
		}

		outer.addView(lv);
		lv.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;

		return outer;
	}

	public void onSubscriptionsChanged(final Collection<String> subscriptions) {

		RedditSubredditManager.getInstance(context, user).getSubreddits(
				subscriptions,
				TimestampBound.ANY,
				new RequestResponseHandler<HashMap<String, RedditSubreddit>, SubredditRequestFailure>() {
					@Override
					public void onRequestFailed(SubredditRequestFailure failureReason) {
						onError(failureReason.asError(context));
					}

					@Override
					public void onRequestSuccess(HashMap<String, RedditSubreddit> result, long timeCached) {
						adapter.setSubreddits(result.values());
					}
				});

		if(loadingView != null) loadingView.setDone(R.string.download_done);
	}

	private void onError(final RRError error) {
		if(loadingView != null) loadingView.setDone(R.string.download_failed);
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			public void run() {
				notifications.addView(new ErrorView(getSupportActivity(), error));
			}
		});
	}

	@Override
	public void onSaveInstanceState(final Bundle outState) {
		// TODO save menu position?
	}

	public void onSelected(final MainMenuAction type, final String name) {
		((MainMenuSelectionListener)getSupportActivity()).onSelected(type, name);
	}

	public void onSelected(final RedditSubreddit subreddit) {
		((MainMenuSelectionListener)getSupportActivity()).onSelected(subreddit);
	}

	@Override
	public void onSubredditSubscriptionListUpdated(RedditSubredditSubscriptionManager subredditSubscriptionManager) {
		onSubscriptionsChanged(subredditSubscriptionManager.getSubscriptionList());
	}

	@Override
	public void onSubredditSubscriptionAttempted(RedditSubredditSubscriptionManager subredditSubscriptionManager) {}

	@Override
	public void onSubredditUnsubscriptionAttempted(RedditSubredditSubscriptionManager subredditSubscriptionManager) {}
}
