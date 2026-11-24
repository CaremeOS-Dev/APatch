// IAPRootService.aidl
package com.zcg.apatch;

import android.content.pm.PackageInfo;
import rikka.parcelablelist.ParcelableListSlice;

interface IAPRootService {
    ParcelableListSlice<PackageInfo> getPackages(int flags);
}