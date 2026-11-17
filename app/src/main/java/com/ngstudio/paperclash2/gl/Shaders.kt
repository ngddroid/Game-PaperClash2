package com.ngstudio.paperclash2.gl

/** GLSL ES 3.00 sources for every pass. World units are grid cells, z is up. */
object Shaders {
    private const val HEAD = "#version 300 es\nprecision highp float;\nprecision highp int;\n"

    /** Hash, value noise, anti-aliased steps and lines, and the shared surface patterns. */
    private const val LIB = """
float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float vnoise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i); float b = hash(i + vec2(1.0, 0.0)); float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float aaStep(float e, float x) { float w = fwidth(x) * 0.75 + 1e-4; return smoothstep(e - w, e + w, x); }
float lineAt(float coord, float spacing, float halfW) {
    float f = coord / spacing;
    float d = abs(fract(f + 0.5) - 0.5) * spacing;
    float aa = fwidth(coord) * 0.8 + 1e-4;
    return 1.0 - smoothstep(halfW - aa, halfW + aa, d);
}
float pat(int id, vec2 p) {
    if (id == 1) return aaStep(0.5, fract((p.x + p.y) * 0.32));
    if (id == 2) { vec2 q = fract(p * 0.45) - 0.5; return 1.0 - aaStep(0.22, length(q)); }
    if (id == 3) { vec2 q = floor(p * 0.4); return mod(q.x + q.y, 2.0); }
    if (id == 4) { float z = p.y * 0.5 + abs(fract(p.x * 0.25) - 0.5) * 1.5; return aaStep(0.5, fract(z)); }
    if (id == 5) { vec2 q = fract(p * 0.33) - 0.5; return 1.0 - aaStep(0.17, abs(q.x) + abs(q.y)); }
    if (id == 6) { float z = p.y * 0.45 + sin(p.x * 0.9) * 0.35; return aaStep(0.5, fract(z)); }
    if (id == 7) {
        vec2 c = floor(p * 0.6); float h = hash(c); vec2 q = fract(p * 0.6) - 0.5;
        return h > 0.55 ? 1.0 - aaStep(0.17, max(abs(q.x), abs(q.y) * 1.6)) : 0.0;
    }
    if (id == 8) return 0.5 + 0.5 * sin((p.x - p.y) * 0.45);
    if (id == 9) return fract((p.x + p.y) * 0.06);
    if (id == 10) return max(lineAt(p.x, 2.0, 0.08), lineAt(p.y, 2.0, 0.08));
    if (id == 11) { float n = vnoise(p * 0.35) + 0.5 * vnoise(p * 0.8); return aaStep(0.85, n); }
    return 0.0;
}
vec3 holo(float t) { return 0.62 + 0.38 * cos(6.2831 * (t + vec3(0.0, 0.33, 0.67))); }
vec3 applyPattern(vec3 base, vec3 accent, int id, float m, float strength) {
    if (id == 8) return base * (0.84 + 0.34 * m) + vec3(0.14) * pow(m, 8.0);
    if (id == 9) return mix(base, holo(m), 0.55);
    return mix(base, accent, m * strength);
}
"""

    // ------------------------------------------------------------------ world quads

    const val QUAD_VS = HEAD + """
layout(location = 0) in vec2 aPos;
uniform mat4 uVP;
uniform float uZ;
out vec2 vWorld;
void main() {
    vWorld = aPos;
    gl_Position = uVP * vec4(aPos, uZ, 1.0);
}
"""

    /** The 3D shape's surface mesh: position with height, and a normal per vertex. */
    const val SURFACE_VS = HEAD + """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
uniform mat4 uVP;
uniform float uZ;
out vec2 vWorld;
out vec3 vN;
out vec3 vPos;
void main() {
    vWorld = aPos.xy;
    vN = aNormal;
    vPos = vec3(aPos.xy, aPos.z + uZ);
    gl_Position = uVP * vec4(vPos, 1.0);
}
"""

