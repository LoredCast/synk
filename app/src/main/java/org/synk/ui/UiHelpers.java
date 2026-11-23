package org.synk.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.synk.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Compact AMOLED view builders and formatting utilities (zero external UI libraries).
 */
public final class UiHelpers {

    public static final int COLOR_BLACK     = 0xFF000000;
    public static final int COLOR_SURFACE   = 0xFF0A0A0A;
    public static final int COLOR_TEXT      = 0xFFFFFFFF;
    public static final int COLOR_SECONDARY = 0xFF8E8E93;
    public static final int COLOR_MUTED     = 0xFF5C5C60;
    public static final int COLOR_GREEN     = 0xFF00E676;
    public static final int COLOR_CYAN      = 0xFF40C4FF;
    public static final int COLOR_AMBER     = 0xFFFFB300;
    public static final int COLOR_RED       = 0xFFFF5252;

    private UiHelpers() {}

    public static int dp(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }

    public static TextView label(Context ctx, String text, int sp, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    public static TextView fieldLabel(Context ctx, String text) {
        TextView tv = label(ctx, text.toUpperCase(Locale.ROOT), 10, COLOR_SECONDARY, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 10);
        lp.bottomMargin = dp(ctx, 3);
        tv.setLayoutParams(lp);
        return tv;
    }

    public static EditText input(Context ctx, String hint, String value, int inputType) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(COLOR_MUTED);
        et.setText(value == null ? "" : value);
        et.setTextColor(COLOR_TEXT);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        et.setInputType(inputType);
        et.setBackgroundResource(R.drawable.bg_input);
        int p = dp(ctx, 10);
        et.setPadding(p, dp(ctx, 8), p, dp(ctx, 8));
        et.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    public static EditText textInput(Context ctx, String hint, String value) {
        return input(ctx, hint, value, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    }

    public static EditText passwordInput(Context ctx, String hint, String value) {
        return input(ctx, hint, value, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    }

    public static Spinner spinner(Context ctx, String[] items, int selectedIdx) {
        Spinner sp = new Spinner(ctx);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, convertView, parent);
                tv.setTextColor(COLOR_TEXT);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tv.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
                return tv;
            }

            @Override
            public View getDropDownView(int pos, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(pos, convertView, parent);
                tv.setBackgroundColor(0xFF101010);
                tv.setTextColor(COLOR_TEXT);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tv.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
                return tv;
            }
        };
        sp.setAdapter(adapter);
        sp.setBackgroundResource(R.drawable.bg_input);
        if (selectedIdx >= 0 && selectedIdx < items.length) sp.setSelection(selectedIdx);
        sp.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sp;
    }

    public static CheckBox checkBox(Context ctx, String text, boolean checked) {
        CheckBox cb = new CheckBox(ctx);
        cb.setText(text);
        cb.setChecked(checked);
        cb.setTextColor(COLOR_TEXT);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 4);
        cb.setLayoutParams(lp);
        return cb;
    }

    public static TextView chip(Context ctx, String text, int textColor) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setBackgroundResource(R.drawable.bg_chip);
        tv.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(ctx, 6);
        tv.setLayoutParams(lp);
        return tv;
    }

    public static Button smallButton(Context ctx, String text, int textColor, boolean accent) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setAllCaps(true);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(accent ? R.drawable.bg_button_accent : R.drawable.bg_button);
        b.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(ctx, 6);
        b.setLayoutParams(lp);
        return b;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static String formatTime(long ms) {
        if (ms <= 0) return "never";
        long diff = System.currentTimeMillis() - ms;
        if (diff < 60_000L && diff >= 0) return "just now";
        if (diff < 3_600_000L && diff >= 0) return (diff / 60_000L) + "m ago";
        if (diff < 86_400_000L && diff >= 0) return (diff / 3_600_000L) + "h ago";
        return new SimpleDateFormat("MMM dd HH:mm", Locale.US).format(new Date(ms));
    }

    public static String formatClock(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }

    public static String formatInterval(int mins) {
        if (mins <= 0) return "MANUAL";
        if (mins < 60) return mins + "m";
        return (mins / 60) + "h";
    }

    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0L) return "-- B/s";
        return formatBytes(bytesPerSec) + "/s";
    }

    public static String formatEta(long etaSeconds) {
        if (etaSeconds < 0L) return "--";
        if (etaSeconds < 60L) return etaSeconds + "s";
        long m = etaSeconds / 60L;
        long s = etaSeconds % 60L;
        if (m < 60L) return m + "m " + s + "s";
        long h = m / 60L;
        return h + "h " + (m % 60L) + "m";
    }
}
