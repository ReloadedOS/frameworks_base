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

package android.app.backup;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.app.backup.BackupAgent.IncludeExcludeRules;
import android.app.backup.BackupAnnotations.BackupDestination;
import android.app.backup.BackupAnnotations.OperationType;
import android.app.backup.FullBackup.BackupScheme.PathWithRequiredFlags;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupAgentTest {
    // An arbitrary user.
    private static final UserHandle USER_HANDLE = new UserHandle(15);
    private static final String DATA_TYPE_BACKED_UP = "test data type";

    @Mock FullBackup.BackupScheme mBackupScheme;

    private BackupAgent mBackupAgent;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testGetIncludeExcludeRules_isNotMigration_returnsRules() throws Exception {
        PathWithRequiredFlags path = new PathWithRequiredFlags("path", /* requiredFlags */ 0);
        Map<String, Set<PathWithRequiredFlags>> includePaths = Collections.singletonMap("test",
                Collections.singleton(path));
        ArraySet<PathWithRequiredFlags> excludePaths = new ArraySet<>();
        excludePaths.add(path);
        IncludeExcludeRules expectedRules = new IncludeExcludeRules(includePaths, excludePaths);

        mBackupAgent = getAgentForBackupDestination(BackupDestination.CLOUD);
        when(mBackupScheme.maybeParseAndGetCanonicalExcludePaths()).thenReturn(excludePaths);
        when(mBackupScheme.maybeParseAndGetCanonicalIncludePaths()).thenReturn(includePaths);

        IncludeExcludeRules rules = mBackupAgent.getIncludeExcludeRules(mBackupScheme);
        assertThat(rules).isEqualTo(expectedRules);
    }

    @Test
    public void getBackupRestoreEventLogger_beforeOnCreate_isNull() {
        BackupAgent agent = new TestFullBackupAgent();

        assertThat(agent.getBackupRestoreEventLogger()).isNull();
    }

    @Test
    public void getBackupRestoreEventLogger_afterOnCreateForBackup_initializedForBackup() {
        BackupAgent agent = new TestFullBackupAgent();
        agent.onCreate(USER_HANDLE, BackupDestination.CLOUD, OperationType.BACKUP);

        assertThat(agent.getBackupRestoreEventLogger().getOperationType()).isEqualTo(
                OperationType.BACKUP);
    }

    @Test
    public void getBackupRestoreEventLogger_afterOnCreateForRestore_initializedForRestore() {
        BackupAgent agent = new TestFullBackupAgent();
        agent.onCreate(USER_HANDLE, BackupDestination.CLOUD, OperationType.RESTORE);

        assertThat(agent.getBackupRestoreEventLogger().getOperationType()).isEqualTo(
                OperationType.RESTORE);
    }

    @Test
    public void getBackupRestoreEventLogger_afterBackup_containsLogsLoggedByAgent()
            throws Exception {
        BackupAgent agent = new TestFullBackupAgent();
        agent.onCreate(USER_HANDLE, BackupDestination.CLOUD, OperationType.BACKUP);

        // TestFullBackupAgent logs DATA_TYPE_BACKED_UP when onFullBackup is called.
        agent.onFullBackup(new FullBackupDataOutput(/* quota = */ 0));

        assertThat(agent.getBackupRestoreEventLogger().getLoggingResults().get(0).getDataType())
                .isEqualTo(DATA_TYPE_BACKED_UP);
    }

    private BackupAgent getAgentForBackupDestination(@BackupDestination int backupDestination) {
        BackupAgent agent = new TestFullBackupAgent();
        agent.onCreate(USER_HANDLE, backupDestination);
        return agent;
    }

    private static class TestFullBackupAgent extends BackupAgent {

        @Override
        public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
                ParcelFileDescriptor newState) throws IOException {
            // Left empty as this is a full backup agent.
        }

        @Override
        public void onFullBackup(FullBackupDataOutput data) {
            getBackupRestoreEventLogger().logItemsBackedUp(DATA_TYPE_BACKED_UP, 1);
        }

        @Override
        public void onRestore(BackupDataInput data, int appVersionCode,
                ParcelFileDescriptor newState) throws IOException {
            // Left empty as this is a full backup agent.
        }
    }
}
