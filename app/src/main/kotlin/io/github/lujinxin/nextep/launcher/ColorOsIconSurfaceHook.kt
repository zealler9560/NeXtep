package io.github.lujinxin.nextep.launcher

import android.graphics.Matrix
import android.graphics.RectF
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/** ColorOS 17 draws the returning icon in a Surface, outside the scaled launcher decor. */
internal object ColorOsIconSurfaceHook {
    fun install(module: XposedModule, loader: ClassLoader) {
        val updater = loader.loadClass("com.android.quickstep.util.animation.f")
        val update = updater.declaredMethods.single { method ->
            val p = method.parameterTypes
            p.size == 8 && p[0].name.endsWith("RectTransformHelper\$TransformParams") &&
                p[2] == RectF::class.java && p[3] == SurfaceControl::class.java && p[6] == Matrix::class.java
        }
        val opening = updater.getDeclaredField("a").apply { isAccessible = true }
        check(opening.type == Boolean::class.javaPrimitiveType)
        val builder = update.parameterTypes[4].declaredMethods.single {
            it.parameterTypes.contentEquals(arrayOf(SurfaceControl::class.java)) && it.returnType != Void.TYPE
        }.returnType
        val setMatrix = builder.declaredMethods.single {
            it.parameterTypes.contentEquals(arrayOf(Matrix::class.java)) && it.returnType == builder
        }
        val fitting = ThreadLocal<TouchCoordinateMapper.Transform?>()
        module.hook(setMatrix).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val fit = fitting.get() ?: return chain.proceed()
                val native = chain.args[0] as? Matrix ?: return chain.proceed()
                val args = chain.args.toTypedArray()
                args[0] = fittedMatrix(native, fit)
                return chain.proceed(args)
            }
        })
        module.hook(update).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val fit = LauncherTransformController.iconSurfaceTransform()
                if (fit == null || opening.getBoolean(chain.thisObject)) return chain.proceed()
                val previous = fitting.get()
                fitting.set(fit)
                try { return chain.proceed() }
                finally { if (previous == null) fitting.remove() else fitting.set(previous) }
            }
        })
    }

    internal fun fittedMatrix(native: Matrix, fit: TouchCoordinateMapper.Transform): Matrix = Matrix(native).apply {
        postScale(fit.scaleX, fit.scaleY)
        postTranslate(fit.translationX, fit.translationY)
    }
}
