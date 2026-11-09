package com.mrboomdev.kotnya.ui

import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * The sora-editor widget (the editor AndroidIDE is built on), wrapped for Compose.
 *
 * The view is keyed by [fileKey] so every open tab gets its own editor state: cursor, selection and
 * undo history never leak from one file into another.
 */
@Composable
fun CodeEditorPane(
    fileKey: String,
    text: String,
    fontSize: Float,
    onTextChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onChanged by rememberUpdatedState(onTextChanged)
    val latestText by rememberUpdatedState(text)

    val background = MaterialTheme.colorScheme.surface
    val foreground = MaterialTheme.colorScheme.onSurface
    val gutter = MaterialTheme.colorScheme.surfaceVariant
    val gutterText = MaterialTheme.colorScheme.onSurfaceVariant
    val currentLine = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val selection = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val keyword = MaterialTheme.colorScheme.primary
    val comment = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    val literal = MaterialTheme.colorScheme.tertiary
    val annotation = MaterialTheme.colorScheme.secondary
    val function = MaterialTheme.colorScheme.primary
    val type = MaterialTheme.colorScheme.secondary
    val tag = MaterialTheme.colorScheme.primary
    val attribute = MaterialTheme.colorScheme.secondary
    val attributeValue = MaterialTheme.colorScheme.tertiary

    val scheme = remember(
        background, foreground, gutter, gutterText, currentLine, selection,
        keyword, comment, literal, annotation, function, type, tag, attribute, attributeValue,
    ) {
        EditorColorScheme().apply {
            setColor(EditorColorScheme.WHOLE_BACKGROUND, background.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, gutter.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER, gutterText.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER_CURRENT, foreground.toArgb())
            setColor(EditorColorScheme.TEXT_NORMAL, foreground.toArgb())
            setColor(EditorColorScheme.CURRENT_LINE, currentLine.toArgb())
            setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, selection.toArgb())
            setColor(EditorColorScheme.SCROLL_BAR_THUMB, foreground.copy(alpha = 0.3f).toArgb())
            setColor(EditorColorScheme.KEYWORD, keyword.toArgb())
            setColor(EditorColorScheme.COMMENT, comment.toArgb())
            setColor(EditorColorScheme.LITERAL, literal.toArgb())
            setColor(EditorColorScheme.ANNOTATION, annotation.toArgb())
            setColor(EditorColorScheme.FUNCTION_NAME, function.toArgb())
            setColor(EditorColorScheme.IDENTIFIER_NAME, type.toArgb())
            setColor(EditorColorScheme.HTML_TAG, tag.toArgb())
            setColor(EditorColorScheme.ATTRIBUTE_NAME, attribute.toArgb())
            setColor(EditorColorScheme.ATTRIBUTE_VALUE, attributeValue.toArgb())
        }
    }

    key(fileKey) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val editor = CodeEditor(context)

                editor.typefaceText = Typeface.MONOSPACE
                editor.colorScheme = scheme
                editor.setTextSize(fontSize)

                // Horizontal panning: word wrap would make the editor's horizontal range zero, and
                // sora's single-axis drag lock can swallow the x movement when the finger drifts
                // slightly vertically at the start. Long-press drag-selection also takes the
                // gesture at 500 ms, which is what made a slow horizontal pan stop mid-swipe.
                editor.isWordwrap = false
                editor.setScrollBarEnabled(true)
                editor.props.singleDirectionDragging = false
                editor.props.dragSelectAfterLongPress = false

                // Compose's drawer and gesture handling must not steal a drag that starts on the
                // editor; the editor consumes it itself.
                editor.setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN ->
                            view.parent?.requestDisallowInterceptTouchEvent(true)

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }

                editor.setEditorLanguage(CodeSyntaxLanguage(SourceKind.fromPath(fileKey)))
                editor.setText(text)

                editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    val current = editor.text.toString()
                    if (current != latestText) onChanged(current)
                }

                editor
            },
            update = { editor ->
                if (editor.colorScheme !== scheme) editor.colorScheme = scheme
                editor.setTextSize(fontSize)

                if (editor.text.toString() != text) {
                    editor.setText(text)
                }
            },
            onRelease = { it.release() },
        )
    }
}
