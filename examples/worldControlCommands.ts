/**
 * Example: expose addWorld/removeWorld/listWorlds as in-game slash commands,
 * so you can drive WorldControl straight from ScriptAPI instead of curl.
 *
 * Reference code, not wired into any build - hakomc-world's own src/index.ts
 * stays a plain re-export of worldControl.ts. To actually run this, import it
 * for its side effect from a behavior pack's script entry (src/index.ts for
 * this repo's own dev world, or your own project's if you're consuming
 * hakomc-world as a library).
 *
 * Requires config/default/variables.json (or the itzg image's VARIABLES env
 * var, see docker-compose.yml at the repo root) to set worldControlApiUrl to
 * a reachable WorldControl instance - e.g. the one under docker/.
 */
import {
  system,
  world,
  CustomCommandStatus,
  CustomCommandParamType,
  CommandPermissionLevel,
} from '@minecraft/server';
import type { CustomCommand, CustomCommandOrigin, Player, StartupEvent } from '@minecraft/server';
import { addWorld, removeWorld, listWorlds } from '../src/worldControl';

function reply(origin: CustomCommandOrigin, message: string): void {
  const source = origin.sourceEntity;
  system.run(() => {
    if (source && 'sendMessage' in source) {
      (source as Player).sendMessage(message);
    } else {
      world.sendMessage(message);
    }
  });
}

system.beforeEvents.startup.subscribe((init: StartupEvent) => {
  const registry = init.customCommandRegistry;

  const worldAdd: CustomCommand = {
    name: 'hakomc:worldadd',
    description: 'Provisions a new world and registers it with Waterdog - doesn\'t move you there, run /server <name> once it\'s up',
    permissionLevel: CommandPermissionLevel.GameDirectors,
    mandatoryParameters: [{ type: CustomCommandParamType.String, name: 'name' }],
    optionalParameters: [{ type: CustomCommandParamType.String, name: 'gamemode' }],
  };
  registry.registerCommand(worldAdd, (origin: CustomCommandOrigin, name: string, gamemode?: string) => {
    reply(origin, `Provisioning '${name}'... this can take a while (container start + health check).`);
    addWorld(name, gamemode ?? 'survival')
      .then(() => reply(origin, `'${name}' is up - run /server ${name} to join it.`))
      .catch((error: unknown) => reply(origin, `Failed to add '${name}': ${error}`));
    return { status: CustomCommandStatus.Success };
  });

  const worldRemove: CustomCommand = {
    name: 'hakomc:worldremove',
    description: 'Stops a world and unregisters it from Waterdog',
    permissionLevel: CommandPermissionLevel.GameDirectors,
    mandatoryParameters: [{ type: CustomCommandParamType.String, name: 'name' }],
  };
  registry.registerCommand(worldRemove, (origin: CustomCommandOrigin, name: string) => {
    removeWorld(name)
      .then(() => reply(origin, `'${name}' removed.`))
      .catch((error: unknown) => reply(origin, `Failed to remove '${name}': ${error}`));
    return { status: CustomCommandStatus.Success };
  });

  const worldList: CustomCommand = {
    name: 'hakomc:worldlist',
    description: 'Lists worlds currently registered with Waterdog',
    permissionLevel: CommandPermissionLevel.Any,
  };
  registry.registerCommand(worldList, (origin: CustomCommandOrigin) => {
    listWorlds()
      .then((worlds) => reply(origin, worlds.map((w) => `${w.name} (${w.address})`).join(', ') || '(no worlds)'))
      .catch((error: unknown) => reply(origin, `Failed to list worlds: ${error}`));
    return { status: CustomCommandStatus.Success };
  });
});