    const val DESK_FS = HEAD + LIB + """
in vec2 vWorld;
uniform vec3 uA;
uniform vec3 uB;
uniform vec3 uInk;
uniform int uPattern;
uniform vec2 uCenter;
uniform float uRadius;
uniform sampler2D uGrain;
out vec4 frag;
void main() {
    vec2 p = vWorld;
    vec3 c = uA;
    if (uPattern == 0) {
        float minor = max(lineAt(p.x, 4.0, 0.06), lineAt(p.y, 4.0, 0.06));
        float major = max(lineAt(p.x, 20.0, 0.14), lineAt(p.y, 20.0, 0.14));
        float diag = lineAt(p.x - p.y, 40.0, 0.07);
        c = mix(uA, uInk, max(max(minor * 0.32, major * 0.75), diag * 0.25));
    } else if (uPattern == 1) {
        float n = vnoise(p * vec2(0.25, 1.6)) * 0.5 + vnoise(p * 0.9) * 0.5;
        c = mix(uB, uA, n);
        c = mix(c, uInk, smoothstep(0.82, 0.95, vnoise(p * vec2(0.12, 2.5))) * 0.35);
    } else if (uPattern == 2) {
        float sq = max(lineAt(p.x, 2.5, 0.05), lineAt(p.y, 2.5, 0.05));
        c = mix(uA, uInk, sq * 0.5);
        float margin = 1.0 - smoothstep(0.1, 0.2, abs(p.x - (uCenter.x - uRadius * 1.15)));
        c = mix(c, vec3(0.93, 0.45, 0.48), margin * 0.8);
    } else if (uPattern == 3) {
        float minor = max(lineAt(p.x, 4.0, 0.05), lineAt(p.y, 4.0, 0.05));
        float major = max(lineAt(p.x, 20.0, 0.11), lineAt(p.y, 20.0, 0.11));
        c = mix(uA, uInk, max(minor * 0.25, major * 0.55));
    } else if (uPattern == 4) {
        float n = vnoise(p * 1.7);
        float specks = smoothstep(0.72, 0.8, vnoise(p * 3.1 + 7.0));
        c = mix(uA, uB, n);
        c = mix(c, uInk, specks * 0.45);
    } else if (uPattern == 5) {
        float n = vnoise(p * 0.07) * 0.6 + vnoise(p * 0.18) * 0.4;
        c = mix(uA, uB, smoothstep(0.3, 0.7, n));
        c = mix(c, uInk, smoothstep(0.62, 0.8, vnoise(p * 0.05 + 3.0)) * 0.35);
    } else if (uPattern == 6) {
        float h = lineAt(p.x + p.y, 1.1, 0.05) * smoothstep(0.55, 0.8, vnoise(p * 0.12));
        c = mix(uA, uInk, h * 0.35);
    } else if (uPattern == 7) {
        float st = aaStep(0.5, fract((p.x - p.y) * 0.06));
        c = mix(uA, uB, st);
        vec2 q = fract(p * 0.125) - 0.5;
        c = mix(c, uInk, 1.0 - aaStep(0.12, length(q)));
    } else if (uPattern == 8) {
        vec2 cell = floor(p * 0.5);
        float h = hash(cell);
        vec2 q = fract(p * 0.5) - 0.5;
        float star = h > 0.93 ? (1.0 - aaStep(0.06 + 0.06 * fract(h * 13.0), length(q))) : 0.0;
        c = mix(uA, uB, vnoise(p * 0.05));
        c = mix(c, uInk, star);
        c += uInk * 0.05 * lineAt(p.x + p.y, 30.0, 0.05);
    } else {
        c = mix(uA, uB, max(lineAt(p.x + p.y, 6.0, 0.08), lineAt(p.x - p.y, 6.0, 0.08)) * 0.6);
        c = mix(c, uInk, pow(0.5 + 0.5 * sin((p.x - p.y) * 0.08), 6.0) * 0.4);
    }
    float g = texture(uGrain, p * 0.05).r;
    c *= 0.9 + 0.14 * g;
    float v = smoothstep(uRadius * 0.9, uRadius * 2.6, length(p - uCenter));
    c *= 1.0 - 0.38 * v;
    frag = vec4(c, 1.0);
}
"""

