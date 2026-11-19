/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The development server's event bus proxy (setupProxy.js).
 *
 * The backend answers exactly the origin it serves, so under `yarn start` the event bus must reach
 * it through this proxy, WebSocket included, carrying the backend's own Origin rather than the
 * development server's. These tests pin that configuration; that the upgrade really goes through
 * against a running backend is a manual check, recorded in the pull request.
 */

jest.mock('http-proxy-middleware', () => jest.fn((context, options) => ({ context, options })));

const proxy = require('http-proxy-middleware');
const setupProxy = require('./setupProxy');

function fakeProxyRequest(headers) {
    const sent = { ...headers };
    return {
        sent,
        getHeader: name => sent[name],
        setHeader: (name, value) => { sent[name] = value; },
    };
}

describe('setupProxy', () => {

    let registered;

    beforeEach(() => {
        proxy.mockClear();
        registered = [];
        setupProxy({ use: middleware => registered.push(middleware) });
    });

    it('proxies the event bus, and nothing else, to the address the backend binds', () => {
        expect(registered).toHaveLength(1);
        expect(registered[0].context).toBe('/eventbus');
        expect(registered[0].options.target).toBe('http://127.0.0.1:8080');
    });

    it('carries the WebSocket upgrade, which the package.json proxy does not rewrite', () => {
        const { options } = registered[0];

        expect(options.ws).toBe(true);
        expect(options.onProxyReqWs).toBe(setupProxy.rewriteOrigin);
        expect(options.onProxyReq).toBe(setupProxy.rewriteOrigin);
    });

    it("replaces the development server's Origin with the backend's own", () => {
        const request = fakeProxyRequest({ origin: 'http://localhost:3000' });

        setupProxy.rewriteOrigin(request);

        expect(request.sent.origin).toBe(setupProxy.BACKEND);
    });

    it('adds no Origin to a request that carried none', () => {
        const request = fakeProxyRequest({});

        setupProxy.rewriteOrigin(request);

        expect(request.sent.origin).toBeUndefined();
    });
});
