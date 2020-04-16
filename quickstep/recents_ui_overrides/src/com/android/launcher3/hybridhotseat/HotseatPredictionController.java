/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.hybridhotseat;

import static com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY;
import static com.android.launcher3.logging.LoggerUtils.newAction;
import static com.android.launcher3.logging.LoggerUtils.newContainerTarget;
import static com.android.launcher3.logging.LoggerUtils.newLauncherEvent;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionManager;
import android.app.prediction.AppPredictor;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Process;
import android.provider.DeviceConfig;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.CellLayout;
import com.android.launcher3.DragSource;
import com.android.launcher3.DropTarget;
import com.android.launcher3.Hotseat;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.Workspace;
import com.android.launcher3.allapps.AllAppsStore;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.appprediction.ComponentKeyMapper;
import com.android.launcher3.appprediction.DynamicItemCache;
import com.android.launcher3.dragndrop.DragController;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.icons.IconCache;
import com.android.launcher3.logging.FileLog;
import com.android.launcher3.logging.UserEventDispatcher;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.ItemInfoWithIcon;
import com.android.launcher3.model.data.LauncherAppWidgetInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.popup.SystemShortcut;
import com.android.launcher3.shortcuts.ShortcutKey;
import com.android.launcher3.touch.ItemLongClickListener;
import com.android.launcher3.uioverrides.DeviceFlag;
import com.android.launcher3.uioverrides.PredictedAppIcon;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.userevent.nano.LauncherLogProto;
import com.android.launcher3.util.ComponentKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.stream.IntStream;

/**
 * Provides prediction ability for the hotseat. Fills gaps in hotseat with predicted items, allows
 * pinning of predicted apps and manages replacement of predicted apps with user drag.
 */
