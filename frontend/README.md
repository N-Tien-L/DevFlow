# React + TypeScript + Vite

This template provides a minimal setup to get React working in Vite with HMR and some Oxlint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the Oxlint configuration

If you are developing a production application, we recommend enabling type-aware lint rules by installing `oxlint-tsgolint` and editing `.oxlintrc.json`:

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "plugins": ["react", "typescript", "oxc"],
  "options": {
    "typeAware": true
  },
  "rules": {
    "react/rules-of-hooks": "error",
    "react/only-export-components": ["warn", { "allowConstantExport": true }]
  }
}
```

See the [Oxlint rules documentation](https://oxc.rs/docs/guide/usage/linter/rules) for the full list of rules and categories.

## Board and Turnstile configuration

The board uses the authenticated `/api/v1` endpoints. For local Vite development with the default Docker Compose backend (`TURNSTILE_ENABLED=false`), no Turnstile key is needed. If the backend has Turnstile enabled, set `VITE_TURNSTILE_REQUIRED=true` and the public `VITE_TURNSTILE_SITE_KEY` in `frontend/.env.local`, then set `TURNSTILE_ENABLED=true` and the private `TURNSTILE_SECRET_KEY` for the backend. For a frontend Docker image, pass `VITE_TURNSTILE_SITE_KEY` and `TURNSTILE_ENABLED=true` as build environment values; the site key is public and the secret key must never be passed to the frontend build.
