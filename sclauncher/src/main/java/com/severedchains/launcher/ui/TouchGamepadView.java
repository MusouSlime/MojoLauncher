package com.severedchains.launcher.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.severedchains.launcher.JVMRunner;
import com.severedchains.launcher.SCGamepadNative;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * On-screen controller overlay: a floating analog stick (left) + PlayStation
 * shape buttons + Start/Select + shoulders/triggers/stick clicks.
 *
 * <p>Each touch produces two outputs: an SDL key event (keyboard path, for
 * menus) and a virtual-gamepad button/axis update via {@link
 * com.severedchains.launcher.SCGamepadNative} (gamepad path, for gameplay).
 *
 * <p>UX concepts borrowed from dracinn/Severed-Chains-Android:
 * <ul>
 *   <li>The left stick is <b>floating</b>: a touch anywhere in the left zone
 *       anchors it under the thumb and drags emit left-stick X/Y axes.</li>
 *   <li>A two-finger <b>pinch</b> resizes the pad; positions/scale/opacity are
 *       persisted.</li>
 *   <li><b>Opacity</b> is user-adjustable (see the launcher menu).</li>
 *   <li>Hides itself when a physical controller reports input and reappears on
 *       the next touch.</li>
 * </ul>
 * Layout is user-arrangeable (long-press drags a cluster, double-tap empty
 * space resets).
 */
public class TouchGamepadView extends View {
    private static final String TAG = "TouchGamepadView";
    /** SharedPreferences file holding the pad layout/scale/opacity. The
     *  launcher's "Reset Controls" menu item clears this. */
    public static final String PREFS = "touchpad_layout_v3";

    // ------------------------------------------------------------------
    // Legend of Dragoon theme: deep indigo panels with a steel-blue border and
    // gold/parchment accents, PS1 button shapes, and the game's own bitmap
    // font (see LodFont) for labels.
    // ------------------------------------------------------------------
    private static final int LOD_FILL = 0xFF0C1733;         // indigo panel
    private static final int LOD_FILL_PRESS = 0xFF20365E;   // lighter on press
    private static final int LOD_BORDER = 0xFF8FB0DA;       // steel blue
    private static final int LOD_BORDER_PRESS = 0xFFE4C170; // gold on press
    private static final int LOD_GOLD = 0xFFD8B36A;
    private static final int LOD_TEXT = 0xFFF3E7C9;         // parchment
    private static final int LOD_TRI = 0xFF5CC06A;          // △ green
    private static final int LOD_CIRC = 0xFFD95C5C;         // ○ red
    private static final int LOD_CROSS = 0xFF6E8FE0;        // × blue
    private static final int LOD_SQUARE = 0xFFD07BB2;       // □ pink

    // Base alphas (further multiplied by the user opacity). Kept low so the pad
    // is see-through and never hides the game behind it.
    private static final int ALPHA_FILL = 0x1A;        // ~10% panel fill (barely visible)
    private static final int ALPHA_FILL_PRESS = 0x40;  // ~25% on press
    private static final int ALPHA_BORDER = 0x59;      // ~35% outline
    private static final int ALPHA_GLYPH = 0xA6;       // ~65% glyphs/labels

    private final Paint mPaintFill;
    private final Paint mPaintBorder;
    private final Paint mPaintStick;
    private final Paint mPaintBackground;
    private final Paint mPaintButton;
    private final Paint mPaintText;
    private LodFont mFont;

    private final List<Button> mButtons = new ArrayList<>();
    private final List<Button> mActiveTouches = new ArrayList<>();

    private int mWidth, mHeight;
    private float mButtonSize;
    private float mMargin;
    private float mDPadSize;
    private float mScale = 1.0f;
    private float mOpacity = 1.0f;

    /** True while a physical pad is in use: draw nothing, but keep receiving
     *  touches (driven by the activity) so the next touch can bring it back. */
    private boolean mHiddenForPhysical = false;

