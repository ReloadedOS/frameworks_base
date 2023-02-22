/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.pm;

import android.content.pm.PackageManager;
import android.util.SparseBooleanArray;

/**
 * Tracks the package verification state for a particular package. Each package verification has a
 * required verifier and zero or more sufficient verifiers. Only one of the sufficient verifier list
 * must return affirmative to allow the package to be considered verified. If there are zero
 * sufficient verifiers, then package verification is considered complete.
 */
class PackageVerificationState {
    private final VerifyingSession mVerifyingSession;

    private final SparseBooleanArray mSufficientVerifierUids;

    private final SparseBooleanArray mRequiredVerifierUids;
    private final SparseBooleanArray mUnrespondedRequiredVerifierUids;

    private boolean mSufficientVerificationComplete;

    private boolean mSufficientVerificationPassed;

    private boolean mRequiredVerificationComplete;

    private boolean mRequiredVerificationPassed;

    private int mOptionalVerifierUid;

    private boolean mHasOptionalVerifier;

    private boolean mOptionalVerificationComplete;

    private boolean mOptionalVerificationPassed;

    private boolean mExtendedTimeout;

    private boolean mIntegrityVerificationComplete;

    /**
     * Create a new package verification state where {@code requiredVerifierUid} is the user ID for
     * the package that must reply affirmative before things can continue.
     */
    PackageVerificationState(VerifyingSession verifyingSession) {
        mVerifyingSession = verifyingSession;
        mSufficientVerifierUids = new SparseBooleanArray();
        mRequiredVerifierUids = new SparseBooleanArray();
        mUnrespondedRequiredVerifierUids = new SparseBooleanArray();
        mRequiredVerificationComplete = false;
        mRequiredVerificationPassed = true;
        mExtendedTimeout = false;
    }

    VerifyingSession getVerifyingSession() {
        return mVerifyingSession;
    }

    /** Add the user ID of the required package verifier. */
    void addRequiredVerifierUid(int uid) {
        mRequiredVerifierUids.put(uid, true);
        mUnrespondedRequiredVerifierUids.put(uid, true);
    }

    /** Returns true if the uid a required verifier. */
    boolean checkRequiredVerifierUid(int uid) {
        return mRequiredVerifierUids.get(uid, false);
    }

    /**
     * Add a verifier which is added to our sufficient list.
     *
     * @param uid user ID of sufficient verifier
     */
    void addSufficientVerifier(int uid) {
        mSufficientVerifierUids.put(uid, true);
    }

    public void addOptionalVerifier(int uid) {
        mOptionalVerifierUid = uid;
        mHasOptionalVerifier = true;
    }

    /** Returns true if the uid a sufficient verifier. */
    boolean checkSufficientVerifierUid(int uid) {
        return mSufficientVerifierUids.get(uid, false);
    }

    /**
     * Should be called when a verification is received from an agent so the state of the package
     * verification can be tracked.
     *
     * @param uid user ID of the verifying agent
     * @return {@code true} if the verifying agent actually exists in our list
     */
    boolean setVerifierResponse(int uid, int code) {
        if (mRequiredVerifierUids.get(uid)) {
            switch (code) {
                case PackageManager.VERIFICATION_ALLOW_WITHOUT_SUFFICIENT:
                    mSufficientVerifierUids.clear();
                    // fall through
                case PackageManager.VERIFICATION_ALLOW:
                    // Two possible options:
                    // - verification result is true,
                    // - another verifier set it to false.
                    // In both cases we don't need to assign anything, just exit.
                    break;
                default:
                    mRequiredVerificationPassed = false;
            }

            mUnrespondedRequiredVerifierUids.delete(uid);
            if (mUnrespondedRequiredVerifierUids.size() == 0) {
                mRequiredVerificationComplete = true;
            }
            return true;
        } else if (mHasOptionalVerifier && uid == mOptionalVerifierUid) {
            mOptionalVerificationComplete = true;
            switch (code) {
                case PackageManager.VERIFICATION_ALLOW:
                    mOptionalVerificationPassed = true;
                    break;
                default:
                    mOptionalVerificationPassed = false;
            }
            return true;
        } else if (mSufficientVerifierUids.get(uid)) {
            if (code == PackageManager.VERIFICATION_ALLOW) {
                mSufficientVerificationPassed = true;
                mSufficientVerificationComplete = true;
            }

            mSufficientVerifierUids.delete(uid);
            if (mSufficientVerifierUids.size() == 0) {
                mSufficientVerificationComplete = true;
            }

            return true;
        }

        return false;
    }

    /**
     * Mark the session as passed required verification.
     */
    void passRequiredVerification() {
        if (mUnrespondedRequiredVerifierUids.size() > 0) {
            throw new RuntimeException("Required verifiers still present.");
        }
        mRequiredVerificationPassed = true;
        mRequiredVerificationComplete = true;
    }

    /**
     * Returns whether verification is considered complete. This means that the required verifier
     * and at least one of the sufficient verifiers has returned a positive verification.
     *
     * @return {@code true} when verification is considered complete
     */
    boolean isVerificationComplete() {
        if (mRequiredVerifierUids.size() > 0 && !mRequiredVerificationComplete) {
            return false;
        }

        if (mHasOptionalVerifier && !mOptionalVerificationComplete) {
            return false;
        }

        if (mSufficientVerifierUids.size() == 0) {
            return true;
        }

        return mSufficientVerificationComplete;
    }

    /**
     * Returns whether installation should be allowed. This should only be called after {@link
     * #isVerificationComplete()} returns {@code true}.
     *
     * @return {@code true} if installation should be allowed
     */
    boolean isInstallAllowed() {
        if (mRequiredVerifierUids.size() > 0 && (!mRequiredVerificationComplete || !mRequiredVerificationPassed)) {
            return false;
        }

        if (mHasOptionalVerifier && !mOptionalVerificationPassed) {
            return false;
        }

        if (mSufficientVerificationComplete) {
            return mSufficientVerificationPassed;
        }

        return true;
    }

    /** Extend the timeout for this Package to be verified. */
    void extendTimeout() {
        if (!mExtendedTimeout) {
            mExtendedTimeout = true;
        }
    }

    /**
     * Returns whether the timeout was extended for verification.
     *
     * @return {@code true} if a timeout was already extended.
     */
    boolean timeoutExtended() {
        return mExtendedTimeout;
    }

    void setIntegrityVerificationResult(int code) {
        mIntegrityVerificationComplete = true;
    }

    boolean isIntegrityVerificationComplete() {
        return mIntegrityVerificationComplete;
    }

    boolean areAllVerificationsComplete() {
        return mIntegrityVerificationComplete && isVerificationComplete();
    }
}
