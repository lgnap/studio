/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The development server's proxy for the event bus, loaded by react-scripts under `yarn start` only
 * (react-scripts 3.0.1, config/paths.js `proxySetup`); the production build does not contain it.
 *
 * The backend answers exactly the origin it serves (MainVerticle.allowedOrigins, issue #46), so a
 * page served by the development server must not talk to it directly from the browser. The
 * `proxy` entry of package.json already carries `/api` and rewrites the Origin of what it forwards,
 * but only on plain HTTP requests: the WebSocket upgrade of the event bus would still reach the
 * backend with `Origin: http://localhost:3000` and be refused. This entry carries `/eventbus`,
 * WebSocket included, and rewrites the Origin on both kinds of request to the backend's own, which
 * is what the page would send if the backend had served it.
 *
 * http-proxy-middleware is the copy react-scripts ships (0.19.2), not a dependency of its own.
 */

const proxy = require('http-proxy-middleware');

// The address the backend binds (MainVerticle.listenHost), rather than `localhost`, which newer
// Node versions may resolve to ::1 first, where nothing listens.
const BACKEND = 'http://127.0.0.1:8080';

function rewriteOrigin(proxyReq) {
    if (proxyReq.getHeader('origin')) {
        proxyReq.setHeader('origin', BACKEND);
    }
}

module.exports = function setupProxy(app) {
    app.use(proxy('/eventbus', {
        target: BACKEND,
        ws: true,
        changeOrigin: true,
        logLevel: 'silent',
        onProxyReq: rewriteOrigin,
        onProxyReqWs: rewriteOrigin,
    }));
};

module.exports.BACKEND = BACKEND;
module.exports.rewriteOrigin = rewriteOrigin;
