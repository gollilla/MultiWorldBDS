/**
 * Exposes addWorld/stopWorld/removeWorld/listWorlds/transferPlayer/
 * getSelfWorldName/copyWorld (from the hakomc-world package,
 * https://github.com/gollilla/MultiWorldBDS) as in-game slash commands, so
 * you can drive WorldControl straight from ScriptAPI instead of curl.
 *
 * Requires config/default/variables.json (or the itzg image's VARIABLES env
 * var, see docker-compose.yml) to set worldControlApiUrl to a reachable
 * WorldControl instance - e.g. the one under MultiWorldBDS's docker/.
 */
import {
  system,
  world,
  CustomCommandStatus,
  CustomCommandParamType,
  CommandPermissionLevel,
} from '@minecraft/server';
import type { CustomCommand, CustomCommandOrigin, Player, StartupEvent } from '@minecraft/server';
import { addWorld, stopWorld, removeWorld, listWorlds, transferPlayer, getSelfWorldName, copyWorld } from 'hakomc-world';
import type { WorldType } from 'hakomc-world';

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
    description: `Provisions a new world (or resumes a stopped one) and registers it with Waterdog - doesn't move you there, run /server <name> once it's up`,
    permissionLevel: CommandPermissionLevel.GameDirectors,
    mandatoryParameters: [{ type: CustomCommandParamType.String, name: 'name' }],
    optionalParameters: [
      { type: CustomCommandParamType.String, name: 'gamemode' },
      { type: CustomCommandParamType.String, name: 'worldType' },
    ],
  };
  registry.registerCommand(
    worldAdd,
    (origin: CustomCommandOrigin, name: string, gamemode?: string, worldType?: string) => {
      reply(origin, `Provisioning '${name}'... this can take a while (container start + health check).`);
      addWorld(name, gamemode ?? 'survival', (worldType as WorldType | undefined) ?? 'normal')
        .then(() => reply(origin, `'${name}' is up - run /server ${name} to join it.`))
        .catch((error: unknown) => reply(origin, `Failed to add '${name}': ${error}`));
      return { status: CustomCommandStatus.Success };
    }
  );

  const worldStop: CustomCommand = {
    name: 'hakomc:worldstop',
    description: `Stops a world and unregisters it from Waterdog, keeping its data so 'worldadd' can resume it later`,
    permissionLevel: CommandPermissionLevel.GameDirectors,
    mandatoryParameters: [{ type: CustomCommandParamType.String, name: 'name' }],
  };
  registry.registerCommand(worldStop, (origin: CustomCommandOrigin, name: string) => {
    stopWorld(name)
      .then(() => reply(origin, `'${name}' stopped.`))
      .catch((error: unknown) => reply(origin, `Failed to stop '${name}': ${error}`));
    return { status: CustomCommandStatus.Success };
  });

  const worldRemove: CustomCommand = {
    name: 'hakomc:worldremove',
    description: 'Stops a world, unregisters it from Waterdog, and permanently erases its data',
    permissionLevel: CommandPermissionLevel.GameDirectors,
    mandatoryParameters: [{ type: CustomCommandParamType.String, name: 'name' }],
  };
  registry.registerCommand(worldRemove, (origin: CustomCommandOrigin, name: string) => {
    removeWorld(name)
      .then(() => reply(origin, `'${name}' removed.`))
      .catch((error: unknown) => reply(origin, `Failed to remove '${name}': ${error}`));
    return { status: CustomCommandStatus.Success };
  });

  const worldTransfer: CustomCommand = {
    name: 'hakomc:worldtransfer',
    description: `Transfers a player to a world registered with Waterdog - defaults to yourself if no target is given`,
    permissionLevel: CommandPermissionLevel.GameDirectors,
    mandatoryParameters: [{ type: CustomCommandParamType.String, name: 'world' }],
    optionalParameters: [{ type: CustomCommandParamType.PlayerSelector, name: 'target' }],
  };
  registry.registerCommand(worldTransfer, (origin: CustomCommandOrigin, world_: string, target?: Player) => {
    const source = origin.sourceEntity;
    const player = target ?? (source && 'sendMessage' in source ? (source as Player) : undefined);
    if (!player) {
      reply(origin, 'No target player to transfer.');
      return { status: CustomCommandStatus.Success };
    }
    transferPlayer(player.name, world_)
      .then(() => reply(origin, `Transferring '${player.name}' to '${world_}'.`))
      .catch((error: unknown) => reply(origin, `Failed to transfer '${player.name}': ${error}`));
    return { status: CustomCommandStatus.Success };
  });

  const worldSelf: CustomCommand = {
    name: 'hakomc:worldself',
    description: `Asks WorldControl which world this script is currently running in`,
    permissionLevel: CommandPermissionLevel.Any,
  };
  registry.registerCommand(worldSelf, (origin: CustomCommandOrigin) => {
    getSelfWorldName()
      .then((name) => reply(origin, `This is '${name}'.`))
      .catch((error: unknown) => reply(origin, `Failed to resolve self: ${error}`));
    return { status: CustomCommandStatus.Success };
  });

  const worldCopy: CustomCommand = {
    name: 'hakomc:worldcopy',
    description: `Copies a stopped world's data to a new (also not-running) name - doesn't start it, run 'worldadd' afterwards`,
    permissionLevel: CommandPermissionLevel.GameDirectors,
    mandatoryParameters: [
      { type: CustomCommandParamType.String, name: 'src' },
      { type: CustomCommandParamType.String, name: 'dst' },
    ],
  };
  registry.registerCommand(worldCopy, (origin: CustomCommandOrigin, src: string, dst: string) => {
    copyWorld(src, dst)
      .then(() => reply(origin, `Copied '${src}' to '${dst}' - run '/hakomc:worldadd ${dst}' to start it.`))
      .catch((error: unknown) => reply(origin, `Failed to copy '${src}' to '${dst}': ${error}`));
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
