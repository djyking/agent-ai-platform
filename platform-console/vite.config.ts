import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, ".", "HARNESS_");
  const target =
    environment.HARNESS_DEV_PROXY_TARGET || "http://127.0.0.1:8097";
  return {
    base: "/console/",
    plugins: [react()],
    server: {
      proxy: {
        "/console/session": target,
        "/console/login": target,
        "/console/auth": target,
        "/console/api": target,
      },
    },
    build: { sourcemap: false, assetsDir: "assets" },
  };
});
