package com.rutinaboxeo.app.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.rutinaboxeo.app.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** Base visual que concentra la paleta y los componentes programáticos de GymFit. */
abstract class GymFitActivity : AppCompatActivity() {
    /** Fondo de tarjetas adaptado al tema activo. */
    internal val cardSurface by lazy(LazyThreadSafetyMode.NONE) { getColor(R.color.card_surface) }
    /** Color principal de texto y fondos oscuros. */
    internal val ink by lazy(LazyThreadSafetyMode.NONE) { getColor(R.color.text_primary) }

    /** Color secundario para textos informativos. */
    internal val muted by lazy(LazyThreadSafetyMode.NONE) { getColor(R.color.text_secondary) }

    /** Color de marca para acciones y elementos activos. */
    internal val red by lazy(LazyThreadSafetyMode.NONE) { getColor(R.color.brand_red) }

    /** Fondo suave para paneles informativos. */
    internal val pale by lazy(LazyThreadSafetyMode.NONE) { getColor(R.color.surface_muted) }

    /** Color de bordes y separadores. */
    internal val line by lazy(LazyThreadSafetyMode.NONE) { getColor(R.color.divider) }

    /** Color que representa estados completados. */
    internal val green by lazy(LazyThreadSafetyMode.NONE) { getColor(R.color.green) }

    /** Color de apoyo para recuperación y elementos programados. */
    internal val orange by lazy(LazyThreadSafetyMode.NONE) { getColor(R.color.orange) }

    /** Rojo reservado para acciones destructivas. */
    internal val danger by lazy(LazyThreadSafetyMode.NONE) { getColor(R.color.danger) }

    /** Convierte píxeles independientes de densidad en píxeles físicos. */
    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    /** Crea un fondo redondeado con relleno y borde opcional. */
    internal fun box(fill: Int, radius: Int = 16, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            border?.let { setStroke(dp(1), it) }
        }

    /** Crea una etiqueta con la tipografía y el color coherentes de la aplicación. */
    internal fun text(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    /** Crea una columna vertical con relleno uniforme opcional. */
    internal fun col(padding: Int = 0): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    /** Crea una fila horizontal con sus elementos centrados verticalmente. */
    internal fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Añade una vista a una columna ocupando el ancho y aplicando márgenes verticales. */
    internal fun add(parent: LinearLayout, child: View, top: Int = 0, bottom: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        })
    }

    /** Envuelve una vista en una tarjeta con fondo, borde y elevación coherentes. */
    internal fun card(child: View, fill: Int = cardSurface, border: Int = line): MaterialCardView =
        MaterialCardView(this).apply {
            setCardBackgroundColor(fill)
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeColor = border
            strokeWidth = dp(1)
            addView(child)
        }

    /** Barra de progreso compacta usada en calendarios, archivo y sesiones. */
    internal fun progressBar(percent: Int, color: Int = red, height: Int = 8): FrameLayout =
        FrameLayout(this).apply {
            background = box(pale, height)
            val value = View(context).apply { background = box(color, height) }
            addView(value, FrameLayout.LayoutParams(0, dp(height)))
            post {
                value.layoutParams = FrameLayout.LayoutParams(
                    width * percent.coerceIn(0, 100) / 100,
                    dp(height)
                )
            }
        }

    /** Celda bento para una cifra y su etiqueta. */
    internal fun metric(value: String, label: String, accent: Int = ink): MaterialCardView =
        card(col(13).apply {
            add(this, text(value, 21f, accent, true))
            add(this, text(label, 12f, muted), 4)
        })

    /** Crea un botón primario o secundario con el estilo de la aplicación. */
    internal fun button(value: String, filled: Boolean = true): MaterialButton = MaterialButton(this).apply {
        text = value
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        cornerRadius = dp(14)
        minHeight = dp(52)
        insetTop = 0
        insetBottom = 0
        backgroundTintList = ColorStateList.valueOf(if (filled) red else cardSurface)
        setTextColor(if (filled) getColor(R.color.on_accent) else red)
        if (!filled) strokeWidth = 0
    }

    /** Crea una etiqueta de estado compacta y legible además del color. */
    internal fun chip(value: String, color: Int = red, fill: Int = pale): TextView =
        text(value, 12f, color, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = box(fill, 20)
        }

    /** Crea un título de sección discreto para separar bloques sin recargar la pantalla. */
    internal fun sectionTitle(value: String): TextView = text(value, 13f, muted, true).apply {
        letterSpacing = 0.06f
    }

    private companion object {
        /** Valor de diseño que hace que una vista ocupe todo el espacio disponible. */
        const val MATCH_PARENT = -1

        /** Valor de diseño que ajusta una vista al tamaño de su contenido. */
        const val WRAP_CONTENT = -2
    }
}
