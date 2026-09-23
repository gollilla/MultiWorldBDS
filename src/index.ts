export { listWorlds, addWorld, removeWorld } from './worldControl';
export type { WorldSummary } from './worldControl';

// Side-effect import: registers the example slash commands (examples/worldControlCommands.ts)
// against @minecraft/server's customCommandRegistry. hakomc's vite plugin hardcodes
// src/index.ts as the behavior pack's script entry, so this is how the example actually
// runs against the dev server (see the repo root's docker-compose.yml). It also means a
// project that `npm install`s hakomc-world as a library and imports from it pulls in this
// registration as a side effect too - fine for this repo's own dev/test loop, but a real
// consuming project should import only from ./worldControl directly if it wants the client
// without the demo commands.
import '../examples/worldControlCommands';
