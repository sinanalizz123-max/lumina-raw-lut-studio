package com.lumina.studio.core.render.gpu

/**
 * M15 shader sources (pure String builders, JVM-tested). One static GLES3
 * program covers the whole GPU color slice; every stage is gated by a
 * uniform so no per-render recompile happens. Stage order matches
 * PreviewRenderer exactly: LUT -> adjusts -> global sat -> per-color HSL ->
 * curves -> point -> grading -> vignette -> micro.
 *
 * GLES calls are isolated in [GlesBackend] (untestable on JVM — honest
 * coverage note: CI compiles that class only; shader MATH is validated
 * on-device via parity screenshots, timing and context-loss recovery).
 */
object GpuShaders {

    const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;
out vec2 vTexCoord;
void main() {
    vTexCoord = aUV;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    // Samplers bound by GlesBackend to texture units 0..3 in this order.
    const val SAMPLER_SRC = "uSrc"
    const val SAMPLER_LUT_TILES = "uLutTiles"
    const val SAMPLER_LUT_1D = "uLut1D"
    const val SAMPLER_CURVES = "uCurvesTex"

    val REQUIRED_SAMPLERS: List<String> =
        listOf(SAMPLER_SRC, SAMPLER_LUT_TILES, SAMPLER_LUT_1D, SAMPLER_CURVES)

    val REQUIRED_UNIFORMS: List<String> = listOf(
        "uUseLut",
        "uLutIs3D",
        "uLutSize",
        "uLutDomainMin",
        "uLutDomainMax",
        "uLutIntensity",
        "uUseAdjust",
        "uAdjustMat",
        "uAdjustOffset",
        "uUseGlobalSat",
        "uSatMat",
        "uSatOffset",
        "uUseHsl",
        "uHslHasLum",
        "uHslAdjust",
        "uUseCurves",
        "uUsePoint",
        "uPoint",
        "uUseGrade",
        "uGradeBlend",
        "uGradeBalance",
        "uLiftShadow",
        "uLiftMid",
        "uLiftHigh",
        "uLiftGlobal",
        "uVignette",
        "uImageSize",
        "uUseMicro",
        "uMicroMat",
        "uMicroOffset"
    )

    fun fragmentShader(): String = FRAGMENT_SHADER

    fun isBalanced(text: String, open: Char, close: Char): Boolean {
        var depth = 0
        for (c in text) {
            if (c == open) depth++
            if (c == close) {
                depth--
                if (depth < 0) return false
            }
        }
        return depth == 0
    }

    private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform sampler2D uLutTiles;
uniform sampler2D uLut1D;
uniform sampler2D uCurvesTex;
uniform float uUseLut;
uniform float uLutIs3D;
uniform float uLutSize;
uniform vec3 uLutDomainMin;
uniform vec3 uLutDomainMax;
uniform float uLutIntensity;
uniform float uUseAdjust;
uniform mat4 uAdjustMat;
uniform vec3 uAdjustOffset;
uniform float uUseGlobalSat;
uniform mat4 uSatMat;
uniform vec3 uSatOffset;
uniform float uUseHsl;
uniform float uHslHasLum;
uniform vec4 uHslAdjust[8];
uniform float uUseCurves;
uniform float uUsePoint;
uniform vec4 uPoint;
uniform float uUseGrade;
uniform float uGradeBlend;
uniform float uGradeBalance;
uniform vec3 uLiftShadow;
uniform vec3 uLiftMid;
uniform vec3 uLiftHigh;
uniform vec3 uLiftGlobal;
uniform float uVignette;
uniform vec2 uImageSize;
uniform float uUseMicro;
uniform mat4 uMicroMat;
uniform vec3 uMicroOffset;
vec3 rgb2hsl(vec3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float l = (mx + mn) * 0.5;
    if (mx == mn) {
        return vec3(0.0, 0.0, clamp(l, 0.0, 1.0));
    }
    float d = mx - mn;
    float s = l > 0.5 ? d / (2.0 - mx - mn) : d / (mx + mn);
    float h = 0.0;
    if (mx == c.r) {
        h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
    } else if (mx == c.g) {
        h = (c.b - c.r) / d + 2.0;
    } else {
        h = (c.r - c.g) / d + 4.0;
    }
    h *= 60.0;
    if (h < 0.0) {
        h += 360.0;
    }
    return vec3(h, clamp(s, 0.0, 1.0), clamp(l, 0.0, 1.0));
}
float hueChan(float p, float q, float t) {
    float tt = t;
    if (tt < 0.0) {
        tt += 1.0;
    }
    if (tt > 1.0) {
        tt -= 1.0;
    }
    if (tt < 0.1666667) {
        return p + (q - p) * 6.0 * tt;
    }
    if (tt < 0.5) {
        return q;
    }
    if (tt < 0.6666667) {
        return p + (q - p) * (0.6666667 - tt) * 6.0;
    }
    return p;
}
vec3 hsl2rgb(vec3 hsl) {
    float h = mod(mod(hsl.x, 360.0) + 360.0, 360.0) / 360.0;
    float s = clamp(hsl.y, 0.0, 1.0);
    float l = clamp(hsl.z, 0.0, 1.0);
    if (s == 0.0) {
        return vec3(l, l, l);
    }
    float q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;
    float p = 2.0 * l - q;
    return vec3(
        hueChan(p, q, h + 0.3333333),
        hueChan(p, q, h),
        hueChan(p, q, h - 0.3333333)
    );
}
float hueDist(float a, float b) {
    float d = mod(abs(a - b), 360.0);
    if (d > 180.0) {
        d = 360.0 - d;
    }
    return d;
}
vec3 fetchLutTexel(float r, float g, float b, float size) {
    int xi = int(b) * int(size) + int(r);
    int yi = int(g);
    return texelFetch(uLutTiles, ivec2(xi, yi), 0).rgb;
}
vec3 lut3D(vec3 c, float size) {
    float maxC = size - 1.0;
    vec3 s = clamp(c, 0.0, 1.0) * maxC;
    vec3 s0 = floor(s);
    vec3 s1 = min(s0 + 1.0, maxC);
    vec3 f = s - s0;
    vec3 c000 = fetchLutTexel(s0.x, s0.y, s0.z, size);
    vec3 c100 = fetchLutTexel(s1.x, s0.y, s0.z, size);
    vec3 c010 = fetchLutTexel(s0.x, s1.y, s0.z, size);
    vec3 c110 = fetchLutTexel(s1.x, s1.y, s0.z, size);
    vec3 c001 = fetchLutTexel(s0.x, s0.y, s1.z, size);
    vec3 c101 = fetchLutTexel(s1.x, s0.y, s1.z, size);
    vec3 c011 = fetchLutTexel(s0.x, s1.y, s1.z, size);
    vec3 c111 = fetchLutTexel(s1.x, s1.y, s1.z, size);
    vec3 c00 = mix(c000, c100, f.x);
    vec3 c10 = mix(c010, c110, f.x);
    vec3 c01 = mix(c001, c101, f.x);
    vec3 c11 = mix(c011, c111, f.x);
    vec3 c0 = mix(c00, c10, f.y);
    vec3 c1 = mix(c01, c11, f.y);
    return mix(c0, c1, f.z);
}
vec3 applyHsl(vec3 c) {
    vec3 hsl = rgb2hsl(c);
    float h = hsl.x;
    float s = hsl.y;
    float l = hsl.z;
    if (s > 0.001) {
        float hueShift = 0.0;
        float satFactor = 0.0;
        float lumDelta = 0.0;
        for (int k = 0; k < 8; k++) {
            vec4 adj = uHslAdjust[k];
            float d = hueDist(h, adj.w);
            float w = clamp(1.0 - d / 35.0, 0.0, 1.0);
            hueShift += w * adj.x * 0.3;
            satFactor += w * adj.y / 100.0;
            lumDelta += w * adj.z * 0.0025;
        }
        h = mod(h + hueShift, 360.0);
        if (h < 0.0) {
            h += 360.0;
        }
        s = clamp(s * (1.0 + satFactor), 0.0, 1.0);
        l = clamp(l + lumDelta, 0.0, 1.0);
        return hsl2rgb(vec3(h, s, l));
    }
    if (uHslHasLum > 0.5) {
        float lumDelta = 0.0;
        float add = 0.0;
        for (int k = 0; k < 8; k++) {
            vec4 adj = uHslAdjust[k];
            float d = hueDist(h, adj.w);
            float w = clamp(1.0 - d / 35.0, 0.0, 1.0);
            lumDelta += w * adj.z * 0.0025;
            if (adj.y > 0.0) {
                add += w * adj.y / 100.0 * 0.1;
            }
        }
        s = clamp(s + add, 0.0, 1.0);
        l = clamp(l + lumDelta, 0.0, 1.0);
        return hsl2rgb(vec3(h, s, l));
    }
    return c;
}
void main() {
    vec4 src = texture(uSrc, vTexCoord);
    vec3 c = src.rgb;
    if (uUseLut > 0.5) {
        vec3 span = max(uLutDomainMax - uLutDomainMin, vec3(0.000001));
        vec3 n = clamp((c - uLutDomainMin) / span, 0.0, 1.0);
        vec3 mapped;
        if (uLutIs3D > 0.5) {
            mapped = lut3D(n, uLutSize);
        } else {
            mapped = vec3(
                texture(uLut1D, vec2(clamp(n.r, 0.0, 1.0), 0.5)).r,
                texture(uLut1D, vec2(clamp(n.g, 0.0, 1.0), 0.5)).g,
                texture(uLut1D, vec2(clamp(n.b, 0.0, 1.0), 0.5)).b
            );
        }
        c = mix(c, mapped, clamp(uLutIntensity, 0.0, 1.0));
    }
    if (uUseAdjust > 0.5) {
        c = (uAdjustMat * vec4(c, 1.0)).rgb + uAdjustOffset;
    }
    if (uUseGlobalSat > 0.5) {
        c = (uSatMat * vec4(c, 1.0)).rgb + uSatOffset;
    }
    if (uUseHsl > 0.5) {
        c = applyHsl(c);
    }
    if (uUseCurves > 0.5) {
        vec3 idx = floor(clamp(c, 0.0, 1.0) * 255.0);
        vec3 m = vec3(
            texture(uCurvesTex, vec2((idx.r + 0.5) / 256.0, 0.5)).a,
            texture(uCurvesTex, vec2((idx.g + 0.5) / 256.0, 0.5)).a,
            texture(uCurvesTex, vec2((idx.b + 0.5) / 256.0, 0.5)).a
        );
        c = vec3(
            texture(uCurvesTex, vec2((floor(clamp(m.r, 0.0, 1.0) * 255.0) + 0.5) / 256.0, 0.5)).r,
            texture(uCurvesTex, vec2((floor(clamp(m.g, 0.0, 1.0) * 255.0) + 0.5) / 256.0, 0.5)).g,
            texture(uCurvesTex, vec2((floor(clamp(m.b, 0.0, 1.0) * 255.0) + 0.5) / 256.0, 0.5)).b
        );
    }
    if (uUsePoint > 0.5) {
        vec3 hsl = rgb2hsl(c);
        float range = clamp(uPoint.y, 10.0, 180.0);
        float d = hueDist(hsl.x, uPoint.x);
        float w = 0.0;
        if (d < range) {
            w = 1.0 - smoothstep(range * 0.4, range, d);
        }
        if (w > 0.0) {
            float s = clamp(hsl.y * (1.0 + w * uPoint.z / 100.0), 0.0, 1.0);
            float l = clamp(hsl.z + w * uPoint.w * 0.0025, 0.0, 1.0);
            c = hsl2rgb(vec3(hsl.x, s, l));
        }
    }
    if (uUseGrade > 0.5) {
        float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
        float shift = clamp(uGradeBalance, -100.0, 100.0) / 100.0 * 0.2;
        float s = 1.0 - smoothstep(0.25 + shift, 0.6 + shift, luma);
        float hh = smoothstep(0.45 + shift, 0.8 + shift, luma);
        float m = max(1.0 - s - hh, 0.0);
        float sum = s + m + hh;
        vec3 wgt = sum > 0.0 ? vec3(s, m, hh) / sum : vec3(1.0, 0.0, 0.0);
        vec3 lift = wgt.x * uLiftShadow + wgt.y * uLiftMid + wgt.z * uLiftHigh + uLiftGlobal;
        c = clamp(c + lift * uGradeBlend, 0.0, 1.0);
    }
    if (uVignette != 0.0) {
        vec2 p = (vTexCoord - 0.5) * uImageSize;
        vec2 center = uImageSize * 0.5;
        float halfDiag = max(length(center), 1.0);
        float d = clamp(length(p) / halfDiag, 0.0, 1.0);
        float gain = clamp(1.0 + (clamp(uVignette, -100.0, 100.0) / 100.0) * 0.8 * d * d, 0.2, 4.0);
        c *= gain;
    }
    if (uUseMicro > 0.5) {
        c = (uMicroMat * vec4(c, 1.0)).rgb + uMicroOffset;
    }
    fragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""
}
