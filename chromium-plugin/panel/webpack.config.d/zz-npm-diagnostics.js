// Temporary diagnostic hook, removed after inspecting Windows webpack resolution.
config.plugins.push({
    apply(compiler) {
        compiler.hooks.beforeRun.tap('HeartbeatNpmDiagnostics', () => {
            const fs = require('fs');
            const path = require('path');
            const source = path.dirname(compiler.options.entry.main.import[0]);
            const root = path.resolve(__dirname, '../../node_modules/@js-joda/core');
            console.log('Heartbeat npm diagnostics:', JSON.stringify({
                node: process.version, platform: process.platform, cwd: process.cwd(),
                configDirectory: __dirname, source, modules: compiler.options.resolve.modules,
            }));
            for (const name of ['package.json', 'dist/js-joda.js', 'dist/js-joda.esm.js']) {
                const file = path.join(root, name);
                console.log(file, fs.existsSync(file) ? {size: fs.statSync(file).size, realPath: fs.realpathSync(file)} : 'MISSING');
            }
            try {
                console.log('Node resolves joda:', require.resolve('@js-joda/core', {paths: [source]}));
            } catch (error) {
                console.error('Node resolution failed:', error.message);
            }
        });
        compiler.hooks.done.tap('HeartbeatNpmDiagnostics', stats => {
            for (const error of stats.compilation.errors) console.error(error.details || error.message);
        });
    },
});
