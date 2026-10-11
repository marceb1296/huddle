# Huddle

Group events any player can start, right where they are. Races, treasure hunts, bounties, airdrops and last one standing: a player types one command, everyone nearby sees the announcement in chat and joins with one click. No arenas to build, no admin needed, and nothing to install on the client.

**Server-side only.** Install it on a NeoForge 1.21.1 server and players join with a normal client, vanilla or modded.

Full documentation, with every command and setting: https://huddle-mod.pages.dev

## Events

- **Race.** Record a track by riding it once, or let the mod generate one from the terrain nearby (lake, river, sprint or circuit). On foot, on horseback, by boat or with elytra, with laps. Checkpoints show up as rings, every track keeps its record, and boats and horses are lent to every racer. Ender pearls, chorus fruit and teleports disqualify.
- **Treasure hunt.** A chest is hidden around the creator, on the surface or underground. Four clues arrive over time, each one narrower than the last: direction and biome, distance, a ring of particles in the sky, and the exact spot. The first to open the chest wins.
- **Bounty.** One participant, drawn at random, is the target. The others hunt them with a distance and direction that get more precise as time goes by. A target who camps in one chunk starts to glow. The group votes the combat mode: anything goes, fists, wooden sword or bow.
- **Airdrop.** A crate comes down from the sky and everyone knows where it will land. To open it, a player has to stay next to it alone and unhurt for 15 seconds, so the others will try to stop them. Loot comes from any loot table, vanilla or from other mods.
- **Last one standing.** Everyone joins with an empty inventory and gets the same lent kit. Players are spread inside a zone that shrinks in stages. Stay outside too long and you are out. The last one alive wins.

The creator can ask for an entry in items. Everyone pays it and the winner takes the pot. If nobody wins, every entry goes back to its owner.

## Safe for a live server

- Event PvP only happens between participants. A player who did not join can never be hurt by an event.
- Dying in an event costs no items (servers can turn this off, and grave mods keep working).
- Nothing is placed in the world. Chests, crates and markers are display entities that are removed when the event ends.
- Tracks and spots are searched only in chunks that are already loaded, a few columns per tick, and never inside player bases.
- Messages and titles reach each player in their own game language. English and Spanish are included.

## For server owners

- Requires NeoForge for Minecraft 1.21.1. Clients do not need the mod.
- Configuration: `config/huddle-server.toml`, created on the first start. Each event can be turned off and has its own player limits, lobby time, cooldown and armor or enchantment caps.
- Bounty and last one standing need 3 players, the rest 2.
- Any player can use the commands: `/event start`, `join`, `leave`, `vote`, `end`, `list` and `/event track ...` for race tracks. They also work in Spanish (`/evento iniciar`, `unirse`...). Operators can end any event with `/event end <creator>`.

## Building

`./gradlew build` with Java 21. The jar ends up in `neoforge-1.21.1/build/libs/`.

## Support

Huddle is free. If it made your server more fun, you can buy me a coffee: https://ko-fi.com/marceb1296

## License

All rights reserved, with permissions: you can play it, run it on any server, include it in modpacks (also monetized ones, downloading it from CurseForge or Modrinth) and make videos. Reuploading the jar, forks, ports and copying the code into other projects are not allowed. See [LICENSE](LICENSE).

---

# Huddle (español)

Eventos grupales que cualquier jugador puede iniciar, justo donde está. Carreras, búsquedas del tesoro, recompensas, suministros aéreos y último en pie: un jugador escribe un comando, los que están cerca ven el anuncio en el chat y se unen con un clic. Sin arenas que construir, sin admin y sin nada que instalar en el cliente.

**Solo de servidor.** Se instala en un servidor NeoForge 1.21.1 y los jugadores entran con un cliente normal, vanilla o con mods.

Documentación completa, con cada comando y opción: https://huddle-mod.pages.dev

## Eventos

- **Carrera.** Se graba una pista recorriéndola una vez, o el mod genera una con el terreno cercano (lago, río, sprint o circuito). A pie, a caballo, en bote o con élitros, con vueltas. Los puntos de control se ven como anillos, cada pista guarda su récord y el evento presta botes y caballos a todos. Las perlas de ender, la fruta coral y los teletransportes descalifican.
- **Búsqueda del tesoro.** Un cofre se esconde alrededor de quien inicia el evento, en la superficie o bajo tierra. Llegan cuatro pistas, cada una más precisa: dirección y bioma, distancia, un anillo de partículas en el cielo y el lugar exacto. Gana quien abre el cofre primero.
- **Recompensa.** Un participante, elegido al azar, es el objetivo. Los demás lo cazan con una distancia y una dirección que se vuelven más precisas con el tiempo. Si se queda en un mismo chunk, empieza a brillar. El grupo vota el modo de combate: todo vale, puños, espada de madera o arco.
- **Suministro aéreo.** Una caja baja del cielo y todos saben dónde va a caer. Para abrirla hay que quedarse a su lado, solo y sin recibir daño, durante 15 segundos, así que los demás van a intentar impedirlo. El botín sale de cualquier tabla de botín, vanilla o de otros mods.
- **Último en pie.** Todos entran con el inventario vacío y reciben el mismo kit prestado. Los jugadores se reparten dentro de una zona que se achica por etapas. Quien se queda afuera demasiado tiempo queda fuera. Gana el último que siga vivo.

Quien inicia el evento puede pedir una entrada en ítems. Todos la pagan y el ganador se lleva el bote. Si nadie gana, cada entrada vuelve a su dueño.

## Seguro para un servidor en uso

- El PvP del evento ocurre solo entre participantes. Quien no se unió nunca recibe daño de un evento.
- Morir en un evento no cuesta ítems (el servidor puede desactivarlo, y los mods de tumbas siguen funcionando).
- No se coloca nada en el mundo. Cofres, cajas y marcadores son entidades de display que se quitan al terminar.
- Las pistas y los lugares se buscan solo en chunks ya cargados, unas columnas por tick, y nunca dentro de las bases de los jugadores.
- Los mensajes y títulos le llegan a cada jugador en el idioma de su juego. Incluye inglés y español.

## Para administradores

- Requiere NeoForge para Minecraft 1.21.1. Los clientes no necesitan el mod.
- Configuración: `config/huddle-server.toml`, que se crea en el primer arranque. Cada evento se puede apagar y tiene sus propios límites de jugadores, tiempo de sala, tiempo de espera y topes de armadura o encantamiento.
- La recompensa y el último en pie necesitan 3 jugadores; los demás, 2.
- Cualquier jugador puede usar los comandos: `/evento iniciar`, `unirse`, `salir`, `votar`, `terminar`, `lista` y `/evento pista ...` para las pistas de carrera. También funcionan en inglés (`/event start`, `join`...). Los operadores pueden terminar cualquier evento con `/event end <creador>`.

## Compilar

`./gradlew build` con Java 21. El jar queda en `neoforge-1.21.1/build/libs/`.

## Apoyo

Huddle es gratis. Si hizo más divertido tu servidor, puedes invitarme un café: https://ko-fi.com/marceb1296

## Licencia

Todos los derechos reservados, con permisos: se puede jugar, usar en cualquier servidor, incluir en modpacks (también monetizados, descargándolo desde CurseForge o Modrinth) y hacer videos. No se permite volver a subir el jar, publicar forks o ports ni copiar el código a otros proyectos. Ver [LICENSE](LICENSE).