    /** Soft shadow under the arena slab (or any blurred mask) on the desk. */
    const val SHADOW_FS = HEAD + """
in vec2 vWorld;
uniform sampler2D uField;
uniform float uSize;
uniform vec2 uOffset;
uniform float uStrength;
out vec4 frag;
void main() {
    float f = texture(uField, (vWorld - uOffset) / uSize).r;
    frag = vec4(0.10, 0.08, 0.20, f * uStrength);
}
"""

    const val ARENA_FS = HEAD + """
in vec2 vWorld;
in vec3 vN;
in vec3 vPos;
uniform sampler2D uField;
uniform sampler2D uGrain;
uniform float uSize;
uniform vec3 uCard;
uniform vec3 uLight;
uniform vec3 uEye;
out vec4 frag;
void main() {
    float cov = texture(uField, vWorld / uSize).r;
    float w = fwidth(cov) * 0.8 + 0.002;
    float a = smoothstep(0.48 - w, 0.48 + w, cov);
    if (a < 0.003) discard;
    vec3 n = normalize(vN);
    float g = texture(uGrain, vWorld * 0.07).r;
    vec3 c = uCard * (0.92 + 0.1 * g);
    // dot-grid notebook paper: a soft ink dot every two cells so motion reads on open paper;
    // the dots fade out when zoomed far out (and toward the horizon) to avoid shimmer
    vec2 f = fract(vWorld * 0.5 + 0.5) - 0.5;
    float dd = length(f) * 2.0;
    float px = length(fwidth(vWorld));
    float aa = px * 0.75;
    float dotA = (1.0 - smoothstep(0.15 - aa, 0.15 + aa, dd)) * (1.0 - smoothstep(0.07, 0.16, px));
    c = mix(c, uCard * vec3(0.68, 0.72, 0.84), dotA * 0.6);
    // light: soft ambient + diffuse, so the dome and its rounded lip read as a solid object
    float ndl = max(dot(n, uLight), 0.0);
    vec3 v = normalize(uEye - vPos);
    float r1 = 1.0 - clamp(dot(n, v), 0.0, 1.0);
    float rim = r1 * r1 * r1;
    float h1 = max(dot(n, normalize(uLight + v)), 0.0);
    float h2 = h1 * h1; float h4 = h2 * h2; float h8 = h4 * h4;
    float sheen = h8 * h8 * h8;
    float slope = 1.0 - n.z;
    c = c * (0.6 + 0.46 * ndl) + vec3(0.07) * sheen + vec3(0.9, 0.95, 1.0) * rim * 0.08;
    // cool ambient occlusion on the steep lip
    c *= 1.0 - smoothstep(0.08, 0.45, slope) * 0.12;
    // die-cut edge: a thin bright paper rim right at the cut
    float edge = 1.0 - smoothstep(0.48, 0.6, cov);
    c = mix(c, vec3(1.0), edge * 0.22);
    frag = vec4(c, a);
}
"""

    /** Depth-only pre-pass of the shape's fully covered top (the desk under it is then skipped). */
    const val DEPTH_FS = HEAD + """
in vec2 vWorld;
uniform sampler2D uField;
uniform float uSize;
out vec4 frag;
void main() {
    float cov = texture(uField, vWorld / uSize).r;
    float w = fwidth(cov) * 0.8 + 0.002;
    if (cov < 0.48 + w * 1.5) discard;
    frag = vec4(0.0);
}
"""

    const val PLAIN_FS = HEAD + """
out vec4 frag;
void main() { frag = vec4(0.0); }
"""

    /**
     * Slab side. Paint drips are worked out per vertex (walls are cut every quarter cell): who owns
     * the land at the rim, and how far down its paint runs.
     */
    /** Hash and value noise only (safe in vertex shaders, which have no fwidth). */
    private const val NOISE = """
float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float vnoise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i); float b = hash(i + vec2(1.0, 0.0)); float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
"""

