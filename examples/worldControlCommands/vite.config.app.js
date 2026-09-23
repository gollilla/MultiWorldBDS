import { defineConfig } from 'vite';
import {
  behaviorPacker as hakomcPlugin,
  supportJsx,
} from 'hakomc/vite-plugin';
import { resolve } from 'path';

export default defineConfig({
  plugins: [
    hakomcPlugin({
      name: "worldcontrol-commands-example",
      uuid: "04b83f73-2853-4567-8fc8-b3f82d387e39",
      // Manifest dependency version strings for beta modules conventionally use
      // "1.0.0-beta" rather than the exact npm build hash - worth reconfirming
      // against current Bedrock docs since @minecraft/server-net/-admin are
      // both still pre-release and the convention could change.
      manifest: {
        dependencies: [
          { module_name: "@minecraft/server-net", version: "1.0.0-beta" },
          { module_name: "@minecraft/server-admin", version: "1.0.0-beta" },
        ],
      },
    }),
    supportJsx(),
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
});
