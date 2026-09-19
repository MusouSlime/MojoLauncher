package com.severedchains.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.severedchains.launcher.JVMRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen controller overlay: D-pad + PlayStation-layout shape buttons +
 * Start/Select + shoulders/triggers/stick clicks.
 *
 * <p>Each touch produces two outputs: an SDL key event (keyboard path, for
 * menus) and a virtual-gamepad button/axis update via {@link
 * com.severedchains.launcher.SCGamepadNative} (gamepad path, for gameplay).
 * Layout is user-arrangeable (long-press drags, double-tap empty resets) and
 * persisted as screen fractions. Shown iff no physical controller is
 * attached (see SCGameActivity).
 */
public class TouchGamepadView extends View {
    private static final String TAG = "TouchGamepadView";
    
    private final Paint mPaintBackground;
    private final Paint mPaintButton;
    private final Paint mPaintText;
    private final Paint mPaintDPad;
    
    private final List<Button> mButtons = new ArrayList<>();
    private final List<Button> mActiveTouches = new ArrayList<>();
    
    private int mWidth, mHeight;
    private float mButtonSize;
    private float mMargin;
    private float mDPadSize;

    // Drag-to-move state (long-press a button to pick its whole cluster up,
    // drop to place). Hold is deliberately long (1.2s) with a tight slop and
    // drift-cancels: holding a direction to walk must NEVER rearrange the
    // pad. Pickup shows a toast so a real move is never a surprise.
    private static final long MOVE_HOLD_MS = 1200;
    private static final float MOVE_SLOP_DP = 8f;
    private Button mMoveCandidate;
    private int mMovePointerId = -1;
    private long mMoveDownTime;
    private float mMoveDownX, mMoveDownY;
    private float mDragLastX, mDragLastY;
    private Button mMovingBtn;
    private long mLastEmptyTapTime;
    private float mLastEmptyTapX, mLastEmptyTapY;
    
    public TouchGamepadView(Context context) {
        this(context, null);
    }
    
    public TouchGamepadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        mPaintBackground = new Paint();
        mPaintBackground.setColor(0x88000000);
        mPaintBackground.setAntiAlias(true);
        
        mPaintButton = new Paint();
        mPaintButton.setColor(0xCC333333);
        mPaintButton.setAntiAlias(true);
        
        mPaintText = new Paint();
        mPaintText.setColor(Color.WHITE);
        mPaintText.setTextSize(24f);
        mPaintText.setAntiAlias(true);
        mPaintText.setTextAlign(Paint.Align.CENTER);
        