    const val WALL_VS = HEAD + NOISE + """
precision highp usampler2D;
layout(location = 0) in vec3 aPos;
// xy = outward normal, z = 0 at the rim .. 1 at the desk
layout(location = 1) in vec3 aNormal;
uniform mat4 uVP;
uniform sampler2D uTerr;
uniform usampler2D uIds;
uniform vec3 uTop[16];
uniform float uSize;
uniform float uRes;
uniform int uDrips;
out vec3 vN;
out float vT;
out vec4 vPaint;
out float vLen;
void main() {
    vN = vec3(aNormal.xy, 0.0);
    vT = aNormal.z;
    vPaint = vec4(0.0);
    vLen = 0.0;
    if (uDrips == 1) {
        vec2 p = aPos.xy - aNormal.xy * 0.8;
        vec2 uv = p / uSize;
        vec4 fl = textureLod(uTerr, uv, 0.0);
        float own = smoothstep(0.38, 0.55, fl.r);
        if (own > 0.0) {
            int sz = int(uSize * uRes);
            ivec2 tc = clamp(ivec2(uv * float(sz)), ivec2(0), ivec2(sz - 1));
            uvec4 ids = texelFetch(uIds, tc, 0);
            int ia = int(ids.r);
            int ib = int(ids.g);
            int id = fl.g >= 0.5 ? ia : ib;
            if (id == 0) id = ia == 0 ? ib : ia;
            if (id > 0) {
                float nz = vnoise(aPos.xy * 1.6) * 0.7 + vnoise(aPos.xy * 4.3 + 3.1) * 0.3;
                vLen = 0.14 + 0.62 * smoothstep(0.35, 0.9, nz);
                vPaint = vec4(uTop[id], own);
            }
        }
    }
    gl_Position = uVP * vec4(aPos, 1.0);
}
"""

    const val WALL_FS = HEAD + """
in vec3 vN;
in float vT;
in vec4 vPaint;
in float vLen;
uniform vec3 uSide;
uniform vec3 uLight;
out vec4 frag;
void main() {
    vec3 n = normalize(vN);
    float ndl = max(dot(n, uLight), 0.0);
    float shade = 0.55 + 0.52 * ndl;
    // paper layers: faint horizontal strata like stacked card
    vec3 c = uSide * shade * (0.96 + 0.04 * step(0.5, fract(vT * 7.0)));
    if (vPaint.a > 0.0) {
        float fw = fwidth(vT) * 1.2 + 0.004;
        float paint = (1.0 - smoothstep(vLen - fw, vLen + fw, vT)) * vPaint.a;
        vec3 pc = vPaint.rgb * (0.62 + 0.46 * ndl);
        // glossy bead along the drip's end
        float bead = smoothstep(vLen - 0.08, vLen - 0.01, vT) * paint;
        pc = mix(pc, min(pc * 1.25 + 0.08, vec3(1.0)), bead * 0.6);
        c = mix(c, pc, paint);
    }
    // bevel highlight at the rim and a darker foot by the desk
    c = mix(c, vec3(1.0), (1.0 - smoothstep(0.0, 0.05, vT)) * 0.32);
    c *= 1.0 - smoothstep(0.55, 1.0, vT) * 0.3;
    frag = vec4(c, 1.0);
}
"""

    // ------------------------------------------------------------------ territory

