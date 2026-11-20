import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  return {
    plugins: [react()],
    resolve: {
      alias:
        env.VITE_API_MODE === "real"
          ? [
              {
                find: /^.*\/mocks\/server$/,
                replacement: fileURLToPath(
                  new URL("./src/mocks/disabled.ts", import.meta.url),
                ),
              },
            ]
          : [],
    },
    server: {
      proxy: {
        "/api": {
          target: env.API_PROXY_TARGET || "http://127.0.0.1:8080",
          changeOrigin: false,
        },
      },
    },
    build: { chunkSizeWarningLimit: 800 },
  };
});