        mPaintDPad = new Paint();
        mPaintDPad.setColor(0xCC444444);
        mPaintDPad.setAntiAlias(true);
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWidth = w;
        mHeight = h;
        layoutButtons();
    }
    
    private void layoutButtons() {
        mButtons.clear();
        // Center the control clusters on the actual screen: left cluster
        // centered in the left half, right cluster in the right half, both
        // vertically centered; top rows centered horizontally.
        float cxLeft = mWidth * 0.25f;
        float cxRight = mWidth * 0.75f;
        float cyMid = mHeight * 0.5f;
        mMargin = Math.min(mWidth, mHeight) * 0.02f;
        mButtonSize = Math.min(mWidth, mHeight) * 0.12f;
        mDPadSize = mButtonSize * 2.5f;

        // D-Pad (left half, vertically centered; true cross, no center key).
        // All four move as one cluster (group "dpad").
        float dpadCenterX = cxLeft;
        float dpadCenterY = cyMid;

        // D-Pad directions (SDL gamepad buttons 11-14)
        mButtons.add(new Button("UP", dpadCenterX, dpadCenterY - mDPadSize/2, mDPadSize/1.5f, mDPadSize/1.5f, 19, 11, -1, "dpad")); // DPAD_UP
        mButtons.add(new Button("DOWN", dpadCenterX, dpadCenterY + mDPadSize/2, mDPadSize/1.5f, mDPadSize/1.5f, 20, 12, -1, "dpad")); // DPAD_DOWN
        mButtons.add(new Button("LEFT", dpadCenterX - mDPadSize/2, dpadCenterY, mDPadSize/1.5f, mDPadSize/1.5f, 21, 13, -1, "dpad")); // DPAD_LEFT
        mButtons.add(new Button("RIGHT", dpadCenterX + mDPadSize/2, dpadCenterY, mDPadSize/1.5f, mDPadSize/1.5f, 22, 14, -1, "dpad")); // DPAD_RIGHT

        // Shape buttons (right half, vertically centered; PlayStation layout:
        // Triangle=top, Cross=bottom, Square=right, Circle=left)
        // SDL: NORTH=3, SOUTH=0, WEST=2, EAST=1. One cluster (group "shapes").
        float shapeX = cxRight;
        float shapeY = cyMid;

        mButtons.add(new Button("△", shapeX, shapeY - mButtonSize * 1.2f, mButtonSize, mButtonSize, 32, 3, -1, "shapes")); // Triangle = D (32) - TOP
        mButtons.add(new Button("×", shapeX, shapeY + mButtonSize * 1.2f, mButtonSize, mButtonSize, 51, 0, -1, "shapes")); // Cross = W (51) - BOTTOM
        mButtons.add(new Button("□", shapeX + mButtonSize * 1.2f, shapeY, mButtonSize, mButtonSize, 30, 1, -1, "shapes")); // Square = A (30) - RIGHT
        mButtons.add(new Button("○", shapeX - mButtonSize * 1.2f, shapeY, mButtonSize, mButtonSize, 47, 2, -1, "shapes")); // Circle = S (47) - LEFT

        // Start / Select (top center, one cluster)
        float topY = mMargin + mButtonSize * 0.6f;
        mButtons.add(new Button("START", mWidth / 2f + mButtonSize * 1.1f, topY, mButtonSize * 1.8f, mButtonSize * 0.9f, 66, 6, -1, "system")); // Enter (66)
        mButtons.add(new Button("SELECT", mWidth / 2f - mButtonSize * 1.1f, topY, mButtonSize * 1.8f, mButtonSize * 0.9f, 62, 4, -1, "system")); // Space (62)

        // Shoulder columns (left column / right column move as units)
        mButtons.add(new Button("L1", mMargin + mButtonSize, topY, mButtonSize * 1.5f, mButtonSize * 0.9f, 50, 9, -1, "left")); // Q (50)
        mButtons.add(new Button("R1", mWidth - mMargin - mButtonSize, topY, mButtonSize * 1.5f, mButtonSize * 0.9f, 44, 10, -1, "right")); // E (44)

        // Trigger buttons (below shoulders)
        float trigY = topY + mButtonSize * 1.1f;
        mButtons.add(new Button("L2", mMargin + mButtonSize, trigY, mButtonSize * 1.5f, mButtonSize * 0.9f, 8, -1, 4, "left")); // 1 (8)
        mButtons.add(new Button("R2", mWidth - mMargin - mButtonSize, trigY, mButtonSize * 1.5f, mButtonSize * 0.9f, 10, -1, 5, "right")); // 3 (10)

        // Stick buttons (below triggers)
        float stickY = trigY + mButtonSize * 1.1f;
        mButtons.add(new Button("L3", mMargin + mButtonSize, stickY, mButtonSize * 1.5f, mButtonSize * 0.9f, 45, 7, -1, "left")); // Z (45)
        mButtons.add(new Button("R3", mWidth - mMargin - mButtonSize, stickY, mButtonSize * 1.5f, mButtonSize * 0.9f, 31, 8, -1, "right")); // C (31)

        // Re-apply any user-arranged positions (fractions survive rotation).
        applySavedLayout();
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // Draw D-Pad background (matches layoutButtons centering)
        float dpadCenterX = mWidth * 0.25f;
        float dpadCenterY = mHeight * 0.5f;
        canvas.drawRoundRect(new RectF(
            dpadCenterX - mDPadSize/2, dpadCenterY - mDPadSize/2,
            dpadCenterX + mDPadSize/2, dpadCenterY + mDPadSize/2
        ), 16f, 16f, mPaintBackground);
        
        // Draw all buttons
        for (Button btn : mButtons) {
            btn.draw(canvas);
        }
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerIndex = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIndex);
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                handleTouchDown(pointerId, x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                handleTouchMove(event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                handleTouchUp(pointerId, x, y);
                break;
        }
        
        invalidate();
        return true;
    }
    
    private void handleTouchDown(int pointerId, float x, float y) {
        Button hit = null;
        for (Button btn : mButtons) {
            if (btn.contains(x, y) && !btn.isPressed()) {
                press(btn, pointerId);
                hit = btn;
                break;
            }
        }
        if (hit != null) {
            mMoveCandidate = hit;
            mMovePointerId = pointerId;
            mMoveDownTime = System.currentTimeMillis();
            mMoveDownX = x;
            mMoveDownY = y;
        } else {
            // Double-tap on empty area resets the layout.
            long now = System.currentTimeMillis();
            float slop = MOVE_SLOP_DP * getResources().getDisplayMetrics().density * 3;
            if (now - mLastEmptyTapTime < 400
                    && Math.hypot(x - mLastEmptyTapX, y - mLastEmptyTapY) < slop) {
                resetLayout();
                mLastEmptyTapTime = 0;
            } else {
                mLastEmptyTapTime = now;
                mLastEmptyTapX = x;
                mLastEmptyTapY = y;
            }
        }
    }
    
    private void handleTouchMove(MotionEvent event) {
        // Long-press drag: pick the button's whole CLUSTER up so it can be
        // repositioned. The key press is released first, so gameplay sees a
        // clean break. Drifting before the hold completes cancels the
        // candidacy outright (walking with a held direction must never
        // unlock the layout).
        if (mMovingBtn == null && mMoveCandidate != null) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (event.getPointerId(i) == mMovePointerId) {
                    float dx = event.getX(i) - mMoveDownX;
                    float dy = event.getY(i) - mMoveDownY;
                    float slop = MOVE_SLOP_DP * getResources().getDisplayMetrics().density;
                    float dist = (float) Math.hypot(dx, dy);
                    if (dist >= slop) {
                        mMoveCandidate = null;
                        mMovePointerId = -1;
                    } else if (System.currentTimeMillis() - mMoveDownTime > MOVE_HOLD_MS
                            && mMoveCandidate.isPressed()) {
                        release(mMoveCandidate);
                        mMovingBtn = mMoveCandidate;
                        mDragLastX = clampX(event.getX(i));
                        mDragLastY = clampY(event.getY(i));
                        mMoveCandidate = null;
                        android.widget.Toast.makeText(getContext(),
                                "Moving " + mMovingBtn.groupName() + " — drop to place",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                    break;
                }
            }
        }
        if (mMovingBtn != null) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (event.getPointerId(i) == mMovingBtn.pointerId
                        || event.getPointerId(i) == mMovePointerId) {
                    float nx = clampX(event.getX(i));
                    float ny = clampY(event.getY(i));
                    // Move the whole cluster by the same delta so the
                    // cluster keeps its shape (d-pad stays a d-pad).
                    float gdx = nx - mDragLastX;
                    float gdy = ny - mDragLastY;
                    mDragLastX = nx;
                    mDragLastY = ny;
                    for (Button b : mButtons) {
                        if (b.group.equals(mMovingBtn.group)) {
                            b.cx = clampX(b.cx + gdx);
                            b.cy = clampY(b.cy + gdy);
                        }
                    }
                    invalidate();
                    return;
                }
            }
            return;
        }
        // For each active touch, check if it moved outside its button
        for (int i = 0; i < event.getPointerCount(); i++) {
            int pointerId = event.getPointerId(i);
            float x = event.getX(i);
            float y = event.getY(i);
            
            Button activeBtn = findActiveButton(pointerId);
            if (activeBtn != null) {
                if (!activeBtn.contains(x, y)) {
                    // Moved off button - release
                    release(activeBtn);
                }
            } else {
                // Check if touch entered a new button
                for (Button btn : mButtons) {
                    if (btn.contains(x, y) && !btn.isPressed()) {
                        press(btn, pointerId);
                        break;
                    }
                }
            }
        }
    }
    
    private void handleTouchUp(int pointerId, float x, float y) {
        if (mMovingBtn != null && pointerId == mMovePointerId) {
            saveLayout();
            mMovingBtn = null;
            mMoveCandidate = null;
            mMovePointerId = -1;
            invalidate();
            return;
        }
        if (pointerId == mMovePointerId) {
            mMoveCandidate = null;
            mMovePointerId = -1;
        }
        Button activeBtn = findActiveButton(pointerId);
        if (activeBtn != null) {
            release(activeBtn);
        }
    }

    private float clampX(float x) {
        return Math.max(mButtonSize / 2f, Math.min(mWidth - mButtonSize / 2f, x));
    }

    private float clampY(float y) {
        return Math.max(mButtonSize / 2f, Math.min(mHeight - mButtonSize / 2f, y));
    }

    private android.content.SharedPreferences prefs() {
        return getContext().getSharedPreferences("touchpad_layout_v1",
                android.content.Context.MODE_PRIVATE);
    }

    private void saveLayout() {
        android.content.SharedPreferences.Editor ed = prefs().edit();
        for (Button btn : mButtons) {
            ed.putFloat(btn.label + "_x", btn.cx / mWidth);
            ed.putFloat(btn.label + "_y", btn.cy / mHeight);
        }
        ed.apply();
    }

    private void applySavedLayout() {
        android.content.SharedPreferences p = prefs();
        for (Button btn : mButtons) {
            float fx = p.getFloat(btn.label + "_x", -1f);
            float fy = p.getFloat(btn.label + "_y", -1f);
            if (fx >= 0 && fy >= 0) {
                btn.cx = fx * mWidth;
                btn.cy = fy * mHeight;
            }
        }
    }

    private void resetLayout() {
        prefs().edit().clear().apply();
        layoutButtons();
        invalidate();
        android.widget.Toast.makeText(getContext(), "Controls reset to default",
                android.widget.Toast.LENGTH_SHORT).show();
    }
    
    private Button findActiveButton(int pointerId) {
        for (Button btn : mActiveTouches) {
            if (btn.pointerId == pointerId) {
                return btn;
            }
        }
        return null;
    }
    
    private void sendKeyEvent(int keyCode, boolean down) {
        // Legacy JVM-side queue (kept, harmless).
        JVMRunner.queueKeyEvent(keyCode, down);
        // Real path: forward to SDL as an SDL keycode (Mojo SDLBackend parity).
        int sdlKey = toSdlKeyCode(keyCode);
        if (sdlKey == 0) return;
        try {
            if (down) git.mojo.sdl.SDLActivity.onNativeKeyDown(sdlKey);
            else git.mojo.sdl.SDLActivity.onNativeKeyUp(sdlKey);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            Log.w(TAG, "SDL native not ready, key dropped: " + keyCode);
        }
    }

    private void sendPadButton(int padButton, boolean down) {
        if (padButton < 0) return;
        com.severedchains.launcher.SCGamepadNative.setButton(padButton, down);
    }

    private void sendPadAxis(int padAxis, boolean down) {
        if (padAxis < 0) return;
        // Digital shoulder triggers: pressed = full deflection.
        com.severedchains.launcher.SCGamepadNative.setAxis(padAxis, down ? 32767 : 0);
    }

    private void press(Button btn, int pointerId) {
        btn.setPressed(true, pointerId);
        mActiveTouches.add(btn);
        sendKeyEvent(btn.keyCode, true);
        sendPadButton(btn.padButton, true);
        sendPadAxis(btn.padAxis, true);
    }

    private void release(Button btn) {
        sendKeyEvent(btn.keyCode, false);
        sendPadButton(btn.padButton, false);
        sendPadAxis(btn.padAxis, false);
        btn.setPressed(false, -1);
        mActiveTouches.remove(btn);
    }

    /**
     * Android keycode -> SDL3 keycode. Arrows/space/enter/digits use SDL's
     * special/ASCII values; letters map A-Z (29-54) to a-z (97-122).
     */
    public static int toSdlKeyCode(int androidKey) {
        switch (androidKey) {
            case 19: return 0x40000052; // DPAD_UP -> SDLK_UP
            case 20: return 0x40000051; // DPAD_DOWN -> SDLK_DOWN
            case 21: return 0x40000050; // DPAD_LEFT -> SDLK_LEFT
            case 22: return 0x4000004F; // DPAD_RIGHT -> SDLK_RIGHT
            case 23: return 13;         // DPAD_CENTER -> SDLK_RETURN
            case 66: return 13;         // ENTER -> SDLK_RETURN
            case 62: return 32;         // SPACE -> SDLK_SPACE
            default: break;
        }
        if (androidKey >= 29 && androidKey <= 54) return 97 + (androidKey - 29); // A-Z
        if (androidKey >= 7 && androidKey <= 16) return 48 + (androidKey - 7);   // 0-9
        return 0;
    }
    
    private class Button {
        final String label;
        float cx, cy;
        final float width, height;
        final int keyCode;
        // SDL gamepad mapping (XInput-style indices); -1 = none.
        final int padButton;
        final int padAxis;
        // Arrange cluster: dragging one member moves every member with the
        // same group ("dpad", "shapes", "system", "left", "right").
        final String group;
        boolean pressed = false;
        int pointerId = -1;

        Button(String label, float cx, float cy, float width, float height,
               int keyCode, int padButton, int padAxis, String group) {
            this.label = label;
            this.cx = cx;
            this.cy = cy;
            this.width = width;
            this.height = height;
            this.keyCode = keyCode;
            this.padButton = padButton;
            this.padAxis = padAxis;
            this.group = group;
        }

        /** Human cluster name for the move-mode toast. */
        String groupName() {
            switch (group) {
                case "dpad": return "D-pad";
                case "shapes": return "buttons";
                case "system": return "start/select";
                case "left": return "left buttons";
                case "right": return "right buttons";
                default: return "controls";
            }
        }
        
        boolean contains(float x, float y) {
            return x >= cx - width/2 && x <= cx + width/2 &&
                   y >= cy - height/2 && y <= cy + height/2;
        }
        
        boolean isPressed() {
            return pressed;
        }
        
        void setPressed(boolean pressed, int pointerId) {
            this.pressed = pressed;
            this.pointerId = pointerId;
        }
        
        void draw(Canvas canvas) {
            float left = cx - width/2;
            float top = cy - height/2;
            float right = cx + width/2;
            float bottom = cy + height/2;
            float radius = Math.min(width, height) * 0.2f;
            
            Paint btnPaint = new Paint(mPaintButton);
            if (pressed) {
                btnPaint.setColor(0xCC666666);
            } else {
                btnPaint.setColor(0xCC333333);
            }
            
            canvas.drawRoundRect(new RectF(left, top, right, bottom), radius, radius, btnPaint);
            
            // Draw label
            canvas.drawText(label, cx, cy + mPaintText.getTextSize()/3f, mPaintText);
        }
    }
}