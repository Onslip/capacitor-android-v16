console.log('capacitor-api-handler.js loaded');

const channel = browser.runtime.connectNative("capacitor.gv.api");
const jsIface = {};

channel.onMessage.addListener(({id, message}) => {
    let result = null;
    console.log('GVAPI request', id, JSON.stringify(message));

    switch (message.action) {
        case 'add-js-interface': {
            result = addJavascriptInterface(message.name, message.methods);
            break;
        }

        case 'evaluate-js': {
            result = evaluateJS(message.script);
            break;
        }
    }

    Promise.resolve(result)
        .then((message) => channel.postMessage({ id, message }))
        .catch(console.error);
});

function addJavascriptInterface(name, methods) {
    jsIface[name] = methods;
}

function evaluateJS(code) {
    return browser.tabs.query({}).then((tabs) => browser.tabs.executeScript(tabs[0].id, {
        code:  `window.eval(${JSON.stringify(code)})`
    })).then((results) => results[0]);
}

browser.webNavigation.onCommitted.addListener((details) => {
    browser.tabs.executeScript(details.tabId, {
        runAt: 'document_start',
        code:  `(${(/** @type Record<string, string[]> */ jsIface) => {
                Object.entries(jsIface).forEach(([name, methods]) => {
                    window.wrappedJSObject[name] = cloneInto(
                        Object.fromEntries(methods.map(method => [method, (...args) => browser.runtime.sendNativeMessage("capacitor.gv.api", { name, method, args })])),
                        window, {
                            cloneFunctions: true,
                        }
                    );
                });

                console.info("Defined additional JS interfaces", JSON.stringify(jsIface));
            }})(${JSON.stringify(jsIface)})`
    });
});
