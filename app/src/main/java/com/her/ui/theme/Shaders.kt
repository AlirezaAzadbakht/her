package com.her.ui.theme

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

/** AGSL sources. Callers gate on API 33 and fall back to gradients below it. */
internal object HerShaders {
    /** A breathing warm glow whose edge shimmers with fbm noise; energy swells reach and brightness. */
    const val HALO = """
uniform float2 uSize;
uniform float uTime;
uniform float uEnergy;
layout(color) uniform half4 uCore;
layout(color) uniform half4 uEdge;

float hash(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += amp * noise(p);
        p *= 2.03;
        amp *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 p = (fragCoord - uSize * 0.5) / (min(uSize.x, uSize.y) * 0.5);
    float r = length(p);
    float2 dir = p / max(r, 0.001);
    float n = fbm(dir * (1.6 + 0.4 * uEnergy) + float2(uTime * 0.11, -uTime * 0.07) + r * 1.3);
    float breath = 0.5 + 0.5 * sin(uTime * (0.8 + 1.2 * uEnergy));
    float reach = 0.52 + 0.2 * uEnergy + 0.06 * breath;
    float d = r + (n - 0.5) * (0.18 + 0.22 * uEnergy);
    float glow = 1.0 - smoothstep(0.0, reach, d);
    glow = glow * glow * (3.0 - 2.0 * glow);
    float core = 1.0 - smoothstep(0.0, reach * 0.55, d);
    float3 col = mix(float3(uEdge.rgb), float3(uCore.rgb), core);
    float alpha = glow * (0.16 + 0.34 * uEnergy) * float(uCore.a);
    return half4(col * alpha, alpha);
}
"""

    /** Static signed grain: lightens or darkens each 1.5px cell; the brush alpha sets strength. */
    const val GRAIN = """
half4 main(float2 fragCoord) {
    float2 cell = floor(fragCoord / 1.5);
    float3 p3 = fract(float3(cell.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    float n = fract((p3.x + p3.y) * p3.z);
    float v = n * 2.0 - 1.0;
    if (v > 0.0) {
        return half4(v, v, v, v);
    }
    return half4(0.0, 0.0, 0.0, -v);
}
"""
}

@RequiresApi(33)
internal class HaloShader {
    private val shader = RuntimeShader(HerShaders.HALO)
    val brush: ShaderBrush = ShaderBrush(shader)

    fun update(width: Float, height: Float, time: Float, energy: Float, core: Color, edge: Color) {
        shader.setFloatUniform("uSize", width, height)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uEnergy", energy)
        shader.setColorUniform("uCore", core.toArgb())
        shader.setColorUniform("uEdge", edge.toArgb())
    }
}

@RequiresApi(33)
internal object GrainShader {
    fun brush(): ShaderBrush = ShaderBrush(RuntimeShader(HerShaders.GRAIN))
}
