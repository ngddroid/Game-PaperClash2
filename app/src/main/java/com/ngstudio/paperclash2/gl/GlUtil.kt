package com.ngstudio.paperclash2.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** A linked shader program with cached uniform locations. */
class GlProgram(vs: String, fs: String, private val name: String) {
    val id: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, v)
        GLES30.glAttachShader(id, f)
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            Log.e(TAG, "link failed ($name): $log")
            throw IllegalStateException("Program $name failed to link: $log")
        }
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            Log.e(TAG, "compile failed ($name ${if (type == GLES30.GL_VERTEX_SHADER) "vs" else "fs"}): $log")
            throw IllegalStateException("Shader $name failed: $log")
        }
        return s
    }

    fun use() = GLES30.glUseProgram(id)

    fun u(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun release() = GLES30.glDeleteProgram(id)

    companion object {
        private const val TAG = "PaperGL"
    }
}

object Gl {
    fun floats(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    fun shorts(n: Int): ShortBuffer = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    fun bytes(n: Int): ByteBuffer = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())

    fun genBuffer(): Int {
        val a = IntArray(1)
        GLES30.glGenBuffers(1, a, 0)
        return a[0]
    }

    fun genTexture(): Int {
        val a = IntArray(1)
        GLES30.glGenTextures(1, a, 0)
        return a[0]
    }

    fun genVao(): Int {
        val a = IntArray(1)
        GLES30.glGenVertexArrays(1, a, 0)
        return a[0]
    }

    fun deleteTexture(t: Int) {
        if (t != 0) GLES30.glDeleteTextures(1, intArrayOf(t), 0)
    }

    fun deleteBuffer(b: Int) {
        if (b != 0) GLES30.glDeleteBuffers(1, intArrayOf(b), 0)
    }

    fun deleteVao(v: Int) {
        if (v != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(v), 0)
    }

    /** Texture parameters: linear or nearest, clamp or repeat, optional mipmaps for bitmaps. */
    fun params(linear: Boolean, repeat: Boolean, mip: Boolean = false) {
        val min = if (mip) GLES30.GL_LINEAR_MIPMAP_LINEAR else if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, min)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST)
        val wrap = if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
    }

    /** Uploads a bitmap (premultiplied by Android) as an RGBA texture. */
    fun bitmapTexture(bmp: Bitmap, repeat: Boolean, mip: Boolean): Int {
        val t = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        params(linear = true, repeat = repeat, mip = mip)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        if (mip) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return t
    }

    fun check(tag: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) Log.w("PaperGL", "GL error 0x${Integer.toHexString(e)} at $tag")
    }
}
