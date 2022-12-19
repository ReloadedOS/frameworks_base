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

package android.service.credentials;

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Request for beginning a create credential request.
 *
 * See {@link BeginCreateCredentialResponse} for the counterpart response
 */
public final class BeginCreateCredentialRequest implements Parcelable {
    private final @NonNull String mCallingPackage;
    private final @NonNull String mType;
    private final @NonNull Bundle mData;

    /**
     * Constructs a new instance.
     *
     * @throws IllegalArgumentException If {@code callingPackage}, or {@code type} string is
     * null or empty.
     * @throws NullPointerException If {@code data} is null.
     */
    public BeginCreateCredentialRequest(@NonNull String callingPackage,
            @NonNull String type, @NonNull Bundle data) {
        mCallingPackage = Preconditions.checkStringNotEmpty(callingPackage,
                "callingPackage must not be null or empty");
        mType = Preconditions.checkStringNotEmpty(type,
                "type must not be null or empty");
        mData = Objects.requireNonNull(data, "data must not be null");
    }

    private BeginCreateCredentialRequest(@NonNull Parcel in) {
        mCallingPackage = in.readString8();
        mType = in.readString8();
        mData = in.readBundle(Bundle.class.getClassLoader());
    }

    public static final @NonNull Creator<BeginCreateCredentialRequest> CREATOR =
            new Creator<BeginCreateCredentialRequest>() {
                @Override
                public BeginCreateCredentialRequest createFromParcel(@NonNull Parcel in) {
                    return new BeginCreateCredentialRequest(in);
                }

                @Override
                public BeginCreateCredentialRequest[] newArray(int size) {
                    return new BeginCreateCredentialRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mCallingPackage);
        dest.writeString8(mType);
        dest.writeBundle(mData);
    }

    /** Returns the calling package of the calling app. */
    @NonNull
    public String getCallingPackage() {
        return mCallingPackage;
    }

    /** Returns the type of the credential to be created. */
    @NonNull
    public String getType() {
        return mType;
    }

    /** Returns the data to be used while resolving the credential to create. */
    @NonNull
    public Bundle getData() {
        return mData;
    }
}
