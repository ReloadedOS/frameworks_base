/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.app.people;

import android.content.pm.ParceledListSlice;
import android.net.Uri;
import android.os.IBinder;

/**
 * System private API for talking with the people service.
 * {@hide}
 */
interface IPeopleManager {
    /**
     * Returns the recent conversations. The conversations that have customized notification
     * settings are excluded from the returned list.
     */
    ParceledListSlice getRecentConversations();

    /**
     * Removes the specified conversation from the recent conversations list and uncaches the
     * shortcut associated with the conversation.
     */
    void removeRecentConversation(in String packageName, int userId, in String shortcutId);

    /** Removes all the recent conversations and uncaches their cached shortcuts. */
    void removeAllRecentConversations();

    /**
     * Returns the last interaction with the specified conversation. If the
     * conversation can't be found or no interactions have been recorded, returns 0L.
     */
    long getLastInteraction(in String packageName, int userId, in String shortcutId);
}
