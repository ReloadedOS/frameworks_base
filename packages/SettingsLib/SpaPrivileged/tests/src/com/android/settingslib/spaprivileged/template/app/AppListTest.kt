/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.template.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.icu.text.CollationKey
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.framework.compose.toState
import com.android.settingslib.spa.framework.util.asyncMapItem
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.app.AppEntry
import com.android.settingslib.spaprivileged.model.app.AppListConfig
import com.android.settingslib.spaprivileged.model.app.AppListData
import com.android.settingslib.spaprivileged.model.app.AppListModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import kotlinx.coroutines.flow.Flow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppListTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun whenNoApps() {
        setContent(appEntries = emptyList())

        composeTestRule.onNodeWithText(context.getString(R.string.no_applications))
            .assertIsDisplayed()
    }

    @Test
    fun couldShowAppItem() {
        setContent(appEntries = listOf(APP_ENTRY_A))

        composeTestRule.onNodeWithText(APP_ENTRY_A.label).assertIsDisplayed()
    }

    @Test
    fun couldShowHeader() {
        setContent(appEntries = listOf(APP_ENTRY_A), header = { Text(HEADER) })

        composeTestRule.onNodeWithText(HEADER).assertIsDisplayed()
    }

    @Test
    fun whenNotGrouped_groupTitleDoesNotExist() {
        setContent(appEntries = listOf(APP_ENTRY_A, APP_ENTRY_B), enableGrouping = false)

        composeTestRule.onNodeWithText(GROUP_A).assertDoesNotExist()
        composeTestRule.onNodeWithText(GROUP_B).assertDoesNotExist()
    }

    @Test
    fun whenGrouped_groupTitleDisplayed() {
        setContent(appEntries = listOf(APP_ENTRY_A, APP_ENTRY_B), enableGrouping = true)

        composeTestRule.onNodeWithText(GROUP_A).assertIsDisplayed()
        composeTestRule.onNodeWithText(GROUP_B).assertIsDisplayed()
    }

    private fun setContent(
        appEntries: List<AppEntry<TestAppRecord>>,
        header: @Composable () -> Unit = {},
        enableGrouping: Boolean = false,
    ) {
        composeTestRule.setContent {
            AppList(
                config = AppListConfig(userId = USER_ID, showInstantApps = false),
                listModel = TestAppListModel(enableGrouping),
                state = AppListState(
                    showSystem = false.toState(),
                    option = 0.toState(),
                    searchQuery = "".toState(),
                ),
                header = header,
                appItem = { AppListItem(it) {} },
                bottomPadding = 0.dp,
                appListDataSupplier = {
                    stateOf(AppListData(appEntries, option = 0))
                }
            )
        }
    }

    private companion object {
        const val USER_ID = 0
        const val HEADER = "Header"
        const val GROUP_A = "Group A"
        const val GROUP_B = "Group B"
        val APP_ENTRY_A = AppEntry(
            record = TestAppRecord(
                app = ApplicationInfo().apply {
                    packageName = "package.name.a"
                },
                group = GROUP_A,
            ),
            label = "Label A",
            labelCollationKey = CollationKey("", byteArrayOf()),
        )
        val APP_ENTRY_B = AppEntry(
            record = TestAppRecord(
                app = ApplicationInfo().apply {
                    packageName = "package.name.b"
                },
                group = GROUP_B,
            ),
            label = "Label B",
            labelCollationKey = CollationKey("", byteArrayOf()),
        )
    }
}

private data class TestAppRecord(
    override val app: ApplicationInfo,
    val group: String? = null,
) : AppRecord

private class TestAppListModel(val enableGrouping: Boolean) : AppListModel<TestAppRecord> {
    override fun transform(userIdFlow: Flow<Int>, appListFlow: Flow<List<ApplicationInfo>>) =
        appListFlow.asyncMapItem { TestAppRecord(it) }

    @Composable
    override fun getSummary(option: Int, record: TestAppRecord) = null

    override fun filter(
        userIdFlow: Flow<Int>,
        option: Int,
        recordListFlow: Flow<List<TestAppRecord>>,
    ) = recordListFlow

    override fun getGroupTitle(option: Int, record: TestAppRecord) =
        if (enableGrouping) record.group else null
}
