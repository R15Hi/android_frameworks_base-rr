/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import com.android.systemui.statusbar.policy.LocationController.LocationChangeCallback;

public interface LocationController extends CallbackController<LocationChangeCallback> {
    boolean isLocationActive();
    boolean isLocationEnabled();
    boolean setLocationEnabled(int mode);
    int getCurrentMode();
    boolean setLocationMode(int mode);
    int getLocationCurrentState();

    /**
     * A callback for change in location settings (the user has enabled/disabled location).
     */
    public interface LocationChangeCallback {
        /**
         * Called whenever location's state changes.
         * @param active
         */
        default void onLocationActiveChanged(boolean active) {}

        /**
         * Called whenever location settings change.
         *
         * @param locationEnabled A value of true indicates that at least one type of location
         *                        is enabled in settings.
         */
        default void onLocationSettingsChanged(boolean locationEnabled) {}
    }
}
