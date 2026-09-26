# GymFit - Android 2.0

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

## Apariencia

El botón de sol/luna de la cabecera alterna entre tema claro y oscuro. La elección
se guarda en preferencias privadas y se recupera al abrir la aplicación; inicialmente
se utiliza el tema del dispositivo. El cambio conserva la pantalla, los campos y las
series registradas, y pausa los temporizadores manteniendo el tiempo restante.

## Estructura de Excel

La descarga genera `MES_AÑO.xlsx` con una hoja `Periodo`: mes numérico (1–12)
y año, editables. El mes del documento prevalece sobre la fecha del móvil al importar.
La hoja `Instrucciones` acompaña al ejemplo completo. Las plantillas antiguas sin
período siguen siendo válidas: se solicita asignarlo durante la importación.

Hoja `Mañana`:

A Orden | B Ejercicio | C Duración (min) | D Indicaciones

Puedes cambiar los pasos y su duración; GymFit calcula el total al importar el archivo.

Hoja `Inicio`:

A Día | B Sesión | C Objetivo | D Notas

Hojas `Semana 1` a `Semana 4`:

A Día | B Bloque | C Ejercicio | D Series | E Reps/tiempo | F Descanso

G Peso | H Reps reales | I RIR | J Nota personal | K Indicaciones | L Vídeo | M Nota de planificación | N Hecho

Las columnas de resultados y la hoja `Historial` se exportan para consulta, pero se
ignoran al importar: cada importación empieza con progreso a cero. Los enlaces
existentes se conservan; esta versión no añade reproducción de vídeos.

## Rutinas mensuales e histórico

- Una importación crea un documento en la carpeta privada `files/routines/`.
- Nombres: `09_2026.json`, `09_2026_1.json`, `09_2026_2.json`… sin sobrescrituras.
- `active.txt` identifica la rutina activa. Las demás permanecen en el histórico.
- Cada JSON incluye versión de esquema, mes, importación, planificación, últimos
  resultados y realizaciones fechadas de tarde y mañana.
- Los campos se guardan tras 400 ms sin escribir y al cambiar de pantalla o salir.
- Una sesión finalizada queda en consulta; «Repetir entrenamiento» abre un registro
  nuevo sin modificar las realizaciones anteriores.
- «Mis rutinas», en Rutina y Plantilla, muestra la planificación e historial mensual.
- «Exportar rutina y resultados» permite elegir un solo documento activo o histórico.
  Nunca se combinan resultados de distintas importaciones en una exportación.
- La migración solicita mes y año y conserva los resultados anteriores sin inventar
  sus fechas. Las preferencias antiguas no se eliminan durante la migración.
- Las escrituras se sincronizan a un temporal y se sustituyen atómicamente. Los datos
  son locales: exporta los meses que quieras conservar antes de desinstalar la app.

El Excel de salida reutiliza las columnas de la plantilla. La recuperación se incluye
directamente en sus semanas; así al reimportar no se sustituyen resultados por otra hoja.

Hoja `Recuperación`:
A Orden | B Ejercicio | C Series | D Reps/tiempo | E Descanso | F Indicaciones

Hoja `Alternativas`:
A Ejercicio principal | B Alternativa 1 | C Alternativa 2 | D Nota

## Semana activa

En la pantalla `Rutina`, selecciona la semana en la que estás entrenando. Esa selección se guarda y la pantalla `Hoy` la utiliza automáticamente.

## Organización del código

- `MainActivity`: navegación y coordinación de los flujos de mañana, tarde, progreso y plantilla.
- `ui/GymFitActivity`: paleta y componentes visuales reutilizables.
- `Models`, `ExerciseFlow`, `ExerciseTiming` y `ExerciseGuide`: modelos y reglas de dominio sin dependencias de interfaz.
- `XlsxRoutineParser` y `VideoLinks`: importación validada y segura de la plantilla.
- `RoutineStore` y `RoutineJson`: persistencia local y serialización, separadas para mantener responsabilidades claras.
- `MonthlyRoutine` y `MonthlyRepository`: archivo mensual, selección activa y realizaciones de entrenamientos.
- `RoutineWorkbook`: exportador XLSX nativo compatible con el importador y seguro para texto que empieza por `=`.
- `src/test`: pruebas de temporización, transiciones, importación, migración y consultas del plan.

## Compilar APK

Abrir el proyecto en Android Studio y usar **Build > Build APK(s)**, o ejecutar `gradlew.bat :app:assembleDebug` en Windows.

Para ejecutar todas las comprobaciones locales:

```powershell
gradlew.bat testDebugUnitTest lintDebug
```
