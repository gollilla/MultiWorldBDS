import { fetch } from 'hakomc';
import { variables } from '@minecraft/server-admin';

/**
 * Thin client for the WaterdogPE WorldControl HTTP API
 * (waterdog/plugin/src/main/java/dev/bdswaterdogpe/worldcontrol), callable
 * from BDS-side scripts via hakomc's fetch() (a fetch-API-shaped wrapper
 * around @minecraft/server-net's HttpClient).
 *
 * @minecraft/server-net and @minecraft/server-admin only work on Bedrock
 * Dedicated Server, and both modules are still pre-release - they must be
 * listed in this world's manifest.json `dependencies` (see
 * vite.config.app.js) and allow-listed in the server's permissions.json
 * (already the case for config/default/permissions.json in this template).
 */

export interface WorldSummary {
  name: string;
  address: string;
}

/**
 * The Waterdog task's WorldControl base URL (e.g. "http://10.42.1.23:8081"),
 * read from this dedicated server's configured variables rather than
 * hardcoded, since it depends on which Fargate task is currently running
 * Waterdog. Configure it in the server's variables JSON (passed via BDS's
 * `--server-config` flag) as `{ "variables": { "worldControlApiUrl": "..." } }`
 * - the exact file name/flag is worth reconfirming against the current
 * @minecraft/server-admin docs before relying on it, since the module is beta.
 *
 * variables.get() is a privileged call that only works during early-execution
 * (this module's top-level evaluation, before any event fires) - calling it
 * lazily from inside a command callback throws "cannot be used in restricted
 * execution", so it's read once here and cached.
 */
const configuredWorldControlApiUrl = variables.get('worldControlApiUrl');

function baseUrl(): string {
  if (typeof configuredWorldControlApiUrl !== 'string' || configuredWorldControlApiUrl.length === 0) {
    throw new Error(
      `WorldControl: worldControlApiUrl is not set in this server's variables config`
    );
  }
  return configuredWorldControlApiUrl.replace(/\/+$/, '');
}

async function request(method: string, path: string, body?: string) {
  const response = await fetch(`${baseUrl()}${path}`, {
    method,
    timeout: 30,
    ...(body !== undefined
      ? { body, headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }
      : {}),
  });
  if (!response.ok) {
    throw new Error(`WorldControl ${method} ${path} failed: ${response.status} ${response.text()}`);
  }
  return response;
}

/** GET /worlds - lists every world currently registered with Waterdog. */
export async function listWorlds(): Promise<WorldSummary[]> {
  const response = await request('GET', '/worlds');
  return response.json() as WorldSummary[];
}

/** normal: a regular generated world. flat: BDS's flat preset. void: a pre-built empty world (Docker target only - see WorldControl's README). */
export type WorldType = 'normal' | 'flat' | 'void';

/**
 * POST /worlds - provisions a world's backend (an ECS task or a Docker
 * container, depending on how WorldControl is deployed) and registers it
 * with Waterdog. Resolves once the world is reachable through the proxy;
 * this can take up to a few minutes (task/container start + health check).
 * Calling this again for a world that was previously stopWorld()'d resumes
 * it against its existing data instead of creating a new one.
 */
export async function addWorld(
  name: string,
  gamemode: string = 'survival',
  worldType: WorldType = 'normal'
): Promise<void> {
  await request(
    'POST',
    '/worlds',
    `name=${encodeURIComponent(name)}&gamemode=${encodeURIComponent(gamemode)}&worldType=${encodeURIComponent(worldType)}`
  );
}

/**
 * POST /worlds/{name}/stop - stops the given world's backend and
 * unregisters it from Waterdog, leaving its data alone so a later addWorld()
 * with the same name resumes it.
 */
export async function stopWorld(name: string): Promise<void> {
  await request('POST', `/worlds/${encodeURIComponent(name)}/stop`);
}

/**
 * DELETE /worlds/{name} - stops the given world's backend, unregisters it
 * from Waterdog, and permanently erases its data.
 */
export async function removeWorld(name: string): Promise<void> {
  await request('DELETE', `/worlds/${encodeURIComponent(name)}`);
}

/**
 * POST /players/{name}/transfer - transfers an already-connected player to
 * a world registered with Waterdog (fast transfer, no reconnect). The
 * player must currently be on this same Waterdog proxy; there's no cross-
 * proxy transfer here.
 */
export async function transferPlayer(playerName: string, worldName: string): Promise<void> {
  await request(
    'POST',
    `/players/${encodeURIComponent(playerName)}/transfer`,
    `world=${encodeURIComponent(worldName)}`
  );
}

/**
 * GET /worlds/self - this world's own registered name, resolved by
 * WorldControl from the request's own source address (Script API has no
 * other way for a world to learn its own name - SERVER_NAME/LEVEL_NAME are
 * only visible as env vars on the server process, not from a script). Can
 * 404 in the brief window between a world's container starting and
 * WorldControl's health check passing - retry rather than treating a single
 * failure as fatal if you call this from a world's own startup code.
 */
export async function getSelfWorldName(): Promise<string> {
  const response = await request('GET', '/worlds/self');
  return (response.json() as { name: string }).name;
}

/**
 * POST /worlds/{src}/copy - copies src's (stopped) world data to a new
 * name, dst, without starting it - a later addWorld(dst) resumes the copy
 * instead of generating a fresh world. Both src and dst must be neither
 * registered nor mid-provisioning, or this fails with 409 (src should be
 * stopWorld()'d first). gamemode/worldType default to whatever src was
 * created with; pass them to override just the copy. Docker target only.
 */
export async function copyWorld(
  src: string,
  dst: string,
  gamemode?: string,
  worldType?: WorldType
): Promise<void> {
  let body = `to=${encodeURIComponent(dst)}`;
  if (gamemode !== undefined) {
    body += `&gamemode=${encodeURIComponent(gamemode)}`;
  }
  if (worldType !== undefined) {
    body += `&worldType=${encodeURIComponent(worldType)}`;
  }
  await request('POST', `/worlds/${encodeURIComponent(src)}/copy`, body);
}
