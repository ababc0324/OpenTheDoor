package io.github.ababc0324.openthedoor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 为系统安装器暴露 cacheDir/update.apk（替代 FileProvider，零第三方依赖）。
 * 仅被 {@link UpdateManager#install} 使用；exported=false + grantUriPermissions=true。
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.ababc0324.openthedoor.apk";

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ("update.apk".equals(uri.getLastPathSegment())) {
            File f = new File(getContext().getCacheDir(), "update.apk");
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        throw new FileNotFoundException(uri.toString());
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
