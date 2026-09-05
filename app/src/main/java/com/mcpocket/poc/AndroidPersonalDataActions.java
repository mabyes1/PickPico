package com.mcpocket.poc;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CalendarContract;
import android.provider.ContactsContract;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URLConnection;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/** Android implementations for personal-context Core capabilities. */
final class AndroidPersonalDataActions {
    private static final String ACTION_CHANNEL_ID = "pickpico_core_actions_v2";
    private static final AtomicInteger ACTION_NOTIFICATION_IDS = new AtomicInteger(9700);

    private AndroidPersonalDataActions() {
    }

    static JSONObject contactsSearch(Context context, JSONObject arguments, long callCount) throws JSONException {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return permissionRequired("contacts", callCount);
        }
        String query = arguments.optString("query", "").trim();
        int limit = Math.max(1, Math.min(100, arguments.optInt("limit", 20)));
        Uri uri = query.isEmpty()
                ? ContactsContract.Contacts.CONTENT_URI
                : Uri.withAppendedPath(
                        ContactsContract.Contacts.CONTENT_FILTER_URI,
                        Uri.encode(query));
        String[] projection = {
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.HAS_PHONE_NUMBER,
                ContactsContract.Contacts.STARRED
        };
        JSONArray contacts = new JSONArray();
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                projection,
                null,
                null,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY);
                int phoneColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER);
                int starredColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED);
                while (cursor.moveToNext() && contacts.length() < limit) {
                    contacts.put(new JSONObject()
                            .put("id", String.valueOf(cursor.getLong(idColumn)))
                            .put("displayName", value(cursor, nameColumn))
                            .put("hasPhoneNumber", cursor.getInt(phoneColumn) != 0)
                            .put("starred", cursor.getInt(starredColumn) != 0));
                }
            }
        } catch (SecurityException error) {
            return permissionRejected("contacts", error, callCount);
        }
        return new JSONObject()
                .put("contacts", contacts)
                .put("count", contacts.length())
                .put("query", query)
                .put("toolCallCount", callCount);
    }

    static JSONObject contactsGet(Context context, JSONObject arguments, long callCount) throws JSONException {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return permissionRequired("contacts", callCount);
        }
        long contactId = parseId(arguments.optString("id", ""), "contact id");
        ContentResolver resolver = context.getContentResolver();
        JSONObject contact = null;
        Uri contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId);
        String[] projection = {
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.STARRED
        };
        try (Cursor cursor = resolver.query(contactUri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                contact = new JSONObject()
                        .put("id", String.valueOf(cursor.getLong(0)))
                        .put("displayName", value(cursor, 1))
                        .put("starred", cursor.getInt(2) != 0);
            }
        } catch (SecurityException error) {
            return permissionRejected("contacts", error, callCount);
        }
        if (contact == null) {
            return new JSONObject()
                    .put("found", false)
                    .put("id", String.valueOf(contactId))
                    .put("toolCallCount", callCount);
        }

        JSONArray phones = new JSONArray();
        String[] phoneProjection = {
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
        };
        try (Cursor cursor = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                phoneProjection,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                new String[]{String.valueOf(contactId)},
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY + " DESC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    phones.put(new JSONObject()
                            .put("number", value(cursor, 0))
                            .put("type", cursor.getInt(1))
                            .put("label", value(cursor, 2))
                            .put("primary", cursor.getInt(3) != 0));
                }
            }
        }

        JSONArray emails = new JSONArray();
        String[] emailProjection = {
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.TYPE,
                ContactsContract.CommonDataKinds.Email.LABEL,
                ContactsContract.CommonDataKinds.Email.IS_PRIMARY
        };
        try (Cursor cursor = resolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                emailProjection,
                ContactsContract.CommonDataKinds.Email.CONTACT_ID + "=?",
                new String[]{String.valueOf(contactId)},
                ContactsContract.CommonDataKinds.Email.IS_PRIMARY + " DESC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    emails.put(new JSONObject()
                            .put("address", value(cursor, 0))
                            .put("type", cursor.getInt(1))
                            .put("label", value(cursor, 2))
                            .put("primary", cursor.getInt(3) != 0));
                }
            }
        }
        contact.put("phones", phones).put("emails", emails);
        return new JSONObject()
                .put("found", true)
                .put("contact", contact)
                .put("toolCallCount", callCount);
    }

    static JSONObject calendarList(Context context, JSONObject arguments, long callCount) throws JSONException {
        if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) {
            return permissionRequired("calendar", callCount);
        }
        long begin = arguments.optLong("startEpochMs", System.currentTimeMillis());
        long end = arguments.optLong("endEpochMs", begin + 7L * 24L * 60L * 60L * 1000L);
        int limit = Math.max(1, Math.min(200, arguments.optInt("limit", 50)));
        String calendarFilter = arguments.optString("calendarId", "").trim();
        JSONArray events = new JSONArray();
        String[] projection = {
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        };
        try (Cursor cursor = CalendarContract.Instances.query(
                context.getContentResolver(), projection, begin, end)) {
            if (cursor != null) {
                while (cursor.moveToNext() && events.length() < limit) {
                    String calendarId = String.valueOf(cursor.getLong(6));
                    if (!calendarFilter.isEmpty() && !calendarFilter.equals(calendarId)) {
                        continue;
                    }
                    events.put(new JSONObject()
                            .put("eventId", String.valueOf(cursor.getLong(0)))
                            .put("title", value(cursor, 1))
                            .put("startEpochMs", cursor.getLong(2))
                            .put("endEpochMs", cursor.getLong(3))
                            .put("allDay", cursor.getInt(4) != 0)
                            .put("location", value(cursor, 5))
                            .put("calendarId", calendarId)
                            .put("calendarName", value(cursor, 7)));
                }
            }
        } catch (SecurityException error) {
            return permissionRejected("calendar", error, callCount);
        }
        return new JSONObject()
                .put("events", events)
                .put("count", events.length())
                .put("calendars", listCalendars(context))
                .put("startEpochMs", begin)
                .put("endEpochMs", end)
                .put("toolCallCount", callCount);
    }

    static JSONObject calendarGet(Context context, JSONObject arguments, long callCount) throws JSONException {
        if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) {
            return permissionRequired("calendar", callCount);
        }
        long eventId = parseId(arguments.optString("eventId", ""), "event id");
        Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId);
        String[] projection = {
                CalendarContract.Events._ID,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.STATUS
        };
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return new JSONObject()
                        .put("found", false)
                        .put("eventId", String.valueOf(eventId))
                        .put("toolCallCount", callCount);
            }
            JSONObject event = new JSONObject()
                    .put("eventId", String.valueOf(cursor.getLong(0)))
                    .put("calendarId", String.valueOf(cursor.getLong(1)))
                    .put("title", value(cursor, 2))
                    .put("description", value(cursor, 3))
                    .put("location", value(cursor, 4))
                    .put("startEpochMs", cursor.isNull(5) ? JSONObject.NULL : cursor.getLong(5))
                    .put("endEpochMs", cursor.isNull(6) ? JSONObject.NULL : cursor.getLong(6))
                    .put("allDay", cursor.getInt(7) != 0)
                    .put("timezone", value(cursor, 8))
                    .put("rrule", value(cursor, 9))
                    .put("status", cursor.getInt(10));
            return new JSONObject()
                    .put("found", true)
                    .put("event", event)
                    .put("toolCallCount", callCount);
        } catch (SecurityException error) {
            return permissionRejected("calendar", error, callCount);
        }
    }

    static JSONObject calendarCreate(Context context, JSONObject arguments, long callCount) throws JSONException {
        if (!hasPermission(context, Manifest.permission.WRITE_CALENDAR)) {
            return permissionRequired("calendar_write", callCount);
        }
        long calendarId = arguments.has("calendarId")
                ? parseId(arguments.optString("calendarId", ""), "calendar id")
                : defaultWritableCalendarId(context);
        if (calendarId < 0L) {
            return new JSONObject()
                    .put("created", false)
                    .put("error", "No writable calendar is available")
                    .put("toolCallCount", callCount);
        }
        ContentValues values = eventValues(arguments, true);
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        try {
            Uri created = context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values);
            if (created == null) {
                return new JSONObject().put("created", false).put("toolCallCount", callCount);
            }
            return new JSONObject()
                    .put("created", true)
                    .put("eventId", created.getLastPathSegment())
                    .put("calendarId", String.valueOf(calendarId))
                    .put("uri", created.toString())
                    .put("toolCallCount", callCount);
        } catch (SecurityException error) {
            return permissionRejected("calendar_write", error, callCount);
        }
    }

    static JSONObject calendarUpdate(Context context, JSONObject arguments, long callCount) throws JSONException {
        if (!hasPermission(context, Manifest.permission.WRITE_CALENDAR)) {
            return permissionRequired("calendar_write", callCount);
        }
        long eventId = parseId(arguments.optString("eventId", ""), "event id");
        ContentValues values = eventValues(arguments, false);
        if (values.size() == 0) {
            return new JSONObject()
                    .put("updated", false)
                    .put("eventId", String.valueOf(eventId))
                    .put("error", "No event fields were supplied")
                    .put("toolCallCount", callCount);
        }
        try {
            int rows = context.getContentResolver().update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    values,
                    null,
                    null);
            return new JSONObject()
                    .put("updated", rows > 0)
                    .put("rows", rows)
                    .put("eventId", String.valueOf(eventId))
                    .put("toolCallCount", callCount);
        } catch (SecurityException error) {
            return permissionRejected("calendar_write", error, callCount);
        }
    }

    static JSONObject calendarDelete(Context context, JSONObject arguments, long callCount) throws JSONException {
        if (!hasPermission(context, Manifest.permission.WRITE_CALENDAR)) {
            return permissionRequired("calendar_write", callCount);
        }
        long eventId = parseId(arguments.optString("eventId", ""), "event id");
        try {
            int rows = context.getContentResolver().delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    null,
                    null);
            return new JSONObject()
                    .put("deleted", rows > 0)
                    .put("rows", rows)
                    .put("eventId", String.valueOf(eventId))
                    .put("toolCallCount", callCount);
        } catch (SecurityException error) {
            return permissionRejected("calendar_write", error, callCount);
        }
    }

    static JSONObject shareSend(
            Context context,
            File workspaceRoot,
            JSONObject arguments,
            long callCount) throws JSONException {
        String text = arguments.optString("text", "");
        String url = arguments.optString("url", "");
        String workspacePath = arguments.optString("workspacePath", "");
        String chooserTitle = arguments.optString("chooserTitle", "Share with");
        String combined = text;
        if (!url.isEmpty()) {
            combined = combined.isEmpty() ? url : combined + "\n" + url;
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        String mimeType = arguments.optString("mimeType", "").trim();
        if (!workspacePath.isEmpty()) {
            try {
                File file = resolveWorkspaceFile(workspaceRoot, workspacePath);
                if (!file.isFile()) {
                    return new JSONObject()
                            .put("shared", false)
                            .put("error", "Workspace file does not exist")
                            .put("workspacePath", workspacePath)
                            .put("toolCallCount", callCount);
                }
                Uri uri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".files",
                        file);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.setClipData(ClipData.newRawUri(file.getName(), uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (mimeType.isEmpty()) {
                    mimeType = URLConnection.guessContentTypeFromName(file.getName());
                }
            } catch (IOException | IllegalArgumentException error) {
                return new JSONObject()
                        .put("shared", false)
                        .put("error", error.getMessage())
                        .put("toolCallCount", callCount);
            }
        }
        if (!combined.isEmpty()) {
            send.putExtra(Intent.EXTRA_TEXT, combined);
        }
        send.setType(mimeType == null || mimeType.isEmpty()
                ? (workspacePath.isEmpty() ? "text/plain" : "application/octet-stream")
                : mimeType);

        Intent chooser = Intent.createChooser(send, chooserTitle)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!AgentAttention.canStartActivityNow(context)) {
            AgentInboxStore.add(context, "share.send", "Share content", chooserTitle);
            int notificationId = postActionNotification(context, chooser, "Share content", chooserTitle);
            return new JSONObject()
                    .put("shared", false)
                    .put("requiresUserAction", true)
                    .put("delivery", notificationId > 0 ? "notification+inbox" : "inbox")
                    .put("notificationId", notificationId > 0 ? notificationId : JSONObject.NULL)
                    .put("toolCallCount", callCount);
        }
        try {
            context.startActivity(chooser);
            return new JSONObject()
                    .put("shared", true)
                    .put("shareSheetOpened", true)
                    .put("toolCallCount", callCount);
        } catch (RuntimeException error) {
            return new JSONObject()
                    .put("shared", false)
                    .put("error", error.getClass().getSimpleName() + ": " + error.getMessage())
                    .put("toolCallCount", callCount);
        }
    }

    private static JSONArray listCalendars(Context context) throws JSONException {
        JSONArray calendars = new JSONArray();
        String[] projection = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE
        };
        try (Cursor cursor = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE LOCALIZED ASC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    int accessLevel = cursor.getInt(3);
                    calendars.put(new JSONObject()
                            .put("calendarId", String.valueOf(cursor.getLong(0)))
                            .put("name", value(cursor, 1))
                            .put("account", value(cursor, 2))
                            .put("accessLevel", accessLevel)
                            .put("writable", accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                            .put("visible", cursor.getInt(4) != 0));
                }
            }
        } catch (SecurityException ignored) {
        }
        return calendars;
    }

    private static long defaultWritableCalendarId(Context context) {
        String[] projection = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.IS_PRIMARY
        };
        long fallback = -1L;
        try (Cursor cursor = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=?",
                new String[]{String.valueOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)},
                CalendarContract.Calendars.IS_PRIMARY + " DESC, "
                        + CalendarContract.Calendars.VISIBLE + " DESC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    if (fallback < 0L) {
                        fallback = id;
                    }
                    if (cursor.getInt(2) != 0 && cursor.getInt(3) != 0) {
                        return id;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return fallback;
    }

    private static ContentValues eventValues(JSONObject arguments, boolean creating) throws JSONException {
        ContentValues values = new ContentValues();
        putStringIfPresent(values, CalendarContract.Events.TITLE, arguments, "title");
        putStringIfPresent(values, CalendarContract.Events.DESCRIPTION, arguments, "description");
        putStringIfPresent(values, CalendarContract.Events.EVENT_LOCATION, arguments, "location");
        putLongIfPresent(values, CalendarContract.Events.DTSTART, arguments, "startEpochMs");
        putLongIfPresent(values, CalendarContract.Events.DTEND, arguments, "endEpochMs");
        if (arguments.has("allDay")) {
            values.put(CalendarContract.Events.ALL_DAY, arguments.optBoolean("allDay", false) ? 1 : 0);
        }
        if (arguments.has("timezone")) {
            values.put(CalendarContract.Events.EVENT_TIMEZONE, arguments.optString("timezone", "UTC"));
        } else if (creating) {
            values.put(CalendarContract.Events.EVENT_TIMEZONE,
                    arguments.optBoolean("allDay", false) ? "UTC" : TimeZone.getDefault().getID());
        }
        if (creating) {
            if (!arguments.has("title") || !arguments.has("startEpochMs") || !arguments.has("endEpochMs")) {
                throw new JSONException("calendar.create requires title, startEpochMs, and endEpochMs");
            }
        }
        return values;
    }

    private static void putStringIfPresent(ContentValues values, String column, JSONObject source, String key) {
        if (source.has(key)) {
            values.put(column, source.optString(key, ""));
        }
    }

    private static void putLongIfPresent(ContentValues values, String column, JSONObject source, String key) {
        if (source.has(key)) {
            values.put(column, source.optLong(key));
        }
    }

    private static File resolveWorkspaceFile(File root, String relativePath) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File candidate = new File(canonicalRoot, relativePath).getCanonicalFile();
        String prefix = canonicalRoot.getPath() + File.separator;
        if (!candidate.getPath().equals(canonicalRoot.getPath()) && !candidate.getPath().startsWith(prefix)) {
            throw new IOException("workspacePath escapes the PickPico workspace");
        }
        return candidate;
    }

    private static int postActionNotification(Context context, Intent target, String title, String body) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
            return -1;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return -1;
        }
        NotificationChannel channel = new NotificationChannel(
                ACTION_CHANNEL_ID,
                "PickPico core actions",
                NotificationManager.IMPORTANCE_HIGH);
        AgentAttention.configureUrgentChannel(channel);
        manager.createNotificationChannel(channel);
        int notificationId = ACTION_NOTIFICATION_IDS.incrementAndGet();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                target,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, ACTION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);
        AgentAttention.applyUrgentBehavior(context, builder, notificationId, target);
        Notification notification = builder.build();
        manager.notify(notificationId, notification);
        AgentAttention.alert(context);
        return notificationId;
    }

    private static JSONObject permissionRequired(String permission, long callCount) throws JSONException {
        return new JSONObject()
                .put("available", false)
                .put("requiresSetup", true)
                .put("permission", permission)
                .put("message", "Grant " + permission + " permission to PickPico")
                .put("toolCallCount", callCount);
    }

    private static JSONObject permissionRejected(String permission, SecurityException error, long callCount)
            throws JSONException {
        return permissionRequired(permission, callCount)
                .put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private static boolean hasPermission(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static long parseId(String raw, String label) throws JSONException {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException error) {
            throw new JSONException("Invalid " + label + ": " + raw);
        }
    }

    private static Object value(Cursor cursor, int column) {
        return cursor.isNull(column) ? JSONObject.NULL : cursor.getString(column);
    }
}
