# GymFit - Android 1.5

Aplicación Android nativa y local para seguir una rutina de boxeo y gimnasio a partir de una plantilla Excel `.xlsx`.

## Funciones
- Pantalla **Hoy**: separa una rutina guiada de movilidad matinal de 15 minutos y el entrenamiento de tarde.
- Rutina de mañana cargada desde la hoja `Mañana` del Excel, con temporizador, pausa y registro de finalización diaria.
- Pantalla **Rutina**: permite elegir semana y día manualmente.
- Pantalla **Progreso**: muestra ejercicios completados por semana e historial.
- Pantalla **Plantilla**: importa, descarga y comparte el Excel editable.
- Importación de `.xlsx` desde el almacenamiento del móvil.
- Lee `Mañana`, `Inicio`, `Semana 1`, `Semana 2`, `Semana 3`, `Semana 4`, `Recuperación` y `Alternativas`.
- Guarda localmente peso, reps reales, RIR, notas personales y ejercicios completados.
- Temporizador de ejercicio para calentamientos y otros bloques medidos en minutos o segundos.
- Sesión de tarde guiada por series: repeticiones manuales o tiempo automático, descanso después de cada serie incluida la última, y marcado automático al finalizar.
- Los ejercicios de mañana y tarde aparecen después de importar un archivo Excel. La app incluye la plantilla actualizada para descargar.
- No requiere cuenta, servidor ni conexión a Internet una vez instalada.

## Estructura de Excel

Hoja `Mañana`:

A Orden | B Ejercicio | C Duración (min) | D Indicaciones

La duración total se calcula en la hoja. Puedes cambiar los pasos y su duración; GymFit los cargará al importar el archivo.

Hoja `Inicio`:

A Día | B Sesión | C Objetivo | D Notas
Hojas `Semana 1` a `Semana 4`:
A Día | B Bloque | C Ejercicio | D Series | E Reps/tiempo | F Descanso

Hoja `Recuperación`:
A Orden | B Ejercicio | C Series | D Reps/tiempo | E Descanso | F Indicaciones

Hoja `Alternativas`:
A Ejercicio principal | B Alternativa 1 | C Alternativa 2 | D Nota

## Semana activa
En la pantalla `Rutina`, selecciona la semana en la que estás entrenando. Esa selección se guarda y la pantalla `Hoy` la utiliza automáticamente.

## Compilar APK
Abrir el proyecto en Android Studio y usar **Build > Build APK(s)**, o ejecutar `gradlew.bat :app:assembleDebug` en Windows.