    /** Smoothed coverage + signed owner-pair field, baked into two render targets. */
    const val BAKE_VS = HEAD + """
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    const val BAKE_FS = HEAD + """
precision highp usampler2D;
uniform usampler2D uOwner;
uniform float uRes;
uniform int uSize;
layout(location = 0) out vec4 outField;
layout(location = 1) out uvec4 outIds;
float bs(float t) {
    t = abs(t);
    if (t < 1.0) return (4.0 - 6.0 * t * t + 3.0 * t * t * t) / 6.0;
    if (t < 2.0) { float u = 2.0 - t; return u * u * u / 6.0; }
    return 0.0;
}
void main() {
    vec2 p = gl_FragCoord.xy / uRes;
    ivec2 base = ivec2(floor(p - 0.5)) - ivec2(1);
    uint ids[4];
    float cov[4];
    ids[0] = 0u; ids[1] = 0u; ids[2] = 0u; ids[3] = 0u;
    cov[0] = 0.0; cov[1] = 0.0; cov[2] = 0.0; cov[3] = 0.0;
    int n = 0;
    float land = 0.0;
    for (int j = 0; j < 4; j++) {
        float wy = bs(p.y - (float(base.y + j) + 0.5));
        for (int i = 0; i < 4; i++) {
            ivec2 c = clamp(base + ivec2(i, j), ivec2(0), ivec2(uSize - 1));
            uint id = texelFetch(uOwner, c, 0).r;
            if (id == 0u) continue;
            float w = wy * bs(p.x - (float(base.x + i) + 0.5));
            land += w;
            int k = 0;
            for (; k < 4; k++) { if (k >= n || ids[k] == id) break; }
            if (k == n) {
                if (n >= 4) continue;
                ids[n] = id;
                n++;
            }
            cov[k] += w;
        }
    }
    int best = -1;
    int second = -1;
    for (int k = 0; k < 4; k++) {
        if (k >= n) break;
        if (best < 0 || cov[k] > cov[best]) { second = best; best = k; }
        else if (second < 0 || cov[k] > cov[second]) { second = k; }
    }
    uint a = 0u;
    uint b = 0u;
    float s = 1.0;
    if (best >= 0) {
        if (second < 0) { a = ids[best]; b = ids[best]; }
        else {
            uint ia = ids[best];
            uint ib = ids[second];
            float d = cov[best] - cov[second];
            if (ia < ib) { a = ia; b = ib; s = d; } else { a = ib; b = ia; s = -d; }
            s = clamp(s / max(land, 0.001) * 2.0, -1.0, 1.0);
        }
    }
    outField = vec4(clamp(land, 0.0, 1.0), 0.5 + 0.5 * s, 0.0, 1.0);
    outIds = uvec4(a, b, 0u, 1u);
}
"""

    const val TERRITORY_FS = HEAD + LIB + """
precision highp usampler2D;
in vec2 vWorld;
in vec3 vN;
in vec3 vPos;
uniform vec3 uLight;
uniform vec3 uEye;
uniform sampler2D uField;
uniform usampler2D uIds;
uniform usampler2D uStamp;
uniform sampler2D uGrain;
uniform float uSize;
uniform float uRes;
uniform int uMode;
uniform vec2 uOffset;
uniform float uSoft;
uniform vec3 uTop[16];
uniform vec3 uSide[16];
uniform vec3 uAcc[16];
uniform vec2 uPat[16];
uniform int uWaveCount;
uniform uint uWaveId[8];
uniform vec4 uWave[8];
out vec4 frag;

vec3 colorFor(int id, vec2 wp) {
    vec3 base = uMode == 1 ? uSide[id] : uTop[id];
    vec2 pp = uPat[id];
    int pid = int(pp.x + 0.5);
    if (pid == 0) return base;
    float m = pat(pid, wp);
    return applyPattern(base, uMode == 1 ? uAcc[id] * 0.75 : uAcc[id], pid, m, pp.y);
}

void main() {
    vec2 wp = vWorld - uOffset;
    vec2 uv = wp / uSize;
    vec4 f = texture(uField, uv);
    float cov = f.r;
    float w = fwidth(cov) * 0.8 + uSoft;
    float a = smoothstep(0.48 - w, 0.48 + w, cov);
    if (a < 0.004) discard;

    float flash = 0.0;
    float lift = 0.0;
    if (uWaveCount > 0) {
        ivec2 sc = ivec2(clamp(floor(wp), vec2(0.0), vec2(uSize - 1.0)));
        uint sid = texelFetch(uStamp, sc, 0).r;
        for (int k = 0; k < 8; k++) {
            if (k >= uWaveCount) break;
            if (sid == uWaveId[k]) {
                vec4 wv = uWave[k];
                float d = distance(wp, wv.xy);
                float front = wv.z * 44.0;
                a *= smoothstep(front + 1.2, front - 0.6, d);
                flash = smoothstep(front - 3.0, front, d) * (1.0 - smoothstep(front, front + 0.6, d)) * wv.w;
                lift = (1.0 - smoothstep(0.0, 0.6, wv.z)) * wv.w;
            }
        }
        if (a < 0.004) discard;
    }
    if (uMode == 0) { frag = vec4(0.12, 0.09, 0.24, 0.30 * a); return; }

    int size = int(uSize * uRes);
    ivec2 tc = clamp(ivec2(uv * float(size)), ivec2(0), ivec2(size - 1));
    uvec4 ids = texelFetch(uIds, tc, 0);
    int ia = int(ids.r);
    int ib = int(ids.g);
    if (ia == 0 && ib == 0) discard;
    if (ia == 0) ia = ib;
    if (ib == 0) ib = ia;
    float s = f.g * 2.0 - 1.0;
    float ws = fwidth(s) * 0.9 + 0.002;
    float t = smoothstep(-ws, ws, s);
    vec3 c = mix(colorFor(ib, wp), colorFor(ia, wp), t);
    vec3 nn = normalize(vN);
    float ndl = max(dot(nn, uLight), 0.0);
    if (uMode == 1) { frag = vec4(c * (0.72 + 0.3 * ndl), a); return; }

    float rim = 1.0 - smoothstep(0.48, 0.62, cov);
    c = mix(c, vec3(1.0), rim * 0.24);
    float seam = ia != ib ? (1.0 - smoothstep(0.0, 0.14, abs(s))) : 0.0;
    c *= 1.0 - seam * 0.28;
    float g = texture(uGrain, wp * 0.09).r;
    c *= 0.9 + 0.13 * g;
    // paint follows the dome's light and has a soft gloss
    vec3 v = normalize(uEye - vPos);
    float s1 = max(dot(nn, normalize(uLight + v)), 0.0);
    float s2 = s1 * s1; float s4 = s2 * s2; float s8 = s4 * s4; float s16 = s8 * s8;
    float spec = s16 * s16;
    c = c * (0.66 + 0.42 * ndl) + vec3(0.16) * spec;
    c = mix(c, vec3(1.0), flash * 0.6);
    c += lift * 0.1;
    frag = vec4(c, a);
}
"""

    // ------------------------------------------------------------------ ribbons

    const val RIBBON_VS = HEAD + """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUV;
uniform mat4 uVP;
uniform float uZ;
out vec2 vUV;
void main() {
    vUV = aUV;
    gl_Position = uVP * vec4(aPos.xy, aPos.z + uZ, 1.0);
}
"""

    const val RIBBON_FS = HEAD + LIB + """
in vec2 vUV;
uniform int uStyle;
uniform vec3 uCol;
uniform vec3 uCol2;
uniform float uTime;
uniform float uAlpha;
uniform int uShadow;
out vec4 frag;
void main() {
    float ac = abs(vUV.y);
    float aa = fwidth(vUV.y) * 1.2 + 0.001;
    float a = 1.0 - smoothstep(1.0 - aa, 1.0, ac);
    if (uShadow == 1) { frag = vec4(0.1, 0.08, 0.22, a * 0.26 * uAlpha); return; }
    if (uShadow == 2) { frag = vec4(uCol * 0.66, a * uAlpha); return; }
    float along = vUV.x;
    vec3 c = uCol;
    c = mix(c, c * 0.78, smoothstep(0.55, 1.0, ac));
    c = mix(c, min(c * 1.18 + 0.06, vec3(1.0)), (1.0 - smoothstep(0.0, 0.35, ac)) * 0.35);
    if (uStyle == 1) { float s = aaStep(0.5, fract((along + vUV.y * 0.4) * 0.9)); c = mix(c, uCol2, s * 0.85); }
    else if (uStyle == 2) { vec2 q = vec2(fract(along * 0.8) - 0.5, vUV.y * 0.45); c = mix(c, uCol2, 1.0 - aaStep(0.18, length(q))); }
    else if (uStyle == 3) { float dash = aaStep(0.45, fract(along * 1.2)); float mid = 1.0 - aaStep(0.12, ac); c = mix(c, uCol2, dash * mid); }
    else if (uStyle == 4) {
        float n = hash(floor(vec2(along * 6.0, vUV.y * 4.0)));
        c *= 0.85 + 0.25 * n;
        a *= 1.0 - smoothstep(0.72, 1.0, ac) * step(0.55, hash(floor(vec2(along * 9.0, 3.0))));
    }
    else if (uStyle == 5) { c = mix(c, 0.55 + 0.45 * cos(6.2831 * (along * 0.08 + vec3(0.0, 0.33, 0.67))), 0.75); }
    else if (uStyle == 6) { float sp = step(0.92, hash(floor(vec2(along * 5.0, vUV.y * 3.0)) + floor(uTime * 8.0))); c = mix(c, vec3(1.0), sp); }
    else if (uStyle == 7) { float core = 1.0 - smoothstep(0.0, 0.45, ac); c = mix(uCol * 0.6, vec3(1.0), core * 0.85) + uCol * 0.3; }
    else if (uStyle == 8) { float z = fract(along * 0.6 + ac * 0.5); c = mix(c, uCol2, aaStep(0.5, z) * 0.8); }
    else if (uStyle == 9) { a *= aaStep(0.35, fract(along * 0.7)); }
    else if (uStyle == 10) { float z = fract(along * 0.7 - ac * 0.45); c = mix(c, uCol2, aaStep(0.5, z) * 0.85); }
    else if (uStyle == 11) { float s = aaStep(0.5, fract((along - vUV.y * 0.6) * 0.75)); c = mix(vec3(1.0), uCol, s); }
    frag = vec4(clamp(c, 0.0, 1.0), a * uAlpha);
}
"""

    // ------------------------------------------------------------------ planes

    const val PLANE_VS = HEAD + """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUV;
uniform mat4 uVP;
uniform mat4 uModel;
out vec3 vN;
out vec2 vUV;
out vec3 vPos;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vPos = w.xyz;
    vN = mat3(uModel) * aNormal;
    vUV = aUV;
    gl_Position = uVP * w;
}
"""

    const val PLANE_FS = HEAD + LIB + """
