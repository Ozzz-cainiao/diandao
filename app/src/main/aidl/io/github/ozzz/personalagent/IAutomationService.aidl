package io.github.ozzz.personalagent;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IAutomationService {
    // Reserved UserService lifecycle transaction; AIDL adds 1 to this ID.
    oneway void destroy() = 16777114;
    Bundle getIdentity() = 1;
    Bundle launchApp(String packageName) = 2;
    Bundle tap(String expectedPackage, int x, int y) = 3;
    Bundle returnHome(String expectedPackage) = 5;
    String dumpUi(String expectedPackage) = 4;
    Bundle swipe(String expectedPackage, int startX, int startY, int endX, int endY, int durationMs) = 6;
    Bundle back(String expectedPackage) = 7;
    ParcelFileDescriptor captureScreen(String expectedPackage) = 8;
    Bundle visitAndReturn(String expectedPackage, int x, int y, String visitPackage, String title) = 9;
}
