package com.severedchains.launcher.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

import com.severedchains.launcher.SCGameActivity;

import git.mojo.sdl.SDLActivity;
import git.mojo.sdl.SDLInputConnection;

/**
 * Hidden IME target for the game screen.
 *
 * <p>The soft keyboard types into this 2dp view; instead of keeping the text
 * locally, each committed change is forwarded to the game as an SDL
 * <b>text-input</b> event ({@link SDLInputConnection#nativeCommitText} ->
 * {@code SDL_SendKeyboardText} -> {@code SDL_EVENT_TEXT_INPUT}). That is the
 * path the game's text fields actually read
 * ({@code SdlPlatformManager}'s {@code SDL_EVENT_TEXT_INPUT} handler ->
 * {@code WindowEvents.onChar}); the previous implementation only sent scancode
 * key events, so typed characters never reached the game.
 *
 * <p>Backspace goes through SDL's unicode key path
 * ({@link SDLInputConnection#nativeGenerateScancodeForUnichar}) and Return
 * through the normal key path. The diff logic mirrors upstream SDL's
 * {@code SDLInputConnection}, so IME composition, prediction, autocorrect and
 * non-ASCII input all work.
 *
 * <p>Mojo's {@code SDLActivity.showTextInput} is a stub, so the game can never
 * raise the IME itself; {@link SCGameActivity} polls {@code SDL_TextInputActive}
 * and focuses this view instead.
 */
public class SDLTextInputView extends EditText {
    private static final String TAG = "SCKbd";

    public SDLTextInputView(Context context) {
        super(context);
    }

    public SDLTextInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SDLTextInputView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE;
        return new GameInputConnection(this);
    }

    /** Forwards IME edits to the game over SDL's text/keyboard event API. */
    private static final class GameInputConnection extends BaseInputConnection {
        /** Text already forwarded to the game (mirrors SDLInputConnection). */
        private String mForwarded = "";

        GameInputConnection(SDLTextInputView target) {
            super(target, true);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            if (!super.commitText(text, newCursorPosition)) return false;
            update();
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            if (!super.setComposingText(text, newCursorPosition)) return false;
            update();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            if (!super.finishComposingText()) return false;
            update();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            // Backspace produces no text change once the field is empty, so
            // send it explicitly (same workaround as upstream SDL).
            if (beforeLength > 0 && afterLength == 0) {
                for (int i = 0; i < beforeLength; i++) backspace();
                return true;
            }
            if (!super.deleteSurroundingText(beforeLength, afterLength)) return false;
            update();
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) sdlKey(13);
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) backspace();
                return true;
            }
            return super.sendKeyEvent(event);
        }

        /** Diffs the editable against what the game already has and sends the delta. */
        private void update() {
            final Editable content = getEditable();
            if (content == null) return;
            final String text = content.toString();

            int match = 0;
            final int compare = Math.min(text.length(), mForwarded.length());
            while (match < compare && text.charAt(match) == mForwarded.charAt(match)) match++;

            for (int i = match; i < mForwarded.length(); i++) backspace();
            if (match < text.length()) commit(text.substring(match));
            mForwarded = text;
        }

        private void commit(String text) {
            if (text.isEmpty() || !SCGameActivity.isJvmStarted()) return;
            try {
                SDLInputConnection.nativeCommitText(text, 1);
            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
                Log.w(TAG, "text dropped (SDL not ready): " + text);
            }
        }

        private void backspace() {
            if (!SCGameActivity.isJvmStarted()) return;
            try {
                SDLInputConnection.nativeGenerateScancodeForUnichar('\b');
            } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {
            }
        }

        private void sdlKey(int sdlKey) {
            if (!SCGameActivity.isJvmStarted()) return;
            try {
                SDLActivity.onNativeKeyDown(sdlKey);
                SDLActivity.onNativeKeyUp(sdlKey);
            } catch (UnsatisfiedLinkError | NoClassDefFoundError ignored) {
            }
        }
    }
}
