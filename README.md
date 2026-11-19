# Lobo 🐺🌕

El juego de Lobo (Mafia / Werewolf) en español, offline y en un solo celular. La app es la narradora: nadie se queda sin jugar.

- Se juega pasando el celular: de noche **todos** tocan un nombre y cada turno dura lo mismo, así nadie se delata
- Roles: Lobos, Aldeanos, Vidente, Doctor y Cazador, con reparto recomendado según jugadores
- Los lobos se coordinan por turnos: cada uno ve lo que propuso su compañero
- Votación con desempate, cazador que se lleva a alguien, revelar o no el rol al morir, primera noche tranquila
- Línea de tiempo y "lo que pensaba el pueblo" al final, estadísticas por jugador
- **Modo Historia** (opcional): escena del crimen con 3 sospechosos (al menos uno es lobo y otro es un inocente incriminado por los lobos), testigos que ven a dos personas (una es lobo), compra del testigo y sospechas anónimas
- Ronda de acusación (señalar 3-2-1 + defensas de 30 s) y registro opcional de votos
- Sonidos de ambientación con botón para silenciar: viento de noche, mercado de día, hoguera, aullido, "¡Está vivo!", campanas. Los sonidos están en `app/src/main/res/raw/` (generados por síntesis); se pueden cambiar por grabaciones reales con el mismo nombre
- La partida se guarda si Android cierra la app

Las reglas están en `Rules.kt` con pruebas (incluidas 10.000 partidas simuladas). Cada compilación corre pruebas de pantalla en un emulador y publica el APK en Releases.
