package com.solo.purecast.ui.background

import java.util.Locale

/**
 * 💡 PureCast Apple Music 原生 GLES2 着色器源码集 (MacShaders)
 */
internal object MacShaders {
    val VERTEX = """
        attribute vec2 a_position;
        attribute vec2 a_texCoord;
        varying highp vec2 v_uv;
        void main() {
            gl_Position = vec4(a_position, 0.0, 1.0);
            v_uv = a_texCoord;
        }
    """.trimIndent()

    val ROTATION_VERTEX = """
        attribute vec2 a_position;
        attribute vec2 a_texCoord;
        uniform float u_angle;
        uniform vec2 u_offset, u_aspect;
        varying highp vec2 v_uv;
        vec2 rotateCW(vec2 p, float t) {
            float c = cos(t);
            float s = sin(t);
            return vec2(c * p.x + s * p.y, -s * p.x + c * p.y);
        }
        void main() {
            float t = u_angle;
            vec2 p = rotateCW(rotateCW(a_position, t) + u_offset, t) * u_aspect;
            gl_Position = vec4(p, 0.0, 1.0);
            v_uv = a_texCoord;
        }
    """.trimIndent()

    val ROTATION = """
        precision mediump float;
        uniform sampler2D u_texture, u_old;
        uniform float u_mix;
        varying highp vec2 v_uv;
        void main() {
            vec3 c = mix(texture2D(u_old, v_uv).rgb, texture2D(u_texture, v_uv).rgb, u_mix);
            float g = dot(c, vec3(0.3, 0.59, 0.11));
            gl_FragColor = vec4(vec3(g) + (c - g) * 1.3, 1.0);
        }
    """.trimIndent()

    val MESH_VERTEX = """
        attribute vec2 a_texCoord, a_from, a_to;
        uniform float u_warpMix;
        varying highp vec2 v_uv;
        void main() {
            float k = u_warpMix;
            gl_Position = vec4(mix(a_from, a_to, k), 0.0, 1.0);
            v_uv = (a_texCoord - 0.5) * 0.8 + 0.5;
        }
    """.trimIndent()

    val MESH = """
        precision mediump float;
        uniform sampler2D u_texture;
        uniform float u_dark, u_luma;
        varying highp vec2 v_uv;
        void main() {
            vec3 c = texture2D(u_texture, vec2(v_uv.x, 1.0 - v_uv.y)).rgb;
            float g = dot(c, vec3(0.3, 0.59, 0.11));
            c = min(vec3(0.9951171875), vec3(g) + (c - g) * 2.0);
            if (u_dark > 0.5) {
                c = mix(c, vec3(0.0), 0.25) - 0.02;
            } else {
                c = min(vec3(1.0), mix(c, vec3(1.0), 0.25) + (u_luma < 0.3 ? 0.38 : 0.08) * 0.05);
            }
            // 💡 将原 MainTvScreen 全屏 22% 黑色保护遮罩 (alpha=0.22 -> *0.78) 直接折入着色器，零画质损失省掉一整层 1080p 混合过绘
            gl_FragColor = vec4(clamp(c, 0.07, 0.97) * 0.78, 1.0);
        }
    """.trimIndent()

    fun gaussian(sigma: Double): String {
        val taps = MacScene.gaussian(sigma)
        fun num(v: Double) = String.format(Locale.US, "%.10f", v)
        return buildString {
            // 💡 片元颜色与高斯权重采用 mediump (Mali-G31 FP16 双倍算力)，UV 与步长保持 highp 防止边界精度偏移
            append("precision mediump float; uniform sampler2D u_texture; uniform highp vec2 u_axis, u_size; varying highp vec2 v_uv;")
            append("vec4 zeroSample(highp vec2 uv) { vec2 f = clamp(min(uv, 1.0 - uv) * u_size + 0.5, 0.0, 1.0); return texture2D(u_texture, uv) * f.x * f.y; }")
            append("void main() { highp vec2 uv = vec2(v_uv.x, 1.0 - v_uv.y); vec4 c = zeroSample(uv) * ${num(taps[0].second)};")
            for ((offset, weight) in taps.drop(1)) {
                append("c += (zeroSample(uv + u_axis * ${num(offset)}) + zeroSample(uv - u_axis * ${num(offset)})) * ${num(weight)};")
            }
            append("gl_FragColor = c; }")
        }
    }
}