    // Drag-to-move state (long-press a button to pick its whole cluster up).
    private static final long MOVE_HOLD_MS = 1200;
    private static final float MOVE_SLOP_DP = 8f;
    private Button mMoveCandidate;
    private int mMovePointerId = -1;
    private long mMoveDownTime;
    private float mMoveDownX, mMoveDownY;
    private float mDragLastX, mDragLastY;
    private Button mMovingBtn;

    // Floating left stick (analog axes 0/1).
    private static final float STICK_DEADZONE = 0.08f;
    private int mStickPointerId = -1;
    private float mStickAnchorX, mStickAnchorY;
    private float mStickHomeX, mStickHomeY;
    private float mStickR;
    private float mStickDeflX, mStickDeflY;
    private float mLastAxisX, mLastAxisY;

    // Two-finger pinch-to-resize.
    private int mPinchId1 = -1;
    private int mPinchId2 = -1;
    private float mPinchStartDist;
    private float mPinchStartScale;
    private final Map<Integer, float[]> mPointers = new HashMap<>();

    private boolean mGestureConsumed;

    public TouchGamepadView(Context context) {
        this(context, null);
    }

    public TouchGamepadView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mPaintFill = new Paint();
        mPaintFill.setAntiAlias(true);
        mPaintFill.setStyle(Paint.Style.FILL);

        mPaintBorder = new Paint();
        mPaintBorder.setAntiAlias(true);
        mPaintBorder.setStyle(Paint.Style.STROKE);
        mPaintBorder.setStrokeWidth(dp(2));

        mPaintBackground = new Paint();
        mPaintBackground.setAntiAlias(true);
        mPaintBackground.setStyle(Paint.Style.FILL);

        mPaintButton = new Paint();
        mPaintButton.setAntiAlias(true);

        mPaintText = new Paint();
        mPaintText.setColor(LOD_TEXT);
        mPaintText.setAntiAlias(true);
        mPaintText.setTextAlign(Paint.Align.CENTER);
        mPaintText.setFakeBoldText(true);

        mPaintStick = new Paint();
        mPaintStick.setAntiAlias(true);

        try {
            mFont = LodFont.load(context);
        } catch (Throwable ignored) {
            mFont = null;
        }

