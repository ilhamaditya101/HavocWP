# CustomWeapon 1.0.0

Plugin for Paper 1.21.1. Includes a legacy CustomModelData conversion of the supplied
[ FAH ] V4 resource pack, custom crafting, permissions, editable recipes and item stats.

## Build
Requires JDK 21 and Maven 3.9+:
```bash
mvn -U clean package
```
The plugin jar is `target/CustomWeapon-1.0.0.jar`.

## Install
1. Put the jar in `plugins/` and start the server once.
2. Edit `plugins/CustomWeapon/config.yml`.
3. Set `resource-pack.public-url` to a publicly reachable URL such as
   `http://your-domain.example:8765/pack.zip`.
4. Allow inbound TCP on the configured port (default 8765) in the host firewall/security group.
5. Restart. The plugin serves its embedded pack at `/pack.zip` and sends it to players on join.
6. Give permissions with LuckPerms, for example:
   `/lp group default permission set customweapon.use true`
   `/lp group vip permission set customweapon.craft.mega_wukong true`
   `/lp group admin permission set customweapon.admin true`

The pack server binds to `0.0.0.0` by default. The URL in `public-url` must be the public
IP/domain reachable by players; `localhost`, a private LAN address, and `CHANGE-ME` will not work.
For production, use HTTPS/reverse proxy if available. If the host blocks extra ports, host
`pack.zip` on a public HTTPS file host and set `public-url` to that direct ZIP URL.

## Commands
- `/ct` help
- `/ct list`
- `/ct edit` opens weapon selector and recipe editor (admin)
- `/ct give <player> <weapon>` (admin)
- `/ct reload` (admin)

## Recipe editor
In `/ct edit`, click a weapon. Click each of the 9 recipe cells to cycle through the
available materials; the gray glass pane means AIR/empty. Click Save Recipe to persist.
The GUI cycles a curated list of common materials. For arbitrary ingredients, edit
`weapons.<id>.recipe` in config.yml (9 entries, row-major) and run `/ct reload`.

## Permissions
- `customweapon.admin` - edit, give, reload (default op)
- `customweapon.use` - general access node (default true)
- Each recipe has its own permission, e.g. `customweapon.craft.mega_wukong`.
  Grant it to players/groups who may craft that weapon.

## Weapon stats
Each weapon supports `damage`, `attack-speed`, and `max-durability` in config.yml.
Damage is the total intended attack damage; the plugin applies the difference from the
base material's vanilla attack damage. Durability is set as the item's maximum durability.
Stats and recipes can be adjusted in config and reloaded.

## Compatibility notes
The uploaded pack declared format 88 and used the post-1.21.4 `assets/minecraft/items`
component-based model selection. Paper 1.21.1 cannot use that item-definition system.
This project converts the listed models to legacy `CustomModelData` overrides and sets
pack format 34. The 1.21.1-compatible base items are iron sword/axe/hoe/pickaxe/shovel;
the original pack's copper tools and spear item entries are mapped to iron equivalents
because copper equipment and the spear base item are not present in 1.21.1.

The original model JSON and textures are retained. However, some model JSON features or
animation/emissive behavior authored against 1.21.11 may not render identically on 1.21.1;
test in a staging server. OptiFine-specific emissive properties require a compatible client
mod and are not guaranteed in vanilla clients.

## Resource-pack download
A plugin cannot make a client render custom textures without the resource pack. This plugin
automatically offers the converted pack on join. Players still need to accept/download it
unless you set `resource-pack.required: true` (which makes refusal prevent joining).
