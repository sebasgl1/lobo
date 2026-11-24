# Lobo 🐺🌕

El juego de Lobo (Mafia / Werewolf) en español, offline y en un solo celular. La app es la narradora: nadie se queda sin jugar.

- Se juega pasando el celular: de noche **todos** tocan un nombre y cada turno dura lo mismo, así nadie se delata
- Roles: Lobos, Aldeanos, Vidente, Doctor y Cazador, con reparto recomendado según jugadores
- Los lobos se coordinan por turnos: cada uno ve lo que propuso su compañero
- Votación con desempate, cazador que se lleva a alguien, revelar o no el rol al morir, primera noche tranquila
- Línea de tiempo y "lo que pensaba el pueblo" al final, estadísticas por jugador
- **Modo Historia** (opcional): escena del crimen con 3 sospechosos (al menos uno es lobo y otro es un inocente incriminado por los lobos), testigos que ven a dos personas (una es lobo), compra del testigo y sospechas anónimas
- **Modo narrador** (con Clásico o Historia): el celular queda en la mesa y una voz grave y pausada narra la noche; todos cierran los ojos y solo el rol llamado toca su elección. Los roles muertos se siguen llamando con turnos simulados de duración parecida. En Modo Historia el testigo pasa a ser un rol fijo (🕯️ Testigo). Usa la voz de Android en español (sin internet si está descargada)
- Ronda de acusación (señalar 3-2-1 + defensas de 30 s) y registro opcional de votos
- Sonidos de ambientación con botón para silenciar: fondos de 2,5 minutos en estéreo (hoguera, noche, mercado) que cambian con fundido y empiezan en un punto al azar, más sonidos sueltos al azar encima (búho, ranas, ramas, ráfagas, aullidos lejanos, chasquidos, herrero, perros, carreta, pájaros) para que nunca suene igual. Efectos con varias versiones (aullido, manada, campanas, flecha, "¡Está vivo!", pájaros). Todo se genera con `tools/sonidos.py` (síntesis con numpy/scipy + ffmpeg) y se puede reemplazar por grabaciones reales con el mismo nombre
- La partida se guarda si Android cierra la app

Las reglas están en `Rules.kt` con pruebas (incluidas 10.000 partidas simuladas). Cada compilación corre pruebas de pantalla en un emulador y publica el APK en Releases.

## Actualizaciones dentro de la app

En la pantalla de inicio, "Buscar actualización" revisa el último Release de GitHub (`update.json`).
Si hay un parche para la versión instalada, descarga solo lo que cambió (`lobo-desde-vX.patch`, formato
LOBODIFF1 = bsdiff + zlib, ver `tools/delta.py`), reconstruye el APK nuevo, verifica su huella SHA-256 y
abre el instalador de Android. Si no hay parche o algo falla, descarga la app completa.
Cada compilación publica parches desde las 3 versiones anteriores (`.github/scripts/make_update.py`).
La app solo usa internet al tocar ese botón.
