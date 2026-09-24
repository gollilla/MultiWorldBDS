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