        SharedPreferences p = prefs();
        mScale = clampScale(p.getFloat("scale", 1.0f));
        mOpacity = clampOpacity(p.getFloat("opacity", 1.0f));
    }

    // ------------------------------------------------------------------
    // Public API used by SCGameActivity / the launcher menu.
    // ------------------------------------------------------------------

    public void setOpacity(float opacity) {
        mOpacity = clampOpacity(opacity);
        prefs().edit().putFloat("opacity", mOpacity).apply();
        invalidate();
    }

    public float getOpacity() {
        return mOpacity;
    }

    /** Called when a physical controller reports input: fade the overlay out. */
    public void hideForPhysical() {
        post(() -> {
            mHiddenForPhysical = true;
            invalidate();
        });
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWidth = w;
        mHeight = h;
        layoutButtons();
    }

    private void layoutButtons() {
        mButtons.clear();
        float cxLeft = mWidth * 0.25f;
        float cxRight = mWidth * 0.75f;
        float cyMid = mHeight * 0.5f;
        mMargin = Math.min(mWidth, mHeight) * 0.02f;
        mButtonSize = Math.min(mWidth, mHeight) * 0.095f * mScale;
        mDPadSize = mButtonSize * 2.3f;

        // D-Pad (left half, vertically centered; true cross, no center key).
        float dpadCenterX = cxLeft;
        float dpadCenterY = cyMid;
        mButtons.add(new Button("UP", dpadCenterX, dpadCenterY - mDPadSize / 2, mDPadSize / 1.5f, mDPadSize / 1.5f, 19, 11, -1, "dpad"));
        mButtons.add(new Button("DOWN", dpadCenterX, dpadCenterY + mDPadSize / 2, mDPadSize / 1.5f, mDPadSize / 1.5f, 20, 12, -1, "dpad"));
        mButtons.add(new Button("LEFT", dpadCenterX - mDPadSize / 2, dpadCenterY, mDPadSize / 1.5f, mDPadSize / 1.5f, 21, 13, -1, "dpad"));
        mButtons.add(new Button("RIGHT", dpadCenterX + mDPadSize / 2, dpadCenterY, mDPadSize / 1.5f, mDPadSize / 1.5f, 22, 14, -1, "dpad"));

        // Floating stick rests in the left third; touched anywhere there.
        mStickHomeX = mWidth * 0.11f;
        mStickHomeY = mHeight * 0.72f;
        mStickR = Math.min(mWidth, mHeight) * 0.095f * mScale;

        // Shape buttons (right half, vertically centered). PlayStation layout:
        // △ top, □ left, ○ right, × bottom.
        float shapeX = cxRight;
        float shapeY = cyMid;
        mButtons.add(new Button("△", shapeX, shapeY - mButtonSize * 1.2f, mButtonSize, mButtonSize, 32, 3, -1, "shapes"));
        mButtons.add(new Button("×", shapeX, shapeY + mButtonSize * 1.2f, mButtonSize, mButtonSize, 51, 0, -1, "shapes"));
        // □ left / ○ right, but the SDL button codes are swapped relative to the
        // labels (□ must send circle's code and vice-versa) so the game action
        // matches the printed shape.
        mButtons.add(new Button("□", shapeX - mButtonSize * 1.2f, shapeY, mButtonSize, mButtonSize, 47, 2, -1, "shapes"));
        mButtons.add(new Button("○", shapeX + mButtonSize * 1.2f, shapeY, mButtonSize, mButtonSize, 30, 1, -1, "shapes"));

        // Start / Select (top center, one cluster).
        float topY = mMargin + mButtonSize * 0.6f;
        mButtons.add(new Button("START", mWidth / 2f + mButtonSize * 1.1f, topY, mButtonSize * 1.8f, mButtonSize * 0.9f, 66, 6, -1, "system"));
        mButtons.add(new Button("SELECT", mWidth / 2f - mButtonSize * 1.1f, topY, mButtonSize * 1.8f, mButtonSize * 0.9f, 62, 4, -1, "system"));

        // Shoulder columns.
        mButtons.add(new Button("L1", mMargin + mButtonSize, topY, mButtonSize * 1.5f, mButtonSize * 0.9f, 50, 9, -1, "left"));
        mButtons.add(new Button("R1", mWidth - mMargin - mButtonSize, topY, mButtonSize * 1.5f, mButtonSize * 0.9f, 44, 10, -1, "right"));

        float trigY = topY + mButtonSize * 1.1f;
        mButtons.add(new Button("L2", mMargin + mButtonSize, trigY, mButtonSize * 1.5f, mButtonSize * 0.9f, 8, -1, 4, "left"));
        mButtons.add(new Button("R2", mWidth - mMargin - mButtonSize, trigY, mButtonSize * 1.5f, mButtonSize * 0.9f, 10, -1, 5, "right"));

        // No L3/R3: the stick clicks are redundant on a touch pad (the stick
        // itself is a touch area) and they crowded the shoulder column.

        applySavedLayout();
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mHiddenForPhysical) {
            return;
        }

        drawStick(canvas);

        for (Button btn : mButtons) {
            btn.draw(canvas);
        }
    }

    /** Floating left stick, drawn as a LoD ring with a gold knob. */
    private void drawStick(Canvas canvas) {
        float baseX = mStickPointerId != -1 ? mStickAnchorX : mStickHomeX;
        float baseY = mStickPointerId != -1 ? mStickAnchorY : mStickHomeY;

        mPaintFill.setStyle(Paint.Style.FILL);
        mPaintFill.setColor(LOD_FILL);
        mPaintFill.setAlpha(a(ALPHA_FILL));
        canvas.drawCircle(baseX, baseY, mStickR, mPaintFill);

        mPaintBorder.setStyle(Paint.Style.STROKE);
        mPaintBorder.setStrokeWidth(dp(2));
        mPaintBorder.setColor(LOD_BORDER);
        mPaintBorder.setAlpha(a(ALPHA_BORDER));
        canvas.drawCircle(baseX, baseY, mStickR, mPaintBorder);
        canvas.drawCircle(baseX, baseY, mStickR * 0.62f, mPaintBorder);

        float nx = baseX + mStickDeflX * mStickR;
        float ny = baseY + mStickDeflY * mStickR;
        mPaintFill.setColor(LOD_GOLD);
        mPaintFill.setAlpha(a(0x66));
        canvas.drawCircle(nx, ny, mStickR * 0.42f, mPaintFill);
        mPaintBorder.setColor(LOD_FILL);
        mPaintBorder.setStyle(Paint.Style.STROKE);
        mPaintBorder.setStrokeWidth(dp(2));
        mPaintBorder.setAlpha(a(ALPHA_BORDER));
        canvas.drawCircle(nx, ny, mStickR * 0.42f, mPaintBorder);
    }

    /** Apply the user opacity to a colour's alpha channel. */
    private int withOpacity(int color) {
        int alpha = Math.round(Color.alpha(color) * mOpacity);
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    /** Scale a base alpha by the configured opacity. */
    private int a(int base) {
        return Math.max(0, Math.min(255, Math.round(base * mOpacity)));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------
    // Touch handling. Returns true only when a control/gesture was consumed;
    // false lets the activity forward the touch to the game as a mouse.
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return handleTouch(event);
    }

    public boolean handleTouch(MotionEvent event) {
        // A touch after a physical controller was used brings the overlay back.
        if (mHiddenForPhysical) {
            mHiddenForPhysical = false;
            invalidate();
        }

        final int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = event.getActionIndex();
                int id = event.getPointerId(idx);
                float x = event.getX(idx);
                float y = event.getY(idx);
                mPointers.put(id, new float[]{x, y});

                Button hit = findButton(x, y);
                if (hit != null) {
                    press(hit, id);
                    mMoveCandidate = hit;
                    mMovePointerId = id;
                    mMoveDownTime = System.currentTimeMillis();
                    mMoveDownX = x;
                    mMoveDownY = y;
                    mGestureConsumed = true;
                } else if (mStickPointerId == -1 && inStickZone(x, y)) {
                    mStickPointerId = id;
                    mStickAnchorX = x;
                    mStickAnchorY = y;
                    mGestureConsumed = true;
                }
                // Empty-area touches fall through to the game as the mouse
                // (no in-game layout reset any more; that lives in the launcher
                // menu as "Reset Controls").

                maybeStartPinch();
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    float[] pos = mPointers.get(event.getPointerId(i));
                    if (pos != null) {
                        pos[0] = event.getX(i);
                        pos[1] = event.getY(i);
                    }
                }
                updatePinch();
                handleTouchMove(event);
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int idx = event.getActionIndex();
                int id = event.getPointerId(idx);
                mPointers.remove(id);
                if (id == mStickPointerId) {
                    releaseStick();
                }
                if (id == mPinchId1 || id == mPinchId2) {
                    endPinch(true);
                }
                handleTouchUp(id);
                break;
            }
        }

        invalidate();
        if (!mGestureConsumed) {
            return false;
        }
        if (mActiveTouches.isEmpty() && mStickPointerId == -1
                && mMovingBtn == null && mPinchId1 == -1) {
            mGestureConsumed = false;
        }
        return true;
    }

    private void handleTouchMove(MotionEvent event) {
        // Long-press drag: pick the button's whole cluster up.
        if (mMovingBtn == null && mMoveCandidate != null) {
            float[] pos = mPointers.get(mMovePointerId);
            if (pos != null) {
                float dx = pos[0] - mMoveDownX;
                float dy = pos[1] - mMoveDownY;
                float slop = MOVE_SLOP_DP * getResources().getDisplayMetrics().density;
                float dist = (float) Math.hypot(dx, dy);
                if (dist >= slop) {
                    mMoveCandidate = null;
                    mMovePointerId = -1;
                } else if (System.currentTimeMillis() - mMoveDownTime > MOVE_HOLD_MS
                        && mMoveCandidate.isPressed()) {
                    release(mMoveCandidate);
                    mMovingBtn = mMoveCandidate;
                    mDragLastX = clampX(pos[0]);
                    mDragLastY = clampY(pos[1]);
                    mMoveCandidate = null;
                    android.widget.Toast.makeText(getContext(),
                            "Moving " + mMovingBtn.groupName() + " — drop to place",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        }
        if (mMovingBtn != null) {
            float[] pos = mPointers.get(mMovePointerId);
            if (pos != null) {
                float nx = clampX(pos[0]);
                float ny = clampY(pos[1]);
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
                return;
            }
        }

        // Stick.
        if (mStickPointerId != -1) {
            float[] pos = mPointers.get(mStickPointerId);
            if (pos != null) {
                updateStick(pos[0], pos[1]);
            }
        }

        // Per-touch button press/release.
        for (int i = 0; i < event.getPointerCount(); i++) {
            int pointerId = event.getPointerId(i);
            if (pointerId == mStickPointerId) continue;
            float x = event.getX(i);
            float y = event.getY(i);

            Button activeBtn = findActiveButton(pointerId);
            if (activeBtn != null) {
                if (!activeBtn.contains(x, y)) {
                    release(activeBtn);
                }
            } else {
                Button hit = findButton(x, y);
                if (hit != null && !hit.isPressed()) {
                    press(hit, pointerId);
                }
            }
        }
    }

    private void handleTouchUp(int pointerId) {
        if (mMovingBtn != null && pointerId == mMovePointerId) {
            saveLayout();
            mMovingBtn = null;
            mMoveCandidate = null;
            mMovePointerId = -1;
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

    // ------------------------------------------------------------------
    // Floating stick
    // ------------------------------------------------------------------

    private boolean inStickZone(float x, float y) {
        return x < mWidth * 0.38f && y > mHeight * 0.30f && y < mHeight * 0.95f;
    }

    private void updateStick(float x, float y) {
        float dx = (x - mStickAnchorX) / mStickR;
        float dy = (y - mStickAnchorY) / mStickR;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1.0f) {
            dx /= len;
            dy /= len;
        }
        if (Math.abs(dx) < STICK_DEADZONE) dx = 0;
        if (Math.abs(dy) < STICK_DEADZONE) dy = 0;

        mStickDeflX = dx;
        mStickDeflY = dy;

        int ax = Math.round(dx * 32767f);
        int ay = Math.round(dy * 32767f);
        if (ax != mLastAxisX) {
            mLastAxisX = ax;
            SCGamepadNative.setAxis(0, ax);
        }
        if (ay != mLastAxisY) {
            mLastAxisY = ay;
            SCGamepadNative.setAxis(1, ay);
        }
    }

    private void releaseStick() {
        mStickPointerId = -1;
        mStickDeflX = 0;
        mStickDeflY = 0;
        if (mLastAxisX != 0) {
            mLastAxisX = 0;
            SCGamepadNative.setAxis(0, 0);
        }
        if (mLastAxisY != 0) {
            mLastAxisY = 0;
            SCGamepadNative.setAxis(1, 0);
        }
    }

    // ------------------------------------------------------------------
    // Pinch-to-resize
    // ------------------------------------------------------------------

    private void maybeStartPinch() {
        if (mPinchId1 != -1) return;
        // Need two free pointers (no button/stick owner).
        int first = -1;
        for (Integer id : mPointers.keySet()) {
            if (id == mStickPointerId) continue;
            if (findActiveButton(id) != null) continue;
            if (first == -1) {
                first = id;
            } else {
                mPinchId1 = first;
                mPinchId2 = id;
                mPinchStartDist = pinchDistance();
                mPinchStartScale = mScale;
                return;
            }
        }
    }

    private float pinchDistance() {
        float[] a = mPointers.get(mPinchId1);
        float[] b = mPointers.get(mPinchId2);
        if (a == null || b == null) return 1f;
        return Math.max(1f, (float) Math.hypot(a[0] - b[0], a[1] - b[1]));
    }

    private void updatePinch() {
        if (mPinchId1 == -1) return;
        float dist = pinchDistance();
        float newScale = clampScale(mPinchStartScale * dist / mPinchStartDist);
        if (Math.abs(newScale - mScale) > 0.01f) {
            mScale = newScale;
            layoutButtons();
            mGestureConsumed = true;
        }
    }

    private void endPinch(boolean persist) {
        mPinchId1 = -1;
        mPinchId2 = -1;
        if (persist) {
            prefs().edit().putFloat("scale", mScale).apply();
        }
    }

    // ------------------------------------------------------------------
    // Button press/release + emit
    // ------------------------------------------------------------------

    private Button findButton(float x, float y) {
        for (Button btn : mButtons) {
            if (btn.contains(x, y)) {
                return btn;
            }
        }
        return null;
    }

    private Button findActiveButton(int pointerId) {
        for (Button btn : mActiveTouches) {
            if (btn.pointerId == pointerId) {
                return btn;
            }
        }
        return null;
    }

    private void press(Button btn, int pointerId) {
        btn.setPressed(true, pointerId);
        mActiveTouches.add(btn);
        // Gamepad FIRST: attaching the virtual pad happens here, and isAttached()
        // below then reflects whether the pad actually took the input.
        sendPadButton(btn.padButton, true);
        sendPadAxis(btn.padAxis, true);
        sendKeyEventIfNoPad(btn.keyCode, true);
    }

    private void release(Button btn) {
        sendKeyEventIfNoPad(btn.keyCode, false);
        sendPadButton(btn.padButton, false);
        sendPadAxis(btn.padAxis, false);
        btn.setPressed(false, -1);
        mActiveTouches.remove(btn);
    }

    /**
     * Keyboard mirror of a pad button, used ONLY while the virtual gamepad is
     * unavailable (attach failed, or a controller-less build). It used to be
     * sent unconditionally, which was invisible while SDLActivity.onNativeKeyDown
     * was being handed pre-converted SDL keycodes (the game resolved nothing and
     * dropped them). Now that the keycodes are correct the game gets every press
     * TWICE -- once as a gamepad button and once as a key -- and menus move two
     * steps per press.
     */
    private void sendKeyEventIfNoPad(int keyCode, boolean down) {
        if (SCGamepadNative.isAttached()) return;
        sendKeyEvent(keyCode, down);
    }

    private void sendKeyEvent(int keyCode, boolean down) {
        // SDLActivity.onNativeKeyDown/Up take an ANDROID keycode (that is what
        // SDL's own Android layer feeds them) and map it to an SDL key
        // internally. Handing them a pre-converted SDL keycode made the game
        // receive an unusable keycode and drop the event ("Unknown key" in the
        // game log), so the keyboard mirror of the pad was dead.
        //
        // NOTE: this used to also call JVMRunner.queueKeyEvent(), which fed a
        // queue nothing ever polls and logged a line to the log file on every
        // single press. Removed.
        try {
            if (down) git.mojo.sdl.SDLActivity.onNativeKeyDown(keyCode);
            else git.mojo.sdl.SDLActivity.onNativeKeyUp(keyCode);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            Log.w(TAG, "SDL native not ready, key dropped: " + keyCode);
        }
    }

    private void sendPadButton(int padButton, boolean down) {
        if (padButton < 0) return;
        SCGamepadNative.setButton(padButton, down);
    }

    private void sendPadAxis(int padAxis, boolean down) {
        if (padAxis < 0) return;
        SCGamepadNative.setAxis(padAxis, down ? 32767 : 0);
    }

    private float clampX(float x) {
        return Math.max(mButtonSize / 2f, Math.min(mWidth - mButtonSize / 2f, x));
    }

    private float clampY(float y) {
        return Math.max(mButtonSize / 2f, Math.min(mHeight - mButtonSize / 2f, y));
    }

    private static float clampScale(float s) {
        return Math.max(0.6f, Math.min(1.8f, s));
    }

    private static float clampOpacity(float o) {
        return Math.max(0.15f, Math.min(1.0f, o));
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void saveLayout() {
        SharedPreferences.Editor ed = prefs().edit();
        for (Button btn : mButtons) {
            ed.putFloat(btn.label + "_x", btn.cx / mWidth);
            ed.putFloat(btn.label + "_y", btn.cy / mHeight);
        }
        ed.putFloat("scale", mScale);
        ed.apply();
    }

    private void applySavedLayout() {
        SharedPreferences p = prefs();
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
        mScale = 1.0f;
        mOpacity = 1.0f;
        layoutButtons();
        invalidate();
        android.widget.Toast.makeText(getContext(), "Controls reset to default",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * Android keycode -> SDL3 keycode. Arrows/space/enter/digits use SDL's
     * special/ASCII values; letters map A-Z (29-54) to a-z (97-122).
     *
     * @deprecated only kept for reference: {@code SDLActivity.onNativeKeyDown}
     * takes an ANDROID keycode and maps it to an SDL key itself, so callers
     * must NOT pre-convert. Converting here handed the game a keycode it
     * could not resolve and it dropped every key event.
     */
    @Deprecated
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
        final int padButton;
        final int padAxis;
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
            return x >= cx - width / 2 && x <= cx + width / 2
                    && y >= cy - height / 2 && y <= cy + height / 2;
        }

        boolean isPressed() {
            return pressed;
        }

        void setPressed(boolean pressed, int pointerId) {
            this.pressed = pressed;
            this.pointerId = pointerId;
        }

        void draw(Canvas canvas) {
            if ("dpad".equals(group)) {
                drawArrow(canvas);
                return;
            }
            if ("shapes".equals(group)) {
                drawShape(canvas);
                return;
            }

            float left = cx - width / 2;
            float top = cy - height / 2;
            float right = cx + width / 2;
            float bottom = cy + height / 2;
            float radius = Math.min(width, height) * 0.28f;
            RectF r = new RectF(left, top, right, bottom);

            mPaintFill.setStyle(Paint.Style.FILL);
            mPaintFill.setColor(pressed ? LOD_FILL_PRESS : LOD_FILL);
            mPaintFill.setAlpha(a(pressed ? ALPHA_FILL_PRESS : ALPHA_FILL));
            canvas.drawRoundRect(r, radius, radius, mPaintFill);

            mPaintBorder.setStyle(Paint.Style.STROKE);
            mPaintBorder.setStrokeWidth(dp(2));
            mPaintBorder.setColor(pressed ? LOD_BORDER_PRESS : LOD_BORDER);
            mPaintBorder.setAlpha(a(ALPHA_BORDER));
            canvas.drawRoundRect(r, radius, radius, mPaintBorder);

            drawLabel(canvas, Math.min(height * 0.55f, mButtonSize * 0.5f));
        }

        private void drawLabel(Canvas canvas, float targetH) {
            if (mFont != null) {
                mFont.draw(canvas, label, cx, cy, targetH, withOpacity(LOD_TEXT));
                return;
            }
            Paint txt = new Paint(mPaintText);
            txt.setAlpha(a(ALPHA_GLYPH));
            txt.setTextSize(Math.max(12f, targetH * 0.9f));
            canvas.drawText(label, cx, cy + txt.getTextSize() / 3f, txt);
        }

        private void drawArrow(Canvas canvas) {
            float r = Math.min(width, height) * 0.42f;
            float dx = 0;
            float dy = 0;
            switch (label) {
                case "UP": dy = -1; break;
                case "DOWN": dy = 1; break;
                case "LEFT": dx = -1; break;
                case "RIGHT": dx = 1; break;
                default: break;
            }
            Path p = new Path();
            if (dy != 0) {
                p.moveTo(cx, cy + dy * r);
                p.lineTo(cx - r * 0.9f, cy - dy * r * 0.55f);
                p.lineTo(cx + r * 0.9f, cy - dy * r * 0.55f);
            } else {
                p.moveTo(cx + dx * r, cy);
                p.lineTo(cx - dx * r * 0.55f, cy - r * 0.9f);
                p.lineTo(cx - dx * r * 0.55f, cy + r * 0.9f);
            }
            p.close();
            mPaintFill.setStyle(Paint.Style.FILL);
            mPaintFill.setColor(pressed ? LOD_GOLD : LOD_BORDER);
            mPaintFill.setAlpha(a(ALPHA_GLYPH));
            canvas.drawPath(p, mPaintFill);
        }

        private void drawShape(Canvas canvas) {
            float r = Math.min(width, height) * 0.34f;
            int col;
            switch (label) {
                case "△": col = LOD_TRI; break;
                case "○": col = LOD_CIRC; break;
                case "×": col = LOD_CROSS; break;
                case "□": col = LOD_SQUARE; break;
                default: col = LOD_GOLD;
            }

            RectF panel = new RectF(cx - r * 1.55f, cy - r * 1.55f, cx + r * 1.55f, cy + r * 1.55f);
            mPaintFill.setStyle(Paint.Style.FILL);
            mPaintFill.setColor(pressed ? LOD_FILL_PRESS : LOD_FILL);
            mPaintFill.setAlpha(a(pressed ? ALPHA_FILL_PRESS : ALPHA_FILL));
            canvas.drawRoundRect(panel, r * 0.9f, r * 0.9f, mPaintFill);

            mPaintBorder.setStyle(Paint.Style.STROKE);
            mPaintBorder.setStrokeWidth(dp(2));
            mPaintBorder.setColor(pressed ? LOD_BORDER_PRESS : LOD_BORDER);
            mPaintBorder.setAlpha(a(ALPHA_BORDER));
            canvas.drawRoundRect(panel, r * 0.9f, r * 0.9f, mPaintBorder);

            mPaintBorder.setColor(col);
            mPaintBorder.setAlpha(a(ALPHA_GLYPH));
            mPaintBorder.setStrokeWidth(Math.max(dp(2), r * 0.26f));
            mPaintBorder.setStyle(Paint.Style.STROKE);
            switch (label) {
                case "○":
                    canvas.drawCircle(cx, cy, r * 0.85f, mPaintBorder);
                    break;
                case "×":
                    canvas.drawLine(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, mPaintBorder);
                    canvas.drawLine(cx - r * 0.7f, cy + r * 0.7f, cx + r * 0.7f, cy - r * 0.7f, mPaintBorder);
                    break;
                case "□":
                    canvas.drawRect(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, mPaintBorder);
                    break;
                default: {
                    Path p = new Path();
                    p.moveTo(cx, cy - r * 0.9f);
                    p.lineTo(cx + r * 0.9f, cy + r * 0.7f);
                    p.lineTo(cx - r * 0.9f, cy + r * 0.7f);
                    p.close();
                    canvas.drawPath(p, mPaintBorder);
                }
            }
        }
    }
}
