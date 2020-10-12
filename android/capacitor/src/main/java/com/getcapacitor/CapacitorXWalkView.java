package com.getcapacitor;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import org.xwalk.core.XWalkView;

public class CapacitorXWalkView extends XWalkView {
    private BaseInputConnection capInputConnection;

    public CapacitorXWalkView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        CapConfig config = new CapConfig(getContext().getAssets(), null);
        boolean captureInput = config.getBoolean("android.captureInput", false);
        if (captureInput) {
            if (capInputConnection == null) {
                capInputConnection = new BaseInputConnection(this, false);
            }
            return capInputConnection;
        }
        return super.onCreateInputConnection(outAttrs);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return false; // Do not let Crosswalk handle the Back button!
        }
        return super.dispatchKeyEvent(event);
    }
}
