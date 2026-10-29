package com.stickerstudio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** A real external share source. Pure Java because the test APK's independent
 * provider process does not inherit Kotlin from the instrumented target APK. */
public class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private String name(Uri uri) {
        String name = uri.getLastPathSegment();
        if (!"source.png".equals(name) && !"disposal.gif".equals(name) && !"bad.png".equals(name)) {
            throw new IllegalArgumentException("Unknown fixture");
        }
        return name;
    }
    @Override public String getType(Uri uri) { return name(uri).endsWith("gif") ? "image/gif" : "image/png"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[] {name(uri)});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("Read-only fixtures");
        String name = name(uri);
        File file = new File(getContext().getCacheDir(), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            if ("bad.png".equals(name)) {
                output.write(new byte[] {1,2,3});
            } else {
                try (InputStream input = getContext().getAssets().open(name)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                }
            }
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
