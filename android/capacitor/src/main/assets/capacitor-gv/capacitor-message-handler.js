window.wrappedJSObject.androidBridge = cloneInto({
        postMessage: (message) => browser.runtime.sendNativeMessage("capacitor.gv.api", {
            name: 'androidBridge', method: 'postMessage', args: [ message ]
        })
    }, window, {
        cloneFunctions: true,
    }
);

console.info("Defined androidBridge JS interface");
