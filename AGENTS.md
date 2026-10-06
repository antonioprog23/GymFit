# Contexto de GymFit para agentes

## Objetivo del producto

GymFit es una aplicación Android nativa, local y sin cuenta para importar una rutina de boxeo/gimnasio desde un `.xlsx`, guiar sesiones de mañana y tarde, registrar resultados y exportar cada rutina mensual con su historial.

Antes de modificar comportamiento funcional, consulta `README.md`: documenta el contrato visible de importación, exportación, temporizadores, temas y rutinas mensuales.

## Stack y comandos

- Kotlin 2.0.21, Android Gradle Plugin 8.13.2 y Java 17.
- Un único módulo Android: `app`.
- `minSdk 26`, `targetSdk 35`, `compileSdk 35`.
- UI clásica creada mayoritariamente en Kotlin; no usa Compose, ViewModel, Room, red ni inyección de dependencias.
- Dependencias principales: AndroidX, AppCompat y Material. Las pruebas locales usan JUnit 4.
- Comprobación completa en Windows: `./gradlew.bat testDebugUnitTest lintDebug`.
- APK de desarrollo: `./gradlew.bat :app:assembleDebug`.

## Mapa de lectura rápida

Lee solo la ruta relacionada con la tarea:

- Navegación, pantallas y coordinación: `app/src/main/java/com/rutinaboxeo/app/MainActivity.kt`.
  - Es una actividad única con vistas programáticas y una barra inferior.
  - Las páginas están en el enum privado `Page`: `TODAY`, `MORNING`, `ROUTINE`, `SESSION`, `PROGRESS`, `TEMPLATE`.
  - También coordina los temporizadores, selectores de archivos, importación/exportación y guardado diferido.
- Componentes y paleta visual reutilizables: `ui/GymFitActivity.kt`; colores y temas: `res/values*/`.
- Modelo central e índices del plan: `Models.kt`.
- Agregados de calendario, fechas y progreso para la interfaz: `RoutinePresentation.kt`.
- Máquina de estados pura de cada ejercicio: `ExerciseFlow.kt`.
- Interpretación de series, trabajo y descanso: `ExerciseTiming.kt`.
- Pasos de mañana: `MorningRoutine.kt`; textos de ayuda: `ExerciseGuide.kt`.
- Importación XLSX: `XlsxRoutineParser.kt`; exportación XLSX: `RoutineWorkbook.kt`.
- Archivo mensual e historial: `MonthlyRoutine.kt` y `MonthlyRepository.kt`.
- Ajuste de una plantilla al período elegido: `MonthlyPlanTemplate.kt`.
- Compatibilidad/migración desde JSON y preferencias antiguas: `RoutineStore.kt`, `RoutineImportPolicy.kt` y `RoutineJson.kt`.
- Vídeos YouTube: `VideoLinks.kt` y `ExerciseVideoActivity.kt`.
- Tema: `ThemePreferences.kt`; sonidos de descanso: `RestCountdownSound.kt` y `RestCountdownCues.kt`.
- Plantilla distribuida: `app/src/main/assets/rutina_plantilla.xlsx`.
- Pruebas de dominio y persistencia: `app/src/test/java/com/rutinaboxeo/app/`.

## Flujo de datos

1. `XlsxRoutineParser.parse` convierte el libro en `RoutinePlan` y valida límites/formato.
2. Una importación crea un `MonthlyRoutine` mediante `MonthlyRepository.create`.
3. Cada documento vive en `files/routines/<id>.json`; `active.txt` selecciona el activo.
4. `MainActivity` mantiene el plan y progreso visibles; los campos editados se guardan con un retraso de 400 ms y al salir/cambiar de pantalla.
5. `MonthlyRepository` conserva realizaciones independientes de tarde y mañana y escribe mediante temporal + sustitución atómica.
6. `RoutineWorkbook.write` exporta un único documento, su plan y resultados; nunca mezcla meses/importaciones.

## Contratos que no deben romperse

- Una importación siempre empieza con progreso a cero. El historial exportado es informativo y no se restaura al reimportar.
- No sobrescribas documentos mensuales: los ids son `MM_AAAA.json`, `MM_AAAA_1.json`, etc.
- No mezcles el progreso de dos documentos. Al borrar el activo, no actives otro automáticamente.
- La rutina activa es la principal del mes actual. `principals.json` conserva una principal por período;
  las futuras son programadas y las restantes históricas o alternativas.