in vec3 vN;
in vec2 vUV;
in vec3 vPos;
uniform vec3 uBase;
uniform vec3 uAccent;
uniform int uPattern;
uniform vec3 uLight;
uniform vec3 uEye;
uniform float uFlash;
uniform vec3 uFlashColor;
uniform sampler2D uGrain;
out vec4 frag;
void main() {
    vec3 n = normalize(vN);
    if (!gl_FrontFacing) n = -n;
    float ndl = max(dot(n, uLight), 0.0);
    vec3 v = normalize(uEye - vPos);
    float rim = pow(1.0 - max(dot(n, v), 0.0), 2.5);
    float m = pat(uPattern, vUV * 7.0);
    vec3 c = applyPattern(uBase, uAccent, uPattern, m, 0.85);
    c *= 0.93 + 0.09 * texture(uGrain, vUV * 0.9).r;
    vec3 col = c * (0.52 + 0.6 * ndl) + rim * 0.16;
    col = mix(col, uFlashColor, uFlash);
    frag = vec4(col, 1.0);
}
"""

    // ------------------------------------------------------------------ sprites

    const val SPRITE_VS = HEAD + """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in vec4 aColor;
uniform mat4 uVP;
out vec2 vUV;
out vec4 vColor;
void main() {
    vUV = aUV;
    vColor = aColor;
    gl_Position = uVP * vec4(aPos, 1.0);
}
"""

    const val SPRITE_FS = HEAD + """
in vec2 vUV;
in vec4 vColor;
uniform sampler2D uTex;
out vec4 frag;
void main() {
    frag = texture(uTex, vUV) * vColor;
}
"""
}
