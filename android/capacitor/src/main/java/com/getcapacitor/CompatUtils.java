package com.getcapacitor;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

import static org.json.JSONObject.NULL;

public abstract class CompatUtils {
    public static final Charset US_ASCII = Charset.forName("US-ASCII");
    public static final Charset UTF_8 = Charset.forName("UTF-8");
    public static final Charset UTF_16 = Charset.forName("UTF-16");

    public static Object wrapJSONObject(Object o) {
        if (o == null) {
            return NULL;
        }
        if (o instanceof JSONArray || o instanceof JSONObject) {
            return o;
        }
        if (o.equals(NULL)) {
            return o;
        }
        try {
            if (o instanceof Collection) {
                return new JSONArray((Collection) o);
            } else if (o.getClass().isArray()) {
                return new JSArray(o);
            }
            if (o instanceof Map) {
                return new JSONObject((Map) o);
            }
            if (o instanceof Boolean ||
                    o instanceof Byte ||
                    o instanceof Character ||
                    o instanceof Double ||
                    o instanceof Float ||
                    o instanceof Integer ||
                    o instanceof Long ||
                    o instanceof Short ||
                    o instanceof String) {
                return o;
            }
            if (o.getClass().getPackage().getName().startsWith("java.")) {
                return o.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static Locale forLanguageTag(String language) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return Locale.forLanguageTag(language);
        } else {
            return new Locale(language.split("-")[0]);
        }
    }

    public static Uri resolveUri(Context ctx, Uri uri) {
        try {
            File realFile = new File(FileUtils.getFileUrlForUri(ctx, uri));

            if (realFile.canRead()) {
                return Uri.fromFile(realFile);
            }
        } catch (Exception ex) {
            // Never mind
        }

        if (Objects.equals(uri.getScheme(), "content")) {
            File dir = new File(ctx.getCacheDir(), CompatUtils.class.getName());
            dir.mkdirs();

            File file = null;

            try (android.database.Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null)) {
                Objects.requireNonNull(cursor).moveToFirst();
                file = new File(dir, cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
            } catch (Exception ex) {
                Logger.error("Unable to get filename of " + uri, ex);
            }

            if (file == null) {
                file = new File(dir, UUID.randomUUID() + "-" + System.currentTimeMillis() + "." +
                        MimeTypeMap.getSingleton().getExtensionFromMimeType(ctx.getContentResolver().getType(uri)));
            }

            try (InputStream is = ctx.getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(file)) {
                byte[] chunk = new byte[4096];

                for (int length = Objects.requireNonNull(is).read(chunk); length != -1; length = is.read(chunk)) {
                    os.write(chunk, 0, length);
                }

                uri = Uri.fromFile(file);
            } catch (Exception ex) {
                Logger.error("Unable to make a copy of " + uri, ex);
            }
        }

        return uri;
    }
}