public class HotseatPredictionController implements DragController.DragListener,
        View.OnAttachStateChangeListener, SystemShortcut.Factory<QuickstepLauncher>,
        InvariantDeviceProfile.OnIDPChangeListener, AllAppsStore.OnUpdateListener,
        IconCache.ItemInfoUpdateReceiver, DragSource {

    private static final String TAG = "PredictiveHotseat";
    private static final boolean DEBUG = false;

    //TODO: replace this with AppTargetEvent.ACTION_UNPIN (b/144119543)
    private static final int APPTARGET_ACTION_UNPIN = 4;

    private static final String PREDICTED_ITEMS_CACHE_KEY = "predicted_item_keys";

    private static final String APP_LOCATION_HOTSEAT = "hotseat";
    private static final String APP_LOCATION_WORKSPACE = "workspace";

    private static final String BUNDLE_KEY_HOTSEAT = "hotseat_apps";
    private static final String BUNDLE_KEY_WORKSPACE = "workspace_apps";

    private static final String BUNDLE_KEY_PIN_EVENTS = "pin_events";

    private static final String PREDICTION_CLIENT = "hotseat";
    private DropTarget.DragObject mDragObject;
    private int mHotSeatItemsCount;
    private int mPredictedSpotsCount = 0;

    private Launcher mLauncher;
    private final Hotseat mHotseat;

    private List<ComponentKeyMapper> mComponentKeyMappers = new ArrayList<>();

    private DynamicItemCache mDynamicItemCache;

    private AppPredictor mAppPredictor;
    private AllAppsStore mAllAppsStore;
    private AnimatorSet mIconRemoveAnimators;
    private boolean mUIUpdatePaused = false;
    private boolean mRequiresCacheUpdate = false;

    private HotseatEduController mHotseatEduController;


    private List<PredictedAppIcon.PredictedIconOutlineDrawing> mOutlineDrawings = new ArrayList<>();

    private final View.OnLongClickListener mPredictionLongClickListener = v -> {
        if (!ItemLongClickListener.canStartDrag(mLauncher)) return false;
        if (mLauncher.getWorkspace().isSwitchingState()) return false;
        // Start the drag
        mLauncher.getWorkspace().beginDragShared(v, this, new DragOptions());
        return false;
    };

    public HotseatPredictionController(Launcher launcher) {
        mLauncher = launcher;
        mHotseat = launcher.getHotseat();
        mAllAppsStore = mLauncher.getAppsView().getAppsStore();
        mAllAppsStore.addUpdateListener(this);
        mDynamicItemCache = new DynamicItemCache(mLauncher, this::fillGapsWithPrediction);
        mHotSeatItemsCount = mLauncher.getDeviceProfile().inv.numHotseatIcons;
        launcher.getDeviceProfile().inv.addOnChangeListener(this);
        mHotseat.addOnAttachStateChangeListener(this);
        if (mHotseat.isAttachedToWindow()) {
            onViewAttachedToWindow(mHotseat);
        }
        showCachedItems();
    }

    /**
     * Returns whether or not the prediction controller is ready to show predictions
     */
    public boolean isReady() {
        return mLauncher.getSharedPrefs().getBoolean(HotseatEduController.KEY_HOTSEAT_EDU_SEEN,
                false);
    }

    /**
     * Transitions to NORMAL workspace mode and shows edu
     */
    public void showEdu() {
        if (mHotseatEduController == null) return;
        mLauncher.getStateManager().goToState(LauncherState.NORMAL, true,
                () -> mHotseatEduController.showEdu());
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        mLauncher.getDragController().addDragListener(this);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        mLauncher.getDragController().removeDragListener(this);
    }

    private void fillGapsWithPrediction() {
        fillGapsWithPrediction(false, null);
    }

    private void fillGapsWithPrediction(boolean animate, Runnable callback) {
        if (!isReady() || mUIUpdatePaused || mDragObject != null) {
            return;
        }
        List<WorkspaceItemInfo> predictedApps = mapToWorkspaceItemInfo(mComponentKeyMappers);
        int predictionIndex = 0;
        ArrayList<WorkspaceItemInfo> newItems = new ArrayList<>();
        // make sure predicted icon removal and filling predictions don't step on each other
        if (mIconRemoveAnimators != null && mIconRemoveAnimators.isRunning()) {
            mIconRemoveAnimators.addListener(new AnimationSuccessListener() {
                @Override
                public void onAnimationSuccess(Animator animator) {
                    fillGapsWithPrediction(animate, callback);
                    mIconRemoveAnimators.removeListener(this);
                }
            });
            return;
        }
        for (int rank = 0; rank < mHotSeatItemsCount; rank++) {
            View child = mHotseat.getChildAt(
                    mHotseat.getCellXFromOrder(rank),
                    mHotseat.getCellYFromOrder(rank));

            if (child != null && !isPredictedIcon(child)) {
                continue;
            }
            if (predictedApps.size() <= predictionIndex) {
                // Remove predicted apps from the past
                if (isPredictedIcon(child)) {
                    mHotseat.removeView(child);
                }
                continue;
            }
            WorkspaceItemInfo predictedItem = predictedApps.get(predictionIndex++);
            if (isPredictedIcon(child) && child.isEnabled()) {
                PredictedAppIcon icon = (PredictedAppIcon) child;
                icon.applyFromWorkspaceItem(predictedItem);
                icon.finishBinding(mPredictionLongClickListener);
            } else {
                newItems.add(predictedItem);
            }
            preparePredictionInfo(predictedItem, rank);
        }
        mPredictedSpotsCount = predictionIndex;
        bindItems(newItems, animate, callback);
    }

    private void bindItems(List<WorkspaceItemInfo> itemsToAdd, boolean animate, Runnable callback) {
        AnimatorSet animationSet = new AnimatorSet();
        for (WorkspaceItemInfo item : itemsToAdd) {
            PredictedAppIcon icon = PredictedAppIcon.createIcon(mHotseat, item);
            mLauncher.getWorkspace().addInScreenFromBind(icon, item);
            icon.finishBinding(mPredictionLongClickListener);
            if (animate) {
                animationSet.play(ObjectAnimator.ofFloat(icon, SCALE_PROPERTY, 0.2f, 1));
            }
        }
        if (animate) {
            if (callback != null) {
                animationSet.addListener(AnimationSuccessListener.forRunnable(callback));
            }
            animationSet.start();
        } else {
            if (callback != null) callback.run();
        }
    }

    /**
     * Unregisters callbacks and frees resources
     */
    public void destroy() {
        mAllAppsStore.removeUpdateListener(this);
        mLauncher.getDeviceProfile().inv.removeOnChangeListener(this);
        mHotseat.removeOnAttachStateChangeListener(this);
        if (mAppPredictor != null) {
            mAppPredictor.destroy();
        }
    }

    /**
     * start and pauses predicted apps update on the hotseat
     */
    public void setPauseUIUpdate(boolean paused) {
        mUIUpdatePaused = paused;
        if (!paused) {
            fillGapsWithPrediction();
        }
    }

    /**
     * Creates App Predictor with all the current apps pinned on the hotseat
     */
    public void createPredictor() {
        AppPredictionManager apm = mLauncher.getSystemService(AppPredictionManager.class);
        if (apm == null) {
            return;
        }
        if (mAppPredictor != null) {
            mAppPredictor.destroy();
        }
        mAppPredictor = apm.createAppPredictionSession(
                new AppPredictionContext.Builder(mLauncher)
                        .setUiSurface(PREDICTION_CLIENT)
                        .setPredictedTargetCount(mHotSeatItemsCount)
                        .setExtras(getAppPredictionContextExtra())
                        .build());
        mAppPredictor.registerPredictionUpdates(mLauncher.getMainExecutor(),
                this::setPredictedApps);
        setPauseUIUpdate(false);
        performBetaCheck();
        if (!isReady()) {
            mHotseatEduController = new HotseatEduController(mLauncher, this::createPredictor);
        }
        mAppPredictor.requestPredictionUpdate();
    }

    private void showCachedItems() {
        ArrayList<ComponentKey> componentKeys = getCachedComponentKeys();
        mComponentKeyMappers.clear();
        for (ComponentKey key : componentKeys) {
            mComponentKeyMappers.add(new ComponentKeyMapper(key, mDynamicItemCache));
        }
        updateDependencies();
        fillGapsWithPrediction();
    }

    private Bundle getAppPredictionContextExtra() {
        Bundle bundle = new Bundle();

        //TODO: remove this way of reporting items
        bundle.putParcelableArrayList(BUNDLE_KEY_HOTSEAT,
                getPinnedAppTargetsInViewGroup((mHotseat.getShortcutsAndWidgets())));
        bundle.putParcelableArrayList(BUNDLE_KEY_WORKSPACE, getPinnedAppTargetsInViewGroup(
                mLauncher.getWorkspace().getScreenWithId(
                        Workspace.FIRST_SCREEN_ID).getShortcutsAndWidgets()));

        ArrayList<AppTargetEvent> pinEvents = new ArrayList<>();
        getPinEventsForViewGroup(pinEvents, mHotseat.getShortcutsAndWidgets(),
                APP_LOCATION_HOTSEAT);
        getPinEventsForViewGroup(pinEvents, mLauncher.getWorkspace().getScreenWithId(
                Workspace.FIRST_SCREEN_ID).getShortcutsAndWidgets(), APP_LOCATION_WORKSPACE);
        bundle.putParcelableArrayList(BUNDLE_KEY_PIN_EVENTS, pinEvents);

        return bundle;
    }

    private ArrayList<AppTargetEvent> getPinEventsForViewGroup(ArrayList<AppTargetEvent> pinEvents,
            ViewGroup views, String root) {
        for (int i = 0; i < views.getChildCount(); i++) {
            View child = views.getChildAt(i);
            final AppTargetEvent event;
            if (child.getTag() instanceof ItemInfo && getAppTargetFromInfo(
                    (ItemInfo) child.getTag()) != null) {
                ItemInfo info = (ItemInfo) child.getTag();
                event = wrapAppTargetWithLocation(getAppTargetFromInfo(info),
                        AppTargetEvent.ACTION_PIN, info);
            } else {
                CellLayout.LayoutParams params = (CellLayout.LayoutParams) views.getLayoutParams();
                event = wrapAppTargetWithLocation(getBlockAppTarget(), AppTargetEvent.ACTION_PIN,
                        root, 0, params.cellX, params.cellY, params.cellHSpan, params.cellVSpan);
            }
            pinEvents.add(event);
        }
        return pinEvents;
    }


    private ArrayList<AppTarget> getPinnedAppTargetsInViewGroup(ViewGroup viewGroup) {
        ArrayList<AppTarget> pinnedApps = new ArrayList<>();
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (isPinnedIcon(child)) {
                WorkspaceItemInfo itemInfo = (WorkspaceItemInfo) child.getTag();
                pinnedApps.add(getAppTargetFromItemInfo(itemInfo));
            }
        }
        return pinnedApps;
    }

    private void setPredictedApps(List<AppTarget> appTargets) {
        mComponentKeyMappers.clear();
        StringBuilder predictionLog = new StringBuilder("predictedApps: [\n");
        ArrayList<ComponentKey> componentKeys = new ArrayList<>();
        for (AppTarget appTarget : appTargets) {
            ComponentKey key;
            if (appTarget.getShortcutInfo() != null) {
                key = ShortcutKey.fromInfo(appTarget.getShortcutInfo());
            } else {
                key = new ComponentKey(new ComponentName(appTarget.getPackageName(),
                        appTarget.getClassName()), appTarget.getUser());
            }
            componentKeys.add(key);
            predictionLog.append(key.toString());
            predictionLog.append(",rank:");
            predictionLog.append(appTarget.getRank());
            predictionLog.append("\n");
            mComponentKeyMappers.add(new ComponentKeyMapper(key, mDynamicItemCache));
        }
        predictionLog.append("]");
        if (Utilities.IS_DEBUG_DEVICE) FileLog.d(TAG, predictionLog.toString());
        updateDependencies();
        if (isReady()) {
            fillGapsWithPrediction();
        } else if (mHotseatEduController != null) {
            mHotseatEduController.setPredictedApps(mapToWorkspaceItemInfo(mComponentKeyMappers));
        }
        // should invalidate cache if AiAi sends empty list of AppTargets
        if (appTargets.isEmpty()) {
            mRequiresCacheUpdate = true;
        }
        cachePredictionComponentKeys(componentKeys);
    }

    private void cachePredictionComponentKeys(ArrayList<ComponentKey> componentKeys) {
        if (!mRequiresCacheUpdate) return;
        StringBuilder builder = new StringBuilder();
        for (ComponentKey componentKey : componentKeys) {
            builder.append(componentKey);
            builder.append("\n");
        }
        mLauncher.getDevicePrefs().edit().putString(PREDICTED_ITEMS_CACHE_KEY,
                builder.toString()).apply();
        mRequiresCacheUpdate = false;
    }

    private ArrayList<ComponentKey> getCachedComponentKeys() {
        String cachedBlob = mLauncher.getDevicePrefs().getString(PREDICTED_ITEMS_CACHE_KEY, "");
        ArrayList<ComponentKey> results = new ArrayList<>();
        for (String line : cachedBlob.split("\n")) {
            ComponentKey key = ComponentKey.fromString(line);
            if (key != null) {
                results.add(key);
            }
        }
        return results;
    }

    private void updateDependencies() {
        mDynamicItemCache.updateDependencies(mComponentKeyMappers, mAllAppsStore, this,
                mHotSeatItemsCount);
    }

    /**
     * Pins a predicted app icon into place.
     */
    public void pinPrediction(ItemInfo info) {
        PredictedAppIcon icon = (PredictedAppIcon) mHotseat.getChildAt(
                mHotseat.getCellXFromOrder(info.rank),
                mHotseat.getCellYFromOrder(info.rank));
        if (icon == null) {
            return;
        }
        WorkspaceItemInfo workspaceItemInfo = new WorkspaceItemInfo((WorkspaceItemInfo) info);
        mLauncher.getModelWriter().addItemToDatabase(workspaceItemInfo,
                LauncherSettings.Favorites.CONTAINER_HOTSEAT, workspaceItemInfo.screenId,
                workspaceItemInfo.cellX, workspaceItemInfo.cellY);
        ObjectAnimator.ofFloat(icon, SCALE_PROPERTY, 1, 0.8f, 1).start();
        icon.pin(workspaceItemInfo);
        AppTarget appTarget = getAppTargetFromItemInfo(workspaceItemInfo);
        notifyItemAction(appTarget, APP_LOCATION_HOTSEAT, AppTargetEvent.ACTION_PIN);
        mRequiresCacheUpdate = true;
    }

    private List<WorkspaceItemInfo> mapToWorkspaceItemInfo(
            List<ComponentKeyMapper> components) {
        AllAppsStore allAppsStore = mLauncher.getAppsView().getAppsStore();
        if (allAppsStore.getApps().length == 0) {
            return Collections.emptyList();
        }

        List<WorkspaceItemInfo> predictedApps = new ArrayList<>();
        for (ComponentKeyMapper mapper : components) {
            ItemInfoWithIcon info = mapper.getApp(allAppsStore);
            if (info instanceof AppInfo) {
                WorkspaceItemInfo predictedApp = new WorkspaceItemInfo((AppInfo) info);
                predictedApp.container = LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION;
                predictedApps.add(predictedApp);
            } else if (info instanceof WorkspaceItemInfo) {
                WorkspaceItemInfo predictedApp = new WorkspaceItemInfo((WorkspaceItemInfo) info);
                predictedApp.container = LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION;
                predictedApps.add(predictedApp);
            } else {
                if (DEBUG) {
                    Log.e(TAG, "Predicted app not found: " + mapper);
                }
            }
            // Stop at the number of hotseat items
            if (predictedApps.size() == mHotSeatItemsCount) {
                break;
            }
        }
        return predictedApps;
    }

    private List<PredictedAppIcon> getPredictedIcons() {
        List<PredictedAppIcon> icons = new ArrayList<>();
        ViewGroup vg = mHotseat.getShortcutsAndWidgets();
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (isPredictedIcon(child)) {
                icons.add((PredictedAppIcon) child);
            }
        }
        return icons;
    }

    private void removePredictedApps(List<PredictedAppIcon.PredictedIconOutlineDrawing> outlines,
            ItemInfo draggedInfo) {
        if (mIconRemoveAnimators != null) {
            mIconRemoveAnimators.end();
        }
        mIconRemoveAnimators = new AnimatorSet();
        removeOutlineDrawings();
        for (PredictedAppIcon icon : getPredictedIcons()) {
            if (!icon.isEnabled()) {
                continue;
            }
            if (icon.getTag().equals(draggedInfo)) {
                mHotseat.removeView(icon);
                continue;
            }
            int rank = ((WorkspaceItemInfo) icon.getTag()).rank;
            outlines.add(new PredictedAppIcon.PredictedIconOutlineDrawing(
                    mHotseat.getCellXFromOrder(rank), mHotseat.getCellYFromOrder(rank), icon));
            icon.setEnabled(false);
            ObjectAnimator animator = ObjectAnimator.ofFloat(icon, SCALE_PROPERTY, 0);
            animator.addListener(new AnimationSuccessListener() {
                @Override
                public void onAnimationSuccess(Animator animator) {
                    if (icon.getParent() != null) {
                        mHotseat.removeView(icon);
                    }
                }
            });
            mIconRemoveAnimators.play(animator);
        }
        mIconRemoveAnimators.start();
    }

    private void notifyItemAction(AppTarget target, String location, int action) {
        if (mAppPredictor != null) {
            mAppPredictor.notifyAppTargetEvent(new AppTargetEvent.Builder(target,
                    action).setLaunchLocation(location).build());
        }
    }

    @Override
    public void onDragStart(DropTarget.DragObject dragObject, DragOptions options) {
        removePredictedApps(mOutlineDrawings, dragObject.dragInfo);
        mDragObject = dragObject;
        if (mOutlineDrawings.isEmpty()) return;
        for (PredictedAppIcon.PredictedIconOutlineDrawing outlineDrawing : mOutlineDrawings) {
            mHotseat.addDelegatedCellDrawing(outlineDrawing);
        }
        mHotseat.invalidate();
    }

    /**
     * Unpins pinned app when it's converted into a folder
     */
    public void folderCreatedFromWorkspaceItem(ItemInfo info, FolderInfo folderInfo) {
        if (info.itemType != LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) {
            return;
        }
        AppTarget target = getAppTargetFromItemInfo(info);
        ViewGroup hotseatVG = mHotseat.getShortcutsAndWidgets();
        ViewGroup firstScreenVG = mLauncher.getWorkspace().getScreenWithId(
                Workspace.FIRST_SCREEN_ID).getShortcutsAndWidgets();

        if (isInHotseat(folderInfo) && !getPinnedAppTargetsInViewGroup(hotseatVG).contains(
                target)) {
            notifyItemAction(target, APP_LOCATION_HOTSEAT, APPTARGET_ACTION_UNPIN);
        } else if (isInFirstPage(folderInfo) && !getPinnedAppTargetsInViewGroup(
                firstScreenVG).contains(target)) {
            notifyItemAction(target, APP_LOCATION_WORKSPACE, APPTARGET_ACTION_UNPIN);
        }
    }

    /**
     * Pins workspace item created when all folder items are removed but one
     */
    public void folderConvertedToWorkspaceItem(ItemInfo info, FolderInfo folderInfo) {
        if (info.itemType != LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) {
            return;
        }
        AppTarget target = getAppTargetFromItemInfo(info);
        if (isInHotseat(info)) {
            notifyItemAction(target, APP_LOCATION_HOTSEAT, AppTargetEvent.ACTION_PIN);
        } else if (isInFirstPage(info)) {
            notifyItemAction(target, APP_LOCATION_WORKSPACE, AppTargetEvent.ACTION_PIN);
        }
    }

    @Override
    public void onDragEnd() {
        if (mDragObject == null) {
            return;
        }

        ItemInfo dragInfo = mDragObject.dragInfo;
        ViewGroup hotseatVG = mHotseat.getShortcutsAndWidgets();
        ViewGroup firstScreenVG = mLauncher.getWorkspace().getScreenWithId(
                Workspace.FIRST_SCREEN_ID).getShortcutsAndWidgets();

        if (dragInfo instanceof WorkspaceItemInfo
                && dragInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
                && dragInfo.getTargetComponent() != null) {
            AppTarget appTarget = getAppTargetFromItemInfo(dragInfo);
            if (!isInHotseat(dragInfo) && isInHotseat(mDragObject.originalDragInfo)) {
                if (!getPinnedAppTargetsInViewGroup(hotseatVG).contains(appTarget)) {
                    notifyItemAction(appTarget, APP_LOCATION_HOTSEAT, APPTARGET_ACTION_UNPIN);
                }
            }
            if (!isInFirstPage(dragInfo) && isInFirstPage(mDragObject.originalDragInfo)) {
                if (!getPinnedAppTargetsInViewGroup(firstScreenVG).contains(appTarget)) {
                    notifyItemAction(appTarget, APP_LOCATION_WORKSPACE, APPTARGET_ACTION_UNPIN);
                }
            }
            if (isInHotseat(dragInfo) && !isInHotseat(mDragObject.originalDragInfo)) {
                notifyItemAction(appTarget, APP_LOCATION_HOTSEAT, AppTargetEvent.ACTION_PIN);
            }
            if (isInFirstPage(dragInfo) && !isInFirstPage(mDragObject.originalDragInfo)) {
                notifyItemAction(appTarget, APP_LOCATION_WORKSPACE, AppTargetEvent.ACTION_PIN);
            }
        }
        mDragObject = null;
        fillGapsWithPrediction(true, this::removeOutlineDrawings);
        mRequiresCacheUpdate = true;
    }

    @Nullable
    @Override
    public SystemShortcut<QuickstepLauncher> getShortcut(QuickstepLauncher activity,
            ItemInfo itemInfo) {
        if (itemInfo.container != LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION) {
            return null;
        }
        return new PinPrediction(activity, itemInfo);
    }

    private void preparePredictionInfo(WorkspaceItemInfo itemInfo, int rank) {
        itemInfo.container = LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION;
        itemInfo.rank = rank;
        itemInfo.cellX = mHotseat.getCellXFromOrder(rank);
        itemInfo.cellY = mHotseat.getCellYFromOrder(rank);
        itemInfo.screenId = rank;
    }

    private void removeOutlineDrawings() {
        if (mOutlineDrawings.isEmpty()) return;
        for (PredictedAppIcon.PredictedIconOutlineDrawing outlineDrawing : mOutlineDrawings) {
            mHotseat.removeDelegatedCellDrawing(outlineDrawing);
        }
        mHotseat.invalidate();
        mOutlineDrawings.clear();
    }

    @Override
    public void onIdpChanged(int changeFlags, InvariantDeviceProfile profile) {
        this.mHotSeatItemsCount = profile.numHotseatIcons;
        createPredictor();
    }

    @Override
    public void onAppsUpdated() {
        fillGapsWithPrediction();
    }

    @Override
    public void reapplyItemInfo(ItemInfoWithIcon info) {
    }

    @Override
    public void onDropCompleted(View target, DropTarget.DragObject d, boolean success) {
        //Does nothing
    }

    @Override
    public void fillInLogContainerData(ItemInfo childInfo, LauncherLogProto.Target child,
            ArrayList<LauncherLogProto.Target> parents) {
        mHotseat.fillInLogContainerData(childInfo, child, parents);
    }

    private class PinPrediction extends SystemShortcut<QuickstepLauncher> {

        private PinPrediction(QuickstepLauncher target, ItemInfo itemInfo) {
            super(R.drawable.ic_pin, R.string.pin_prediction, target,
                    itemInfo);
        }

        @Override
        public void onClick(View view) {
            dismissTaskMenuView(mTarget);
            pinPrediction(mItemInfo);
        }
    }

    private void performBetaCheck() {
        if (isReady()) return;
        int hotseatItemsCount = mHotseat.getShortcutsAndWidgets().getChildCount();

        int maxItems = DeviceConfig.getInt(
                DeviceFlag.NAMESPACE_LAUNCHER, "max_homepage_items_for_migration", 5);

        // -1 to exclude smart space
        int workspaceItemCount = mLauncher.getWorkspace().getScreenWithId(
                Workspace.FIRST_SCREEN_ID).getShortcutsAndWidgets().getChildCount() - 1;

        // opt user into the feature without onboarding tip or migration if they don't have any
        // open spots in their hotseat and have more than maxItems in their hotseat + workspace

        if (hotseatItemsCount == mHotSeatItemsCount && workspaceItemCount + hotseatItemsCount
                > maxItems) {
            mLauncher.getSharedPrefs().edit().putBoolean(HotseatEduController.KEY_HOTSEAT_EDU_SEEN,
                    true).apply();

            LauncherLogProto.Action action = newAction(LauncherLogProto.Action.Type.TOUCH);
            LauncherLogProto.Target target = newContainerTarget(LauncherLogProto.ContainerType.TIP);
            action.touch = LauncherLogProto.Action.Touch.TAP;
            target.tipType = LauncherLogProto.TipType.HYBRID_HOTSEAT;
            target.controlType = LauncherLogProto.ControlType.HYBRID_HOTSEAT_CANCELED;

            // temporarily encode details in log target (go/hotseat_migration)
            target.rank = 2;
            target.cardinality = (workspaceItemCount * 1000) + hotseatItemsCount;
            target.pageIndex = maxItems;
            LauncherLogProto.LauncherEvent event = newLauncherEvent(action, target);
            UserEventDispatcher.newInstance(mLauncher).dispatchUserEvent(event, null);


        }
    }

    /**
     * Fill in predicted_rank field based on app prediction.
     * Only applicable when {@link ItemInfo#itemType} is PREDICTED_HOTSEAT
     */
    public static void encodeHotseatLayoutIntoPredictionRank(
            @NonNull ItemInfo itemInfo, @NonNull LauncherLogProto.Target target) {
        QuickstepLauncher launcher = QuickstepLauncher.ACTIVITY_TRACKER.getCreatedActivity();
        if (launcher == null || launcher.getHotseatPredictionController() == null
                || itemInfo.getTargetComponent() == null) {
            return;
        }
        HotseatPredictionController controller = launcher.getHotseatPredictionController();

        final ComponentKey k = new ComponentKey(itemInfo.getTargetComponent(), itemInfo.user);

        final List<ComponentKeyMapper> predictedApps = controller.mComponentKeyMappers;
        OptionalInt rank = IntStream.range(0, predictedApps.size())
                .filter((i) -> k.equals(predictedApps.get(i).getComponentKey()))
                .findFirst();

        target.predictedRank = 10000 + (controller.mPredictedSpotsCount * 100)
                + (rank.isPresent() ? rank.getAsInt() + 1 : 0);
    }

    private static boolean isPredictedIcon(View view) {
        return view instanceof PredictedAppIcon && view.getTag() instanceof WorkspaceItemInfo
                && ((WorkspaceItemInfo) view.getTag()).container
                == LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION;
    }

    private static boolean isPinnedIcon(View view) {
        if (!(view instanceof BubbleTextView && view.getTag() instanceof WorkspaceItemInfo)) {
            return false;
        }
        ItemInfo info = (ItemInfo) view.getTag();
        return info.container != LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION && (
                info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION);
    }

    private static boolean isInHotseat(ItemInfo itemInfo) {
        return itemInfo.container == LauncherSettings.Favorites.CONTAINER_HOTSEAT;
    }

    private static boolean isInFirstPage(ItemInfo itemInfo) {
        return itemInfo.container == LauncherSettings.Favorites.CONTAINER_DESKTOP
                && itemInfo.screenId == Workspace.FIRST_SCREEN_ID;
    }

    private static AppTarget getAppTargetFromItemInfo(ItemInfo info) {
        if (info.getTargetComponent() == null) return null;
        ComponentName cn = info.getTargetComponent();
        return new AppTarget.Builder(new AppTargetId("app:" + cn.getPackageName()),
                cn.getPackageName(), info.user).setClassName(cn.getClassName()).build();
    }

    private AppTarget getAppTargetFromInfo(ItemInfo info) {
        if (info == null) return null;
        if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET
                && info instanceof LauncherAppWidgetInfo
                && ((LauncherAppWidgetInfo) info).providerName != null) {
            ComponentName cn = ((LauncherAppWidgetInfo) info).providerName;
            return new AppTarget.Builder(new AppTargetId("widget:" + cn.getPackageName()),
                    cn.getPackageName(), info.user).setClassName(cn.getClassName()).build();
        } else if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
                && info.getTargetComponent() != null) {
            ComponentName cn = info.getTargetComponent();
            return new AppTarget.Builder(new AppTargetId("app:" + cn.getPackageName()),
                    cn.getPackageName(), info.user).setClassName(cn.getClassName()).build();
        } else if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT
                && info instanceof WorkspaceItemInfo) {
            ShortcutKey shortcutKey = ShortcutKey.fromItemInfo(info);
            //TODO: switch to using full shortcut info
            return new AppTarget.Builder(new AppTargetId("shortcut:" + shortcutKey.getId()),
                    shortcutKey.componentName.getPackageName(), shortcutKey.user).build();
        } else if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_FOLDER) {
            return new AppTarget.Builder(new AppTargetId("folder:" + info.id),
                    mLauncher.getPackageName(), info.user).build();
        }
        return null;
    }

    private AppTargetEvent wrapAppTargetWithLocation(AppTarget target, int action, ItemInfo info) {
        return wrapAppTargetWithLocation(target, action,
                info.container == LauncherSettings.Favorites.CONTAINER_HOTSEAT
                        ? APP_LOCATION_HOTSEAT : APP_LOCATION_WORKSPACE, info.screenId, info.cellX,
                info.cellY, info.spanX, info.spanY);
    }

    private AppTargetEvent wrapAppTargetWithLocation(AppTarget target, int action, String root,
            int screenId, int x, int y, int spanX, int spanY) {
        return new AppTargetEvent.Builder(target, action).setLaunchLocation(
                String.format(Locale.ENGLISH, "%s/%d/[%d,%d]/[%d,%d]", root, screenId, x, y, spanX,
                        spanY)).build();
    }

    /**
     * A helper method to generate an AppTarget that's used to communicate workspace layout
     */
    private AppTarget getBlockAppTarget() {
        return new AppTarget.Builder(new AppTargetId("block"),
                mLauncher.getPackageName(), Process.myUserHandle()).build();
    }
}
