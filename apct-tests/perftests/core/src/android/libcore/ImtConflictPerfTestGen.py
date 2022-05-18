#!/usr/bin/env python3
#
# Copyright 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import sys

max_conflict_depth = 20 # In practice does not go above 20 for reasonable IMT sizes
try:
    imt_size = int(sys.argv[1])
except (IndexError, ValueError):
    print("Usage: python ImtConflictPerfTestGen.py <IMT_SIZE>")
    sys.exit(1)

license = """\
/*
 * Copyright 2016 The Android Open Source Project
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
"""
description = """
/**
 * This file is script-generated by ImtConflictPerfTestGen.py.
 * It measures the performance impact of conflicts in interface method tables.
 * Run `python ImtConflictPerfTestGen.py > ImtConflictPerfTest.java` to regenerate.
 *
 * Each interface has 64 methods, which is the current size of an IMT. C0 implements
 * one interface, C1 implements two, C2 implements three, and so on. The intent
 * is that C0 has no conflicts in its IMT, C1 has depth-2 conflicts in
 * its IMT, C2 has depth-3 conflicts, etc. This is currently guaranteed by
 * the fact that we hash interface methods by taking their method index modulo 64.
 * (Note that a "conflict depth" of 1 means no conflict at all.)
 */\
"""

print(license)
print("package android.libcore;")
imports = """
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
"""
print(imports)
print(description)

print("@RunWith(AndroidJUnit4.class)")
print("@LargeTest")
print("public class ImtConflictPerfTest {")
print("    @Rule")
print("    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();")
print("")
# Warm up interface method tables
print("    @Before")
print("    public void setup() {")
for i in range(max_conflict_depth):
    print("        C{0} c{0} = new C{0}();".format(i))
    for j in range(i+1):
        print("        callF{}(c{});".format(imt_size * j, i))
print("    }")

# Print test cases--one for each conflict depth
for i in range(max_conflict_depth):
    print("    @Test")
    print("    public void timeConflictDepth{:02d}() {{".format(i+1))
    print("        C{0} c{0} = new C{0}();".format(i))
    print("        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();")
    print("        while (state.keepRunning()) {")
    # Cycle through each interface method in an IMT entry in order
    # to test all conflict resolution possibilities
    for j in range(max_conflict_depth):
        print("            callF{}(c{});".format(imt_size * (j % (i + 1)), i))
    print("        }")
    print("    }")

# Make calls through the IMTs
for i in range(max_conflict_depth):
    print("    public void callF{0}(I{1} i) {{ i.f{0}(); }}".format(imt_size*i, i))

# Class definitions, implementing varying amounts of interfaces
for i in range(max_conflict_depth):
    interfaces = ", ".join(["I{}".format(j) for j in range(i+1)])
    print("    static class C{} implements {} {{}}".format(i, interfaces))

# Interface definitions, each with enough methods to fill an entire IMT
for i in range(max_conflict_depth):
    print("    interface I{} {{".format(i))
    for j in range(imt_size):
        print("        default void f{}() {{}}".format(i*imt_size + j))
    print("    }")

print("}")