package com.rutinaboxeo.app

import java.text.Normalizer
import java.util.Locale

/** Proporciona instrucciones de apoyo cuando la plantilla no incluye una específica. */
object ExerciseGuide {
    /** Obtiene una indicación segura y adaptada al nombre o bloque del ejercicio. */
    fun forExercise(item: RoutineExercise): String {
        if (item.instruction.isNotBlank()) return item.instruction.trim()
        val name = Normalizer.normalize(item.exercise.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
        return when {
            name.contains("descanso completo") -> "Día de descanso. Deja que el cuerpo se recupere antes de la siguiente sesión."
            name.contains("rutina completa") -> "Sigue la secuencia de la hoja Recuperación de la plantilla, a un ritmo cómodo."
            name.contains("bici/remo por asaltos") -> "Alterna los tramos fuertes y suaves que indica la plantilla, sin perder el control de la respiración."
            name.contains("bicicleta") || name.contains("bici o remo") -> "Muévete al ritmo y durante el tiempo indicados. Empieza suave y mantén una respiración regular."
            name.contains("caminar") -> "Camina despacio durante el tiempo indicado para bajar pulsaciones."
            name.contains("sentadilla con barra") -> "Flexiona caderas y rodillas con el tronco estable; sube empujando el suelo con ambos pies."
            name.contains("sentadilla") || name.contains("hack squat") || name.contains("prensa") -> "Baja con control flexionando caderas y rodillas; vuelve a subir sin bloquearlas bruscamente."
            name.contains("peso muerto rumano") -> "Lleva la cadera atrás con la espalda estable y la carga cerca de las piernas; vuelve a erguirte."
            name.contains("zancada") -> "Da el paso indicado, flexiona ambas piernas con control y vuelve al centro. Alterna los lados."
            name.contains("salto") -> "Impúlsate con decisión y aterriza con las rodillas ligeramente flexionadas y el cuerpo estable."
            name.contains("hip thrust") -> "Apoya la espalda, eleva la cadera empujando con los pies y baja de forma controlada."
            name.contains("curl femoral") -> "Flexiona las rodillas contra la resistencia y vuelve despacio a la posición inicial."
            name.contains("gemelo") && !name.contains("estiramiento") -> "Eleva los talones, haz una breve pausa arriba y baja de forma controlada."
            name.contains("tibialis") -> "Eleva las puntas de los pies manteniendo los talones apoyados y vuelve despacio."
            name.contains("press banca") || name.contains("press inclinado") -> "Baja las mancuernas con control hacia el pecho y empuja hasta volver arriba."
            name.contains("press hombro") -> "Empuja las mancuernas por encima de los hombros y bájalas con control."
            name.contains("flexiones") -> "Baja el pecho con el cuerpo alineado y empuja para volver. Adapta la explosividad a tu control."
            name.contains("remo") -> "Tira de la carga hacia el tronco llevando los codos atrás; vuelve lentamente."
            name.contains("jalon") -> "Lleva la barra hacia la parte alta del pecho y vuelve arriba sin encoger los hombros."
            name.contains("face pull") -> "Tira de la cuerda hacia la cara con los codos abiertos y vuelve con control."
            name.contains("elevaciones laterales") -> "Eleva los brazos hacia los lados hasta una altura cómoda y baja lentamente."
            name.contains("curl biceps") -> "Flexiona los codos para elevar las mancuernas y bájalas sin balancear el tronco."
            name.contains("triceps") -> "Extiende los codos contra la resistencia y vuelve despacio, sin mover el tronco."
            name.contains("farmer carry") -> "Camina la distancia indicada sujetando las cargas con el tronco erguido y los hombros estables."
            name.contains("pallof") -> "Aleja las manos del pecho resistiendo el giro del tronco; vuelve y cambia de lado."
            name.contains("cable chop") -> "Lleva el agarre en diagonal con el tronco controlado; repite a ambos lados."
            name.contains("dead bug") -> "Boca arriba, extiende brazo y pierna contrarios sin despegar la zona lumbar del suelo."
            name.contains("plancha lateral") -> "Mantén el cuerpo alineado de lado durante el tiempo indicado y cambia de lado."
            name.contains("equilibrio") -> "Mantente sobre una pierna durante el tiempo indicado; apóyate si lo necesitas."
            name.contains("90/90") -> "Sentado en posición 90/90, cambia las rodillas de lado con un movimiento suave."
            name.contains("rotaciones toracicas") -> "A cuatro apoyos, gira la parte alta de la espalda siguiendo la mano con la mirada."
            name.contains("movilidad de tobillo") -> "Lleva la rodilla hacia delante sin levantar el talón y cambia de lado."
            name.contains("movilidad de hombros") -> "Haz círculos suaves con los hombros y mueve los brazos sin forzar el rango."
            name.contains("movilidad de munecas") || name.contains("pronacion") || name.contains("extension de muneca") ->
                "Mueve las muñecas lentamente en el recorrido indicado y cambia de mano."
            name.contains("estiramiento") -> "Colócate en el estiramiento indicado, respira con calma y mantén la posición sin rebotes."
            name.contains("respiracion") -> "Respira despacio, dejando que el abdomen se expanda al inhalar y soltando el aire con calma."
            item.block.contains("calentamiento", ignoreCase = true) -> "Realiza el movimiento a un ritmo suave para preparar el cuerpo."
            item.block.contains("cardio", ignoreCase = true) -> "Mantén el ritmo y el tiempo indicados, respirando de forma regular."
            else -> "Realiza ${item.reps.takeUnless { it.isBlank() || it == "-" } ?: "las repeticiones indicadas"} con control y sin dolor. Descansa entre series según la plantilla."
        }
    }

    /** Expresión reutilizada para normalizar búsquedas con o sin tildes. */
    private val DIACRITICS = Regex("\\p{M}+")
}
