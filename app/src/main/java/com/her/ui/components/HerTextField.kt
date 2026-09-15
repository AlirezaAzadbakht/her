package com.her.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.ui.theme.Her
import com.her.ui.theme.HerMotion
import com.her.ui.theme.rememberShake
import com.her.ui.theme.shake

/**
 * A quiet labelled field: the label warms on focus and a gradient underline grows out
 * from the centre. [shakeTrigger] shakes the field each time it increases.
 */
@Composable
fun HerTextField(
    label: String,
    value: String,
    secret: Boolean = false,
    keyboard: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    shakeTrigger: Int = 0,
    onChange: (String) -> Unit,
) {
    val colors = Her.colors
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(HerMotion.Emphasized, easing = HerMotion.EmphasizedDecelerate),
        label = "field-focus",
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            error -> scheme.error
            focused -> scheme.primary
            else -> scheme.onSurfaceVariant
        },
        animationSpec = tween(HerMotion.Standard),
        label = "field-label",
    )
    val shake = rememberShake(shakeTrigger)
    Column(modifier.fillMaxWidth().padding(bottom = 18.dp).shake(shake)) {
        Text(label, color = labelColor, fontSize = 12.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onBackground),
            cursorBrush = SolidColor(scheme.primary),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            singleLine = true,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .drawBehind {
                    val y = size.height / 2
                    drawLine(
                        color = if (error) scheme.error.copy(alpha = 0.6f) else scheme.outline,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                    if (focus > 0f) {
                        val half = size.width / 2 * focus
                        drawLine(
                            brush = Brush.horizontalGradient(colors.glow),
                            start = Offset(size.width / 2 - half, y),
                            end = Offset(size.width / 2 + half, y),
                            strokeWidth = size.height,
                        )
                    }
                },
        )
    }
}
