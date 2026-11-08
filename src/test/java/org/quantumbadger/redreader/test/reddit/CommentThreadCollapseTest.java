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

package org.quantumbadger.redreader.test.reddit;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.quantumbadger.redreader.activities.HtmlViewActivity;
import org.quantumbadger.redreader.adapters.FilteredCommentListingManager;
import org.quantumbadger.redreader.common.General;
import org.quantumbadger.redreader.common.PrefsUtility;
import org.quantumbadger.redreader.common.RRThemeAttributes;
import org.quantumbadger.redreader.fragments.CommentListingFragment;
import org.quantumbadger.redreader.reddit.RedditCommentListItem;
import org.quantumbadger.redreader.reddit.kthings.JsonUtils;
import org.quantumbadger.redreader.reddit.kthings.RedditThing;
import org.quantumbadger.redreader.reddit.prepared.RedditChangeDataManager;
import org.quantumbadger.redreader.reddit.prepared.RedditParsedComment;
import org.quantumbadger.redreader.reddit.prepared.RedditRenderableComment;
import org.quantumbadger.redreader.views.RedditCommentView;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import androidx.recyclerview.widget.RecyclerView;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class CommentThreadCollapseTest {

	private ActivityController<HtmlViewActivity> mHost;
	private CommentListingFragment mFragment;
	private FilteredCommentListingManager mListing;
	private RedditChangeDataManager mChanges;

	private static void setStatic(final Class<?> type, final String name, final Object value)
			throws Exception {
		final Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
	}

	private static Object field(final Object object, final String name) throws Exception {
		final Field field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(object);
	}

	@Before
	public void setUp() throws Exception {
		setStatic(org.quantumbadger.redreader.account.RedditAccountManager.class,
				"singleton", null);
		final Field changes = RedditChangeDataManager.class.getDeclaredField("INSTANCE_MAP");
		changes.setAccessible(true);
		((java.util.Map<?, ?>)changes.get(null)).clear();
		final Field cachedPrefs = General.class.getDeclaredField("mPrefs");
		cachedPrefs.setAccessible(true);
		((AtomicReference<?>)cachedPrefs.get(null)).set(null);
		final android.app.Application app = RuntimeEnvironment.getApplication();
		setStatic(PrefsUtility.class, "mRes", app.getResources());
		setStatic(PrefsUtility.class, "sharedPrefs", General.getSharedPrefs(app));
		setStatic(PrefsUtility.class, "appContext", app);
		final Intent intent = new Intent(app, HtmlViewActivity.class);
		intent.putExtra("html", "<p>Test</p>");
		intent.putExtra("title", "Test");
		mHost = Robolectric.buildActivity(HtmlViewActivity.class, intent).setup();
		mFragment = new CommentListingFragment(
				mHost.get(), null, new ArrayList<>(), null, null, false);
		mListing = (FilteredCommentListingManager)field(mFragment, "mCommentListingManager");
		mListing.setLoadingVisible(false);
		mChanges = RedditChangeDataManager.getInstance(
				org.quantumbadger.redreader.account.RedditAccountManager.getAnon());
	}

	@After
	public void tearDown() {
		mHost.pause().stop().destroy();
	}

	private RedditCommentListItem comment(
			final String id,
			final RedditCommentListItem parent) throws Exception {
		final String json = "{\"kind\":\"t1\",\"data\":{\"id\":\"" + id
				+ "\",\"name\":\"t1_" + id + "\",\"author\":\"tester\","
				+ "\"body\":\"Test\",\"body_html\":\"&lt;p&gt;Test&lt;/p&gt;\","
				+ "\"created_utc\":1700000000.0}}";
		final RedditThing.Comment parsed = (RedditThing.Comment)JsonUtils.INSTANCE
				.decodeRedditThingFromStream(new ByteArrayInputStream(
						json.getBytes(StandardCharsets.UTF_8)));
		final RedditRenderableComment comment = new RedditRenderableComment(
				new RedditParsedComment(parsed.getData(), mHost.get()), null, null, "", true,
				false, true);
		return new RedditCommentListItem(comment, parent, mFragment, mHost.get(), null);
	}

	private RedditCommentView view(final RedditCommentListItem item) {
		final RedditCommentView view = new RedditCommentView(
				mHost.get(), new RRThemeAttributes(mHost.get()), mFragment, mFragment);
		view.reset(mHost.get(), item);
		return view;
	}

	@Test
	public void testNestedCollapseHidesOnlyInvokingThread() throws Exception {
		final RedditCommentListItem root = comment("root", null);
		final RedditCommentListItem child = comment("child", root);
		final RedditCommentListItem grandchild = comment("grandchild", child);
		final RedditCommentListItem next = comment("next", null);
		mListing.addComments(Arrays.asList(root, child, grandchild, next));
		mFragment.handleThreadCollapse(view(grandchild));
		Assert.assertTrue(root.isCollapsed(mChanges));
		Assert.assertFalse(next.isCollapsed(mChanges));
		Assert.assertEquals(2, mListing.getAdapter().getItemCount());
		Assert.assertSame(root, mListing.getItemAtPosition(0));
		Assert.assertSame(next, mListing.getItemAtPosition(1));
	}

	@Test
	public void testCollapsedRootCanBeExpandedByConfiguredTapAction() throws Exception {
		final RedditCommentListItem root = comment("root", null);
		final RedditCommentListItem child = comment("child", root);
		mListing.addComments(Arrays.asList(root, child));
		mFragment.handleThreadCollapse(view(child));
		mFragment.handleThreadCollapse(view(root));
		Assert.assertFalse(root.isCollapsed(mChanges));
		Assert.assertEquals(2, mListing.getAdapter().getItemCount());
	}

	@Test
	public void testMissingAncestorsCollapseHighestListedAncestor() throws Exception {
		final RedditCommentListItem root = comment("root", null);
		final RedditCommentListItem child = comment("child", root);
		final RedditCommentListItem grandchild = comment("grandchild", child);
		mListing.addComments(Arrays.asList(child, grandchild));
		mFragment.handleThreadCollapse(view(grandchild));
		Assert.assertFalse(root.isCollapsed(mChanges));
		Assert.assertTrue(child.isCollapsed(mChanges));
		Assert.assertEquals(1, mListing.getAdapter().getItemCount());
	}

	@Test
	public void testLastThreadKeepsCollapsedRoot() throws Exception {
		final RedditCommentListItem root = comment("root", null);
		final RedditCommentListItem child = comment("child", root);
		mListing.addComments(Arrays.asList(root, child));
		mFragment.handleThreadCollapse(view(child));
		Assert.assertEquals(1, mListing.getAdapter().getItemCount());
		Assert.assertSame(root, mListing.getItemAtPosition(0));
	}

	@Test
	public void testOrdinaryCollapseDoesNotJumpWithoutTouchExploration() throws Exception {
		final RedditCommentListItem root = comment("root", null);
		final RedditCommentListItem next = comment("next", null);
		mListing.addComments(Arrays.asList(root, next));
		mHost.get().setContentView(mFragment.createCombinedListingAndOverlayView());
		mHost.visible();
		final View content = mHost.get().getWindow().getDecorView();
		content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
		content.layout(0, 0, 1080, 1920);
		Shadows.shadowOf(Looper.getMainLooper()).idle();
		final RecyclerView recycler = (RecyclerView)field(mFragment, "mRecyclerView");
		final RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(0);
		Assert.assertNotNull(holder);
		final int before = (Integer)field(mFragment, "mParentJumpCount");
		mFragment.handleCommentVisibilityToggle((RedditCommentView)holder.itemView);
		Assert.assertEquals(before, field(mFragment, "mParentJumpCount"));
	}

	@Test
	public void testToolbarContainerDoesNotHideOtherAccessibleControls() throws Exception {
		final LinearLayout toolbar = (LinearLayout)field(mFragment, "mFloatingToolbar");
		Assert.assertNotNull(toolbar);
		Assert.assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
				toolbar.getImportantForAccessibility());
		Assert.assertTrue(toolbar.getChildCount() >= 2);
		for(int i = 0; i < 2; i++) {
			Assert.assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
					toolbar.getChildAt(i).getImportantForAccessibility());
		}
	}
}