- Una sesión extra siempre escribe mediante el id explícito de su documento y nunca modifica la principal.
- Una rutina con sesiones pendientes no se puede eliminar.
- Una repetición crea un `WorkoutRecord` nuevo; no modifica sesiones finalizadas.
- El descanso ocurre después de cada serie, incluida la última. Mantén las transiciones en `ExerciseFlow`, no dupliques esa lógica en la UI.
- Al pausar, cambiar de pantalla, tema o abrir vídeo, conserva campos y tiempo restante; detén los sonidos activos.
- El mes de la hoja `Periodo` es obligatorio y tiene prioridad. Los Excel semanales antiguos están obsoletos; solo sus JSON permanecen legibles y exportables como histórico.
- `Calendario` contiene exactamente una fila por día real del período y `Rutina` al menos un ejercicio por cada fecha. Los días deben ser enteros, únicos en `Calendario` y estar dentro del mes.
- La recuperación activa usa el tipo `Recuperación`, contiene movilidad o estiramientos y cuenta en el progreso.
- El parser XLSX aplica límites contra archivos comprimidos maliciosos y acepta solo los días/hojas definidos; conserva esas validaciones.
- Los enlaces de vídeo solo admiten formatos YouTube validados por `VideoLinks`; no cargues URLs arbitrarias en el `WebView`.
- Al exportar texto procedente del usuario, evita que valores iniciados por `=` se conviertan en fórmulas.
- Toda la información del usuario permanece local. No introduzcas servidor, analítica, permisos o red adicional sin petición explícita.

## Convenciones de implementación

- Mantén las reglas de negocio en objetos Kotlin puros y testeables; `MainActivity` debe limitarse en lo posible a coordinación y renderizado.
- Reutiliza los helpers de `GymFitActivity` para UI (`text`, `button`, `card`, `row`, `col`, `dp`) y los recursos de color existentes; soporta tema claro y oscuro.
- Conserva los textos visibles en español y la documentación KDoc breve en español.
- No añadas una biblioteca XLSX pesada: importador y exportador trabajan directamente con ZIP/XML por diseño.
- Evita cambios de esquema incompatibles. Si añades campos persistidos, proporciona valores por defecto y lectura compatible con JSON anterior.
- Trata `RoutineExercise.key()` como identidad persistida; cambiar su composición rompe resultados guardados.
- Haz cambios pequeños en `MainActivity`; extrae lógica pura cuando pueda cubrirse con pruebas unitarias.
- No edites archivos generados en `build/`, `.gradle/`, `.idea/` o `.kotlin/`.

## Estrategia de pruebas

Añade o actualiza la prueba más cercana al comportamiento:

- Importación, hojas o validaciones XLSX: `XlsxRoutineParserTest`.
- Exportación y seguridad del texto: `RoutineWorkbookTest`.
- Historial, migración, borrado o aislamiento mensual: `MonthlyRepositoryTest`.
- Series, descansos y transiciones: `ExerciseFlowTest` y `ExerciseTimingTest`.
- Modelos, ordenación y claves: `DomainModelTest`.
- Calendario, progreso y consulta de pesos: `RoutinePresentationTest`.
- Enlaces: `VideoLinksTest`.
- Avisos finales: `RestCountdownCuesTest`.
- Política de primera instalación/migración: `RoutineImportPolicyTest`.

Tras cambios de código ejecuta como mínimo `./gradlew.bat testDebugUnitTest`; para entregar una modificación Android completa, ejecuta también `./gradlew.bat lintDebug`. Si solo cambias documentación, no es necesario ejecutar Gradle.

## Método de trabajo recomendado

1. Identifica el contrato afectado en `README.md` y en la sección anterior.
2. Abre únicamente los archivos indicados en el mapa y sus pruebas; no releas el repositorio completo.
3. Comprueba el estado de Git y preserva cambios ajenos.
4. Implementa primero la regla en código puro/persistencia y después conecta la UI.
5. Añade una regresión unitaria y ejecuta las comprobaciones proporcionales al cambio.
6. Si cambia una función visible, actualiza `README.md` y este archivo solo cuando el mapa o los contratos hayan cambiado.
