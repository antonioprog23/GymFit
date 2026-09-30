# GymFit - Android 2.0

Aplicación Android nativa y local para seguir una rutina de boxeo y gimnasio a partir de una plantilla Excel `.xlsx`.

## Funciones

- Pantalla **Hoy**: separa una rutina guiada de movilidad matinal de 15 minutos y el entrenamiento de tarde.
- Rutina de mañana cargada desde la hoja `Mañana` del Excel, con temporizador, pausa y registro de finalización diaria.
- Pantalla **Rutina**: muestra el calendario real del mes y permite abrir cada fecha.
- Pantalla **Progreso**: muestra ejercicios completados por tramos del mes e historial.
- Pantalla **Plantilla**: importa, descarga y comparte el Excel editable.
- Importación de `.xlsx` desde el almacenamiento del móvil.
- Lee `Periodo`, `Calendario`, `Rutina`, `Mañana` y `Alternativas`.
- Guarda localmente peso, reps reales, RIR, notas personales y ejercicios completados.
- Temporizador de ejercicio para calentamientos y otros bloques medidos en minutos o segundos.
- Sesión de tarde guiada por series: repeticiones manuales o tiempo automático, descanso después de cada serie incluida la última, y marcado automático al finalizar.
- Los ejercicios de mañana y tarde aparecen después de importar un archivo Excel. La app incluye la plantilla actualizada para descargar.
- No requiere cuenta, servidor ni conexión a Internet una vez instalada.

## Apariencia

En Ajustes, «Sonido de descanso» activa o silencia los avisos (activados por defecto).
Suena un pitido a los 5, 4, 3, 2 y 1 segundos restantes y un tono distinto al terminar.
Solo se aplica a los descansos de la sesión de tarde, utiliza el volumen multimedia
y conserva la elección al cerrar la aplicación. Al pausar o salir, se silencia.

El botón de sol/luna de la cabecera alterna entre tema claro y oscuro. La elección
se guarda en preferencias privadas y se recupera al abrir la aplicación; inicialmente
se utiliza el tema del dispositivo. El cambio conserva la pantalla, los campos y las
series registradas, y pausa los temporizadores manteniendo el tiempo restante.

## Estructura de Excel

Antes de descargar o compartir una plantilla se eligen mes y año. GymFit genera
exactamente 28, 29, 30 o 31 fechas y guarda `MES_AÑO.xlsx`. El período incluido en
el documento es obligatorio y prevalece sobre la fecha del móvil al importar.
La hoja `Instrucciones` acompaña al ejemplo completo. Las antiguas plantillas por
semanas ya no se importan y muestran un error que solicita la plantilla mensual.

Hoja `Mañana`:

A Orden | B Ejercicio | C Duración (min) | D Indicaciones

Puedes cambiar los pasos y su duración; GymFit calcula el total al importar el archivo.

Hoja `Calendario`:

A Día | B Fecha | C Día de la semana | D Tipo | E Sesión | F Objetivo |
G Notas | H Fase | I Intensidad | J Regla

Debe contener una sola fila por cada día real del mes, sin días ausentes, repetidos,
decimales ni fuera de rango. `Recuperación` identifica movilidad y estiramientos y
cuenta para el progreso igual que un entrenamiento.

Hoja `Rutina`:

A Día | B Bloque | C Ejercicio | D Series | E Reps/tiempo | F Descanso

G Peso | H Reps reales | I RIR | J Nota personal | K Indicaciones | L Vídeo | M Nota de planificación | N Hecho

El número de `Día` puede repetirse: cada fila representa un ejercicio de esa fecha.
Cada día del calendario debe tener al menos un ejercicio. Las columnas de resultados
y la hoja `Historial` se exportan para consulta, pero se
ignoran al importar: cada importación empieza con progreso a cero. Los enlaces
existentes se conservan. «Ver vídeo» abre los enlaces válidos de YouTube dentro de
GymFit, tanto en movilidad como en la sesión de tarde. Se admiten enlaces normales,
cortos, Shorts y emisiones. El entrenamiento queda pausado sin perder sus campos.
La reproducción requiere Internet; si YouTube restringe el vídeo, usa «Abrir en
YouTube». El reproductor se carga únicamente al pulsar el botón y conserva sus
controles oficiales. No se descargan vídeos ni se necesitan claves de API.

## Rutinas mensuales e histórico

En «Mis rutinas», abre una importación y pulsa «Eliminar rutina». La confirmación
identifica el documento exacto y advierte de que el borrado no se puede deshacer.
«Exportar antes» solo exporta: después hay que volver a solicitar el borrado.
La Excel conserva los resultados para consulta, pero no restaura el historial al importarla.
Las rutinas activas con entrenamientos pendientes no se pueden eliminar; primero
deben finalizarse. Al borrar la activa, no se selecciona otra automáticamente ni
se recuperan datos antiguos. Los demás documentos permanecen intactos.

- Una importación crea un documento en la carpeta privada `files/routines/`.
- Nombres: `09_2026.json`, `09_2026_1.json`, `09_2026_2.json`… sin sobrescrituras.
- `active.txt` identifica la rutina activa. Las demás permanecen en el histórico.
- Los JSON nuevos usan el esquema 3: incluyen calendario, `dayOfMonth`, mes,
  importación, planificación, últimos resultados y realizaciones de tarde y mañana.
- Los JSON de esquema 2 continúan leyéndose como histórico. Conservan semana y día,
  se pueden consultar y exportar, pero no vuelven a habilitar entrenamientos semanales.
- Los campos se guardan tras 400 ms sin escribir y al cambiar de pantalla o salir.
- Una sesión finalizada queda en consulta; «Repetir entrenamiento» abre un registro
  nuevo sin modificar las realizaciones anteriores.
- «Mis rutinas», en Rutina y Plantilla, muestra la planificación e historial mensual.
- «Exportar rutina y resultados» permite elegir un solo documento activo o histórico.
  Nunca se combinan resultados de distintas importaciones en una exportación.
- La migración solicita mes y año y conserva los resultados anteriores sin inventar
  fechas. Su Excel de salida usa `Plan anterior` e `Historial` y es un informe de
  consulta, no una plantilla reimportable. Las preferencias antiguas no se eliminan.
- Las escrituras se sincronizan a un temporal y se sustituyen atómicamente. Los datos
  son locales: exporta los meses que quieras conservar antes de desinstalar la app.

El Excel mensual de salida reutiliza `Calendario` y `Rutina`. La recuperación activa
se incluye directamente en las fechas correspondientes y sus ejercicios participan
en el mismo cálculo de progreso.

Hoja `Alternativas`:
A Ejercicio principal | B Alternativa 1 | C Alternativa 2 | D Nota

## Fecha activa

En `Rutina` se selecciona un día real del mes. `Hoy` abre automáticamente la fecha
actual solo cuando coincide con el período de la rutina activa.

## Organización del código

- `MainActivity`: navegación y coordinación de los flujos de mañana, tarde, progreso y plantilla.
- `ui/GymFitActivity`: paleta y componentes visuales reutilizables.
- `Models`, `RoutinePresentation`, `ExerciseFlow`, `ExerciseTiming` y `ExerciseGuide`: modelos, agregados de progreso y reglas de dominio sin dependencias de interfaz.
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
