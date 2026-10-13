// KGP runs this configuration from build/wasm/packages/heartbeat-panel, while Yarn
// hoists runtime dependencies to build/wasm/node_modules. Windows webpack can fail
// to find those installed packages through its relative node_modules lookup.
// Keep the default lookup and add the workspace's absolute directory as a fallback.
config.resolve.modules.push(require('path').resolve(__dirname, '../../node_modules'));
