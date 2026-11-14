package com.afroni.pocketwright.session

import android.app.Presentation
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.MainThread

/**
 * Une vraie fenêtre pour la WebView d'un onglet caché que l'on pilote : une [Presentation] sur un écran virtuel
 * privé, que seule l'app voit et où rien d'autre ne s'affiche.
 *
 * Hors de toute fenêtre, une WebView se met en page et exécute sa page, mais ne dessine pas : un geste (glissé,
 * lancer, molette) y reste en suspens après le premier mouvement, car son défilement passe par la vue Android et
 * ses images (PW-029). Dans une fenêtre, elle dessine à chaque image comme à l'écran, et le geste suit.
 *
 * Personne ne regarde cet écran : il ne fait que [SIZE_PX] pixels de côté, et la WebView y garde sa propre taille,
 * qui le dépasse. La page se met en page et défile à la taille de son viewport, mais la fenêtre ne coûte que des
 * tampons minuscules. La session demande à Chromium de préparer ses tuiles pour toute la vue, comme à l'écran
 * (`setOffscreenPreRaster`). Les captures et les aperçus, dessinés à part, voient toute la vue. Les images composées
 * sur l'écran sont jetées dès leur arrivée.
 */
internal class HiddenWindow private constructor(
    private val reader: ImageReader,
    private val display: VirtualDisplay,
    private val presentation: Presentation,
    private val container: FrameLayout,
) {
    @Volatile private var released = false

    /** La fenêtre est toujours là : Android peut la fermer de lui-même (écran retiré). */
    val usable: Boolean get() = !released && presentation.isShowing

    fun holds(view: View): Boolean = view.parent === container

    /** Pose la vue en haut à gauche, à sa taille, ou change sa taille si elle y est déjà. */
    @MainThread
    fun put(view: View, widthPx: Int, heightPx: Int) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        if (holds(view)) {
            val lp = view.layoutParams
            if (lp == null || lp.width != w || lp.height != h) view.layoutParams = FrameLayout.LayoutParams(w, h)
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(w, h))
    }

    /** Rend la vue (elle quitte la fenêtre) et libère l'écran virtuel. */
    @MainThread
    fun release() {
        if (released) return
        released = true
        container.removeAllViews()
        runCatching { presentation.dismiss() }
        runCatching { display.release() }
        // Le lecteur se ferme sur son fil, après la dernière image en cours de lecture.
        frames.post { runCatching { reader.close() } }
    }

    companion object {
        private const val TAG = "PocketWright"

        /** Le côté de l'écran virtuel, en pixels : assez pour que la vue y soit dessinée, pas plus. */
        private const val SIZE_PX = 64
        private const val MAX_IMAGES = 2

        /** Après autant d'échecs de suite, on n'essaie plus : le système ne veut pas de ces fenêtres. */
        private const val MAX_FAILURES = 5

        /** Le fil qui jette les images de tous les écrans virtuels. */
        private val frames: Handler by lazy {
            Handler(HandlerThread("pw-hidden-frames").apply { start() }.looper)
        }

        @Volatile private var failures = 0

        /**
         * Une fenêtre à la densité de l'écran du téléphone, pour que la vue garde ses mesures, ou null si le système la
         * refuse : l'onglet reste alors sans fenêtre, comme avant.
         */
        @MainThread
        fun create(context: Context, name: String): HiddenWindow? {
            if (failures >= MAX_FAILURES) return null
            var reader: ImageReader? = null
            var display: VirtualDisplay? = null
            return try {
                // Un format opaque : le système compose dans le format qu'il préfère, et personne ne lit les pixels.
                reader = if (Build.VERSION.SDK_INT >= 29) {
                    ImageReader.newInstance(SIZE_PX, SIZE_PX, ImageFormat.PRIVATE, MAX_IMAGES, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                } else {
                    ImageReader.newInstance(SIZE_PX, SIZE_PX, ImageFormat.PRIVATE, MAX_IMAGES)
                }
                // Une image non lue bloquerait la composition, puis le dessin de la fenêtre.
                reader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, frames)
                display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                    name, SIZE_PX, SIZE_PX, context.resources.displayMetrics.densityDpi, reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                ) ?: error("écran virtuel refusé")
                val container = FrameLayout(context)
                val presentation = Host(context, display.display, container)
                presentation.show()
                failures = 0
                HiddenWindow(reader, display, presentation, container)
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "Fenêtre cachée impossible : ${e.message}")
                runCatching { display?.release() }
                runCatching { reader?.close() }
                null
            }
        }
    }

    /** La fenêtre elle-même : sans focus ni toucher, pour ne jamais rien prendre à l'écran réel. */
    private class Host(context: Context, display: Display, private val content: View) : Presentation(context, display) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.apply {
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                )
                setBackgroundDrawable(null)
            }
            setContentView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
}
