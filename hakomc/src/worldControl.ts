import { http, HttpRequest, HttpRequestMethod } from '@minecraft/server-net';
import { variables } from '@minecraft/server-admin';
import { debug } from 'hakomc';

/**
 * Thin client for the WaterdogPE WorldControl HTTP API
 * (waterdog/plugin/src/main/java/dev/bdswaterdogpe/worldcontrol), callable
 * from BDS-side scripts via @minecraft/server-net.
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
 */
function baseUrl(): string {
  const configured = variables.get('worldControlApiUrl');
  if (typeof configured !== 'string' || configured.length === 0) {
    throw new Error(
      `WorldControl: worldControlApiUrl is not set in this server's variables config`
    );
  }
  return configured.replace(/\/+$/, '');
}

async function request(method: HttpRequestMethod, path: string, body?: string) {
  const req = new HttpRequest(`${baseUrl()}${path}`).setMethod(method).setTimeout(30);
  if (body !== undefined) {
    req.setBody(body);
    req.addHeader('Content-Type', 'application/x-www-form-urlencoded');
  }
  debug(`WorldControl: ${method} ${path}`);
  const response = await http.request(req);
  if (response.status >= 400) {
    debug(`WorldControl: ${method} ${path} failed (${response.status})`, response.body);
    throw new Error(`WorldControl ${method} ${path} failed: ${response.status} ${response.body}`);
  }
  return response;
}

/** GET /worlds - lists every world currently registered with Waterdog. */
export async function listWorlds(): Promise<WorldSummary[]> {
  const response = await request(HttpRequestMethod.GET, '/worlds');
  return JSON.parse(response.body) as WorldSummary[];
}

/**
 * POST /worlds - provisions a new BDS world as an ECS task and registers it
 * with Waterdog. Resolves once the world is reachable through the proxy;
 * this can take up to a few minutes (ECS task start + health check).
 */
export async function addWorld(name: string, gamemode: string = 'survival'): Promise<void> {
  await request(
    HttpRequestMethod.POST,
    '/worlds',
    `name=${encodeURIComponent(name)}&gamemode=${encodeURIComponent(gamemode)}`
  );
  debug(`WorldControl: world '${name}' is up (${gamemode})`);
}

/** DELETE /worlds/{name} - unregisters and stops the given world's task. */
export async function removeWorld(name: string): Promise<void> {
  await request(HttpRequestMethod.DELETE, `/worlds/${encodeURIComponent(name)}`);
  debug(`WorldControl: world '${name}' removed`);
}
