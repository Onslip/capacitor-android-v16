console.log('capacitor-proxy-handler.js loaded');

const channel = browser.runtime.connectNative("capacitor.gv.proxy");
const pending = [];

function sendMessage(message) {
    return new Promise((resolve) => {
        channel.postMessage({ id: pending.length, message });
        pending[pending.length] = resolve;
    }).then(
        r => { console.info("Proxy Result: ", JSON.stringify(r), JSON.stringify(message)); return r; },
        e => { console.error("Proxy Error: ", JSON.stringify(e), JSON.stringify(message)); throw e; }
    );
}

channel.onMessage.addListener((response) => {
    pending[response.id]?.(response.message);
    delete pending[response.id];
});

browser.proxy.onRequest.addListener((requestDetils) => {
    return sendMessage(requestDetils); // Let native side generate the ProxyInfo response
}, { urls: ["<all_urls>"] });

browser.proxy.onError.addListener((error) => {
    console.error(`Proxy error: ${error.message}`);
});
